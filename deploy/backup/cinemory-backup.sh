#!/usr/bin/env bash
# 운영 DB 일일 논리 백업 (deploy-spec 10절 P6-1·P6-2)
# 설치: sudo install -m 750 deploy/backup/cinemory-backup.sh /usr/local/sbin/cinemory-backup
# 실행: systemd timer(cinemory-backup.timer)가 매일 03:30 KST. 수동: sudo systemctl start cinemory-backup
#
# ⚠️ 덤프에는 사용자 데이터(이메일·비밀번호 해시)가 있다 — 파일 600, 디렉터리 700, 서버 밖으로 내리지 않는다.
#    로컬 초기화용 역덤프는 이것이 아니라 콘텐츠 10개 테이블만 받는다(deploy-spec D-5).
# 디스크 장애·인스턴스 손실은 EBS 스냅샷(DLM, P6-2)이 맡는다. 이 덤프는 "테이블 하나를 되돌리기" 같은 논리 복구용이다.
set -euo pipefail
umask 077

DB=cinemory
BACKUP_DIR=/var/backups/cinemory
KEEP_DAYS=7

[[ $EUID -eq 0 ]] || { echo "root로 실행하세요(sudo 또는 systemd)." >&2; exit 1; }
install -d -m 700 "$BACKUP_DIR"

STAMP=$(date +%Y%m%d-%H%M)
FINAL="$BACKUP_DIR/$DB-$STAMP.sql.gz"
TMP="$FINAL.partial"
trap 'rm -f "$TMP"' EXIT

# root는 auth_socket으로 비밀번호 없이 접속한다 — 비밀번호가 명령줄·파일 어디에도 없다.
# --single-transaction: InnoDB 일관 스냅샷(잠금 없이 운영 중 덤프). --set-gtid-purged=OFF: 다른 서버·스키마로 복원할 때 GTID 충돌 방지.
# DB 이름만 넘긴다(--databases 아님) — CREATE DATABASE/USE가 없어 임시 스키마로 복원할 수 있다(복원 리허설).
mysqldump --single-transaction --quick --routines --triggers --set-gtid-purged=OFF "$DB" | gzip -6 > "$TMP"

# 완료 표식 — mysqldump는 정상 종료 시 마지막 줄에 "-- Dump completed"를 쓴다. 중간에 끊긴 덤프를 백업으로 남기지 않는다.
gzip -t "$TMP"
zcat "$TMP" | tail -n 1 | grep -q "Dump completed" || { echo "덤프가 완료 표식 없이 끝났습니다." >&2; exit 1; }

mv "$TMP" "$FINAL"
trap - EXIT

# 7일 보관 — 오늘 것을 포함해 KEEP_DAYS개 날짜가 남는다.
find "$BACKUP_DIR" -maxdepth 1 -name "$DB-*.sql.gz" -mtime +$((KEEP_DAYS - 1)) -print -delete

echo "백업 완료: $FINAL ($(du -h "$FINAL" | cut -f1)) · 보관 $(ls -1 "$BACKUP_DIR"/$DB-*.sql.gz | wc -l)개"
