# CineMory 실서버 배포 스펙 (2026-10)

> **이 문서는 새 세션이 컨텍스트 없이 실서버 배포를 시작할 수 있도록 쓴 인수인계 문서다.**
> `CineMory_기획노트.md` **4-INF절**(2026-08-27 상의)을 이어받아, 배포 결정 5건(D-1~D-5)을 확정하고
> 실행 순서를 Phase 0~6으로 내렸다. **Phase 1은 파일 단위 지시**까지, Phase 2 이후는 체크리스트
> 수준이며 착수 시 이 문서에 세부를 채운다.
>
> **배경 — 배포가 4주 앞당겨졌다.** 4-INF는 배포 시점을 *"10월 말, 발표에서 2~3주 역산"* 으로
> 잡았으나, **2026-10-01 지도교수 피드백으로 10월 초 착수**가 됐다. 교수님은 *"서버 배포가 안전하게
> 되는 것"* 을 중시한다. 이로써 구조가 바뀐다 — **"기능이 굳은 뒤 마지막에 한 번 이관"이 아니라
> "배포된 서버 위에서 M3-b·CineMap을 개발"** 한다. 그래서 이 문서의 결정들은 *한 번 잘 올리는 것*보다
> **올린 뒤에 안전하게 계속 바꿀 수 있는 것**(Flyway, CI/CD, 스냅샷)에 무게를 둔다.

**착수 전에 읽을 것**

| 문서 | 왜 |
|---|---|
| `CineMory_기획노트.md` **4-INF** | 구성 A안(단일 인스턴스 동거) 채택 근거, A→B/C 전환 비용, 결합 2건(`profile_image`·`@Scheduled`) |
| `docs/security-spec.md` **S-11** | "배포 전 반드시 처리할 것" — **L-10·L-11이 아직 미처리**(이 문서 1-1·1-2에서 처리) |
| `docs/schema/cinemory_backup_v17.sql` | 현행 스키마. Flyway 기준점(baseline)이 된다 |
| `docs/movie-seed-runbook.md` | 시드·resync 실행 절차. 이관 이후에는 **운영에서** 실행한다 |
| `CLAUDE.md` | 공통 규칙. ⚠️ "델타를 양쪽 DB에 적용" 규칙은 1-4 완료 후 교체된다 |

---

## 1. 현황 점검 (2026-10-01, 코드 기준)

문서상 "완료/선행"과 실제 코드가 어긋난 지점부터 적는다. **배포 중에 처음 만나면 진단이 가장
어려운 유형**들이다.

| 항목 | 문서 상태 | 실제 코드 | 영향 |
|---|---|---|---|
| **L-10** `jwt.secret` 환경변수 주입 | "8월 말 선행" | ❌ `application-secret.yml` + `spring.profiles.include: secret` 그대로 | 운영 기동 실패 위험이 가장 큰 항목 |
| **L-11** 시간대 고정 | "8월 말 선행" | ❌ `ClockConfig`가 `systemDefaultZone()`만 쓰고 `TZ`는 어디서도 고정 안 됨 | 아래 **버그 1건**이 실제로 존재 |
| `application-prod.yml` | L-12 완료 | Springdoc만 꺼져 있음. DB 접속(`localhost`/`root`)·`show-sql: true`는 기본값 그대로 | 운영 프로파일을 채워야 함 |
| 비밀 파일 위치 | — | `application-secret.yml`이 **`src/main/resources`** 안에 있다 | `.gitignore`라 CI 빌드엔 안 들어가지만, **로컬에서 `bootJar`를 만들면 jar 안에 비밀이 그대로 포장된다** |
| 헬스 체크 | — | Actuator 의존성 없음 | CI 배포 후 확인 수단·업타임 모니터 대상이 없음 |
| 앱 API 주소 | — | `EXPO_PUBLIC_API_BASE_URL`(`.env.local`) 하나, **`eas.json` 없음** | 빌드 프로필별 서버 주소 분리 필요 (Phase 5) |
| 데이터 규모 | 4-INF 기준 4,609편 | **12,750편** | 이관 리허설의 중요도가 올라감 |

### ⚠️ L-11이 이미 만든 버그 — `BoxOfficeScheduler`

```java
@Scheduled(cron = "${cinemory.boxoffice.daily-sync-cron}", zone = "Asia/Seoul")  // 05:00 KST에 발화
public void syncDailyBoxOffice() {
    LocalDate targetDate = LocalDate.now().minusDays(1);   // ← JVM 기본 시간대 기준
```

크론은 `zone = "Asia/Seoul"`로 **05:00 KST**에 정확히 발화하지만, 그 안의 `LocalDate.now()`는
**JVM 기본 시간대**를 따른다. JVM이 UTC면 05:00 KST = **전날 20:00 UTC**라 `now()`가 이미 하루 전이고,
`minusDays(1)`까지 더해 **이틀 전 박스오피스를 수집**한다. 에러가 나지 않고 데이터만 하루씩 밀려
쌓이는 유형이라 발견이 늦다. `BoxOfficeSyncService`의 `targetDate == null` 분기(수동 수집 기본값)도
같은 문제를 갖는다.

→ **코드를 고치지 않고 JVM 시간대를 Asia/Seoul로 고정하는 것으로 해소한다**(1-2). `ClockConfig`의
설계 의도(*"시간대는 JVM 옵션 또는 TZ로 한 곳에서 맞춘다"*)와 같은 방향이며, JPA Auditing·토큰 만료와
기준이 하나로 맞춰진다. 대신 **잘못된 시간대로 기동하면 아예 뜨지 않게** 가드를 둔다(1-2-B).

---

## 2. 확정 결정 D-1 ~ D-5 (2026-10-01)

6-0의 D-1~D-4, M3-a의 RA-1~RA-7과 같은 성격의 관문이다. **번호는 이 문서 안에서만 유효**하다
(tmdb-sync의 D-1~D-4와 별개).

### D-1. 클라우드 — AWS EC2 `t4g.small`, 서울 리전

| 항목 | 값 |
|---|---|
| 서비스 | **AWS EC2** (서울 `ap-northeast-2`) |
| 인스턴스 | **`t4g.small`** — 2vCPU / 2GiB / ARM64(Graviton) |
| 디스크 | EBS gp3 30GB |
| 계정 | **신규 계정, 프리 플랜** — 가입 크레딧 $100 + 활동 보상 최대 $100, 6개월 |
| 예상 비용 | 약 $20/월(인스턴스 + EBS + 퍼블릭 IPv4) → **크레딧으로 발표까지 실부담 0원** |

