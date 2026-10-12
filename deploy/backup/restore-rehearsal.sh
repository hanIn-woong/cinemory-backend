#!/usr/bin/env bash
# 복원 리허설 — 덤프를 임시 스키마에 복원하고 운영 DB와 테이블별 행 수를 비교한다 (deploy-spec 10절)
# 실행: sudo bash deploy/backup/restore-rehearsal.sh [덤프 파일]   (생략하면 가장 최근 덤프)
# 운영 스키마는 읽기만 한다. 임시 스키마(cinemory_restore_check)는 끝나면 지운다.
# ⚠️ 운영 중이라 덤프 이후 쓰기가 있었다면 그 테이블만 차이가 날 수 있다 — 차이가 나면 해당 테이블의 최근 쓰기를 먼저 의심한다.
set -euo pipefail

SRC=cinemory
TMPDB=cinemory_restore_check
DUMP=${1:-$(ls -1t /var/backups/cinemory/cinemory-*.sql.gz 2>/dev/null | head -1)}

[[ $EUID -eq 0 ]] || { echo "sudo로 실행하세요." >&2; exit 1; }
[[ -n $DUMP && -f $DUMP ]] || { echo "덤프 파일이 없습니다: ${DUMP:-<없음>}" >&2; exit 1; }

echo "덤프: $DUMP"
mysql -e "DROP DATABASE IF EXISTS $TMPDB; CREATE DATABASE $TMPDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
trap 'mysql -e "DROP DATABASE IF EXISTS '"$TMPDB"';"' EXIT

start=$SECONDS
zcat "$DUMP" | mysql "$TMPDB"
echo "복원 $((SECONDS - start))s"

printf '%-32s %10s %10s\n' TABLE "$SRC" "$TMPDB"
diff=0
for t in $(mysql -N -e "SELECT table_name FROM information_schema.tables WHERE table_schema='$SRC' AND table_type='BASE TABLE' ORDER BY table_name;"); do
  a=$(mysql -N -e "SELECT COUNT(*) FROM \`$SRC\`.\`$t\`;")
  b=$(mysql -N -e "SELECT COUNT(*) FROM \`$TMPDB\`.\`$t\`;" 2>/dev/null || echo "없음")
  mark=""; [[ $a == "$b" ]] || { mark="  ← 차이"; diff=$((diff + 1)); }
  printf '%-32s %10s %10s%s\n' "$t" "$a" "$b" "$mark"
done

if (( diff == 0 )); then echo "✅ 모든 테이블 행 수 일치"; else echo "⚠️ 차이 $diff개 테이블"; exit 2; fi
