# CineMory — 관리자 페이지 스펙 (M6)

> **이 문서는 새 세션이 컨텍스트 없이 착수할 수 있도록 쓴 인수인계 문서다.** 2026-10-15에 범위를, 2026-10-19에
> 1단계 세부를 확정했다. **1단계 = 회원·영화 관리(조회 + 영화 단건 재동기화).** 2단계 이후(감사 로그·제재·신고·공지·
> 대시보드)는 9절에 범위만 남긴다.
>
> **일정** — M3-b 추천보다 **먼저** 한다(2026-10-19 사용자 결정). 최종 마감 11/18, 11/12부터 기능 동결(기획노트 4절).
> 각 단계(5절 A-1~A-6)는 **끝날 때마다 `main`에 머지·배포 가능한 상태로 닫는다** — 어디서 멈춰도 그 시점이 완성본이다.
>
> **계약은 구현 후 레이어 스펙으로 옮긴다** — 엔드포인트는 `controller-layer-spec.md`, 서비스 로직은 `service-layer-spec.md`,
> 스키마는 `docs/schema/v26-delta.sql`. 이 문서는 **결정 기록과 근거**로 남는다(`account-integrity-spec.md`와 같은 위치).

**착수 전에 읽을 것**

| 문서 | 왜 |
|---|---|
| `docs/controller-layer-spec.md` **5-0-D · 5-0-D-1 · 5-6-B** | `PageResponse`, 정렬은 화이트리스트 enum, `AdminController` 빈 이름 충돌 해소 방식 |
| `docs/tmdb-sync-spec.md` · `MovieSeedService.resync` | 재동기화가 공유하는 `running` 플래그와 429 처리 |
| `docs/deploy-spec.md` **2-6 · D-3** | Nginx 확정본(`deploy/nginx/cinemory.conf`)·certbot webroot 방식, Flyway 확장/축소 규칙 |
| `docs/security-spec.md` **S-11 L-3** | Access Token 즉시 무효화 불가(30분) — 권한 강등의 반영 시점 |
| 프론트 `cinemory-app/src/api/client.ts` | 단일 비행(single-flight) 재발급 — 관리자 웹도 같은 이유로 똑같이 해야 한다 |

---

## 1. 확정 사항 (AD-1 ~ AD-10)

| # | 항목 | 결정 | 근거 / 기각한 안 |
|---|---|---|---|
| AD-1 | 형태 | **웹 백오피스** | 업계 표준. 넓은 화면의 표·필터, 스토어 심사 없는 배포, 관리자 코드가 앱 번들에 들어가지 않는다 |
| AD-2 | 프론트 스택 | **React + Vite + TypeScript + Refine + Ant Design** | RN 경험(React)을 살리고 CRUD 화면을 빠르게 만든다. Ant Design은 표·폼·한국어 로케일이 갖춰져 있다. 기각 — Expo Web(표·대시보드 UI 약함, 관리자 코드가 앱과 섞임), Thymeleaf(확장성 낮음) |
| AD-3 | 리포 | **별도 리포 `cinemory-admin`** | 배포 단위가 다르다(정적 파일 vs jar). 백엔드 CI에 Node 빌드를 섞지 않는다 |
| AD-4 | 호스팅 | **`admin.cinemory.co.kr` 서브도메인, 같은 EC2의 Nginx가 정적 파일 서빙 + `/api/`를 백엔드로 프록시** | API가 이미 `api.` 서브도메인이라 `admin.`이 자연스럽다(업계에서도 서브도메인이 더 흔함). **`/api/`를 같은 호스트에서 프록시하므로 브라우저 입장에서 같은 출처 → CORS 설정을 건드리지 않는다**(로컬은 Vite dev 프록시로 같은 효과). 기각 — `api.` 아래 `/admin` 경로(API 도메인에 화면이 섞임), 교차 출처 호출(운영 CORS에 origin 추가·프리플라이트) |
| AD-5 | 관리자 계정 | **운영 전용 이메일/비밀번호 계정**을 DB에서 `ADMIN`으로 승격. 기존 `POST /api/auth/login` 사용 | `RoleType` 주석의 "승격 API 없음" 유지. 개인 카카오 계정을 관리자로 쓰면 웹용 카카오 로그인 흐름이 따로 필요하다. 개인·운영 계정 분리는 업계 기본 |
| AD-6 | 관리자 판정 | **신규 `GET /api/admin/me`** — 일반 사용자는 기존 인가 규칙으로 403 | `UserResponse`에 `role`을 추가하면 앱 계약이 바뀐다. 이 엔드포인트는 `hasRole('ADMIN')`을 그대로 타므로 서버가 판정한다 |
| AD-7 | 토큰 보관(웹) | **Access Token은 메모리, Refresh Token은 `sessionStorage`** | 탭을 닫으면 끝나고 새로고침은 버틴다. 업계 표준은 httpOnly 쿠키지만 백엔드가 본문으로 토큰을 주는 앱 계약이라 지금 바꾸지 않는다 — AD-4로 같은 출처가 됐으므로 **나중에 쿠키로 옮기기 쉽다**(9절). XSS 완화는 Nginx CSP 헤더(6절) |
| AD-8 | 1단계 범위 | **회원: 목록·검색·상세 / 영화: 목록·검색·상세·단건 재동기화** | 제재를 빼서 **회원 탈퇴(Part C) 선행이 필요 없다**. 쓰기가 재동기화 하나뿐이라 감사 로그는 2단계 |
| AD-9 | 단건 재동기화 | **`MovieSeedService.resyncOne(movieId)`** — 일괄 시드·resync와 **`running` 플래그 공유**, 실행 중이면 409 | 같은 영화를 일괄 작업과 동시에 갱신하면 매핑 행(`movie_genre` 등) 교체가 겹친다. 기존 `resync(fromId, limit)`는 커서 일괄용이라 버튼에 쓰기 어색하다 |
| AD-10 | 마지막 동기화 시각 | **`movie.synced_at` nullable 컬럼 추가(V26, 확장)** — 기존 행은 NULL로 둔다. **A-2는 잘라내도 되는 단계** | `movie`에 `created_at`뿐이다. `created_at`으로 백필하면 그 뒤 resync된 영화의 시각이 거짓이 된다. NULL은 화면에서 "기록 없음(도입 전)" |

---

## 2. 범위 밖 (1단계에서 하지 않는 것)

- 쓰기 동작: 회원 정보 수정·제재·삭제, 영화 정보 수정·삭제, 일괄 시드/resync 버튼(기존 API는 그대로 두되 화면은 없다)
- 감사 로그, 관리자 권한 세분화, IP 제한
- 앱(`cinemory-app`) 변경 — **없다**

---

## 3. 백엔드 API 계약

공통: 전부 `/api/admin/**` → 기존 `hasRole('ADMIN')`(`SecurityConfig`). 페이징은 `PageResponse`(5-0-D), **page는 0-based**.
정렬은 5-0-D-1처럼 **화이트리스트 enum을 `sort`로 받고 Service가 `Sort`로 바꾼다**(자유 `sort`는 버린다). `size` 상한은 기존 컨벤션을 따른다.

> ⚠️ **관리자 API는 공개 범위(`UserAccessPolicy`)를 적용하지 않는다** — 관리자는 `PRIVATE` 사용자도 본다.
> 그래서 앱용 Service를 재사용하지 말고 **관리자 전용 조회 Service + 전용 DTO**를 둔다. 앱용 DTO를 재사용하면
> 앱 계약 변경이 관리자 화면을, 관리자 필드 추가가 앱 응답을 흔든다.

### 3-1. `GET /api/admin/me`

| 응답 200 | `AdminMeResponse { userId, email, nickname }` |
|---|---|
| 403 | 일반 사용자(기존 `JwtAccessDeniedHandler`) |

현재 사용자는 기존 컨트롤러와 같은 방식(`@AuthUser Long userId`)으로 받는다.

### 3-2. `GET /api/admin/users`

| 파라미터 | 설명 |
|---|---|
| `q` (선택) | trim 후 공백이면 무시. **이메일 또는 닉네임 부분 일치**(대소문자 무시는 COLLATE `utf8mb4_0900_ai_ci`가 해 준다) |
| `sort` | `AdminUserSort`: `CREATED_AT_DESC`(기본) · `CREATED_AT_ASC` · `NICKNAME_ASC` — 모두 마지막에 `id` 동률 정렬 |
| `page` · `size` | 0-based, 기본 20 |

응답 `PageResponse<AdminUserSummaryResponse>`

```text
AdminUserSummaryResponse {
  userId, email, nickname,
  role,               // USER | ADMIN
  hasPassword,        // 이메일 가입 여부 — User.hasPassword()
  socialProviders,    // [KAKAO, GOOGLE] — 연결된 소셜, 없으면 []
  createdAt
}
```