**왜 EC2인가.** 후보는 Lightsail(간소화 VPS)·EC2(IaaS)·Oracle Always Free·NCP·해외 PaaS(Render 등)였다.

- **기준이 둘이었다** — ① 교수님이 중시하는 **배포 안정성·예측성**, ② 사용자가 원하는 **이력서 가치**(업계 표준 경험).
  Lightsail은 ①에서, EC2는 ②에서 앞선다. **EC2의 복잡함(VPC·보안 그룹·EBS·IAM을 부품으로 다룸)이 곧
  학습 가치**이고, 사고 유형이 잘 알려져 있어 첫날 규칙 4개(Phase 2-1)로 ①을 보완할 수 있다고 판단했다.
- **Oracle 기각** — 사양은 가장 좋으나 2026년 무료 한도 축소(4코어/24GB → 2코어/12GB), "Out of capacity"
  생성 실패, 정책 적용이 들쭉날쭉 — **통제할 수 없는 변수**가 ①과 정면으로 충돌한다.
- **NCP 보류** — 국내 공공·금융 진로라면 의미가 있으나 신규 가입 크레딧 프로모션이 2026-09-30에
  종료됐고, 무료 Micro 서버(1vCPU/1GB)는 조건 미달. 일반 채용에서의 인지도는 AWS가 앞선다.
- **해외 PaaS 기각** — 서울 리전이 없고 관리형 MySQL이 없다(Postgres 중심). 스키마가
  `utf8mb4_0900_ai_ci`라 MySQL 8.0이 필수다.
- **ARM(`t4g`)을 고른 이유** — 같은 사양의 x86(`t3.small`)보다 싸다. jar는 아키텍처 무관이고 Java 21·
  MySQL 8.0 모두 ARM64를 지원한다.

**2GB로 충분한가 — 메모리 예산**

| 구성 요소 | 예상 |
|---|---|
| OS·기본 데몬 | ~300MB |
| MySQL (`innodb_buffer_pool_size=384M` + 오버헤드) | ~550MB |
| Spring Boot (`-Xmx640m` + 메타스페이스·스레드) | ~900MB |
| Nginx | ~30MB |
| **합계** | **~1.8GB** → **스왑 2GB 필수** |

**2GB가 유지되는 조건은 D-2다** — 임베딩 모델을 서버에 상주시키지 않는다. 바뀌면 4GB(`t4g.medium`)로
올린다(EBS 스냅샷 → 새 인스턴스 → 탄력적 IP 재연결, 30분 안팎).

⚠️ **프리 플랜 만료 처리** — 프리 플랜은 **6개월 경과 또는 크레딧 소진 시 계정이 자동으로 닫히고,
90일 안에 유료 전환하지 않으면 데이터까지 삭제**된다. 발표 후에도 유지하려면 **2027-03 이전에
유료 플랜으로 전환**한다(남은 크레딧은 이어서 쓰인다). Phase 2-1에서 캘린더에 박아 둔다.

### D-2. 추천 방식 (= 기획노트 R-2) — 하이브리드

> **이 결정이 D-1의 서버 크기를 정하므로 배포 전에 닫았다.** 추천 로직 세부는 M3-b 설계 세션 몫이다.

| 기능 | 방식 |
|---|---|
| **"내 취향 추천"** | **규칙 기반** — M3-a 선호 점수(장르·배우·감독·국가 가중치)로 미시청작을 정렬하고, 가장 크게 기여한 항목을 **추천 이유**로 함께 내린다 |
| **"자연어로 찾기"** | **임베딩으로 후보 추출 → 규칙 점수로 재정렬** — 질의와 의미가 가까운 상위 N편 안에서 사용자 취향을 섞어 *"질의에 맞으면서 내 취향인 영화"* 를 낸다 |

- **자연어 질의를 넣기로 한 순간 양자택일이 아니게 됐다** — 규칙 기반은 자유 문장을 이해하지 못하고,
  임베딩은 추천 이유를 설명하지 못한다. 둘의 장단점이 정확히 반대라 섞는다.
  *"M3-a 분석 → 규칙 추천 → 자연어 검색"* 이 한 줄로 이어지는 것이 발표 서사로도 가장 강하다.
- **질의 임베딩은 외부 API로 생성한다(서버 상주 모델 없음).** 로컬 소형 모델은 한국어 뉘앙스 질의에서
  불리하고, 버스트형 CPU를 JVM·MySQL과 나눠 써 지연이 들쭉날쭉해진다. 품질 투자는 모델이 아니라
  **임베딩 입력 텍스트 구성**(줄거리 결손 처리 — tmdb-sync D-4)과 **하이브리드 랭킹**에 한다.
- **영화 벡터는 개발 PC에서 배치로 생성해 DB에 적재**한다. 실행 시점에 모델이 필요한 것은 질의 하나뿐이다.
- **모델 선정은 오프라인 비교 평가로** — 후보 2~3개 × 한국어 테스트 질의 20개. 결과표가 보고서의
  "모델 선정 근거"가 된다. 로컬 모델이 충분히 좋게 나오면 그때 4GB로 올린다.

**함께 정해진 것 — R-4(임베딩 저장 위치): MySQL 테이블.** 12,750편 × 768차원 × 4B ≈ 39MB라 메모리
전수 비교로 충분하고 별도 벡터 DB가 필요 없다.

**아직 열려 있는 것(인프라 무관, 배포 후 M3-b 설계 세션)** — R-3 콜드 스타트, 위시를 취향 신호로
넣을지(R-1 잔여), 점수 공식 세부, 임베딩 API 제공사.

### D-3. 스키마 마이그레이션 — Flyway (baseline v17)

**왜 수동 델타를 그만두는가.** 지금까지의 델타 문화(근거·데이터 보정·롤백 부록을 주석으로 남김)는
**자산이며 유지한다.** 문제는 *"어느 DB가 어느 버전인지"* 가 사람의 기억에만 있다는 점이다 — 실제로
`v9-delta.sql`은 적용 후 지워져 커밋된 적이 없고, `v15-delta.sql`은 리포에 없으며, `cinemory_test`
분리 후 *"양쪽에 적용할 것"* 이라는 기억 의존 규칙이 생겼다. 배포 후에는 적용 대상이 **3곳**(개발·테스트·
운영)이 되고, 적용 주체에 **CI 자동 배포**가 추가된다.

**결정적 이유는 순서다.** `ddl-auto: validate`라 새 jar는 새 스키마 없이 기동하지 못한다. 수동 방식에서는
*"운영 DB에 델타 → jar 재시작"* 을 매번 사람이 지켜야 하고, CI가 jar만 먼저 올리면 서버가 죽는다.
**Flyway는 스키마 변경을 jar에 함께 실어 순서를 자동으로 보장한다.**

