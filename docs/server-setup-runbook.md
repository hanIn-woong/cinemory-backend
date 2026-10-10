# 운영 서버 구축 런북 (AWS EC2)

> **목적** — 빈 AWS 계정에서 시작해 `https://api.cinemory.co.kr/actuator/health`가 `UP`이 될 때까지,
> **처음 하는 사람도 그대로 따라 할 수 있는** 순서와 명령을 담는다. 2026-10-07 첫 구축을 그대로 옮겼다.
>
> **설계 근거는 여기가 아니라 `docs/deploy-spec.md` 6절(Phase 2)에 있다.** 왜 그 사양·설정인지는 그쪽,
> 여기는 순서·화면·명령·기대 출력만 담는다. 서버에 설치하는 설정 파일의 확정본은 리포 `deploy/`다.
>
> 범위는 Phase 2까지 — 데이터 이관(Phase 3)·CI/CD(Phase 4)는 deploy-spec 7·8절.

---

## 전체 그림

```
[로컬 Windows PC]                                  [EC2 t4g.small · Ubuntu 24.04 arm64]
  .pem 키 ── ssh ──────────────────────────────▶  ~/cinemory/deploy/   (설정 파일 원본)
  deploy/ ── scp ──────────────────────────────▶   ├ /etc/mysql/mysql.conf.d/zz-cinemory.cnf
  bootJar ── scp ──────────────────────────────▶   ├ /etc/cinemory/cinemory.env      (비밀, 600 root)
                                                   ├ /etc/systemd/system/cinemory.service
[가비아 DNS] api.cinemory.co.kr ─ A ─▶ 탄력적 IP     ├ /etc/nginx/sites-available/cinemory
                                                   └ /opt/cinemory/app.jar
```

### 이번 구축에서 쓴 이름

| 항목 | 값 |
|---|---|
| 리전 | 서울 `ap-northeast-2` |
| 인스턴스 이름 | `cinemory-prod` |
| 보안 그룹 | `cinemory-sg` |
| 키 페어 | `cinemory-key` (로컬 `C:\Users\<사용자>\.ssh\cinemory-key.pem`) |
| 도메인 | `cinemory.co.kr`(가비아) → API 주소 **`api.cinemory.co.kr`** |
| 서버 작업 폴더 | `~/cinemory` (그 아래 `deploy/`) |

> ⚠️ **이 문서에 적지 않는 것** — 탄력적 IP, AWS 계정 ID, 비밀번호·키 값. 명령 안의 `<탄력적IP>`는
> **꺾쇠까지 지우고** 실제 값을 넣는다(꺾쇠를 남기면 `Could not resolve hostname`).

### 명령을 어디서 실행하는가

| 표시 | 위치 |
|---|---|
| `powershell` | **로컬 PC의 PowerShell**(프롬프트 `PS C:\...>`). cmd가 아니다 — `$HOME`·`$env:USERNAME`이 cmd에선 동작하지 않는다 |
| `bash` | **서버 안**(ssh 접속 후, 프롬프트 `ubuntu@ip-...:~/cinemory$`) |

> 💡 **긴 명령은 한 줄 통째로 붙여 넣고, Enter 전에 줄 끝까지 들어갔는지 본다.** 첫 구축에서 줄이 잘려
> `install: missing destination file operand`가 나고, 다른 시도에서는 이름이 깨진 파일이 생겼다(진단 표 참고).

---

## 0. 사전 준비 (로컬)

1. **도메인을 가장 먼저 산다**(8단계 절차). DNS 반영에 수 분~수십 분이 걸리므로, 서버 작업 동안 반영되게 한다.
   첫 구축에서는 5단계 뒤에 사서 대기가 생겼다.
2. **운영 환경변수 값**을 모아 둔다 — 로컬 `config/application-secret.yml`에 있다(9-②의 대응표).
3. `.\gradlew bootJar`가 되는 main 최신 상태.

---

## 1. AWS 계정 (deploy-spec 2-1)

### 1-1. 가입

- 루트 이메일은 오래 쓸 주소로(비밀번호 재설정·결제 알림 수신).
- 해외결제 가능한 카드(소액 승인 후 취소), 휴대폰 인증.
- **Support 플랜: Basic(무료).**
- **Free plan / Paid plan** 선택 화면 — D-1은 **Free plan → 6개월 뒤 유료 전환**을 전제로 한다.

### 1-2. 루트 MFA

우상단 계정 이름 → **Security credentials** → **Assign MFA device**.