**쿼리 — N+1 금지.** ① `UserRepository`에 `q` 조건 `@Query` 페이지 조회 1회(+count) ② 페이지의 `userId` 목록으로
`UserSocialAccountRepository`에 **신규** `findAllByUserIdIn(ids)` 1회 → 서비스에서 `userId`별로 묶는다. 총 3쿼리.
`LIKE '%q%'`는 인덱스를 못 타지만 사용자 수가 작아 문제없다(인덱스 추가 안 함).

### 3-3. `GET /api/admin/users/{userId}`

응답 `AdminUserDetailResponse` — 3-2의 필드 + 아래. 없으면 `USER_NOT_FOUND`(404).

```text
  profileImage, privacySetting, updatedAt,
  activity {
    watchRecordCount,    // 전체 회차
    watchedMovieCount,   // 본 영화 수 = 대표 기록 수(is_representative = true)
    reviewCount, wishCount, collectionCount,
    followerCount, followingCount,
    lastWatchDate        // MAX(watch_date), 없으면 null
  }
```

카운트는 단건 상세라 **항목별 `count` 쿼리**로 충분하다(약 8회). 이미 있는 `ReviewRepository.countByUserId`,
`FollowRepository.countByFollowerId/countByFollowingId`는 재사용하고 없는 것만 추가한다.

### 3-4. `GET /api/admin/movies`

| 파라미터 | 설명 |
|---|---|
| `q` (선택) | `title` 또는 `originalTitle` 부분 일치 |
| `tmdbId` (선택) | 정확히 일치. `q`와 함께 오면 AND |
| `sort` | `AdminMovieSort`: `ID_DESC`(기본, 최근 등록순) · `RELEASE_DATE_DESC`(NULL 맨 뒤) · `SYNCED_AT_ASC`(NULL 맨 앞 — "오래 동기화 안 된 것", **A-2를 하는 경우에만 추가**) |

응답 `PageResponse<AdminMovieSummaryResponse>`

```text
AdminMovieSummaryResponse {
  movieId, tmdbId, koficMovieCd, title, originalTitle, releaseDate,
  posterPath,          // TMDB 경로 그대로. base URL 조립은 관리자 웹 몫(앱과 같은 규칙)
  voteAverage, voteCount, createdAt,
  syncedAt             // A-2 이후. 그 전에는 필드 없음
}
```

`movie` 약 1.3만 행이라 `LIKE` 풀스캔도 수 ms다. 앱의 검색 API(`{registered, suggestions}`, 1-based)는 재사용하지 않는다 — 계약이 다르다.

### 3-5. `GET /api/admin/movies/{movieId}`

응답 `AdminMovieDetailResponse` — 3-4의 필드 + 아래. 없으면 `MOVIE_NOT_FOUND`(404).

```text
  overview, runtime, backdropPath,
  genres[], countries[], directors[],   // 이름 목록
  castCount,                             // 출연진 수만 — 전체 목록은 싣지 않는다
  usage { watchRecordCount, reviewCount, wishCount, collectionCount }
```

장르·국가·감독은 영화 상세(`MovieQueryService`)가 이미 쓰는 Repository 조회를 재사용한다(새 쿼리 최소화).

### 3-6. `POST /api/admin/movies/{movieId}/resync`

TMDB에서 이 영화 하나를 다시 받아 갱신하고, **갱신 후 다시 읽은 `AdminMovieDetailResponse`**(200)를 돌려준다.

| 상황 | 응답 |
|---|---|
| 영화 없음 | `MOVIE_NOT_FOUND` 404 |
| 일괄 시드/resync 실행 중 | `SEED_ALREADY_RUNNING` 409 |
| TMDB 한도 초과 | `TMDB_RATE_LIMITED` 429 |
| TMDB에서 삭제된 영화 | `TMDB_MOVIE_NOT_FOUND` 404 — **우리 DB 행은 그대로 둔다**(기록·리뷰가 걸려 있다) |
| 참조 테이블 비어 있음 | `REFERENCE_DATA_NOT_SEEDED` 500(기존 가드) |

**구현** — `MovieSeedService.resyncOne(Long movieId)`:
`running.compareAndSet` → `ensureReferenceDataSeeded()` → 영화 조회 → `movieSyncService.syncFromTmdb(movie.getTmdbId())` → `finally running.set(false)`.
일괄 `resync`와 달리 **예외를 흡수하지 않고 그대로 던진다**(단건이라 호출자가 결과를 알아야 한다).
조회 응답 조립은 3-5의 Service를 호출한다(트랜잭션 밖에서 동기화가 끝난 뒤 새로 읽기).

### 3-7. 배치 위치 · 빈 이름

| 대상 | 위치 |
|---|---|
| `GET /api/admin/me`, `/users/**` | **신규** `domain/user/controller/AdminController` — `@RestController("userAdminController")` |
| `/movies` 조회·단건 resync | **기존** `domain/movie/controller/AdminController`(`movieAdminController`)에 추가 |
| 조회 Service | `domain/user/service/AdminUserQueryService`, `domain/movie/service/AdminMovieQueryService` — `@Transactional(readOnly = true)` |
| DTO | 각 도메인 `dto/` 아래 `Admin*` 접두 |

클래스명 `AdminController` 중복은 5-6-B의 규칙대로 **빈 이름만 명시**해 해소한다.

---

## 4. 스키마 — V26 `synced_at` (A-2, 선택)

```sql
-- V26__movie_synced_at.sql  — 확장: nullable 컬럼 추가(옛 코드는 이 컬럼을 모른 채 그대로 동작)
ALTER TABLE movie ADD COLUMN synced_at DATETIME(6) NULL;
```

- 분류: **확장 1문장.** 축소할 일 없음.
- 엔티티: `Movie.syncedAt` + 비즈니스 메서드 `markSynced(LocalDateTime syncedAt)`(setter 금지 규칙). `jpa-entity-spec.md`에 필드 추가.
- 갱신 지점: **`MovieSyncPersister.persist()` 한 곳** — 신규 등록·갱신 모두 `movie.markSynced(LocalDateTime.now(clock))`(`ClockConfig`의 `Clock` 주입).
  매번 값이 바뀌므로 dirty check상 UPDATE가 매번 나간다 — 의도된 동작이다.
- 백필 없음(AD-10). 설계 근거·롤백은 `docs/schema/v26-delta.sql`. 적용 후 스키마 재덤프(현행 규칙).

---

## 5. 실행 순서 (단계마다 머지 가능)

| 단계 | 리포 | 내용 | 완료 판정 |
|---|---|---|---|
| **A-1** | backend | 3절 API 6개 + 테스트(7절) | `./gradlew test` 통과 → PR 머지 → CI 배포. 앱 영향 없음 |
| **A-2** ✅ *(선택)* | backend | 4절 V26 + `markSynced` + `syncedAt` 필드·`SYNCED_AT_ASC` | 운영 기동 후 `flyway_schema_history`에 V26, 단건 resync 후 값이 채워짐 |
| **A-3** | 운영 작업 | 8절 관리자 계정 준비 | 운영에서 `GET /api/admin/me` 200 |
| **A-4** | admin(신규) | 6-1~6-4 — 로컬에서 로그인·회원·영화 화면 | 로컬 백엔드(`bootRun`) 대상으로 6-5 체크리스트 |
| **A-5** | backend + 서버 | 6-6 Nginx 서버 블록 · DNS · 인증서 | `https://admin.cinemory.co.kr` 접속, `/api/admin/me` 200 |
| **A-6** ✅ | admin | 6-7 배포 워크플로 | `main` 푸시 → 자동 반영 |

A-4까지 끝나면 로컬 시연은 가능하다. 시간이 없으면 **A-5까지(수동 업로드)** 로 멈추고 A-6을 자른다.

---

## 6. 관리자 웹 (`cinemory-admin`)

### 6-1. 구성

```text
cinemory-admin/
├─ src/
│  ├─ api/
│  │  ├─ client.ts          # fetch 래퍼 — 토큰 부착 · 401 단일 비행 재발급 · 에러 정규화
│  │  ├─ endpoints.ts       # 경로 문자열은 여기만
│  │  └─ api.d.ts           # 생성물 — 손으로 쓰지 않는다(npm run gen:api)
│  ├─ providers/
│  │  ├─ authProvider.ts    # Refine AuthProvider
│  │  └─ dataProvider.ts    # Refine DataProvider — PageResponse ↔ Refine 변환
│  ├─ pages/  login · users/{list,show} · movies/{list,show}
│  ├─ constants/tmdb.ts     # tmdbImageUrl(path, size)
│  └─ App.tsx               # Refine + antd 레이아웃(사이드바: 회원 · 영화)
├─ vite.config.ts           # dev 프록시 /api → http://localhost:8080
└─ index.html               # <meta name="robots" content="noindex,nofollow">
```