**업계 입지** — Spring Boot가 공식 자동 구성을 제공하는 도구는 Flyway·Liquibase 둘이고, Java/Spring
단일 팀에선 Flyway가 주류다. Community 에디션은 Apache 2.0 유지(undo는 2025-05부터 Enterprise 전용 —
롤백 스크립트를 설계 문서에 계속 두는 이 문서의 방침과 충돌 없음). Liquibase는 2025-09 5.0부터 FSL로
라이선스가 바뀌었다.

**조건 5건 (D-3과 한 묶음으로 확정)**

1. **실행 SQL과 설계 문서를 분리한다.** 실행 파일은 `src/main/resources/db/migration/V{n}__*.sql` —
   **한 곳에라도 적용되면 동결**(Flyway가 체크섬 불일치 시 기동을 거부한다). 근거·상태·롤백·변경 이력은
   지금처럼 `docs/schema/v{n}-delta.sql`에 두고 자유롭게 고친다. ⚠️ 지금까지는 적용 후에도 델타 헤더
   (`상태: ✅ 적용 완료`)를 고쳐 왔다 — **그 습관이 실행 파일에 닿으면 운영이 기동하지 못한다.**
2. **의존성은 `spring-boot-starter-flyway` + `org.flywaydb:flyway-mysql`.** Boot 4 모듈화로
   `flyway-core`만 넣으면 **에러 없이 마이그레이션이 그냥 돌지 않는다.** 도입 직후
   `flyway_schema_history` 테이블 생성을 반드시 확인한다.
3. **`V17__baseline.sql` + `baseline-version: 17`.** 이미 v17인 `cinemory`·`cinemory_test`는 V17을 건너뛰고
   V18부터, **빈 DB(운영 초기화·CI)는 V17부터** 실행된다.
4. **MySQL DDL은 트랜잭션으로 되돌릴 수 없다** — 마이그레이션은 작게 쪼개고, **운영 배포 직전 EBS 스냅샷**을
   CI가 자동으로 찍는다(Phase 4). 실패 시 스냅샷 복원 + `flyway repair`.
5. **Flyway는 스키마와 소량의 참조 데이터까지만.** 영화·인물 데이터는 D-5대로 덤프 이관한다.

### D-4. 배포 방식 — jar + systemd + Nginx(certbot) + MySQL 직접 설치

| 층 | 선택 | 이유 |
|---|---|---|
| 앱 실행 | **jar + systemd** | 2GB 메모리 여유, **첫 배포는 층이 적을수록 진단이 쉽다**, `bootBuildImage`로 컨테이너 전환 경로가 이미 열려 있음(로컬 파일 I/O 0건 — 4-INF) |
| 리버스 프록시 | **Nginx + certbot** | **실무 표준**(이력서 기준). Caddy(인증서 완전 자동)를 원안으로 냈다가 교체 — 인증서 만료 위험은 갱신 드라이런 + 업타임 모니터의 인증서 만료 체크로 막는다 |
| DB | **MySQL 8.0 호스트 직접 설치** | 백업(`mysqldump`)·EBS 스냅샷이 단순. 컨테이너 볼륨 실수로 데이터가 사라지는 유형을 원천 차단 |

- **Docker Compose 전환은 배포 안정화 이후의 선택 과제**로 둔다. ARM(`t4g`) 이미지를 CI에서 만들려면
  멀티 아키텍처 빌드·ARM 러너 같은 추가 결정이 생겨 첫 배포의 위험을 늘린다. *"systemd로 먼저 안정화 →
  컨테이너로 이전"* 은 실무에서도 흔한 순서다.

### D-5. 데이터 이관 범위 — 참조·콘텐츠만, 사용자 데이터는 운영에서 새로

