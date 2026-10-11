# 운영 런북 (CineMory 운영 서버)

> **목적** — 운영 중에 일어나는 일을 **증상 → 확인 → 조치** 순서로 따라 할 수 있게 담는다. 구축(처음부터 만들기)은 `server-setup-runbook.md`,
> 결정과 근거는 `deploy-spec.md`(8절 CI/CD · 10절 Phase 6). 서버에 설치하는 파일의 확정본은 리포 `deploy/`다.
>
> ⚠️ **비밀번호·키 평문 금지** — 이 문서와 명령 출력에 비밀을 적지 않는다. 비밀은 서버 `/etc/cinemory/cinemory.env`(600)에만 있다.
> 명령은 **어디서 실행하는지**(로컬 PowerShell / 서버 bash / 콘솔)를 표시한다. 서버 접속: `ssh -i $HOME\.ssh\cinemory-key.pem ubuntu@api.cinemory.co.kr`

---

## 0. 한눈에 — 무엇이 지켜보고 무엇이 남는가

| 장치 | 무엇을 | 알림·결과 | 설정 위치 |
|---|---|---|---|
| **UptimeRobot** `cinemory-api` | 5분마다 `https://api.cinemory.co.kr/actuator/health`에 `"status":"UP"` | Down/Up 이메일 | uptimerobot.com |
| **CloudWatch** `cinemory-prod-system-check` | 시스템 상태 확인 2분 연속 실패 | **자동 복구**(다른 호스트로) + 이메일 | EC2 › 인스턴스 › CloudWatch 알람 · SNS `cinemory-alerts` |
| **CloudWatch** `cinemory-prod-instance-check` | 인스턴스 상태 확인 3분 연속 실패 | **재부팅** + 이메일 | 같음 |
| **DLM** `cinemory daily snapshot 7d` | 매일 03:00 KST EBS 스냅샷(`cinemory-daily`) | 7개 보관 | EC2 › Lifecycle Manager · 볼륨 태그 `cinemory:backup=daily` |
| **덤프 timer** `cinemory-backup.timer` | 매일 03:30 KST `mysqldump` | `/var/backups/cinemory` 7일 | 서버 systemd · 리포 `deploy/backup/` |
| **CI 배포** | `main` push(문서 제외) → 스냅샷 → 교체 → 헬스 → 실패 시 자동 복구 | Actions 결과 · 스냅샷 `cinemory-pre-deploy-*` 5개 | `.github/workflows/deploy.yml` |
| **certbot** | 인증서 자동 갱신(systemd timer) | — (만료 알림 없음) | 서버 |

> ⚠️ **인증서 만료 사전 알림이 없다** — UptimeRobot의 인증서 만료 알림은 유료다(2026-10-11 확인, P6-3). 대신 ① §1 주간 점검의 `certbot renew --dry-run`과 남은 일수,
> ② 만에 하나 만료되면 HTTPS가 실패해 **UptimeRobot이 Down을 보낸다**(사후). Let's Encrypt는 90일 인증서를 **30일 전부터** 갱신하므로 주간 점검이면 실패를 3주 이상 앞서 본다.

**스냅샷 이름으로 구분** — `cinemory-daily`(DLM, 7개) · `cinemory-pre-deploy-<커밋>`(CI, 5개) · `cinemory-schema-only-…`·`cinemory-data-migrated-…`(수동, 지우지 않는다).

---

## 1. 정기 점검 (주 1회, 5분)

서버 bash:
```bash
systemctl list-timers cinemory-backup.timer certbot.timer --no-pager   # 두 timer 모두 NEXT가 있다
sudo ls -lt /var/backups/cinemory | head -8                           # 최근 7일치, 크기가 갑자기 줄지 않았다
sudo journalctl -u cinemory-backup --since "-8 days" --no-pager | grep -E "백업 완료|error|실패" | tail -8
sudo certbot renew --dry-run 2>&1 | tail -2                           # Congratulations, all simulated renewals succeeded
sudo certbot certificates 2>/dev/null | grep -E "Expiry"              # VALID: NN days — 30일 미만이면 갱신이 실패하고 있다(§3-3)
df -h /                                                                # 사용률 80% 미만
sudo journalctl -u cinemory --since "-7 days" -p err --no-pager | tail -20   # 앱 에러 로그
```
콘솔: EC2 › Snapshots에 `cinemory-daily`가 **7개 안팎**, CloudWatch 알람 2개 **OK**, UptimeRobot **Up**.

---

## 2. 배포 · 재배포 · 롤백