- 패키지는 설치 시점의 최신 안정판. **Refine은 메이저 버전마다 훅·필드 이름이 다르므로(예: 페이지 번호 필드명) 설치된 버전 문서를 기준으로 쓴다.**
- `gen:api` — `openapi-typescript`로 **로컬 백엔드** `http://localhost:8080/v3/api-docs`에서 생성(운영은 Swagger 차단, M5).
- Vite dev 프록시 덕분에 로컬도 같은 출처다 → **백엔드 `cinemory.cors.allowed-origins`를 바꾸지 않는다.**

### 6-2. `client.ts` — 단일 비행 재발급 ⚠️

백엔드는 **리프레시 회전 + 재사용 감지**를 한다. 동시 401에서 `reissue`가 두 번 나가면 두 번째가 "이미 쓴 토큰"으로 판정돼
**그 사용자의 전 세션이 폐기된다**(앱 `CLAUDE.md`와 같은 함정). 따라서:

1. 진행 중인 재발급 `Promise`를 모듈 변수 하나에 보관하고, 401을 받은 요청은 **모두 그 Promise를 기다린 뒤 한 번만 재시도**한다.
2. `POST /api/auth/reissue {refreshToken}` 성공 → 새 쌍 저장(Access는 메모리, Refresh는 `sessionStorage`) → 대기 요청 재시도.
3. 재발급 실패 → 토큰 삭제 → `/login`.
4. `/api/auth/login`·`/reissue` 자체의 401은 재발급 대상이 아니다(무한 루프 방지).
5. 에러 본문은 백엔드 `ErrorResponse {status, code, message, errors}` — 화면은 `message`를 보여 주고 분기는 `code`로 한다.

### 6-3. Provider

**authProvider**

| 메서드 | 동작 |
|---|---|
| `login` | `POST /api/auth/login` → 토큰 저장 → `GET /api/admin/me`. **403이면 즉시 `logout` 후 "관리자 권한이 없는 계정입니다"** — 일반 계정의 리프레시 토큰을 남기지 않는다 |
| `logout` | `POST /api/auth/logout {refreshToken}`(인증 필요 — Access 부착) → 실패해도 로컬 토큰은 지운다 |
| `check` | 메모리 Access 또는 `sessionStorage` Refresh가 있으면 통과(새로고침 직후는 Refresh로 재발급) |
| `getIdentity` | `GET /api/admin/me` |
| `onError` | 401은 `client.ts`가 처리하므로 여기 오면 로그아웃, 403은 로그아웃 |

**dataProvider** — 리소스 `users`·`movies`만.

- `getList`: Refine의 페이지 번호는 **1-based → `page = 현재 - 1`**. `pageSize → size`. 정렬은 컬럼 정렬 대신 **`sort` enum 선택 상자**로 받는다(자유 정렬 불가 계약). 필터는 `q`(+ 영화 `tmdbId`).
  응답 `{ data: content, total: totalElements }`.
- `getOne`: 상세 엔드포인트.
- `custom`: 재동기화 `POST /movies/{id}/resync`.
- 생성·수정·삭제는 구현하지 않는다(호출되면 에러).

### 6-4. 화면

| 화면 | 내용 |
|---|---|
| 로그인 | 이메일·비밀번호만. 실패는 서버 `message` 그대로(`INVALID_CREDENTIALS`) |
| 회원 목록 | 표: ID · 닉네임 · 이메일 · 가입 경로 태그(이메일/카카오/구글 — `hasPassword` + `socialProviders`) · 권한 · 가입일. 검색창(`q`) · 정렬 선택 |
| 회원 상세 | 기본 정보 + 활동 지표 카드 8개(3-3) |
| 영화 목록 | 표: 포스터 썸네일(`tmdbImageUrl`, 없으면 자리 표시) · 제목(원제) · 개봉일 · TMDB ID(`https://www.themoviedb.org/movie/{tmdbId}` 새 탭) · 평점/투표수 · 등록일 · (동기화 시각). 검색창(`q`)·TMDB ID 입력·정렬 선택 |
| 영화 상세 | 정보 + 장르/국가/감독 + 사용 지표 + **[TMDB 재동기화]** — 확인 모달 → 성공 시 응답으로 화면 갱신 + 토스트. 409("다른 동기화 작업이 실행 중"), 429("잠시 후 다시"), `TMDB_MOVIE_NOT_FOUND`("TMDB에서 삭제된 영화 — 우리 데이터는 유지됨")를 구분해 안내 |

공통 헤더: 로그인한 관리자 닉네임 · 로그아웃. 개인정보(이메일)가 보이는 화면이라 **스크린샷·데모 영상에는 테스트 계정만** 띄운다.

### 6-5. 로컬 체크리스트 (A-4 완료 판정)

1. 일반 계정 로그인 → "관리자 권한이 없는 계정" + 로그인 화면 유지
2. 관리자 로그인 → 회원 목록 · 검색 · 정렬 · 페이지 이동(마지막 페이지에서 다음 비활성 = `last`)
3. 회원 상세의 카운트가 DB와 일치(1명 표본)
4. 영화 검색(한글 제목·원제·TMDB ID) · 상세 · 재동기화 성공
5. 일괄 resync 실행 중 단건 재동기화 → 409 안내
6. Access 만료 상황(개발용으로 TTL 짧게) 에서 목록 새로고침 → 재발급 **1회**만(네트워크 탭) · 화면 유지
7. 로그아웃 → 뒤로 가기로 보호 화면 접근 불가

### 6-6. 서버 — DNS · 인증서 · Nginx (A-5)

1. **DNS**: `admin` A 레코드 = 기존 탄력적 IP.
2. **인증서**: 기존과 같은 webroot 방식으로 **별도 인증서** — `sudo certbot certonly --webroot -w /var/www/html -d admin.<도메인> --deploy-hook "systemctl reload nginx"`.
   ⚠️ 서버 블록을 넣기 **전에** 발급한다(인증서가 없으면 `nginx -t` 실패 — deploy-spec 2-6과 같은 순서). 80 블록의 ACME 경로가 필요하므로 **80 블록만 먼저 넣고 발급 → 443 블록 추가** 순서로 한다.
3. **Nginx**: 확정본 `deploy/nginx/cinemory.conf`에 서버 블록 2개를 **같은 파일에** 추가한다(`limit_req_zone cinemory_auth`를 공유).
   ⚠️ **자리 표시자는 `admin.example.net`** — 기존 `sed 's/example\.com/…/g'`에 걸리지 않게 다른 문자열을 쓰고, 설치 절차에 `sed 's/admin\.example\.net/admin.<도메인>/g'`를 한 줄 추가한다.

```nginx
server {
    listen 80;
    listen [::]:80;
    server_name admin.example.net;
    location /.well-known/acme-challenge/ { root /var/www/html; }
    location / { return 301 https://$host$request_uri; }
}

server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name admin.example.net;

    ssl_certificate     /etc/letsencrypt/live/admin.example.net/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/admin.example.net/privkey.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;

    client_max_body_size 1m;
    proxy_set_header Host              $host;
    proxy_set_header X-Real-IP         $remote_addr;
    proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Host  $host;
    proxy_set_header X-Forwarded-Port  $server_port;

    # 보안 헤더 — 관리자 화면 전용. antd는 런타임에 <style>을 넣어 style-src에 'unsafe-inline'이 필요하다
    add_header X-Frame-Options        "DENY" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header Referrer-Policy        "no-referrer" always;
    add_header X-Robots-Tag           "noindex, nofollow" always;
    add_header Content-Security-Policy "default-src 'self'; img-src 'self' data: https://image.tmdb.org; style-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'" always;

    limit_req_status 429;
    error_page 429 = @rate_limited;
    location @rate_limited {            # api 블록과 같은 JSON 429
        default_type application/json;
        charset utf-8;
        charset_types application/json;
        add_header Retry-After 60 always;
        return 429 '{"status":429,"code":"TOO_MANY_REQUESTS","message":"요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.","errors":[]}';
    }

    location = /api/auth/login {
        limit_req zone=cinemory_auth burst=20 nodelay;
        proxy_pass http://127.0.0.1:8080;
    }
    location /api/ {                     # 같은 출처 프록시 — CORS 불필요(AD-4)
        proxy_pass http://127.0.0.1:8080;
    }

    root /var/www/cinemory-admin/current;
    location /assets/ {                  # Vite 해시 파일명 — 영구 캐시
        add_header Cache-Control "public, max-age=31536000, immutable" always;
        try_files $uri =404;
    }
    location / {                         # SPA — 새로고침해도 index.html
        add_header Cache-Control "no-cache" always;
        try_files $uri /index.html;
    }
}
```