| 구분 | 테이블 | 처리 |
|---|---|---|
| **이관** (10) | `genre` `country` `ott_platform` `person` `movie` `movie_genre` `movie_country` `movie_actor` `movie_director` `box_office_record` | **데이터만** 덤프(`--no-create-info`) → 운영 임포트 |
| **새로 생성** (9) | `user` `watch_record` `review` `wish_movie` `collection` `collection_movie` `follow` `comment` `notification` | 운영에서 처음부터 |
| **이관 안 함** (2) | `refresh_token` `password_reset_token` | 운영 JWT 키가 새로 발급되므로 무의미 |
| **비어 있음** (1) | `theater` | B-9(잔여 #5) 해결 시 운영에서 직접 시드 |

- **스키마는 Flyway, 데이터만 덤프** — 테이블 생성문까지 넣으면 Flyway(V17)와 충돌한다.
- **검증용 시청 기록 1,000건(관리자 계정, user id 276)은 옮기지 않는다** — 운영 리포트·추천 데모가 오염된다.
- **이관 시점부터 영화·박스오피스 데이터의 원본은 운영 DB다.** 로컬에서도 온디맨드 동기화·resync를 하면
  두 DB가 갈라지므로, 로컬에 최신이 필요하면 운영에서 역으로 덤프해 온다. 대규모 시드·resync는 이후
  운영에서 실행한다(`limit` 분할 — 리버스 프록시 타임아웃 회피).
- **박스오피스 공백** — 로컬 서버가 꺼져 있던 날과 이관~운영 첫 수집 사이에 빈 날짜가 생긴다.
  `POST /api/admin/box-office/sync?targetDate=`(이미 존재, 수집 멱등) + `rematch`로 메운다(Phase 3-4).
- **운영 계정 3종** — 관리자(가입 후 DB에서 `role` 변경, **런북·문서에 비밀번호 평문 금지**), 데모 계정
  (**앱/API로 실제 사용 흐름대로** 수십~백여 건 — 검증 로직을 그대로 거쳐 운영 E2E를 겸한다), 개인 계정.

---

## 3. 전체 구성

```
[Android 앱 (EAS preview/production)]
        │ HTTPS (443)
        ▼
┌──────────────── EC2 t4g.small (ap-northeast-2) ─────────────────┐
│  보안 그룹: 22(내 IP만) · 80 · 443                                │
│                                                                  │
│  Nginx ──(proxy 127.0.0.1:8080)──▶ Spring Boot (systemd, prod)   │
│    └ certbot (Let's Encrypt)          │ Flyway (기동 시 자동)      │
│                                       ▼                          │
│                               MySQL 8.0 (127.0.0.1:3306)         │
│                                                                  │
│  EBS gp3 30GB ── 일일 스냅샷(DLM) + 배포 직전 스냅샷(CI)          │
│  cron mysqldump (일일, 7일 보관)                                  │
└──────────────────────────────────────────────────────────────────┘
        ▲ 탄력적 IP ◀── DNS A 레코드 (도메인)
        │
[GitHub Actions] main push → 빌드 → 스냅샷 → 배포 → health check
```

**바깥에 노출되는 것은 Nginx(80·443)뿐이다.** Spring Boot는 `127.0.0.1`에만 바인딩하고(1-3),
MySQL 3306은 보안 그룹에서도 닫는다 — 이중으로 막는다.

---

## 4. 실행 순서

| Phase | 내용 | 기간 | 목표일 |
|---|---|---|---|
| **0** | D-1~D-5 확정 · 문서 반영 | — | ✅ 10/1 |
| **1** | **코드 선행** — L-10·L-11·prod 프로파일·Flyway (로컬 검증) | 1~2일 | 10/2 |
| **2** | AWS 계정 보안 · EC2 구축 · MySQL · Nginx · 도메인/HTTPS | 2~3일 | 10/5 |
| **3** | 데이터 이관 · 공백 보충 · 운영 계정 | 반나절~1일 | 10/6 |
| **4** | CI/CD (GitHub Actions) | 1일 | 10/7 |
| **5** | 앱 — `eas.json` · 키 해시 · 실기기 E2E (LTE) | 1~2일 | 10/9 |
| **6** | 백업·모니터링·런북 · **복원 리허설** | 반나절 | 10/10 |
| 버퍼 | 처음 하는 배포는 반드시 예상보다 오래 걸린다 | ~1주 | 10/17 |

Linux 서버(SSH·apt·systemctl) 경험이 없다면 Phase 2에 1~2일을 더한다.

**소셜 로그인(구글·네이버 + 계정 연결)은 Phase 2~3 동안 병렬로 개발한다** — 그 기간은 콘솔 작업이 대부분이라 Claude Code가
비어 있다. 단 **`feature/social-login` 브랜치에서만** 하고, **Phase 5 E2E는 카카오만 있는 상태로 통과시킨 뒤 머지**한다.
인증 핵심을 고치는 작업이라 첫 배포에 함께 실으면 로그인 실패 시 *"서버 환경 문제인지 새 코드 문제인지"* 를 가를 수 없다.
브랜치는 **Phase 1 머지 후** 딴다(1-1이 카카오 `allowed-audiences` 설정을 건드린다). 설계는 `account-integrity-spec.md` Part D.

**Phase 1은 Claude Code가, Phase 2·3·5의 콘솔 작업은 사람이 한다.** 서버 안에서 실행할 설정 파일·
스크립트(systemd 유닛, Nginx 설정, 백업 스크립트)는 착수 시 이 문서에 확정본을 적어 Claude Code가 리포
(`deploy/` 디렉터리)에 만든다.

---

## 5. Phase 1 — 코드 선행 (파일 단위 지시)

> **배포하지 않고 로컬에서 전부 검증된다.** 순서대로 진행하고, 단위마다 컴파일·기동 확인 → 커밋.
> 브랜치: `develop`에서 `feature/deploy-phase1`.

### 1-1. 비밀 외부화 (L-10)

**목표** — 운영은 **환경변수만으로** 기동하고, 비밀이 jar에 절대 들어가지 않는다. 로컬은 지금처럼
비밀 파일로 돈다.

**① 비밀 파일을 리소스 밖으로 옮긴다**

| 파일 | 변경 |
|---|---|
| `src/main/resources/application-secret.yml` | → **`config/application-secret.yml`** (프로젝트 루트의 `config/`)로 이동. 내용 변경 없음 |
| `.gitignore` | `config/` 추가 (기존 `application-secret.yml` 항목은 유지 — 이중 방어) |

- **이유** — `src/main/resources`에 있으면 로컬 `bootJar` 산출물에 포장된다. Spring Boot는 작업 디렉터리의
  `./config/application-{profile}.yml`을 자동으로 읽으므로, `bootRun`·`./gradlew test`(작업 디렉터리 =
  프로젝트 루트)는 그대로 동작한다.

**② `spring.profiles.include: secret`을 프로파일 그룹으로 바꾼다** — `application.yml`

```yaml
spring:
  profiles:
    # include: secret  ← 제거. include는 어느 프로파일에서든 secret을 끌어와,
    #                     prod를 로컬에서 검증할 때도 비밀 파일이 환경변수 누락을 가려 버린다.
    default: local
    group:
      local: secret     # 아무 프로파일도 지정하지 않은 로컬 실행
      test: secret      # ./gradlew test (build.gradle이 test 프로파일 고정)
      # prod에는 secret을 묶지 않는다 — 운영은 환경변수만 쓴다
```

- `application-test.yml`의 상단 주석(*"profiles.include: secret에서 그대로 가져온다"*)을 그룹 방식으로 고친다.

**③ `application-prod.yml`에 비밀 플레이스홀더를 추가한다** (기존 Springdoc 설정 유지)

```yaml
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
  mail:
    username: ${MAIL_USERNAME}
    password: ${MAIL_PASSWORD}

jwt:
  secret: ${JWT_SECRET}

tmdb:
  access-token: ${TMDB_ACCESS_TOKEN}

kofic:
  api-key: ${KOFIC_API_KEY}

mail:
  password-reset:
    from: ${MAIL_FROM}

oauth:
  kakao:
    allowed-audiences: ${KAKAO_ALLOWED_AUDIENCES}   # 쉼표 구분 → List<String> 바인딩
```

- **기본값(`${X:default}`)을 두지 않는다** — 누락 시 기동이 실패해야 한다(fail-fast). L-10이 경고한
  *"기동은 실패하고 로그는 다른 얘기를 하는"* 상황을, *"플레이스홀더 X를 해석할 수 없다"* 는 정확한 로그로
  바꾸는 것이 목적이다.
- ⚠️ **운영의 `KAKAO_ALLOWED_AUDIENCES`는 네이티브 앱 키 하나만** 넣는다. REST API 키는 런북의 웹 플로우
  검증용이었다 — 운영에서 허용할 이유가 없다(최소 권한).
- ⚠️ **운영 `JWT_SECRET`은 새로 발급한다**(`openssl rand -base64 64`). 로컬 값을 재사용하지 않는다.
- `tmdb`/`kofic`은 미설정 시 *"경고만 남기고 건너뛴다"* 는 기존 원칙이 있으나, 운영에서는 박스오피스 수집·
  온디맨드 동기화가 필수 기능이므로 **운영에서는 필수로 취급**한다(플레이스홀더 기본값 없음).

**환경변수 목록** (운영 서버 `/etc/cinemory/cinemory.env`, 권한 600 — Phase 2-5)

| 변수 | 내용 |
|---|---|
| `DB_URL` | `jdbc:mysql://127.0.0.1:3306/cinemory?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul` |
| `DB_USERNAME` / `DB_PASSWORD` | 앱 전용 계정(`root` 금지 — Phase 2-4) |
| `JWT_SECRET` | 신규 발급 |
| `TMDB_ACCESS_TOKEN` / `KOFIC_API_KEY` | 로컬과 같은 값 |
| `MAIL_USERNAME` / `MAIL_PASSWORD` / `MAIL_FROM` | Gmail 앱 비밀번호. `MAIL_FROM` = `MAIL_USERNAME` |
| `KAKAO_ALLOWED_AUDIENCES` | 네이티브 앱 키 |

### 1-2. 시간대 고정 (L-11)

**A. 고정 지점 — 코드가 아니라 실행 환경 3곳**

| 위치 | 설정 | 담당 |
|---|---|---|
| JVM | `-Duser.timezone=Asia/Seoul` | systemd 유닛 (Phase 2-5) |
| OS | `timedatectl set-timezone Asia/Seoul` | Phase 2-2 |
| MySQL | `default-time-zone = '+09:00'` | Phase 2-3 |

**B. 시간대 가드 — 잘못된 시간대로는 기동하지 않는다** (신규)

| 파일 | 내용 |
|---|---|
| `global/config/TimeZoneGuard.java` (신규) | `@Component` + `ApplicationRunner`가 아니라 **빈 초기화 시점**에 검사(`@PostConstruct`) — 스케줄러·요청이 돌기 전에 막기 위해. `cinemory.required-time-zone` 값이 설정돼 있고 `ZoneId.systemDefault()`와 다르면 `IllegalStateException`(메시지에 기대값·실제값·해결법 `-Duser.timezone` 명시). 값이 비어 있으면 검사하지 않는다 |
| `application-prod.yml` | `cinemory.required-time-zone: Asia/Seoul` |

- **로컬·테스트는 검사하지 않는다**(프로퍼티 미설정). 개발 PC 시간대를 강제할 이유가 없다.
- **이유** — 1절의 박스오피스 버그처럼 L-11은 **에러 없이 데이터만 틀어지는** 유형이다. "TZ를 잘 설정하자"는
  규칙만으로는 systemd 유닛 수정 한 번에 무력화된다. 가드는 그 실수를 **기동 실패**로 바꾼다.

**C. 로컬 검증 — 가드가 실제로 막는지**

```powershell
# 실패해야 한다 (prod + UTC)
java "-Duser.timezone=UTC" -jar build/libs/<jar> --spring.profiles.active=prod
# 성공해야 한다 (prod + Asia/Seoul) — 1-1의 환경변수가 설정된 셸에서
java "-Duser.timezone=Asia/Seoul" -jar build/libs/<jar> --spring.profiles.active=prod
```

### 1-3. 운영 프로파일 보강

**① `application-prod.yml` 추가 항목**

```yaml
spring:
  jpa:
    show-sql: false
    properties:
      hibernate:
        format_sql: false
        use_sql_comments: false
  lifecycle:
    timeout-per-shutdown-phase: 20s

server:
  address: 127.0.0.1                  # Nginx만 접근. 외부에서 8080 직접 접근 차단(보안 그룹과 이중)
  forward-headers-strategy: native    # Nginx의 X-Forwarded-* 를 신뢰 — 요청 스킴이 https로 인식된다
  shutdown: graceful                  # 배포 재시작 시 처리 중 요청을 마무리

management:
  endpoints:
    web:
      exposure:
        include: health               # health만 노출
  endpoint:
    health:
      show-details: never

logging:
  level:
    root: INFO
    org.hibernate.SQL: WARN
```

- `show-sql: true`는 base `application.yml`에 그대로 둔다(로컬 개발용). prod가 덮어쓴다.
- CORS(`cinemory.cors.allowed-origins`)는 건드리지 않는다 — **RN 네이티브는 CORS와 무관**(프론트 B-3).

**② Actuator 의존성** — `build.gradle`

```groovy
implementation 'org.springframework.boot:spring-boot-starter-actuator'
```

**③ 헬스 체크 공개** — `SecurityConfig.PUBLIC_GET_ENDPOINTS`에 `"/actuator/health"` 추가.
**`/actuator/**`로 넓히지 않는다** — 노출은 1-3 ①에서 health 하나로 막았지만, 인가 화이트리스트도 정확히
그 경로만 연다(이중 방어, 5-0-F의 "정확한 패턴" 원칙).

- 화이트리스트 회귀 테스트(기존 `SecurityErrorDispatchTest` 등)에 `GET /actuator/health` → 200(비로그인)을 추가.

### 1-4. Flyway 도입 (D-3)

**① 의존성** — `build.gradle`

```groovy
// Boot 4 모듈화 — flyway-core만 넣으면 자동 구성이 붙지 않아 "에러 없이 아무 일도 안 일어난다".
// 반드시 스타터를 쓴다. MySQL 지원은 Flyway 10부터 별도 모듈이다.
implementation 'org.springframework.boot:spring-boot-starter-flyway'
implementation 'org.flywaydb:flyway-mysql'
```

**② 설정** — `application.yml` (전 프로파일 공통)

```yaml
spring:
  flyway:
    enabled: true
    baseline-on-migrate: true    # 이미 테이블이 있는 DB(cinemory·cinemory_test)는 v17로 기준점만 찍는다
    baseline-version: 17
    # locations 기본값 classpath:db/migration
```

**③ `src/main/resources/db/migration/V17__baseline.sql` 생성** — `docs/schema/cinemory_backup_v17.sql`에서

- `CREATE TABLE` 문(22개)과 그에 필요한 `SET` 문만 남긴다.
- **제거** — 덤프 헤더/푸터의 세션 변수 저장·복원(`/*!40101 SET @OLD_... */` 류), `LOCK TABLES`/`UNLOCK TABLES`,
  `DROP TABLE IF EXISTS`, 테이블 옵션의 **`AUTO_INCREMENT=N`**(로컬 시퀀스 값이 운영에 새면 안 된다).
- 파일 맨 위 주석 3줄만 — *"v17 기준점. 설계 근거는 docs/schema/. 이 파일은 동결 — 절대 수정 금지."*
- ⚠️ **이 파일을 만든 뒤에는 고치지 않는다.** 실수를 발견하면 V18로 고친다.

**④ 동작 확인 (반드시)**

| 대상 | 기대 결과 |
|---|---|
| `cinemory`(개발)로 기동 | `flyway_schema_history`에 **`<< Flyway Baseline >>` 버전 17** 한 행. V17 실행 안 됨 |
| `./gradlew test` (`cinemory_test`) | 위와 동일 + 전체 테스트 통과 |
| **빈 스키마**(`cinemory_flyway_check` 임시 생성)로 기동 | **V17 실행** → 22개 테이블 생성 → `ddl-auto: validate` 통과. 확인 후 스키마 삭제 |

- 세 번째가 **운영 초기화와 같은 경로**다. 여기서 통과해야 Phase 3이 안전하다.

**⑤ 문서 규칙 교체** — `CLAUDE.md` "DB / 스키마 원칙"

- *"델타를 `cinemory`에 적용하면 `cinemory_test`에도 반드시 같이 적용"* → **폐기.** 대신:
  - 스키마 변경은 **`db/migration/V{n}__설명.sql`(실행, 적용 후 동결)** + **`docs/schema/v{n}-delta.sql`
    (설계 근거·롤백, 자유 수정)** 한 쌍으로 만든다.
  - 적용은 앱 기동이 한다. 수동 `mysql < delta.sql` 금지(history 테이블과 어긋난다).
  - 진실의 원천은 여전히 덤프(`cinemory_backup_v{n}.sql`) — 적용 후 재덤프는 계속한다.

### 1-5. Phase 1 완료 기준

| # | 확인 | 방법 |
|---|---|---|
| 1 | 로컬 기동·테스트가 그대로 된다 | `bootRun` (프로파일 미지정) / `./gradlew test` |
| 2 | **jar에 비밀이 없다** | `jar tf build/libs/*.jar \| findstr secret` → 결과 없음 |
| 3 | **prod는 환경변수만으로 뜬다** | 비밀 파일이 있는 상태에서 `--spring.profiles.active=prod` + 환경변수 → 기동. 환경변수 하나를 빼면 해당 플레이스홀더 이름이 찍히며 **실패** |
| 4 | 시간대 가드 | 1-2 C의 두 명령 |
| 5 | `/actuator/health` | prod 기동 후 `curl http://127.0.0.1:8080/actuator/health` → `{"status":"UP"}`, 비로그인 |
| 6 | Flyway 3경로 | 1-4 ④ 표 |
| 7 | Swagger 차단 유지 | prod에서 `/v3/api-docs` → 404 (L-12 회귀) |

완료 후 `security-spec.md` S-11의 **L-10·L-11을 ✅ 완료로** 갱신한다.

---

### 1-6. 스키마 무결성 정리 V18~V21 (1-4 직후)

> **`docs/account-integrity-spec.md` Part A.** Flyway의 첫 실전 마이그레이션이다. **Phase 3(데이터 이관) 전에** 끝내
> 운영 DB가 처음부터 V21로 시작하게 한다.

| 파일 | 내용 |
|---|---|
| `V18__collection_movie_cascade.sql` | `collection_movie → collection` RESTRICT → CASCADE (회원 탈퇴의 선행 조건) |
| `V19__watch_record_ott_restrict_check.sql` | `watch_record → ott_platform` SET NULL → RESTRICT + `chk_watch_record_ott` |
| `V20__reference_fk_restrict.sql` | `genre`·`person` 참조 FK CASCADE → RESTRICT |
| `V21__watch_record_rating_check.sql` | `chk_watch_record_rating` (1~10 정수) |

- ⚠️ 3절 D-5의 데이터 덤프는 **V21 스키마에 들어간다** — 이관 대상 10개 테이블은 컬럼 변경이 없어 그대로 들어가지만,
  V20의 RESTRICT 때문에 **임포트 중 `FOREIGN_KEY_CHECKS=0`이 필수**다(Phase 3-2에 이미 포함).

## 6. Phase 2 — 서버 구축 (체크리스트)

> 콘솔 작업은 사람이, 서버 설정 파일은 Claude Code가 `deploy/`에 만든다. 착수 시 세부를 확정한다.

### 2-1. AWS 계정 — 첫날 규칙 4개 (EC2 안전 운영의 전제)

1. **루트 계정 MFA.** 루트 액세스 키는 만들지 않는다(키 유출 → 채굴 인스턴스 → 폭탄 청구가 가장 흔한 사고).
   일상 작업은 MFA를 건 **IAM 사용자**로.
2. **AWS Budgets 알림 $10 / $20** 두 단계.
3. **중지해도 EBS·퍼블릭 IP는 과금된다** — 안 쓰는 리소스는 삭제.
4. **보안 그룹 — SSH(22)는 내 IP만**, 80·443만 전체 허용, **3306은 절대 열지 않는다.**

- 리전은 **서울(`ap-northeast-2`)** 고정 — 콘솔 우상단 확인.
- **프리 플랜 유료 전환 마감(가입일 + 6개월)** 을 캘린더에 등록(D-1 ⚠️).

### 2-2. EC2

- AMI **Ubuntu 24.04 LTS (arm64)**, `t4g.small`, EBS gp3 30GB, 키 페어 생성 → **`.pem` 즉시 백업**(분실 시 접속 불가).
- **탄력적 IP** 할당·연결.
- 스왑 2GB, `timedatectl set-timezone Asia/Seoul`, `unattended-upgrades`.
- Java 21 (arm64).

### 2-3. MySQL 8.0

- Ubuntu 24.04 기본 저장소의 `mysql-server`(8.0 계열) — 스키마가 `utf8mb4_0900_ai_ci`라 **8.0 이상 필수**.
- `bind-address = 127.0.0.1`, `innodb_buffer_pool_size = 384M`, `default-time-zone = '+09:00'`,
  기본 문자셋 `utf8mb4` / `utf8mb4_0900_ai_ci`.
- 스키마 `cinemory` 생성(**빈 상태** — 테이블은 Flyway가 만든다).

### 2-4. DB 계정

- 앱 전용 계정 `cinemory_app@localhost` — `cinemory.*`에 대한 권한(Flyway가 DDL을 실행하므로 DDL 포함).
  **`root`를 앱에 쓰지 않는다.**

### 2-5. 앱 서비스 (systemd)

- 실행 사용자 `cinemory`(로그인 불가), jar `/opt/cinemory/app.jar`, 직전 버전 `app.jar.prev` 보관(롤백용).
- `/etc/cinemory/cinemory.env` — 1-1 환경변수, **권한 600, 소유자 root**.
- 유닛 핵심: `EnvironmentFile=`, `ExecStart=java -Xms256m -Xmx640m -Duser.timezone=Asia/Seoul -jar /opt/cinemory/app.jar --spring.profiles.active=prod`, `Restart=on-failure`, `SuccessExitStatus=143`.

### 2-6. 도메인 · Nginx · HTTPS

- 도메인 1개 구매(연 1~2만 원대) → **A 레코드 = 탄력적 IP**.
- Nginx: 80 → 443 리다이렉트, 443 → `127.0.0.1:8080` 프록시, `X-Forwarded-For/Proto/Host` 전달,
  `proxy_read_timeout` 120s(관리자 시드·resync는 `limit` 분할 전제).
- certbot(Nginx 플러그인)으로 인증서 발급 → **`certbot renew --dry-run` 성공 확인**(D-4 — 만료 위험 대응).

### 2-7. 최초 기동

- 빈 `cinemory`로 앱 기동 → **Flyway가 V17 실행** → `validate` 통과 → `https://<도메인>/actuator/health` UP.
- 이 상태에서 **EBS 스냅샷 #1** ("schema-only").

---

### 2-8. (프로필 사진 착수 시) S3 · CloudFront · 인스턴스 역할

`docs/account-integrity-spec.md` **B-2**. 첫 배포에는 필요 없다 — 프로필 사진 기능을 붙일 때 한다. 서버 권한은
**EC2 인스턴스 역할**로 주고, 설정은 `MEDIA_BUCKET`·`MEDIA_PUBLIC_BASE_URL`(비밀 아님)을 `cinemory.env`에 추가한다.

## 7. Phase 3 — 데이터 이관 (체크리스트)

1. **로컬 덤프 (데이터만, 10개 테이블)** — PowerShell `>` 리다이렉트는 인코딩·덮어쓰기 문제가 있으므로
   **`--result-file`** 을 쓴다(시드 런북에서 `seed.log` 유실을 겪은 것과 같은 함정).
   ```
   mysqldump -u root -p --no-create-info --skip-triggers --single-transaction --complete-insert ^
     cinemory genre country ott_platform person movie movie_genre movie_country ^
     movie_actor movie_director box_office_record --result-file=cinemory_data_v17.sql
   ```
   ⚠️ 덤프 파일은 **리포에 커밋하지 않는다**(`docs/schema/cinemory_backup_*.sql`처럼 무시 대상).
2. **운영 임포트** — 앱 중지 → `SET FOREIGN_KEY_CHECKS=0;` → 임포트 → `SET FOREIGN_KEY_CHECKS=1;` → 앱 기동.
3. **검증** — 10개 테이블 행 수를 로컬과 대조(특히 `movie` · `movie_actor` · `person` · `box_office_record`),
   `GET /api/movies/random`·영화 상세·검색 응답 확인. → **EBS 스냅샷 #2** ("data-migrated").
4. **박스오피스 공백 보충** — 로컬 `MAX(target_date)` 다음 날부터 어제까지
   `POST /api/admin/box-office/sync?targetDate=YYYY-MM-DD` 반복 → `POST /api/admin/box-office/rematch`.
5. **운영 계정** — 관리자(가입 → `UPDATE user SET role='ADMIN' WHERE email=...`), 데모 계정(앱으로 실제 입력).

---

## 8. Phase 4 — CI/CD (체크리스트)

- 트리거: **`main` push** (`develop` → `main` 머지가 곧 배포).
- 단계: JDK 21 → `./gradlew bootJar -x test` → **배포 직전 EBS 스냅샷** → jar 전송 → `app.jar.prev` 보관 후
  교체 → `systemctl restart` → `https://<도메인>/actuator/health` 재시도 확인 → **실패 시 `app.jar.prev`로 자동 복구**.
- **테스트는 1단계에서 로컬 실행**(`./gradlew test`가 실 MySQL `cinemory_test`에 의존). 2단계에서 Actions에
  MySQL 서비스 컨테이너를 붙인다 — 1-4 덕분에 **빈 DB에 Flyway V17이 스키마를 만들어 주므로** 덤프 로드가 필요 없다.

**🔲 P4-1 (착수 시 확정) — 서버 접속 방식**

| 안 | 내용 | 비고 |
|---|---|---|
| A. SSH | Actions에서 SSH 키로 접속 | 보안 그룹 22를 Actions IP 대역(넓음)에 열어야 한다 |
| **B. SSM + OIDC** (추천) | GitHub OIDC로 IAM 역할 위임 → SSM Run Command | **22를 외부에 열지 않는다.** 배포 직전 스냅샷에도 IAM 역할이 어차피 필요하므로 한 번에 해결. IAM 실무 경험(이력서) |

---

## 9. Phase 5 — 앱 (요약, 상세는 `cinemory-app/docs/`)

> 프론트 계약은 프론트 리포 문서가 단일 출처다. 착수 시 `cinemory-app/docs/`에 별도 문서로 내린다.

- **HTTPS 필수** — Android 릴리스 빌드는 기본적으로 평문 HTTP를 막는다. Phase 2-6이 선행.
- `eas.json` 신설 — `development`(로컬 백엔드, Dev Client) / `preview`(운영, APK 배포) / `production`.
  프로필마다 `EXPO_PUBLIC_API_BASE_URL`.
- ⚠️ **EAS 빌드 키스토어의 키 해시를 카카오 콘솔에 등록** — 안 하면 **preview APK에서만 카카오 로그인이
  깨진다**(security-spec L-7 잔여, M5에서 이번으로 앞당겨짐). Play 앱 서명 키는 M5에서.
- **실기기 E2E — 반드시 Wi-Fi를 끄고 LTE로**: 로컬 로그인 · 카카오 로그인 · 401 → 재발급 · 기록 CRUD ·
  리포트 3종 · **실제 SMTP 발송**(M5 미검증 항목) · 재설정 딥링크.
- `gen:api`는 계속 **로컬 서버 기준**(운영은 Swagger가 꺼져 있다 — L-12).

---

## 10. Phase 6 — 운영 기본기 (체크리스트)

- **백업 2중** — ① cron `mysqldump`(전체 DB, 이제 사용자 데이터 포함) 일일 · gzip · 7일 보관, ② DLM으로 EBS
  스냅샷 일일 · 7일 보관.
- ⚠️ **복원 리허설 1회** — 덤프를 임시 스키마에 복원해 행 수 확인. **복원해 본 적 없는 백업은 백업이 아니다.**
- 외부 업타임 모니터 — `/actuator/health` + **인증서 만료 체크**(D-4 Nginx 선택의 보완 조건).
- CloudWatch 상태 확인 실패 알람.
- **`docs/deploy-runbook.md` 신설** — 기동·재배포·롤백(`app.jar.prev`/스냅샷)·백업 복원·인증서 갱신·
  Flyway 실패 시 `repair`. **비밀번호·키 평문 금지.**
- ⚠️ **발표용 로컬 폴백을 지우지 않는다**(4-INF) — 로컬 기동이 되는 상태를 유지한다.

---

## 11. 배포 이후 개발 규칙

| 상황 | 규칙 |
|---|---|
| 스키마 변경 | 1-4 ⑤ — `V{n}` + 설계 델타 한 쌍. 운영 반영은 CI 배포가 한다 |
| 영화 데이터 시드·resync | **운영에서** 실행, `limit` 분할. 로컬은 필요 시 운영에서 역덤프 |
| 새 비밀(예: M3-b 임베딩 API 키) | 로컬은 `config/application-secret.yml`, 운영은 `cinemory.env` + `application-prod.yml` 플레이스홀더 |
| 배포 | `develop` → `main` 머지. 머지 전 로컬 `./gradlew test` (Phase 4 2단계 전까지) |
| 잔여 #19(대표 기록 UNIQUE) | 스키마 변경이라 **Flyway 도입 후** 처리 |

---

## 12. 미결 항목 — 배포와 맞물리는 것

| # | 항목 | 언제 | 왜 지금 적어 두는가 |
|---|---|---|---|
| P4-1 | CI 서버 접속 방식 (SSH vs SSM+OIDC) | Phase 4 착수 시 | 8절 |
| C-1 | **CineMap 지도 SDK 선택** | Phase 5 전 권장 | 지도 SDK도 대개 **패키지명·서명 키 해시 등록**이 필요하다 — 카카오 로그인 키 해시와 **Phase 5에서 한 번에** 처리하면 빌드 재배포가 한 번 준다 |
| C-2 | **theater 시드 입력 방식** (잔여 #5) | CineMap 착수 시 | 배포로 판단이 바뀌었다 — "서버 파일" 방식은 운영 서버에 파일을 올리는 결합이 생긴다(L-13과 같은 논리). **멀티파트가 유리** |
| M-1 | M3-b 설계 세션 (R-3·위시 신호·점수 공식·임베딩 제공사) | 배포 후 | D-2에서 인프라 축만 닫았다 |
| X-1 | Docker Compose 전환 | 배포 안정화 후, 여유 시 | D-4 |
| L-1 | Rate limiting | 미정 | 서버가 공개 인터넷에 노출되면서 무게가 늘었다. 최소한 Nginx `limit_req`(코드 변경 0)를 `login`·`reissue`·`password-reset/request`에 거는 안을 Phase 2-6에서 검토 |
| S-1 | 소셜 로그인 병렬 개발 | Phase 2 착수 시 | 4절 — 머지는 Phase 5 이후. 머지 후 **제공자별 서명 키 등록**(개발·EAS)과 실기기 E2E가 추가로 필요 |

---

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-10-01 | **소셜 로그인 병렬 개발 방침 추가(4절·12절 S-1).** 10월 우선순위가 *실서버 → 소셜 로그인 → 추천·CineMap → 그 외*로 확정됐다(기획노트 4절). 소셜 로그인은 Phase 2~3의 콘솔 작업 기간에 별도 브랜치로 개발하되, **첫 배포 검증(Phase 5)은 카카오만으로** 통과시킨 뒤 머지한다 — 첫 배포에 인증 변경을 섞지 않는다 |
| 2026-10-01 | **1-6(V18~V21)·2-8(S3·CloudFront) 추가.** 배포 전 미구현 기능 점검에서 프로필 수정·회원 탈퇴를 설계하다 FK 정책 함정 3건과 별점 CHECK 누락을 찾아 `docs/account-integrity-spec.md`로 확정했다. 스키마 변경은 **Flyway 도입(1-4) 직후, 데이터 이관 전**에 넣어 운영 DB가 V21로 시작하게 한다. 프로필 사진 인프라는 첫 배포 범위 밖이라 2-8로 분리 |
| 2026-10-01 | **신설 — 배포 결정 D-1~D-5 확정, Phase 0~6 실행 순서, Phase 1 파일 단위 지시.** 지도교수 피드백으로 배포가 4-INF의 10월 말에서 **10월 초로 4주 앞당겨졌다.** 구조가 *"마지막에 한 번 이관"* 에서 *"배포된 서버 위에서 개발"* 로 바뀌어, 결정들이 **올린 뒤에 안전하게 계속 바꿀 수 있는 것**(Flyway·CI/CD·스냅샷)에 무게를 둔다. **D-1 EC2 `t4g.small`(서울)** — 기준이 *배포 안정성*(교수님)과 *이력서 가치*(업계 표준) 둘이었고, EC2의 복잡함이 곧 학습 가치라 첫날 규칙 4개로 안정성을 보완. Oracle은 통제 불가 변수로 기각. **D-2 하이브리드 추천(= R-2)** — 서버 크기를 결정하므로 배포 전에 닫았다. 자연어 질의를 넣기로 하면서 양자택일이 아니게 됐고, 질의 임베딩을 외부 API로 생성해 2GB 유지. **R-4는 MySQL로 함께 확정.** **D-3 Flyway(baseline v17)** — 결정적 이유는 `validate` 하에서 jar와 스키마의 적용 순서. 조건 5건(실행 SQL 동결·설계 문서 분리, Boot 4 스타터, baseline, 배포 전 스냅샷, 데이터는 덤프). **D-4 jar+systemd+Nginx(certbot)+MySQL 직접 설치** — 원안 Caddy를 이력서 기준으로 Nginx로 교체, Docker는 안정화 후. **D-5 참조·콘텐츠 10개 테이블만 데이터 덤프 이관**, 사용자 데이터는 운영에서 새로, 이관 후 영화 데이터 원본은 운영. **현황 점검에서 발견** — L-10·L-11이 문서상 "8월 말 선행"이었으나 **미처리**였고, **L-11이 이미 버그를 만들어 두었다**(`BoxOfficeScheduler`의 `LocalDate.now()`가 JVM 시간대를 따라 UTC 서버에서 **이틀 전**을 수집). 코드 수정 대신 JVM 시간대 고정 + **기동 시 시간대 가드**로 해소하기로 함. 또 비밀 파일이 `src/main/resources`에 있어 **로컬 `bootJar`에 비밀이 포장되는 문제**를 발견 → `config/`로 이동 |