- **배포** — PR을 `main`에 머지하면 자동(`docs/**`·`*.md`만 바뀐 push는 배포하지 않는다). 결과는 GitHub › Actions › `deploy` › Summary.
- **재배포**(같은 코드로 다시) — Actions › `deploy` › **Run workflow**(main).
- **자동 복구** — 새 jar가 180초 안에 UP이 안 되면 서버 스크립트가 `app.jar.prev`로 되돌린다. 종료 코드: `0` 성공 · `2` 복구됨 · `3` 복구도 실패 · `1` 사전 조건 실패.
  - `2`면 원인을 고쳐 재배포한 **직후**, 그 배포에 백필이 있는 확장 마이그레이션이 있었다면 해당 스펙의 **보정 쿼리**를 실행한다(deploy-spec D-3 조건 6).
  - 실패한 jar는 서버 `/opt/cinemory/app.jar.failed`, 로그는 Actions 출력의 `journalctl` 60줄.
- **수동 롤백**(자동 복구 이후 더 이전 버전으로 가야 할 때) — 서버 bash:
  ```bash
  sudo cp -p /opt/cinemory/app.jar.prev /opt/cinemory/app.jar && sudo systemctl restart cinemory
  ```
  ⚠️ 롤백이 성립하는 조건은 확장/축소(D-3 조건 6) — 새 버전이 옛 코드가 쓰는 컬럼을 지웠다면 옛 jar도 뜨지 못한다(→ §4 스냅샷 복원).
- 배포 스크립트 리허설·CI 준비 절차: `server-setup-runbook.md` 13절.

---

## 3. 증상별 대응

### 3-1. UptimeRobot "Down" 메일

서버 bash에서 바깥 → 안쪽 순서로 좁힌다.
```bash
curl -s http://127.0.0.1:8080/actuator/health; echo      # ① 앱 자체
sudo systemctl status cinemory --no-pager | head -5       # ② active (running)인가
sudo journalctl -u cinemory -n 80 --no-pager              # ③ 마지막 로그
sudo systemctl status nginx mysql --no-pager | grep -E "●|Active"   # ④ Nginx·MySQL
df -h /                                                   # ⑤ 디스크 가득
```

| ① 결과 | 판단 · 조치 |
|---|---|
| `{"status":"UP"...}` | 앱은 정상 — **Nginx·인증서·DNS**. `sudo nginx -t`, `curl -sI https://api.cinemory.co.kr`(인증서 오류?), 보안 그룹 80/443 |
| `{"status":"DOWN"}` | 앱은 떴는데 DB 헬스 실패 — ④의 MySQL, ⑤ 디스크 |
| 연결 거부 | 앱이 죽었다 — ③ 로그의 마지막 예외. `ProdStartupGuard` 메시지면 env(`/etc/cinemory/cinemory.env`), Flyway면 §3-4. 재시작: `sudo systemctl restart cinemory` |
| ssh 자체가 안 된다 | 인스턴스 문제 — EC2 콘솔 › 상태 확인. CloudWatch 알람이 복구·재부팅 중일 수 있다(§3-2) |

### 3-2. CloudWatch 알람 메일

- **system-check** — AWS가 인스턴스를 다른 호스트로 **자동 복구**한다(IP·EBS 유지). 몇 분 뒤 UptimeRobot이 Up으로 돌아오는지 본다. 반복되면 AWS 상태 페이지.
- **instance-check** — **재부팅**된다. 앱·MySQL·Nginx는 systemd가 다시 띄운다. 재부팅 후 `sudo journalctl -b -1 -p err --no-pager | tail -30`(직전 부팅의 에러)로 원인을 본다 — 메모리 부족(`Out of memory`)이 흔하다.

### 3-3. 인증서

certbot이 systemd timer로 자동 갱신하고, 갱신 시 `--deploy-hook`으로 Nginx를 reload한다(구축 10-②).
```bash
sudo certbot certificates                 # 만료일 · 남은 일수
sudo certbot renew --dry-run              # 갱신 경로 점검
sudo certbot renew && sudo systemctl reload nginx   # 수동 갱신(만료 임박·실패 시)
```
갱신 실패의 흔한 원인: 80번 포트 차단(보안 그룹), `/.well-known/acme-challenge/` 경로 변경.

### 3-4. Flyway 마이그레이션 실패로 기동 실패

증상: 배포 후 자동 복구(`2`) 또는 `3`, 로그에 `FlywayException` / `Migration … failed`.
- MySQL DDL은 트랜잭션으로 되돌릴 수 없어 **절반만 적용**됐을 수 있다. `flyway_schema_history`에 `success = 0` 행이 남으면 옛 jar·새 jar 모두 검증에서 막힌다.
- **1순위: 배포 직전 스냅샷으로 복원**(§4-3) — Actions Summary의 스냅샷 ID. 가장 확실하다.
- 스냅샷 복원이 과하면(데이터가 그 사이 쌓였다): 실패한 마이그레이션의 **부분 적용을 손으로 되돌린 뒤** 실패 기록만 지운다(= `flyway repair`가 하는 일).
  ```bash
  sudo mysql cinemory -e "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;"
  # 부분 적용된 DDL을 되돌린 뒤
  sudo mysql cinemory -e "DELETE FROM flyway_schema_history WHERE success = 0;"
  ```
  고친 마이그레이션은 **새 번호 `V{n+1}`** 로 낸다 — 적용된(실패 포함) 파일은 고치지 않는다(CLAUDE.md 동결 규칙).

