#!/usr/bin/env bash
# jar 교체 → 재시작 → 헬스 확인 → 실패 시 app.jar.prev로 자동 복구 (deploy-spec 8절 P4-1b)
# 실행: sudo bash cinemory-deploy.sh <새 jar — s3://... 또는 서버 로컬 경로>
#   - CI: SSM Run Command(root)가 매 배포 S3에서 이 스크립트와 jar를 받아 실행한다. 서버에 설치하지 않는다.
#   - 롤백 리허설: 로컬 경로로 깨진 파일을 넘긴다(런북 13절).
#
# 종료 코드 — 0: 배포 성공 / 2: 새 jar 실패, 이전 jar로 복구됨 / 3: 복구도 실패(서버 DOWN, 사람이 개입) / 1: 사전 조건 실패(아무것도 바꾸지 않음)
# ⚠️ 복구가 성립하려면 새 jar의 마이그레이션이 확장만이어야 한다(D-3 조건 6) — Flyway는 이미 적용된 스키마를 되돌리지 않는다.
set -euo pipefail

SOURCE=${1:?사용법: cinemory-deploy.sh <s3://... | 로컬 jar 경로>}
APP_DIR=/opt/cinemory
SERVICE=cinemory
HEALTH_URL=http://127.0.0.1:8080/actuator/health
HEALTH_TIMEOUT=${HEALTH_TIMEOUT:-180}   # 초. Flyway + JPA 초기화 포함 기동 시간보다 넉넉하게
AWS=/snap/bin/aws                       # SSM 실행 환경은 PATH에 /snap/bin이 없을 수 있다

[[ $EUID -eq 0 ]] || { echo "sudo로 실행하세요." >&2; exit 1; }
[[ -f $APP_DIR/app.jar ]] || { echo "$APP_DIR/app.jar 가 없습니다 — 최초 배치는 런북 9-④로 합니다." >&2; exit 1; }

# 두 배포가 겹치면 app.jar.prev를 서로 덮는다. 워크플로 concurrency와 이중으로 막는다.
exec 9>/run/cinemory-deploy.lock
flock -n 9 || { echo "다른 배포가 진행 중입니다." >&2; exit 1; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

log() { echo "[$(date '+%H:%M:%S')] $*"; }

wait_healthy() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT)) body
  while (( SECONDS < deadline )); do
    # 파이프(curl | grep -q)를 쓰지 않는다 — grep이 먼저 끝나면 pipefail이 성공을 실패로 뒤집을 수 있다
    body=$(curl -fsS --max-time 5 "$HEALTH_URL" 2>/dev/null) || body=
    [[ $body == *'"status":"UP"'* ]] && return 0
    sleep 5
  done
  return 1
}

restart() {
  # 실패 재시작이 StartLimitBurst에 걸려 있으면 restart가 거부된다 — 카운터부터 비운다.
  systemctl reset-failed "$SERVICE" 2>/dev/null || true
  systemctl restart "$SERVICE"
}

# 1) 새 jar를 작업 폴더로 — 운영 파일은 아직 건드리지 않는다
log "새 jar 가져오기: $SOURCE"
if [[ $SOURCE == s3://* ]]; then
  "$AWS" s3 cp --only-show-errors "$SOURCE" "$WORK/app.jar"
else
  cp "$SOURCE" "$WORK/app.jar"
fi
[[ -s $WORK/app.jar ]] || { echo "새 jar가 비어 있습니다 — 아무것도 바꾸지 않았습니다." >&2; exit 1; }

# 2) 보관 후 교체
cp -p "$APP_DIR/app.jar" "$APP_DIR/app.jar.prev"
install -m 644 -o cinemory -g cinemory "$WORK/app.jar" "$APP_DIR/app.jar"
log "교체 완료 — 재시작, 헬스 대기(최대 ${HEALTH_TIMEOUT}s)"
restart

if wait_healthy; then
  log "✅ 배포 성공 — $HEALTH_URL UP"
  exit 0
fi

# 3) 자동 복구 — 실패한 jar는 진단용으로 남긴다(다음 실패가 덮어쓴다)
log "❌ 새 jar가 UP이 되지 않았습니다. 최근 로그:"
journalctl -u "$SERVICE" -n 60 --no-pager || true
cp -p "$APP_DIR/app.jar" "$APP_DIR/app.jar.failed"
cp -p "$APP_DIR/app.jar.prev" "$APP_DIR/app.jar"
log "app.jar.prev로 복구 — 재시작"
restart

if wait_healthy; then
  log "↩️  복구 완료 — 이전 버전으로 UP. 원인을 고쳐 재배포한 뒤 해당 확장 마이그레이션의 보정 쿼리를 실행할 것(D-3 조건 6)"
  exit 2
fi

log "🚨 복구도 실패 — 서버 DOWN. 사람이 개입: 런북 13절(스냅샷 복원 · flyway repair)"
journalctl -u "$SERVICE" -n 60 --no-pager || true
exit 3