> ⚠️ `add_header`는 **하위 `location`에 `add_header`가 하나라도 있으면 상위 것을 상속하지 않는다**(Nginx 규칙). 위 `/assets/`·`/`
> 블록은 `Cache-Control`을 넣으므로 **보안 헤더가 빠진다.** 구현 시 보안 헤더를 `snippets/cinemory-admin-headers.conf`로 빼서
> 서버 블록과 두 location에 `include`하거나, 각 location에 반복한다. 확인: `curl -I https://admin.<도메인>/`에 CSP가 보여야 한다.
>
> ⚠️ **같은 출처 판정은 백엔드도 한다.** 브라우저는 같은 출처 POST에도 `Origin`을 붙이고, Spring은 `Origin`과 요청의
> 스킴·호스트를 비교해 CORS 요청인지 정한다. `forward-headers-strategy: native` + `X-Forwarded-Host/Proto` 덕분에
> `https://admin.<도메인>`으로 인식돼 통과해야 정상이다. **로그인 POST가 403 "Invalid CORS request"면** 전달 헤더를 먼저 의심하고,
> 우회로 운영 env의 허용 origin에 `https://admin.<도메인>`을 추가한다.

### 6-7. 배포 (A-6)

- 서버 디렉터리: `/var/www/cinemory-admin/releases/<sha>/` + 심볼릭 링크 `current` → **링크 교체로 원자적 전환**, 직전 2개만 보관(롤백 = 링크 되돌리기).
- 워크플로(`cinemory-admin/.github/workflows/deploy.yml`, `main` 푸시): `npm ci` → `npm run build` → `dist`를 tar로 묶어
  기존 아티팩트 버킷 `admin-releases/<sha>/dist.tar.gz`에 업로드 → **기존 백엔드와 같은 SSM 방식**으로 서버에서 내려받아 풀고 링크 교체.
- IAM: 배포 역할의 신뢰 정책 `sub`에 `repo:hanIn-woong/cinemory-admin:ref:refs/heads/main`을 **추가**(와일드카드 금지 — server-setup-runbook 기준).
  권한 정책은 버킷 `/*` PutObject·SSM이 이미 있어 그대로 된다.
- **A-6을 자르는 경우** — 로컬에서 빌드한 tar를 같은 경로로 올리고 SSM 명령 한 번(수동). 절차는 운영 런북에 적는다.

---

## 7. 테스트 (A-1)

| 대상 | 내용 |
|---|---|
| 인가 | `/api/admin/me`·`/users`·`/movies` — 비로그인 401, `USER` 403, `ADMIN` 200 |
| `AdminUserQueryService` | `q`가 이메일·닉네임 양쪽에 걸림 · 공백 `q`는 전체 · 소셜 2개 연결 사용자의 `socialProviders` · **`PRIVATE` 사용자도 조회됨** · 카운트 8종(대표/전체 회차 구분) · 없는 ID 404 |
| `AdminMovieQueryService` | 제목·원제 부분 일치 · `tmdbId` AND · 정렬 enum별 순서(NULL 위치) · 상세의 장르/국가/감독/카운트 |
| `resyncOne` | 성공 시 **영화 `id` 불변·행 중복 없음** · 실행 중 409 · TMDB 404/429 전파(TMDB 클라이언트는 기존 테스트처럼 대역) |
| 쿼리 수 | 회원 목록 1페이지 = 3쿼리(목록·count·소셜) — Hibernate statistics로 단언(N+1 회귀 방지) |

---

## 8. 관리자 계정 준비 (A-3, 운영 1회)

1. 운영 DB에서 현황 확인: `SELECT id, email, role FROM user WHERE role = 'ADMIN';`
   (`movie-seed-runbook`의 `CINEMORY_ADMIN_EMAIL`처럼 이메일 관리자 계정이 이미 있으면 그것을 쓰고 2~3을 건너뛴다.)
2. 운영 전용 이메일로 기존 가입 API(`POST /api/auth/signup`) 가입.
3. `UPDATE user SET role = 'ADMIN' WHERE email = '<운영 이메일>';` → **1 row** 확인.
4. **다시 로그인** — 권한은 로그인 때 Access Token(`role` 클레임)에 실린다.
5. (권장) 운영 계정으로 `GET /api/admin/me` 200을 확인한 뒤 **개인 카카오 계정이 `ADMIN`이면 `USER`로 강등**(최소 권한).
   강등은 Access Token 만료(최대 30분, L-3) 뒤 반영된다. 시드·resync 런북의 관리자 계정 안내도 운영 계정으로 바꾼다.

비밀번호는 비밀번호 관리자에 보관하고 리포·문서에 적지 않는다.

---

## 9. 2단계 이후 (범위만 — 2026-10-15 확정 목록)

> **2026-10-19 갱신** — 2단계는 **감사 로그 · 인물 한글명 덮어쓰기 · 대시보드**로 확정(아래 10~14절). 회원 제재·공지·신고는 **3단계**.

| 순서(잘라낼 때 뒤에서부터) | 기능 | 메모 |
|---|---|---|
| 1 | **감사 로그** | 이후 모든 쓰기 동작이 남긴다 — 2단계 첫 항목. 개인정보 열람(회원 상세) 기록도 여기서 검토 |
| 2 | **회원 제재** (경고 → 기간 정지 → 영구 정지) | **회원 탈퇴(Part C) 후** — 사용자 상태·토큰 폐기를 탈퇴가 먼저 정한다. 정지 시 리프레시 토큰 폐기 필수 |
| 3 | 대시보드 | 읽기 전용 집계라 싸고 발표 화면이 좋다 |
| 4 | 공지사항 | 앱 설정 화면 안 목록/상세 — 앱 작업 동반 |
| 5 | 신고 처리 + 콘텐츠 검수 | 신규 도메인(앱 신고 API·화면부터). ⚠️ `domain/report`는 M3-a 리포트 — 다른 이름 |
| — | 토큰을 httpOnly 쿠키로 | AD-7 후속. 같은 출처라 CORS credentials 없이 가능 |
| — | 관리자 호스트 IP 제한 · `api.` 호스트에서 `/api/admin/**` 차단 | 차단 전 시드·resync 런북이 `admin.` 호스트를 쓰도록 바꿔야 한다 |

보류(기획노트 8절): 큐레이션, 홈 배너, 권한 세분화, 1:1 문의, 푸시, 기능 플래그, 추천 가중치 튜닝 화면.

---

# 2단계 — 감사 로그 · 인물 한글명 · 대시보드 (2026-10-19 확정)

> 1단계(A-1~A-6)는 2026-10-19 운영 배포까지 끝났다. 2단계는 **B-1 → B-2 → B-3** 순서로, 단계마다 머지·배포 가능하게 닫는다.
> 일정은 10/20~10/28(M3-b 전). 시간이 모자라면 **B-3 → B-2 순으로 자른다**(B-1은 B-2의 전제라 먼저 한다).

**착수 전에 읽을 것** — 위 1단계 표 + `CineMory_기획노트.md` 8절 **"인물명 한글화"**(`name_ko` + `COALESCE` 결정과 Wikidata 일괄 매핑 계획),
`M3a-report-spec.md`(리포트 인물 TOP 쿼리), `service-layer-spec.md` 4-8-J·K(인물 점수 쿼리 개정 이력).

## 10. 확정 사항 (BD-1 ~ BD-9)