---

## 4. 백업 · 복원

### 4-1. 덤프(논리 백업)

- 위치 `/var/backups/cinemory/cinemory-YYYYMMDD-HHMM.sql.gz`(root 600), 7일. ⚠️ **사용자 데이터 포함 — 서버 밖으로 내리지 않는다.**
- 수동 실행: `sudo systemctl start cinemory-backup` → `sudo journalctl -u cinemory-backup -n 5 --no-pager`
- **복원 리허설**(분기 1회 권장 · 마이그레이션이 크게 바뀐 뒤): `sudo bash ~/cinemory/deploy/backup/restore-rehearsal.sh` → `✅ 모든 테이블 행 수 일치`

### 4-2. 덤프로 복원

**테이블 하나**(잘못된 UPDATE/DELETE 되돌리기) — 운영을 멈추지 않는다.
```bash
sudo mysql -e "CREATE DATABASE cinemory_restore CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
sudo bash -c 'zcat /var/backups/cinemory/<파일>.sql.gz | mysql cinemory_restore'
# cinemory_restore.<테이블>에서 필요한 행을 확인해 cinemory로 INSERT … SELECT / UPDATE … JOIN
sudo mysql -e "DROP DATABASE cinemory_restore;"
```

**DB 전체** — 앱을 멈추고 한다. 덤프 시각 이후의 쓰기는 사라진다.
```bash
sudo systemctl stop cinemory
sudo systemctl start cinemory-backup                    # 지금 상태도 남겨 둔다(되돌릴 길)
sudo mysql -e "DROP DATABASE cinemory; CREATE DATABASE cinemory CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
sudo bash -c 'zcat /var/backups/cinemory/<복원할 파일>.sql.gz | mysql cinemory'
sudo systemctl start cinemory && sleep 30 && curl -s http://127.0.0.1:8080/actuator/health; echo
```
앱 DB 계정(`cinemory_app`)의 권한은 DB 단위라 DROP/CREATE 뒤에도 유지된다 — 기동 실패 시 `SHOW GRANTS FOR 'cinemory_app'@'localhost';`.

### 4-3. EBS 스냅샷으로 복원 (서버 전체를 그 시점으로)

디스크 손상 · 마이그레이션 사고 · 서버 설정이 망가졌을 때. **스냅샷 이후의 모든 변경(데이터·설정)이 사라진다.**
1. 콘솔 EC2 › Instances › `cinemory-prod` › **Actions › Monitor and troubleshoot › Replace root volume**
2. **Restore from snapshot** → 스냅샷 선택(`cinemory-daily` / `cinemory-pre-deploy-<커밋>` / 수동) → 교체
   - 인스턴스가 재부팅되며 루트 볼륨이 바뀐다. IP·인스턴스 ID·역할은 그대로다. 옛 볼륨은 남는다(확인 후 삭제).
3. 기동 후 헬스 확인 → **앱 jar도 스냅샷 시점 것**이다. 최신 코드가 필요하면 Actions **Run workflow**(마이그레이션 사고였다면 고친 뒤).
4. ⚠️ 교체 직후 **볼륨 ID가 바뀐다** — GitHub Variable `VOLUME_ID`, 배포 역할 정책의 볼륨 ARN(`deploy/iam/cinemory-github-deploy-policy.json`), 새 볼륨의 태그 `cinemory:backup=daily`(DLM 대상)를 **모두 새 ID로** 고친다. 안 하면 다음 배포의 스냅샷 단계와 일일 스냅샷이 실패한다.

---

## 5. 지우지 말 것

- 수동 스냅샷 #1 `cinemory-schema-only-v22-20261007` · #2 `cinemory-data-migrated-20261008` — 태그가 없어 CI·DLM 정리 대상이 아니다.
- **발표용 로컬 폴백**(deploy-spec 4-INF) — 로컬 기동이 되는 상태를 유지한다.

---

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-10-11 | 최초 작성 — 인증서 만료 알림은 UptimeRobot 유료라 **주간 점검(dry-run · 남은 일수)으로 대신**(§0 주석). 배포 Phase 6(deploy-spec 10절 P6-4). 감시 장치 7종을 한 표로(§0), 주간 점검(§1), 배포·롤백(§2, 구축 런북 13절과 연결), 증상별 대응(§3 — 업타임 Down은 바깥→안쪽 순서), 백업·복원 3단계(§4 — 테이블 하나 / DB 전체 / EBS 루트 볼륨 교체). **루트 볼륨 교체 후 볼륨 ID가 바뀌어 CI 변수·IAM 정책·DLM 태그를 함께 고쳐야 한다**는 함정을 §4-3에 남겼다. Flyway 실패는 서버에 Flyway CLI가 없어 `repair`를 SQL로 대신한다(§3-4) |