- 첫 구축은 **패스키(구글 비밀번호 관리자)** 로 등록했다. 인증 앱(Google Authenticator 등)도 된다.
- 패스키를 쓰면 루트의 안전이 **구글 계정의 안전**에 기댄다 — 구글 계정 2단계 인증 필수.
- MFA 기기는 최대 8개 — **예비(인증 앱)를 하나 더** 등록해 두면 구글 계정 문제 시 잠기지 않는다.
- 패스키가 없는 PC에서는 화면의 QR을 휴대폰으로 스캔해 인증(휴대폰 블루투스 켜기).
- **루트 액세스 키는 만들지 않는다.**

### 1-3. IAM 사용자 (이후 모든 작업은 이 사용자로)

루트로 로그인한 상태에서:

1. 검색창 `IAM` → **Users → Create user**. 이름 예: `<이름>-admin`.
2. ☑ **Provide user access to the AWS Management Console** → Identity Center 안내가 나오면
   **I want to create an IAM user**. 비밀번호는 Custom password.
3. **Attach policies directly** → **`AdministratorAccess`** 체크.
   검색하면 여러 개가 뜬다 — 이름 뒤에 아무것도 없고 유형이 **"AWS 관리형 - 직무"** 인 것.
   `AdministratorAccess-Amplify` 등 접미사 붙은 것은 서비스 전용이다.
4. 생성 화면의 **Console sign-in URL**(`https://<계정ID 12자리>.signin.aws.amazon.com/console`) 저장.
5. **결제 정보 접근 허용(루트로 1회)** — 우상단 계정 이름 → **Account** →
   **IAM user and role access to Billing information** → Edit → **Activate IAM Access**.
   ⚠️ 이걸 안 켜면 `AdministratorAccess`가 있어도 IAM 사용자가 **Budgets·결제 화면에 못 들어간다.**
6. 루트 로그아웃 → IAM 사용자로 로그인 → **Security credentials**에서 이 사용자에게도 MFA 등록.
7. **액세스 키는 만들지 않는다** — 콘솔 작업엔 불필요. CI용 키는 Phase 4에서 권한을 좁힌 별도 사용자로.

**IAM 로그인 화면 입력값**

| 칸 | 값 |
|---|---|
| Account ID | 계정 ID **12자리 숫자만** — ⚠️ 콘솔에 `1234-5678-9012`처럼 대시로 표시되지만 **대시 없이** 입력한다(넣으면 "인증 정보가 정확하지 않음") |
| IAM username | 1번에서 정한 이름 |
| Password | 2번에서 정한 비밀번호(브라우저가 루트 비밀번호를 자동 입력하지 않았는지 확인) |

로그인 화면에서 "Root user"가 아니라 **"IAM user"** 를 골라야 이 칸들이 나온다.

### 1-4. Budgets 알림

검색창 `Budgets` → **Create budget** → **Customize (advanced)** → **Cost budget**.

| 단계 | 설정 |
|---|---|
| Set budget | Period **Monthly**, Amount **`$20`**. Tags는 비워 둔다 |
| Configure alerts | **Add an alert threshold** 3개 — `50`% Actual($10) · `100`% Actual($20) · `100`% Forecasted. 각각 이메일 |
| Attach actions | 건너뜀 |

예산 하나에 임계값 둘이 deploy-spec의 *"$10 / $20 두 단계"* 다.

### 1-5. 리전 · 마감일

- 우상단 리전 **아시아 태평양(서울)**. IAM·Budgets는 "Global"로 보이는 게 정상 — **EC2 화면에서 꼭 확인.**
- Billing and Cost Management 홈의 Free plan 영역에서 **유료 전환 마감일 → 캘린더 등록.**

---

## 2. EC2 인스턴스 (deploy-spec 2-2)

검색창 `EC2`(또는 서비스 → Compute → EC2) → 리전 서울 확인 → **Launch instance**.

| 항목 | 값 |
|---|---|
| Name | `cinemory-prod` |
| AMI | **Ubuntu Server 24.04 LTS**, 아키텍처 **64-bit (Arm)** ← 기본값 x86, 반드시 바꾼다 |
| Instance type | **`t4g.small`** (*Free tier eligible* 표시 확인) |
| Key pair | **Create new key pair** → `cinemory-key`, **RSA**, **.pem** |
| Network settings | 아래 표 |
| Storage | **30 GiB, gp3** |

### Network settings