| # | 항목 | 결정 | 근거 / 기각한 안 |
|---|---|---|---|
| BD-1 | 2단계 범위 | **감사 로그 · 인물 한글명 덮어쓰기 · 대시보드** | 셋 다 **회원 탈퇴(Part C)와 무관**하다. 제재는 탈퇴가 정하는 사용자 생명주기(상태·토큰 폐기) 위에 얹어야 해서 **3단계로 미룬다**(9절) |
| BD-2 | 감사 로그 기록 대상 | **관리자 쓰기 API 전부 + 회원 개인정보 조회(목록·상세)** | 쓰기 추적이 감사 로그의 본래 목적. 조회까지 남기는 것은 개인정보 처리 시스템의 **접속기록을 남기는 업계·법령 관행**(개인정보 안전성 확보조치 기준)을 따른 것 — 이메일이 보이는 화면이 대상이다. 감사 로그 조회 자체는 남기지 않는다(자기 참조 소음) |
| BD-3 | 기록 방식 | **서비스 호출로 명시적 기록**(`AdminAuditService.record(...)`) | AOP 어노테이션은 "어디서 기록되는지"가 코드에 안 보이고 상세(변경 전/후) 값을 넣기 어렵다. 명시 호출은 테스트에서 단언하기 쉽다 |
| BD-4 | 관리자 참조 | **FK 없이 `admin_user_id` + `admin_email_snapshot`** | 로그는 관리자 계정이 탈퇴·삭제돼도 남아야 한다. FK를 걸면 탈퇴가 막히거나(RESTRICT) 로그가 지워진다(CASCADE). 이메일은 그 시점 값 보존용 **불변 스냅샷**(수정 메서드 없음 — CLAUDE.md 규칙) |
| BD-5 | 대상 참조 | **`target_type`(Enum) + `target_id`(Long), 연관관계 매핑 없음** | `comment`와 같은 다형 참조 규칙(CLAUDE.md). 대상이 지워져도 로그는 남는다 |
| BD-6 | 상세 값 | **`detail` JSON 컬럼**(`@JdbcTypeCode(SqlTypes.JSON)` + `Map<String, Object>`) | 동작마다 남길 값이 다르다(재동기화 결과 수 / 한글명 전후 / 검색어). 컬럼을 동작별로 늘리지 않는다. MySQL이 JSON 형식을 검증한다 |
| BD-7 | 인물 한글명 저장 | **`person.name_ko` nullable 컬럼(V28, 확장)**, 표시는 **`name_ko`가 있으면 그것, 없으면 `name`** | 기획노트 8절에서 이미 확정한 구조. **TMDB 재동기화는 `name`만 덮어쓰고 `name_ko`는 건드리지 않는다** — 관리자 수정이 재동기화로 사라지지 않는다. 서버가 기존 `name` 필드에 표시 값을 내리므로 **앱 계약·앱 코드 변경 없음** |
| BD-8 | 한글명 편집 동선 | **영화 상세 → 출연진·감독 목록에서 바로 편집** + 인물 검색 화면 | 실제 작업은 "이 영화 출연진이 영어로 나온다"에서 시작한다. 인물 23만 명을 목록으로 훑는 동선은 쓸모가 없다 |
| BD-9 | 활성 사용자 정의 | **기간 내 리프레시 토큰이 발급된 사용자 수**(로그인 + 재발급) | 접속 기록 테이블이 없다. Access Token이 30분이라 앱을 열면 재발급이 일어나므로 "앱을 연 사용자"의 근사치가 된다. 기록 활동 기준(시청 기록을 남긴 사용자)도 함께 보여 준다. ⚠️ 나중에 만료 토큰 정리 작업을 넣으면 과거 활성 수가 줄어든다 — 정리 보관 기간 ≥ 대시보드 최대 기간(90일)으로 둔다 |
| BD-10 | 영화 제목 수정 방식 | **`movie.title_ko` 덮어쓰기 컬럼**(V29, 확장) — 표시는 `title_ko`가 있으면 그것, 없으면 `title` | 인물 한글명(BD-7)과 같은 구조. 기각 — `title` 직접 수정(TMDB 재동기화가 `title`을 다시 덮어써 수정이 사라지므로 "수정 잠금" 플래그가 추가로 필요하고 복잡해진다). 사용자 결정 2026-10-20 |
| BD-11 | 제목순 정렬 | **표시 제목 기준** — 정렬 키가 `COALESCE(title_ko, title)` | 화면에 보이는 제목과 정렬 순서가 어긋나면 안 된다. `Movie`에 읽기 전용 `@Formula` 속성 `sortTitle`을 두고 `RecordSort`·`WishSort`의 제목 정렬이 그것을 쓴다. 사용자 결정 2026-10-20 |
| BD-12 | 진행 시점 | **B-3(대시보드) 전에 B-2.5로** | 표시 지점이 많아(12-B-2) 스펙부터 쓴다. 사용자 결정 2026-10-20 |
| BD-13 | 검색·박스오피스 매칭 | **`title_ko`도 검색 대상**(앱 영화 검색 registered · 관리자 영화 검색) · **박스오피스 재매칭의 제목 일치에도 포함** | 제목을 한글로 고치면 그 제목으로 검색돼야 한다. 박스오피스는 KOFIC 한글 제목과 `movie.title`을 완전 일치로 맞추므로(4-7) TMDB가 영문으로 준 영화도 `title_ko`로 매칭된다. 매칭 규칙 자체(동명이인 판별)는 불변 |

## 11. B-1 감사 로그

### 11-1. 스키마 — V27 (확장: 테이블 추가 1문장)

```sql
-- V27__admin_action_log.sql
CREATE TABLE admin_action_log (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    admin_user_id        BIGINT       NOT NULL,
    admin_email_snapshot VARCHAR(255) NOT NULL,
    action               VARCHAR(40)  NOT NULL,
    target_type          VARCHAR(20)  NULL,
    target_id            BIGINT       NULL,
    detail               JSON         NULL,
    ip                   VARCHAR(45)  NULL,
    created_at           DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_admin_action_log_created_at (created_at),
    KEY idx_admin_action_log_target (target_type, target_id, created_at),
    KEY idx_admin_action_log_admin (admin_user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
```

- FK 없음(BD-4·5). `target_*`가 NULL인 동작은 대상이 없는 일괄 작업(시드 등).
- 엔티티 `AdminActionLog extends BaseCreatedAtEntity`, 정적 팩토리 하나, **수정 메서드 없음**(불변). 삭제 API도 없다.
- 설계 근거·롤백은 `docs/schema/v27-delta.sql`. 보관 기간은 지금은 무기한(관행상 최소 1년).

### 11-2. 동작 목록 — `AdminAction` (Enum, `EnumType.STRING`)

| 값 | 기록 지점 | `target` | `detail` 예 |
|---|---|---|---|
| `USER_LIST_VIEW` | `GET /api/admin/users` | — | `{q, sort, page}` |
| `USER_DETAIL_VIEW` | `GET /api/admin/users/{id}` | `USER`, id | — |
| `MOVIE_RESYNC` | `POST /api/admin/movies/{id}/resync` 성공 | `MOVIE`, id | `{tmdbId}` |
| `MOVIE_RESYNC_BATCH` | `POST /api/admin/movies/resync` | — | `{fromId, limit, updated, skipped, stoppedByRateLimit}` |
| `MOVIE_SEED_BOX_OFFICE` · `MOVIE_SEED_DISCOVER` | 시드 2종 | — | 요청 파라미터 + 결과 수 |
| `GENRE_SEED` · `COUNTRY_SEED` | 참조 시드 | — | 결과 수 |
| `BOX_OFFICE_SYNC` · `BOX_OFFICE_REMATCH` | 박스오피스 관리자 API | — | `{targetDate}` / `{limit}` + 결과 수 |
| `PERSON_NAME_KO_UPDATE` | B-2 | `PERSON`, id | `{before, after}` |

`AdminTargetType`: `USER` · `MOVIE` · `PERSON`. **스케줄러가 실행한 박스오피스 동기화는 관리자 행위가 아니므로 남기지 않는다.**
**성공한 동작만 남긴다** — 실패는 기존 로그(애플리케이션 로그)로 충분하고, "무엇이 바뀌었나"를 답하는 표에 실패가 섞이면 읽기 어렵다.

### 11-3. 기록 경로

- **`@AuditActor AuditActor actor`** 인자 해석기 신설(`global/security/resolver`, 기존 `AuthUserArgumentResolver`와 같은 방식) —
  `AuditActor(Long adminUserId, String ip)`. IP는 `request.getRemoteAddr()`(Nginx `X-Forwarded-For` + `forward-headers-strategy: native`로 실제 클라이언트 IP가 들어온다).
- **`AdminAuditService.record(AuditActor, AdminAction, AdminTargetType, Long targetId, Map<String,Object> detail)`** — 관리자 이메일을 조회해 스냅샷으로 저장.
- **트랜잭션 규칙**
  - B-2처럼 DB 쓰기가 있는 동작: **같은 트랜잭션**(쓰기 서비스 안에서 호출) — 둘 다 커밋되거나 둘 다 롤백.
  - 재동기화·시드처럼 트랜잭션 밖에서 외부 API를 부르는 동작, 조회 동작: **동작이 성공한 뒤 컨트롤러가 `record`를 호출**(컨트롤러는 두 서비스에 위임만 한다 — 5절 "복수 Service 주입 허용").
    로그 저장이 실패해도 동작은 이미 끝났다 — 이 경우는 예외를 그대로 올려 500으로 알린다(조용히 삼키지 않는다).
  - 조회 서비스는 `readOnly`를 유지하고, `record`는 자기 트랜잭션(`@Transactional`)으로 쓴다.

### 11-4. API — `GET /api/admin/audit-logs`

| 파라미터 | 설명 |
|---|---|
| `action` · `targetType` · `targetId` · `adminUserId` (선택) | 정확히 일치, AND |
| `from` · `to` (선택, `yyyy-MM-dd`) | KST 날짜, `to` 포함 |
| `page` · `size` | 0-based. 정렬은 **`createdAt DESC, id DESC` 고정**(정렬 파라미터 없음) |

