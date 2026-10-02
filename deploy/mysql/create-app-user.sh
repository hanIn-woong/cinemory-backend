#!/usr/bin/env bash
# 앱 DB 계정 생성 + cinemory.env의 DB_PASSWORD 채우기 (deploy-spec 2-4)
# 실행: sudo bash deploy/mysql/create-app-user.sh
#
# 비밀번호는 명령줄 인자·화면·셸 히스토리 어디에도 나타나지 않는다.
# 생성(openssl) → 셸 변수 → mysql 표준 입력 / env 파일로만 흐른다. printf는 셸 내장이라 프로세스 목록에도 안 뜬다.
set -euo pipefail

ENV_FILE=/etc/cinemory/cinemory.env

[[ $EUID -eq 0 ]] || { echo "sudo로 실행하세요." >&2; exit 1; }
[[ -f $ENV_FILE ]] || { echo "$ENV_FILE 이 없습니다 — 2-4의 템플릿 설치를 먼저 하세요." >&2; exit 1; }
# 재실행으로 이미 쓰고 있는 비밀번호를 덮어쓰지 않게 — 비어 있을 때만 진행한다.
grep -qx 'DB_PASSWORD=' "$ENV_FILE" || { echo "DB_PASSWORD가 비어 있지 않습니다 — 중단합니다." >&2; exit 1; }

# 16진수만 — systemd EnvironmentFile의 이스케이프나 Spring 플레이스홀더(${...})로 해석될 문자가 없다.
pw=$(openssl rand -hex 24)

printf "CREATE DATABASE IF NOT EXISTS cinemory CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'cinemory_app'@'localhost' IDENTIFIED BY '%s';
GRANT ALL PRIVILEGES ON cinemory.* TO 'cinemory_app'@'localhost';
" "$pw" | mysql

# env 파일의 DB_PASSWORD= 줄만 바꾼다. mktemp는 600으로 만들고, 같은 디렉터리(700 root)라 mv가 원자적이다.
tmp=$(mktemp "${ENV_FILE}.XXXXXX")
while IFS= read -r line || [[ -n $line ]]; do
    if [[ $line == 'DB_PASSWORD=' ]]; then
        printf 'DB_PASSWORD=%s\n' "$pw"
    else
        printf '%s\n' "$line"
    fi
done < "$ENV_FILE" > "$tmp"
mv "$tmp" "$ENV_FILE"

echo "완료 — cinemory_app 계정 생성, $ENV_FILE 의 DB_PASSWORD 채움(값은 출력하지 않음)."