VPC·Subnet·Auto-assign public IP는 **기본값 그대로**. 방화벽만 설정한다.
화면이 두 가지로 나온다 — 블록 우상단 **Edit** 여부에 따라.

**(가) 기본 화면 — 체크박스**: Create security group 선택 후
☑ Allow SSH traffic from → **My IP**(Anywhere 금지) · ☑ Allow HTTPS · ☑ Allow HTTP.

**(나) Edit 화면 — 이름·설명 칸이 보이는 경우**(첫 구축은 이쪽)

- Security group name `cinemory-sg`, Description은 영문만(예: `cinemory prod - ssh my ip, http/https`).
- 규칙 1은 이미 있다(ssh). **Add security group rule**로 2개 추가해 최종 **딱 3개**:

| Type | Port | Source type |
|---|---|---|
| ssh | 22 | **My IP** (`x.x.x.x/32`) |
| HTTP | 80 | Anywhere (`0.0.0.0/0`) |
| HTTPS | 443 | Anywhere (`0.0.0.0/0`) |

**3306·8080은 추가하지 않는다.** Advanced network configuration은 펼치지 않는다.

### 생성 후

1. **Instances**에서 Instance state **Running**, Status check **`3/3 checks passed`**(시스템·인스턴스·EBS —
   deploy-spec 초판의 "2/2"는 옛 표기). 생성 직후 1~3분은 Initializing.
2. **`.pem` 보관** — 다운로드는 **한 번뿐**. `C:\Users\<사용자>\.ssh\`로 옮기고 다른 곳에도 백업. **리포 폴더 금지.**
3. **탄력적 IP** — Network & Security → **Elastic IPs → Allocate**(기본값) →
   **Actions → Associate** → Resource type Instance, `cinemory-prod`.
   인스턴스 상세의 Public IPv4가 이 IP로 바뀌었는지 확인. 연결된 퍼블릭 IPv4도 시간당 과금(크레딧 차감)된다.

---

## 3. 첫 SSH 접속

```powershell
# 키 파일이 있는지 — False면 다운로드 폴더에서 옮긴다
Test-Path $HOME\.ssh\cinemory-key.pem

# 권한 정리(처음 1회) — 넓으면 ssh가 키 사용을 거부한다. 두 줄 모두 실행해야 한다
icacls $HOME\.ssh\cinemory-key.pem /inheritance:r
icacls $HOME\.ssh\cinemory-key.pem /grant:r "$($env:USERNAME):(R)"
icacls $HOME\.ssh\cinemory-key.pem      # 본인(R) 한 줄만 남아야 한다