응답 `PageResponse<AdminAuditLogResponse { id, adminUserId, adminEmail, action, targetType, targetId, detail, ip, createdAt }>`.
조건이 선택이라 Repository는 **JPQL 동적 조건**(`(:action IS NULL OR l.action = :action)` 형식) 한 개로 둔다 — 행 수가 작아 인덱스 선택 문제는 없다.

### 11-5. 관리자 웹

- 사이드바에 **"감사 로그"** — 표: 시각 · 관리자 · 동작(한글 라벨) · 대상(링크: 회원/영화/인물 상세) · IP · 상세(펼치기, JSON 보기). 필터: 동작 · 기간.
- 회원 상세·영화 상세 하단에 **"이 대상의 최근 기록 5건"**(`targetType`+`targetId` 필터 재사용).

### 11-6. 테스트

- 동작별로 **성공 시 1행, 실패 시 0행**(재동기화 409·404 포함) · 스냅샷 이메일 · IP · `detail` 값.
- 조회 2종이 기록되고 감사 로그 조회는 기록되지 않음.
- 필터 조합 · 정렬 고정 · `from/to` 경계(KST 자정).
- 일반 사용자 403(기존 인가 테스트에 경로 추가).

## 12. B-2 인물 한글명 덮어쓰기

### 12-1. 스키마 — V28 (확장: nullable 컬럼 추가 1문장)

```sql
-- V28__person_name_ko.sql
ALTER TABLE person ADD COLUMN name_ko VARCHAR(100) NULL;
```

- 엔티티 `Person.nameKo` + **`changeNameKo(String nameKo)`**(trim, 빈 문자열은 null = 해제, 100자 초과는 `IllegalArgumentException`)
  + **`displayName()`**(`nameKo != null ? nameKo : name`). `jpa-entity-spec.md`에 필드 추가.
- **`updateProfile(name, profilePath)`(재동기화 경로)는 `nameKo`를 건드리지 않는다** — 테스트로 고정한다(BD-7의 핵심).
- 인덱스 없음 — 관리자 검색은 `LIKE`(12-3), 앱은 인물 ID로만 조회한다.

### 12-2. 표시 지점 교체 — 앱 계약 불변

리포에서 인물 이름이 응답으로 나가는 지점은 아래 6곳이다(2026-10-19 확인). **필드 이름(`name`)은 그대로 두고 값만 표시 이름으로 바꾼다.**

| 지점 | 변경 |
|---|---|
| `ActorResponse` · `DirectorResponse` (영화 상세) | `getName()` → `displayName()` |
| `CollectionService` 감독 이름 조합 | 같음 |
| `AdminMovieQueryService` 감독 목록 | 같음(관리자 상세는 12-3에서 원본·한글을 따로 보여 준다) |
| `ReportRepository` 네이티브 4개(선호 배우·감독 TOP, 연간 최다 배우·감독) | `p.name AS name` → **`COALESCE(p.name_ko, p.name) AS name`**, `GROUP BY`에 `p.name_ko` 추가 |

⚠️ 구현 전에 **`getName()`·`p.name`을 리포 전체에서 다시 검색**해 위 표에 없는 지점이 생겼는지 확인한다(검색·추천 등 이후 추가분).
⚠️ **M3-b 임베딩 입력에 인물명을 넣는다면 표시 이름이 아니라 무엇을 넣을지 그때 정한다** — 한글명을 바꿀 때마다 벡터를 다시 만들어야 하는 결합(기획노트 8절).

### 12-3. API

**`GET /api/admin/movies/{movieId}/credits`** — 편집 동선의 출발점(BD-8)

```text
AdminMovieCreditsResponse {
  directors: [ AdminCreditItem ],
  cast:      [ AdminCreditItem ]      // display_order 오름차순, 상위 20명(화면 노출 범위와 같게)
}
AdminCreditItem { personId, tmdbPersonId, name /*TMDB 원본*/, nameKo, profilePath, displayOrder?, roleTier?, characterName? }
```

N+1 금지 — `movie_actor`·`movie_director`를 `person`과 **JOIN FETCH**로 각 1쿼리.

**`GET /api/admin/persons`**

| 파라미터 | 설명 |
|---|---|
| `q` (선택) | `name` 또는 `name_ko` 부분 일치(1단계의 `LikePattern` 이스케이프 재사용) |
| `missingKorean` (선택, boolean) | `true`면 **`name_ko IS NULL AND name NOT REGEXP '[가-힣]'`** — 한글 이름이 하나도 없는 인물만 |
| `page` · `size` | 0-based, 정렬은 `id ASC` 고정 |

응답 `PageResponse<AdminPersonSummaryResponse { personId, tmdbPersonId, name, nameKo, profilePath, creditCount }>` —
`creditCount`(출연+연출 수)는 **페이지의 ID로 묶어 1쿼리씩**(1단계 소셜 계정과 같은 방식). ⚠️ `person`이 약 23만 행이라 `LIKE '%q%'` 풀스캔이
수백 ms일 수 있다 — 운영 규모에서 실측해 1초를 넘으면 `q`를 **접두 일치**(`q%`)로 바꾼다.

**`PATCH /api/admin/persons/{personId}/name-ko`** — 본문 `{ "nameKo": "톰 행크스" }`(`null`·빈 문자열 = 해제)

- 200 `AdminPersonSummaryResponse` · 없음 `PERSON_NOT_FOUND` 404(없으면 `ErrorCode`에 추가) · 100자 초과 400.
- 서비스(`AdminPersonCommandService`) 한 트랜잭션에서 `changeNameKo` + 감사 로그 `PERSON_NAME_KO_UPDATE {before, after}`.
- 값이 같으면 저장·기록하지 않고 200(멱등).

### 12-4. 관리자 웹

- **영화 상세에 "출연진·감독" 탭** — 행마다 사진 · TMDB 이름 · 한글명 입력칸 · 저장. 저장 후 그 행만 갱신(목록 다시 받기 불필요).
- 사이드바 **"인물"** — 검색 · "한글명 없는 인물만" 토글 · 같은 인라인 편집. TMDB 인물 페이지 링크(`https://www.themoviedb.org/person/{tmdbPersonId}`)를 두어 표기 확인에 쓴다.

### 12-5. 테스트

- `changeNameKo` 정규화(trim·빈 문자열·길이) · **`updateProfile`이 `nameKo`를 보존** · `displayName` 폴백.
- 영화 상세·리포트 TOP 응답의 `name`이 한글명으로 바뀜(리포트는 기존 `ReportServiceTest` 데이터에 `name_ko` 한 명 추가).
- `missingKorean` 필터(한글 원본·한글명 보유·둘 다 없음 3종) · PATCH 멱등 · 감사 로그 1행.

## 12-B. B-2.5 영화 제목 한글화 덮어쓰기 (2026-10-20 추가)

> TMDB `ko-KR` 응답에도 한글 제목이 없는 영화가 있어 `title`에 영문(원제 폴백, `MovieSyncPersister.resolveTitle`)이 저장된다. 관리자가 한글 제목을 직접 넣는다.
> 구조는 **B-2 인물 한글명과 같다** — 컬럼 하나 + 표시 폴백 + 재동기화 비덮어쓰기. 다른 점은 **표시 지점이 훨씬 많다**는 것(제목은 앱 거의 모든 화면에 나온다).

### 12-B-1. 스키마 — V29 (확장: nullable 컬럼 추가 1문장)

```sql
-- V29__movie_title_ko.sql
ALTER TABLE movie ADD COLUMN title_ko VARCHAR(255) NULL;
```

- 분류: **확장 1문장**, 축소 없음. `movie` 약 1.3만 행이라 INSTANT가 아니어도 무관하다. 설계 근거·롤백은 `docs/schema/v29-delta.sql`.
- 엔티티: `Movie.titleKo` + **`changeTitleKo(String)`**(trim, 빈 문자열은 null=해제, 255자 초과는 `IllegalArgumentException`) + **`displayTitle()`**(`titleKo != null ? titleKo : title`)
  + 정렬 전용 **`sortTitle`**(`@Formula("COALESCE(title_ko, title)")`, 읽기 전용 — 정렬 말고는 쓰지 않는다. 표시는 `displayTitle()`).
- **`updateMetadata`(재동기화 경로)는 `titleKo`를 건드리지 않는다** — 테스트로 고정(BD-10의 핵심). 인덱스 없음.

### 12-B-2. 표시 지점 교체 — 앱 계약 불변(필드 `title`은 그대로, 값만 표시 제목)

리포에서 영화 제목이 응답으로 나가는 지점은 아래와 같다(2026-10-20 확인). **구현 전에 `getTitle()`·`m.title`을 리포 전체에서 다시 검색해** 표 밖의 지점이 없는지 확인한다.

