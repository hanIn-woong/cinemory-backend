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