ssh -i $HOME\.ssh\cinemory-key.pem ubuntu@<탄력적IP>
```

- `icacls` 성공 출력은 *"1 파일을 처리했으며 0 파일은 처리하지 못했습니다"* — **실패가 아니라 성공**이다.
- ⚠️ 첫 줄(`/inheritance:r`)만 하고 멈추면 **본인도 못 읽는 파일**이 된다. 둘째 줄까지 한다.
- 첫 접속의 `Are you sure you want to continue connecting` → **`yes`**(전체 입력). 지문이 `known_hosts`에 저장된다.
  이후 이 질문이나 `REMOTE HOST IDENTIFICATION HAS CHANGED`가 다시 뜨면, 인스턴스를 새로 만든 게 아닌 한 의심한다.
- `ubuntu@ip-172-...:~$`가 뜨면 성공.

---

## 4. 서버 기본 설정 (deploy-spec 2-2)

```bash
sudo timedatectl set-timezone Asia/Seoul
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
sudo apt update && sudo apt install -y openjdk-21-jre-headless unattended-upgrades
```

설치 중 서비스 재시작을 묻는 보라색 화면이 뜨면 Enter(OK).

```bash
timedatectl | grep "Time zone"   # Asia/Seoul (KST, +0900)
free -h                          # Swap: 2.0Gi
java -version                    # openjdk 21 ... aarch64
```

---

## 5. `deploy/` 올리기

리포를 서버에 클론하지 않고 `deploy/`만 올린다(비공개 리포 인증이 필요 없다).

```bash
mkdir -p ~/cinemory
```

```powershell
# 새 PowerShell 창(로컬). ssh 창이 아니다
scp -i $HOME\.ssh\cinemory-key.pem -r C:\Users\<사용자>\capstone\cinemory-backend\deploy ubuntu@<탄력적IP>:~/cinemory/
```

```bash
cd ~/cinemory
find deploy -type f
# deploy/cinemory.env.example
# deploy/mysql/create-app-user.sh
# deploy/mysql/zz-cinemory.cnf
# deploy/nginx/cinemory.conf
# deploy/systemd/cinemory.service
```

**이후 서버 명령은 전부 `~/cinemory`에서 실행한다.** 재접속하면 `cd ~/cinemory`부터.

> 💡 줄바꿈 — 리포 `.gitattributes`가 `eol=lf`라 Windows에서 올려도 LF다. 스크립트가
> `$'\r': command not found`를 내면 CRLF로 바뀐 것이다: `file deploy/mysql/create-app-user.sh`로 확인
> (`with CRLF line terminators`가 붙으면 CRLF).

---

## 6. MySQL 8.0 (deploy-spec 2-3)

```bash
sudo apt update
sudo apt install -y mysql-server
```

⚠️ **설치를 반드시 확인한 뒤 넘어간다** — 첫 구축에서 설치가 실패(첫 부팅 직후 자동 업데이트의 apt 잠금으로 추정)한 것을
놓치고 진행해, 8단계 스크립트에서야 `mysql: command not found`로 드러났다.

```bash
mysql --version            # mysql  Ver 8.0.xx-0ubuntu0.24.04.x for Linux on aarch64
systemctl is-active mysql  # active
```

- 두 명령을 함께 붙여 넣으면 첫 줄 출력이 묻혀 `active`만 보일 수 있다 — **따로** 실행한다.
- `Could not get lock /var/lib/dpkg/lock-frontend` → 1~2분 뒤 같은 명령 재실행.

```bash
sudo install -m 644 deploy/mysql/zz-cinemory.cnf /etc/mysql/mysql.conf.d/zz-cinemory.cnf
sudo systemctl restart mysql          # 성공 시 출력 없음. 실패하면 sudo journalctl -u mysql -n 30
sudo mysql -e "SELECT @@global.time_zone, @@character_set_server, @@collation_server, @@innodb_buffer_pool_size, @@bind_address;"
```

| 컬럼 | 기대값 |
|---|---|
| time_zone | `+09:00` |
| character_set_server | `utf8mb4` |
| collation_server | `utf8mb4_0900_ai_ci` |
| innodb_buffer_pool_size | `402653184` |
| bind_address | `127.0.0.1` |

---

## 7. DB 계정 (deploy-spec 2-4)

```bash
sudo install -d -o root -g root -m 700 /etc/cinemory
sudo install -m 600 -o root -g root deploy/cinemory.env.example /etc/cinemory/cinemory.env
sudo ls -l /etc/cinemory/                 # cinemory.env 하나만, -rw------- root root
sudo bash deploy/mysql/create-app-user.sh
# 완료 — cinemory_app 계정 생성, /etc/cinemory/cinemory.env 의 DB_PASSWORD 채움(값은 출력하지 않음).
```

비밀번호는 스크립트가 만들어 MySQL과 env 파일에만 넣는다 — **사람이 보거나 칠 일이 없다.**

```bash
sudo mysql -e "SHOW GRANTS FOR 'cinemory_app'@'localhost';"
# GRANT USAGE ON *.* ...  /  GRANT ALL PRIVILEGES ON `cinemory`.* ...
sudo mysql -e "SHOW DATABASES;"                          # cinemory 포함
sudo grep -c '^DB_PASSWORD=.\+' /etc/cinemory/cinemory.env   # 1
```

| 스크립트 메시지 | 의미 · 조치 |
|---|---|
| `DB_PASSWORD가 비어 있지 않습니다` | 이미 성공한 상태. 재실행하지 말고 위 확인만 |
| `mysql: command not found` | MySQL 미설치 → 6단계부터. 스크립트는 첫 쓰기 전에 멈추므로 **부작용 없음**, 설치 후 그대로 재실행 |
| `ERROR 1396 ... CREATE USER failed` | 계정은 있는데 env에 비밀번호가 없는 상태 — 계정 삭제 후 재실행 필요 |

---

## 8. 도메인 (deploy-spec 2-6 앞부분) — 0단계에서 미리

가비아 기준.

1. 이름 검색 → **등록 가능** 확인(`cinemory.com`은 선점돼 있어 `cinemory.co.kr`로 갔다. 확장자는 동작에 차이 없다).
2. 신청: 기간 1년, **WHOIS 공개 대행** 켜기, 네임서버 **가비아 기본값**, 호스팅 등 **추가 상품 해제**.
3. **My가비아 → DNS 관리툴** → 도메인 **설정 → 레코드 추가 → 저장**(저장을 눌러야 반영):

| 타입 | 호스트 | 값/위치 | TTL |
|---|---|---|---|
| A | `api` | `<탄력적IP>` | 600 |

API를 서브도메인 `api.`에 두는 이유 — 루트 도메인을 나중에 웹페이지 등에 쓸 여지를 남긴다.

```powershell
Resolve-DnsName api.cinemory.co.kr -Type A     # IPAddress = 탄력적 IP. 처음엔 "이름 없음"일 수 있다
```

---

## 9. 앱 서비스 (deploy-spec 2-5)

### ① 실행 사용자 · 폴더

```bash
sudo useradd --system --no-create-home --shell /usr/sbin/nologin cinemory
sudo install -d -o cinemory -g cinemory -m 755 /opt/cinemory
```

### ② env 파일 채우기

JWT 비밀키는 **서버에서 새로** 만든다(로컬 값 재사용 금지).

```bash
openssl rand -base64 64 | tr -d '\n'; echo
```

⚠️ `tr -d '\n'`을 빼면 출력이 **두 줄**로 나뉜다(88자를 64자에서 줄바꿈). 그대로 붙이면 키가 잘린다.

```bash
sudo nano /etc/cinemory/cinemory.env
```

| env 키 | 값 (로컬 `config/application-secret.yml`) |
|---|---|
| `DB_URL` `DB_USERNAME` `DB_PASSWORD` | **건드리지 않는다**(템플릿·7단계가 채움) |
| `JWT_SECRET` | 위에서 만든 한 줄 |
| `TMDB_ACCESS_TOKEN` | `tmdb.access-token` |
| `KOFIC_API_KEY` | `kofic.api-key` |
| `MAIL_USERNAME` | `spring.mail.username` |
| `MAIL_PASSWORD` | `spring.mail.password` — Gmail 앱 비밀번호 16자, 공백 없이 |
| `MAIL_FROM` | `mail.password-reset.from` — username과 같은 값 |
| `KAKAO_ALLOWED_AUDIENCES` | **네이티브 앱 키 하나만**(주석 `네이티브 앱 키 (RN SDK용)`). REST API 키는 넣지 않는다 |
| `GOOGLE_ALLOWED_AUDIENCES` | `oauth.google.allowed-audiences` — **구글 웹 클라이언트 ID 하나만**(2026-10-11 추가, 소셜 로그인 PR 머지 **전에** 넣는다 — deploy-spec 1-1) |

- 형식은 `KEY=value` — **따옴표·공백·`export` 금지**(systemd 문법).
- nano: 붙여 넣기 = 마우스 오른쪽 클릭(Windows 터미널), 저장 `Ctrl+O` → Enter, 종료 `Ctrl+X`.

```bash
sudo grep -E '^[A-Z_]+=$' /etc/cinemory/cinemory.env || echo "빈 키 없음"
```

키 이름이 출력되면 그 키가 비어 있다. 비어 있으면 `ProdStartupGuard`가 키 이름을 모아 기동을 실패시킨다 —
**값이 틀린 것은 잡지 못한다**(Phase 3-4·5에서 확인).

### ③ 유닛

```bash
sudo install -m 644 deploy/systemd/cinemory.service /etc/systemd/system/cinemory.service
sudo systemd-analyze verify /etc/systemd/system/cinemory.service    # 출력 없음 = 통과
sudo systemctl daemon-reload && sudo systemctl enable cinemory
# Created symlink /etc/systemd/system/multi-user.target.wants/cinemory.service → ...  (enable 성공 = 재부팅 시 자동 기동)
```

### ④ jar 빌드 · 업로드 · 배치

```powershell
cd C:\Users\<사용자>\capstone\cinemory-backend
git switch main; git pull
.\gradlew bootJar
Get-ChildItem build\libs        # -plain 이 붙지 않은 것 (예: cinemory-0.0.1-SNAPSHOT.jar, 약 69MB)
scp -i $HOME\.ssh\cinemory-key.pem build\libs\cinemory-0.0.1-SNAPSHOT.jar ubuntu@<탄력적IP>:~/app.jar
```

비밀 파일이 jar에 들어가지 않았는지(Phase 1-1) — Git Bash: `unzip -l build/libs/*SNAPSHOT.jar | grep -i secret` → **출력 없어야** 한다.

```bash
sudo install -m 644 -o cinemory -g cinemory ~/app.jar /opt/cinemory/app.jar && rm ~/app.jar
ls -l /opt/cinemory/            # cinemory cinemory ... app.jar
```

**아직 `start`하지 않는다** — Nginx·HTTPS를 갖춘 뒤 11단계에서.

---

## 10. Nginx · HTTPS (deploy-spec 2-6)

### ① 설치 · 도메인 연결 확인

```bash
sudo apt install -y nginx certbot
curl -sI http://api.cinemory.co.kr | head -1     # HTTP/1.1 200 OK — 도메인 → 서버 → Nginx 기본 페이지
```

200이 안 나오면 인증서 발급도 실패한다 — DNS 반영(8단계)·보안 그룹 80부터 확인.

### ② 인증서 발급 (기본 사이트가 떠 있는 상태에서)

```bash
sudo certbot certonly --webroot -w /var/www/html -d api.cinemory.co.kr --deploy-hook "systemctl reload nginx"
```

- 처음엔 이메일(만료 알림 수신용), 약관 동의 `Y`, 뉴스레터 `N`을 묻는다.
- `Successfully received certificate.` → 성공.
- `--deploy-hook`은 **이 최초 명령에** 붙여야 갱신 설정에 저장된다.

### ③ 리포 설정 설치

```bash
sudo install -m 644 deploy/nginx/cinemory.conf /etc/nginx/sites-available/cinemory
sudo sed -i 's/example\.com/api.cinemory.co.kr/g' /etc/nginx/sites-available/cinemory
grep -n 'cinemory.co.kr' /etc/nginx/sites-available/cinemory
```

→ **5줄** — 3번 줄(파일 상단 주석) + `server_name` 2줄 + 인증서 경로 2줄. `example.com`이 남으면 안 된다.

```bash
sudo ln -s /etc/nginx/sites-available/cinemory /etc/nginx/sites-enabled/cinemory
sudo rm /etc/nginx/sites-enabled/default
sudo nginx -t
# nginx: the configuration file /etc/nginx/nginx.conf syntax is ok
# nginx: configuration file /etc/nginx/nginx.conf test is successful
sudo systemctl reload nginx
```

⚠️ `nginx -t`가 실패하면 **reload하지 않는다**(기존 설정으로 계속 동작한다). **서버에서 고치지 말고 리포 파일을
고쳐 다시 설치**한다 — 리포가 확정본이다.

### ④ 갱신 경로 확인

```bash
sudo certbot renew --dry-run          # Congratulations, all simulated renewals succeeded
curl -sI http://api.cinemory.co.kr | head -2    # 301 + Location: https://...
curl -sI https://api.cinemory.co.kr | head -1   # HTTP/2 502 — 앱을 아직 안 띄워서. 이 단계에선 정상
```

---

## 11. 최초 기동 · 검증 (deploy-spec 2-7)

```bash
sudo systemctl start cinemory
sudo journalctl -u cinemory -f        # Ctrl+C로 보기만 종료(앱은 계속 돈다)
```

| 로그 | 의미 |
|---|---|
| `Migrating schema `cinemory` to version "17 - baseline"` … `"22 - fix watch record ott check"` | Flyway가 빈 DB에 V17→V22 |
| `Successfully applied 6 migrations ... now at version v22` | 첫 구축 2.8초 |
| `Started CinemoryApplication in xx seconds` | 첫 구축 20초 |

실패하면 `sudo journalctl -u cinemory -n 80 --no-pager`. 5분에 5회 실패하면 유닛이 재시작을 멈춘다(무한 반복 없음).

```bash
curl -s http://127.0.0.1:8080/actuator/health; echo                      # "status":"UP"
curl -s https://api.cinemory.co.kr/actuator/health; echo                 # {"groups":["liveness","readiness"],"status":"UP"}
curl -s -o /dev/null -w '%{http_code}\n' https://api.cinemory.co.kr/v3/api-docs   # 404 (L-12)
```

**요청 제한(L-1)** — 반드시 **한 줄로** 실행한다. 10r/m이라 6초마다 1회가 회복돼, 나눠 치면 마지막이 200으로 나온다.
직전 테스트 후 1~2분 기다리고 실행하면 결과가 깔끔하다.

```bash
for i in $(seq 30); do curl -s -o /dev/null -w '%{http_code} ' -X POST https://api.cinemory.co.kr/api/auth/nonce; done; echo; curl -si -X POST https://api.cinemory.co.kr/api/auth/nonce | grep -iE '^HTTP|content-type|retry-after|TOO_MANY'
```

```
200 … (약 21개, 직전 테스트 여파로 더 적을 수 있다) 429 … 429
HTTP/2 429
content-type: application/json; charset=utf-8
retry-after: 60
{"status":429,"code":"TOO_MANY_REQUESTS","message":"요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.","errors":[]}
```

---

## 12. EBS 스냅샷 #1 ("schema-only")

1. **EC2 → Instances → `cinemory-prod` → Storage 탭** → Volume ID 클릭.
2. **Actions → Create snapshot** → Description `schema-only v22 (YYYY-MM-DD)`.
3. **EC2 → Snapshots**에서 `Completed` 확인(수 분).
4. **Name 붙이기** — 목록의 Name 칸 연필 아이콘(또는 Tags → Manage tags → Key `Name`). 첫 구축: `cinemory-schema-only-v22-20261007`.
   Description은 생성 후 못 바꾸지만 Name은 태그라 언제든 바꾼다. 자동 스냅샷(Phase 4·6)이 쌓이면 수동 지점을 찾기 어려우므로
   **`cinemory-<무엇 직전>-<날짜>`** 규칙으로 붙인다(예: `cinemory-pre-import-…`, `cinemory-pre-v23-…`).

Phase 3(데이터 이관)이 잘못되면 이 지점으로 되돌린다.

---

## 다시 접속할 때 · 자주 쓰는 명령

```powershell
ssh -i $HOME\.ssh\cinemory-key.pem ubuntu@<탄력적IP>
```

```bash
sudo systemctl status cinemory --no-pager           # 상태
sudo journalctl -u cinemory -n 100 --no-pager       # 최근 로그
sudo journalctl -u cinemory -f                      # 실시간 로그
sudo systemctl restart cinemory                     # 재시작 (env 수정 후에도)
sudo nano /etc/cinemory/cinemory.env                # 환경변수 수정 → restart 필요
sudo grep DB_PASSWORD /etc/cinemory/cinemory.env    # 화면에 나온다 — 꼭 필요할 때만
```

**jar 수동 교체** (CI/CD 전까지. 직전 버전을 `app.jar.prev`로 남겨 롤백에 쓴다 — deploy-spec 2-5)

```bash
sudo cp -p /opt/cinemory/app.jar /opt/cinemory/app.jar.prev
sudo install -m 644 -o cinemory -g cinemory ~/app.jar /opt/cinemory/app.jar && rm ~/app.jar
sudo systemctl restart cinemory && sudo journalctl -u cinemory -f
# 롤백: sudo cp -p /opt/cinemory/app.jar.prev /opt/cinemory/app.jar && sudo systemctl restart cinemory
```

⚠️ 새 jar에 새 Flyway 마이그레이션이 있으면 **롤백 jar로는 되돌릴 수 없다**(DDL은 트랜잭션 밖 — deploy-spec D-3).
마이그레이션이 든 배포 전에는 EBS 스냅샷을 먼저 찍는다.

---

## 실패했을 때 — 증상별 진단

| 증상 | 원인 · 조치 |
|---|---|
| IAM 로그인 *"인증 정보가 정확하지 않음"* | 계정 ID에 **대시**를 넣었다 → 12자리 숫자만. 그 밖에 사용자 이름 오타, 콘솔 접근 미활성(Users → Security credentials → Console sign-in), 브라우저의 루트 비밀번호 자동 입력 |
| IAM 사용자로 Budgets 화면 접근 거부 | 1-3의 5번(결제 정보 IAM 접근) 미설정 → 루트로 켠다 |
| `ssh: Could not resolve hostname` | `<탄력적IP>` 자리에 꺾쇠·자리표시가 남았거나 공백이 섞였다 |
| ssh가 멈춰 있다가 `Connection timed out` | **장소를 옮겨 내 공인 IP가 바뀌었다** → EC2 → Security Groups → `cinemory-sg` → Inbound rules → Edit → SSH Source를 다시 **My IP** → Save. 학교 등 22번을 막는 네트워크면 휴대폰 핫스팟 |
| ssh `Permission denied (publickey)` | 키 경로·사용자명(`ubuntu`) 확인. `icacls`를 첫 줄만 했으면 둘째 줄(`/grant:r`)까지 |
| ssh `UNPROTECTED PRIVATE KEY FILE` | 3단계 `icacls` 두 줄 |
| `install: missing destination file operand` | 붙여 넣다 줄이 잘렸다. 아무것도 바뀌지 않았으니 한 줄 통째로 재실행 |
| `/etc/cinemory`에 이름이 깨진 파일(`cinemory.enDDDD…`) | 잘못된 붙여넣기의 잔해. 이름을 치지 말고 `sudo find /etc/cinemory -maxdepth 1 -type f ! -name cinemory.env`로 **목록부터 확인** → 그 한 줄만이면 같은 명령 끝에 `-delete` |
| 스크립트 `mysql: command not found` | MySQL 미설치(6단계 확인을 건너뜀). 부작용 없음 → 설치 후 재실행 |
| `apt` `Could not get lock` | 첫 부팅 직후 자동 업데이트 실행 중. 1~2분 뒤 재시도 |
| `nginx -t` 인증서 파일 없음 | 10-②(인증서 발급) 전에 ③을 했다. 순서대로 |
| certbot 발급 실패 | `curl -sI http://<도메인>`이 200인지(DNS·보안 그룹 80) |
| `https://…` 502 | 앱이 안 떠 있다. 11단계 전엔 정상, 이후면 `journalctl -u cinemory` |
| 기동 실패, 로그에 키 이름 나열 | `ProdStartupGuard` — env 빈 키. 9-② 확인 명령 |
| L-1 테스트 마지막 응답이 200 | 명령을 나눠 실행해 허용량이 회복됐다 → 한 줄로 재실행 |
| 30회 전부 200 | 요청 제한 미적용 — `/etc/nginx/sites-enabled/cinemory`가 링크돼 있는지, `default`가 남아 있는지 |
| `systemctl restart` 때 `unit file ... changed on disk. Run 'systemctl daemon-reload'` | 유닛 파일을 `install`로 다시 덮은 뒤 `daemon-reload`를 빠뜨렸다(첫 구축의 9-③에서 생겼다). 재시작은 **메모리의 옛 설정으로** 된다. `diff /etc/systemd/system/cinemory.service ~/cinemory/deploy/systemd/cinemory.service`가 같고 `cinemory.service.d`가 없으면 `sudo systemctl daemon-reload && sudo systemctl restart cinemory`. 다르면 reload 전에 무엇이 바뀌었는지 확인 |
| 여러 줄 붙여넣기 뒤 `>` 프롬프트가 나오고 엉뚱한 오류 | 명령 사이의 **설명 문장까지 복사**돼 그 안의 `"`가 따옴표를 열었다. Ctrl+C 후 코드 블록만 다시 복사 |
| `\`로 이은 명령이 `No such file or directory` / 주소 없는 curl | 붙여넣기에서 줄 끝에 **공백이 붙어** `\`가 줄바꿈 대신 공백을 이었다. `\` 대신 긴 값은 변수에 담아 한 줄로(예: `A=https://…; curl … "$A/sync"`) |
| `python3 -c` 가 `IndentationError: unexpected indent` | 붙여넣기 중 줄이 꺾이며 둘째 줄 앞에 공백이 생겼다. 한 줄짜리 짧은 python만 쓰거나 bash로 대체 |

---

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-10-08 | 진단 표에 4행 추가 — Phase 3 서버 작업에서 실제로 겪은 것: `daemon-reload` 경고(Phase 2 9-③ 잔여, 내용은 리포와 동일해 reload로 해소), 설명 문장 동반 복사, 줄 끝 공백으로 `\` 이음 실패, python 들여쓰기 오류. 공통 교훈은 **여러 줄 명령은 `\`·heredoc 대신 짧은 변수 대입 줄들로** |
| 2026-10-07 | 최초 작성 — **2026-10-07 첫 구축(Phase 2)을 그대로 옮겼다.** deploy-spec 6절은 결정·근거·서버 명령 위주라, 콘솔 화면(가입·MFA·IAM·Budgets·EC2 마법사·탄력적 IP)·로컬 Windows 작업(`.pem` 권한·`scp`·jar 배치)·도메인 구매가 *"무엇을"* 수준으로만 있어 재현이 어려웠다. 설계 문서를 클릭 단위로 덮지 않으려 런북으로 분리. 실행 중 막힌 지점(계정 ID 대시, MySQL 설치 실패 미확인, 붙여넣기 잘림, 장소 이동 후 SSH timeout, L-1 확인 타이밍)을 진단 표로 남겼다. 순서는 실제와 한 곳 다르다 — **도메인 구매를 0단계로 당겼다**(실제로는 서버 작업 뒤에 사서 DNS 대기가 생겼다) |