| 지점 | 변경 |
|---|---|
| 응답 DTO 6곳 — `MovieDetailResponse` · `MovieListItemResponse` · `MovieSummaryResponse` · `UserMovieListItemResponse`(내 영화) · `WishListItemResponse`(찜) · `CollectionMovieListItemResponse` | `movie.getTitle()` → `movie.displayTitle()` |
| `ReportRepository` 네이티브 8개(`m.title AS title`) | `COALESCE(m.title_ko, m.title) AS title`, `GROUP BY`에 쓰인 2곳(`m.title`)에는 `m.title_ko`를 추가 |
| 제목 정렬 4개 — `RecordSort.TITLE`·`TITLE_DESC` · `WishSort.TITLE`·`TITLE_DESC` | `"movie.title"` → **`"movie.sortTitle"`**(BD-11). 보조키(`id DESC`)·NULL 규칙은 그대로 |
| 앱 영화 검색 `registered` — `MovieRepository.findByTitleContainingOrOriginalTitleContaining` | **`title_ko`도 부분 일치**(BD-13). TMDB `suggestions`는 외부 결과라 불변 |
| 박스오피스 재매칭 — `MovieRepository.findByTitle` | **`title` 또는 `title_ko` 완전 일치**(BD-13). 호출부 `BoxOfficeSyncService`의 동명이인 판별은 불변 |
| 관리자 영화 목록·상세(`AdminMovieSummaryResponse` · `AdminMovieDetailResponse`) | **`title`(TMDB 원본)과 `titleKo`를 따로** 싣는다(필드 추가 = 확장). 관리자 검색 `q`는 `title`·`originalTitle`·`titleKo` |

⚠️ **제목 정렬의 한글 순서** — `title_ko`가 NULL인 영화는 `title`(영문일 수 있음)로 정렬돼 한글 제목 사이에 섞인다. 의도한 동작이다(화면에 보이는 제목 기준 — BD-11).
⚠️ M3-b 임베딩 입력에 제목을 넣는다면 **표시 제목이 아니라 무엇을 넣을지 그때 정한다**(인물과 같은 결합 — 한글 제목을 바꿀 때마다 벡터를 다시 만들어야 한다).

### 12-B-3. API

**`PATCH /api/admin/movies/{movieId}/title-ko`** — 본문 `{ "titleKo": "아바타: 물의 길" }`(`null`·빈 문자열 = 해제)

- 200 **`AdminMovieDetailResponse`**(갱신된 상세 — 화면이 그대로 상태를 바꾼다) · 없음 `MOVIE_NOT_FOUND` 404 · 255자 초과 400.
- 서비스(`AdminMovieCommandService`)가 **한 트랜잭션**에서 `changeTitleKo` + 감사 로그 **`MOVIE_TITLE_KO_UPDATE`** `{before, after}`(`target`=`MOVIE`, id; `before`/`after`는 null까지 명시 — 11-3·B-2와 같은 규칙). `AdminAction`에 값 추가(`EnumType.STRING`, 마이그레이션 불필요).
- **값이 같으면(공백만 다른 경우 포함) 저장·기록 없이 200**(멱등).

### 12-B-4. 관리자 웹

- 영화 상세 **"기본 정보" 탭에 "한글 제목" 입력칸 + 저장**(TMDB 제목은 읽기 전용으로 따로 보인다). 저장 후 응답으로 상세를 바로 갱신하고 감사 로그 "최근 기록"을 다시 받는다.
- 영화 목록은 **표시 제목 + 원제**를 보여 준다(`titleKo ?? title`). 한글 제목이 있는 영화에는 표시를 붙이지 않는다(일관성 우선).
- 감사 로그 동작 라벨 `MOVIE_TITLE_KO_UPDATE` → "영화 한글 제목 수정".

### 12-B-5. 테스트

- `changeTitleKo` 정규화(trim·빈 문자열·255자) · **`updateMetadata`가 `titleKo`를 보존**(실제 재동기화 경로) · `displayTitle` 폴백.
- 표시 지점: 영화 상세·목록·요약·내 영화·찜·컬렉션 목록·리포트(5-8 쿼리 중 2개 이상, `ReportServiceTest` 데이터에 `title_ko` 한 편 추가)의 `title`이 한글 제목으로 바뀌고 없으면 원본.
- **정렬**: `RecordSort.TITLE`·`WishSort.TITLE`(오름·내림)이 표시 제목 기준이고 `title_ko` NULL 영화가 `title`로 섞임(`LibrarySortTest` 확장).
- 검색: `title_ko`로 앱 `registered`와 관리자 `q`에 걸림. 박스오피스 `findByTitle`이 `title_ko`에도 일치.
- PATCH 멱등 · 해제 · 404 · 256자 400 · 감사 로그 1행(`before`/`after`) · 인가(401/403).

## 13. B-3 대시보드 (읽기 전용, 스키마 변경 없음)

### 13-1. API — `GET /api/admin/dashboard?days=30`

`days`는 7~90(기본 30, 범위 밖 400). **날짜는 KST**(DB 접속이 `serverTimezone=Asia/Seoul`, 기존 리포트와 같은 기준).

```text
AdminDashboardResponse {
  totals {                         // 지금 시점 누적
    users, movies, watchRecords, reviews,
    moviesNeverSynced,             // synced_at IS NULL — 동기화 이력 없는 영화
    personsMissingKorean           // B-2를 했을 때만. 아니면 필드 없음
  },
  recent {                         // 최근 7일 (days와 무관하게 고정)
    newUsers, activeUsers /*BD-9*/, recordingUsers /*기록을 남긴 사용자*/, newWatchRecords
  },
  daily: [ { date, newUsers, activeUsers, newWatchRecords, newReviews, newMovies } ],   // days일, 빈 날은 0으로 채운다
  topMovies: [ { movieId, title, posterPath, recordCount } ]                            // 최근 days일 기록 수 TOP 10
}
```

- 집계는 **`GROUP BY DATE(created_at)` 쿼리를 지표별로 1개씩**(약 10쿼리) — 빈 날 채우기는 서비스에서. 테이블이 작아 캐시는 두지 않는다.
- `newMovies`는 `movie.created_at` 기준 — 사용자 검색으로 새로 등록된(온디맨드 동기화) 영화 수다.
- 일별 활성 사용자는 `COUNT(DISTINCT user_id)` 를 날짜별로(리프레시 토큰 `created_at`).
- ⚠️ 10/12 운영 1회 작업으로 기록 1,016건이 한꺼번에 들어가 그날 `newWatchRecords`가 튄다 — 오류가 아니다(화면에 굳이 보정하지 않는다).

### 13-2. 관리자 웹

- 로그인 후 첫 화면을 **대시보드**로 바꾼다(사이드바 맨 위).
- 상단 KPI 카드(누적 4 + 최근 7일 3) → 일별 추이 선 그래프 2개(사용자: 신규·활성 / 콘텐츠: 기록·리뷰·영화 등록) → TOP 10 영화 표(행 클릭 → 영화 상세) → 운영 알림 카드("동기화 이력 없는 영화 N편", "한글명 없는 인물 N명" → 각 목록으로 이동).
- 차트는 Ant Design 계열 차트(예: `@ant-design/charts`) 또는 Recharts — 설치 시점 안정판. **발표 화면이므로 실제 운영 데이터로 확인**하고 데모 영상에는 개인정보(이메일)가 보이는 화면을 넣지 않는다.

### 13-3. 테스트

- 빈 날 0 채우기 · `days` 경계(7·90·범위 밖) · KST 자정 경계(23:59/00:00 기록) · 활성 사용자 중복 제거(같은 날 재발급 여러 번 = 1명) · TOP 10 동률 정렬(`movie_id`).

## 14. 2단계 실행 순서

| 단계 | 리포 | 내용 | 완료 판정 |
|---|---|---|---|
| **B-1** 🟡 backend ✅ | backend → admin | V27 · 기록 지점 11-2 전부 · 조회 API · 감사 로그 화면 | 운영에서 재동기화 1회 → 감사 로그 화면에 1행 |
| **B-2** 🟡 backend ✅ | backend → admin | V28 · 표시 지점 6곳 · API 3개 · 출연진 탭·인물 화면 | 운영에서 인물 1명 한글명 저장 → **앱 영화 상세에 한글로 표시**(앱 재배포 없이) → 그 영화 재동기화 후에도 유지 |
| **B-2.5** | backend → admin | V29 · 표시 지점 12-B-2 전부 · PATCH API · 영화 상세 한글 제목 편집 | 운영에서 영문 제목 영화 1편의 한글 제목 저장 → **앱 영화 상세·내 영화·검색에 한글로 표시** → 그 영화 재동기화 후에도 유지 |
| **B-3** | backend → admin | 대시보드 API · 첫 화면 | 운영 데이터로 수치 표본 3개를 SQL과 대조 |

**3단계로 넘기는 것**: 회원 제재(회원 탈퇴 Part C 이후), 공지사항, 신고·콘텐츠 검수 — 9절 표.

---

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-10-19 | **신설.** 1단계(회원·영화 관리) 확정 — AD-1~AD-10. 근거 요약: 웹 백오피스가 업계 표준(AD-1), API가 `api.` 서브도메인이라 `admin.` 서브도메인 + 같은 출처 프록시로 CORS를 건드리지 않음(AD-4), 개인 카카오 계정 대신 운영 이메일 계정(AD-5 — 웹 카카오 로그인 흐름 회피), 앱 계약을 건드리지 않으려 `UserResponse.role` 대신 `/api/admin/me`(AD-6), 제재를 빼 탈퇴 선행 제거(AD-8), 단건 재동기화는 일괄 작업과 `running` 공유(AD-9 — 매핑 행 교체 경합), `synced_at`은 백필 없이 NULL(AD-10 — `created_at` 백필은 거짓 시각). 일정은 M3-b보다 먼저(사용자 결정) |
| 2026-10-19 | **A-1 구현 완료.** API 6개 + 테스트(`AdminApiAuthorizationTest`·`AdminUserQueryServiceTest`·`AdminMovieQueryServiceTest`·`MovieResyncOneTest`). 계약 변경 없음 — 구현 기록은 `controller-layer-spec.md` 5-9. 스펙에 없던 결정 두 가지: `q`의 `%`·`_`는 `escape '!'`로 리터럴 처리(`LikePattern`), 단건 resync의 응답 재조회는 Controller가 `resyncOne` 뒤에 `getMovie`를 호출해 한다 |
| 2026-10-19 | **A-2 구현 완료.** V26 `movie.synced_at`(확장 1문장) + `Movie.markSynced` + `MovieSyncPersister.persist()`에서 `Clock`으로 기록 + 관리자 영화 응답 `syncedAt` + `AdminMovieSort.SYNCED_AT_ASC`(NULL 맨 앞). 백필 없음 — 설계 근거·롤백은 `docs/schema/v26-delta.sql`. 같은 `feature/admin-api` 브랜치에서 A-1 뒤에 커밋(사용자 결정) |
| 2026-10-19 | **A-3 완료(운영).** 운영 전용 계정 `cadmin295@gmail.com`(id 8) 가입 → `UPDATE`로 `ADMIN` 승격 → 재로그인 → 운영 `GET /api/admin/me` 200. 개인 카카오 계정은 원래 `USER`라 강등 불필요. 기존 이메일 관리자 `hiu8525+admin`(id 1)은 시드·resync 런북이 쓰므로 당분간 유지 |
| 2026-10-19 | **A-4 완료(`cinemory-admin` 로컬).** 6-5 체크리스트를 로컬 백엔드 대상 브라우저 자동화로 검증(24개 중 23 통과, 1은 토스트 대기 시간 문제 — 5번 "일괄 중 409"는 화면 미확인, 서비스 테스트로 대체). 검증 중 두 가지를 고쳤다: ① **Vite 프록시가 `Origin`을 그대로 전달해 로그인 POST가 403(Invalid CORS request)** — 6-6 주의와 같은 원인이며 dev 프록시에서 `Origin`을 뗀다 ② `@refinedev/react-router`가 `react-router` 7.x를 요구해 8.x와 두 벌이 생김 → 7.x 고정. `openapi-typescript`가 TS 5.x를 요구해 `typescript`를 5.9로 고정 |
| 2026-10-19 | **A-5 착수 — Nginx 서버 블록 반영.** `deploy/nginx/cinemory.conf`에 `admin.example.net` 서버 블록 2개, `cinemory-admin-headers.conf`(보안 헤더 snippet — 서버 블록과 두 location에 include) 신설. 스펙과 다른 점: ① CSP `img-src`에 `https://*.cloudfront.net` 추가(프로필 사진, 관리자 회원 상세) ② 임시 80 블록 없이 `api.` 80 블록이 기본 서버로 ACME를 받는다. 서버 절차는 `server-setup-runbook.md` 10-⑤ |
| 2026-10-19 | **A-6 구현 — 관리자 웹 배포 자동화.** `cinemory-admin`의 `deploy.yml`(OIDC·S3 `admin-releases/<sha>/`·SSM)과 `deploy/admin-deploy.sh`(releases/<sha> 배치 → `mv -T`로 `current` 원자적 교체 → 응답 확인 실패 시 이전 릴리스 복구 → 직전 2개 보관). 실행 전 WSL 리눅스에서 시뮬레이션해 **정리 순서 버그**를 잡았다 — tar가 폴더 mtime을 빌드 시각으로 복원해 옛 빌드 재배포 시 방금 올린 릴리스가 정리 대상이 될 수 있어 배치 직후 `touch`한다. 준비 절차는 `server-setup-runbook.md` 13-⑦, 운영은 `operations-runbook.md` 2-1 |
| 2026-10-19 | **A-6 완료(운영).** `cinemory-admin` main push → Actions `deploy` 성공(빌드 → S3 → SSM → 외부 확인, 33초). 첫 시도는 OIDC 거부로 실패했다 — 새 리포의 토큰 `sub`가 ID가 붙은 형식이라 신뢰 정책(옛 형식)과 어긋남. 정확한 값을 신뢰 정책에 추가해 해결(와일드카드 아님). 진단용 임시 워크플로는 삭제 |
| 2026-10-19 | **2단계 확정(10~14절, BD-1~BD-9).** 범위는 감사 로그·인물 한글명·대시보드 — 셋 다 회원 탈퇴와 무관해 지금 할 수 있고, 제재는 탈퇴 뒤 3단계(BD-1). 감사 로그는 쓰기 전부 + 개인정보 조회(접속기록 관행, BD-2), FK 없는 관리자 스냅샷·다형 대상(BD-4·5 — 로그는 대상·관리자가 지워져도 남아야 한다). 인물 한글명은 기획노트 8절의 `name_ko` + 표시 폴백을 관리자 수동 입력 경로로 먼저 구현하고, 재동기화가 덮어쓰지 않는 것을 테스트로 고정(BD-7). 활성 사용자는 접속 기록이 없어 리프레시 토큰 발급으로 근사(BD-9) |
| 2026-10-19 | **B-1 백엔드 구현.** V27(`CREATE TABLE` 1문장 — **확장**, 축소 없음) · `AdminActionLog` · `AdminAuditService`/`AdminAuditQueryService` · `GET /api/admin/audit-logs` · 기록 11곳. 스펙과 다른 점 두 가지: 어노테이션 이름 `@CurrentAdmin`(레코드 `AuditActor`와 같은 이름 충돌), 엔티티는 정적 팩토리 대신 `@Builder`(필드 7개). `AdminAuditLogTest` 13개. 감사 로그 화면(11-5)은 백엔드 배포 후 admin 리포에서 |
| 2026-10-20 | **B-2 백엔드 구현.** V28(`ALTER TABLE person ADD COLUMN name_ko … NULL` 1문장 — **확장**, 축소 없음 · MySQL 8 INSTANT라 23만 행에도 잠금 없음) · `Person.nameKo`/`changeNameKo`/`displayName` · 표시 지점 6곳 · `GET /api/admin/persons` · `PATCH /api/admin/persons/{id}/name-ko` · `GET /api/admin/movies/{id}/credits` · `PERSON_NOT_FOUND`. 스펙에 없던 결정: ① 감사 로그 `detail`은 `{before, after}`를 **null까지 명시**(처음 입력과 해제를 구별) ② credits의 "상위 20명"은 앱 영화 상세와 같은 상수(`display_order ≤ 20` = 21명)를 공유해 화면 노출 범위와 일치시킴. 테스트 `AdminPersonApiTest` 10 · `PersonNameKoSyncTest` 4 · `PersonNameKoTest` 6 + `ReportServiceTest` 보강. 화면(12-4)은 백엔드 배포 후 admin 리포에서 |
| 2026-10-20 | **B-2.5 추가(12-B절, BD-10~BD-13) — 영화 제목 한글화 덮어쓰기.** B-2 완료 확인 뒤 사용자가 요청(TMDB가 한글 제목을 주지 않는 영화의 `title`이 영문). 결정(사용자): ① `movie.title_ko` 덮어쓰기 컬럼(V29, 확장 — `title` 직접 수정은 재동기화에 덮여 기각) ② 제목순 정렬은 표시 제목 기준(`@Formula` `sortTitle`) ③ B-3 전에 진행. 표시 지점은 응답 DTO 6 · 리포트 네이티브 8 · 제목 정렬 4 · 앱 검색 · 박스오피스 재매칭 · 관리자 목록·상세. 스펙의 "영화 정보 수정"은 1단계 범위 밖(2절)이었고 이 절이 그 한 가지(제목)만 연다 |
