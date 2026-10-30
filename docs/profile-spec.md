# CineMory — 사용자 프로필 스펙 (백엔드)

> **이 문서는 새 세션이 컨텍스트 없이 착수할 수 있도록 쓴 인수인계 문서다.** 2026-10-30 프로필 설계 세션에서 확정한 결정과 근거,
> API 계약, 스키마, 단계별 실행 순서를 담는다. 앱 화면은 `cinemory-app/docs/profile-screen-spec.md`.
> 프로필 **사진 업로드**는 이미 설계된 `account-integrity-spec.md` **Part B + B-5(2026-10-30 재점검)**를 따른다 — 이 문서는 순서와 연결만 다룬다.
>
> **계약은 구현 후 `controller-layer-spec.md`로 옮긴다.** 이 문서는 결정 기록과 근거로 남는다.

| 단계 | 내용 | 선행 | 비고 |
|---|---|---|---|
| **PB** | **프로필 사진 업로드** — Part B(B-1~B-5) 전체 + 앱 네이티브 모듈 2종 + **재빌드** | AWS 콘솔 작업(B-2, 사용자) | **가장 먼저** — 재빌드가 최대 일정 변수(B-5-5) |
| **P-1** | 닉네임 규칙 + 소셜 가입 시 제공자 정보 미수집 + **기존 제공자 사진 일괄 제거** + `newUser` (PR-6~PR-11) | PB(제공자 사진 `http` 분기를 P-1이 제거) | |
| **P-2** | 프로필 필드 — 자기소개 · 인생 영화 · 커버(영화 배경 **또는 업로드**) + 통합 저장 API + **PRIVATE 프로필 제한** (PR-1~PR-5) | P-1(닉네임 검증 재사용) · PB(업로드 인프라 재사용) | |

각 단계는 끝날 때마다 `main`에 머지·배포 가능한 상태로 닫는다. 앱은 PB → P-1 → P-2 순서로 화면이 채워진다(앱 스펙).

**범위 밖(보류 — 2026-10-30)**: 영화 포스터를 아바타로(불필요 — 사용자 결정), 취향 요약 배지·활동 수 요약(보류).

**착수 전에 읽을 것**

| 문서 | 왜 |
|---|---|
| `account-integrity-spec.md` **Part B · B-5** | 사진 업로드 설계 전체. B-5가 B-1~B-4보다 우선 |
| `account-integrity-spec.md` **D-2 · D-5** | 소셜 가입 흐름(`signUpOAuth`의 D-2-A 분기) — PR-9가 이 흐름의 입력을 줄인다 |
| `social-feed-spec.md` **10절(F-4)** | 사용자 검색(2~20자)·새 가입자 기본 공개 범위 FRIENDS(V36) — 닉네임 규칙을 같은 길이로 맞춘다. 검색 결과의 PRIVATE 제외는 PR-5와 같은 방향 |
| `controller-layer-spec.md` **5-1** | 현행 `PATCH /me/nickname`·`PATCH /me/privacy`·`GET /me`·`GET /{userId}/profile` |

---

## 1. 확정 결정 (2026-10-30, 같은 날 개정 — 변경 이력)

| # | 항목 | 결정 | 이유 |
|---|---|---|---|
| PR-1 | **프로필 수정 = 한 화면 · 한 번 저장** | `PUT /api/users/me/profile` — 닉네임·자기소개·영화 커버·인생 영화·공개 범위를 **전체 치환**으로 한 번에. **이미지(사진·업로드 커버)는 별도 업로드 흐름**이라 즉시 반영 | 수정 기능이 설정 화면에 흩어져 있었다. 전체 치환은 기존 `CollectionUpdateRequest`와 같은 관례(부분 수정의 "null = 지움/안 바꿈" 모호성이 없다) |
| PR-2 | 자기소개 | `user.bio varchar(150)` nullable — 앞뒤 공백 제거, 빈 문자열은 `null`, **최대 150자(코드포인트) · 줄바꿈 2개까지** | 헤더에 2~3줄 |
| PR-3 | **인생 영화** | **최대 3편, 순서 있음, 언제든 변경**. 등록된 영화면 무엇이든(시청 기록 여부 무관) | Letterboxd "Favorite Films"와 같은 정체성 표현. 3편은 헤더 한 줄에 스크롤 없이 들어간다 |
| PR-4 | **커버 = 영화 배경 또는 직접 업로드** | 둘 중 하나만(또는 없음 → 앱 기본 그라데이션). ① **영화**: `cover_movie_id` — 현재 인생 영화 중 하나 + `backdrop_path` 있음 ② **업로드**: `cover_image`(S3 키 `cover/{userId}/{uuid}.jpg`, Part B 인프라 재사용). DB CHECK로 **동시에 둘 다 불가** | 배경 이미지가 없는 영화를 대비해 업로드를 추가(사용자 결정). 영화 후보를 인생 영화로 묶는 이유는 그대로 — 앱 API 어디에도 배경 이미지가 노출되지 않아 임의 영화를 고르려면 검색 응답까지 바뀐다. 업로드가 그 빈틈을 메운다 |
| PR-5 | **프로필 정보의 공개** | 본인·PUBLIC·FRIENDS = **헤더 전부**(사진·닉네임·자기소개·커버·인생 영화·팔로워/팔로잉 수). **PRIVATE(본인 아닐 때) = 사진·닉네임 + "비공개 계정"만** — 나머지는 서버가 `null`, `restricted: true`. 팔로우 버튼 숨김(이미 팔로우 중이면 언팔로우만) | 사용자 결정. **서버에서 막는다** — 앱에서만 숨기면 API 직접 조회로 보인다. 닉네임·사진은 팔로우 목록 등에 이미 나오는 식별 정보라 남긴다(숨기면 "누구인지 모르는 페이지"가 된다). FRIENDS 본문(기록 등)은 지금처럼 맞팔로우일 때만 — 헤더 규칙과 별개 |
| PR-6 | **닉네임 규칙** | 앞뒤 공백 제거 후 **2~20자(코드포인트)**, 공백만 불가, 제어 문자 불가, **예약어 포함 불가**(`관리자`·`운영자`·`cinemory`·`씨네모리`·`admin` — 대소문자 무시). 가입·변경·프로필 저장에 같은 규칙. **기존 20자 초과는 그대로**(바꿀 때만 새 규칙) | 가입 50자 / 변경 30자 **불일치** 해소, 검색(2~20자)과 일치. 중복을 허용하므로(PR-7) **운영자 사칭 방지는 예약어가 유일한 장치**다 |
| PR-7 | **닉네임 중복 허용** | **고유 제약·태그 없음.** 사용자는 사진·기록·프로필로 구분하고, **관리자는 `id`로 구분**(관리자 웹은 이미 id·이메일 기준) | 사용자 결정 — 왓챠피디아도 중복을 허용하고 큰 불편이 없다. 고유화의 비용(409 처리·기존 중복 정리·가입 실패)과 숫자 태그의 관리 부담(어디서나 표시·사칭 쉬움 — Discord가 2023년에 태그를 폐지)을 모두 피한다. **한계**: 닉네임+사진을 베낀 사칭은 막지 못한다 — 인증 배지·신고는 출시 단계 |
| PR-8 | (삭제) | ~~기존 중복 정리 마이그레이션~~ — PR-7 개정으로 불필요 | |
| PR-9 | **소셜 가입 시 제공자 닉네임·사진을 가져오지 않는다** | 카카오·구글 검증기가 `nickname`/`name`·`picture`를 읽지 않는다. 새 소셜 가입자는 **자동 닉네임 + 사진 없음** | ① **개인정보** — 구글 `name`은 대부분 실명, 카카오도 실명·본인 얼굴이 흔하다. 새 기본값 FRIENDS(V36)와 닉네임 검색으로 **검색·노출되는 구조**가 되는데, 사용자는 로그인했을 뿐 소셜 기능 공개에 동의한 적이 없다 ② 제공자 CDN URL이 바뀌어 깨지는 문제·`toHttps` 임시 처리 불필요. 카카오 콘솔 동의 항목(닉네임·프로필 사진)도 끈다(운영) |
| PR-10 | **자동 닉네임 + 가입 직후 설정 화면** | 서버가 `관객` + 6자리 숫자를 만든다(중복 허용이라 검사 없음). 소셜 로그인 응답에 **`newUser`** — 앱이 가입 직후 한 번 닉네임 설정 화면을 띄운다(자동값이 채워져 있어 확인만 눌러도 된다) | 자동 닉네임만 두면 "관객482913"이 검색·피드에 그대로 뜬다. 화면을 건너뛰어도(앱 종료 등) 계정은 이미 쓸 수 있다 |
| PR-11 | **기존 사용자가 가져온 사진 — 일괄 제거** | 마이그레이션이 `profile_image LIKE 'http%'`(제공자 URL)를 `NULL`로. **닉네임은 그대로** | 사용자 결정. 운영 사용자가 적고 대부분 본인·데모 계정이라 부담이 작다. 실명 얼굴 사진 노출을 기존 사용자까지 정리하고, 사진 URL 해석의 `http` 분기(Part B P-2·P-3)를 **코드에서 지울 수 있다**. 닉네임은 사진만큼 민감하지 않고 사용자가 바꿀 수 있어 유지 |
| PR-12 | 기존 개별 API | `PATCH /me/nickname`·`PATCH /me/privacy`는 **유지하되 앱은 PR-1로 옮긴다** — 제거는 다음 릴리스(확장/축소). `PATCH /me/nickname`도 PR-6 규칙 적용 | 배포된 옛 앱이 계속 동작해야 한다. 규칙을 두 경로에서 다르게 두면 다시 30/50자 같은 불일치가 생긴다 |

## 2. 스키마

> 번호는 착수 시 다음 번호(F-4가 V36). 아래는 V37~V39 가정. 각 파일은 `docs/schema/v{n}-delta.sql`(근거·사전 점검·롤백) 한 쌍.

### V37 — 기존 제공자 사진 제거 (P-1, PR-11)

```sql
UPDATE `user` SET profile_image = NULL WHERE profile_image LIKE 'http%';
```
- 사전 점검(delta): `SELECT COUNT(*) FROM user WHERE profile_image LIKE 'http%'` — 운영 배포 전 대상 건수 기록.
- ⚠️ **순서 조건** — PB(업로드)가 먼저 머지돼 있으면 우리 S3 키는 `profile/…`라 이 조건에 걸리지 않는다. 지우는 것은 제공자 URL뿐이다.
- 되돌릴 수 없는 데이터 변경 — delta에 "롤백 = 없음(의도된 삭제)"을 명시. 이 마이그레이션과 **같은 릴리스에서** 검증기가 사진을 더는 가져오지 않아야(PR-9) 다시 쌓이지 않는다.
- P-1은 `ProfileImageUrlResolver`의 `http` 분기를 지운다(이후 `profile_image`는 `NULL` 또는 우리 키뿐). 지우기 전에 V37 적용 후 `http%`가 0건인지 테스트로 고정.

### V38 — 프로필 필드 (P-2)

```sql
ALTER TABLE `user`
  ADD COLUMN bio VARCHAR(150) NULL,
  ADD COLUMN cover_movie_id BIGINT NULL,
  ADD COLUMN cover_image VARCHAR(500) NULL,
  ADD CONSTRAINT fk_user_cover_movie FOREIGN KEY (cover_movie_id) REFERENCES movie (id) ON DELETE SET NULL,
  ADD CONSTRAINT chk_user_cover_single CHECK (cover_movie_id IS NULL OR cover_image IS NULL);
```
- 커버는 장식이라 영화 삭제를 막지 않는다(`SET NULL`). 사용자 콘텐츠의 `→ movie RESTRICT`와 다른 이유.
- ⚠️ MySQL은 **`ON DELETE SET NULL`이 걸린 컬럼을 CHECK에 쓸 수 없다**(FK 참조 동작 컬럼 제약). 적용이 거부되면 CHECK를 빼고 **엔티티 메서드가 둘 중 하나만 채우도록 강제**(`User.changeCoverToMovie` / `changeCoverToImage` / `clearCover`) + 테스트로 고정한다 — 구현 시 확인해 delta에 결과를 기록.

### V39 — 인생 영화 (P-2)

```sql
CREATE TABLE user_favorite_movie (
  id         BIGINT   NOT NULL AUTO_INCREMENT,
  user_id    BIGINT   NOT NULL,
  movie_id   BIGINT   NOT NULL,
  position   TINYINT  NOT NULL,              -- 1~3
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_favorite_movie (user_id, movie_id),
  UNIQUE KEY uk_user_favorite_position (user_id, position),
  KEY fk_user_favorite_movie_movie (movie_id),
  CONSTRAINT fk_user_favorite_movie_user  FOREIGN KEY (user_id)  REFERENCES `user` (id) ON DELETE CASCADE,
  CONSTRAINT fk_user_favorite_movie_movie FOREIGN KEY (movie_id) REFERENCES movie (id) ON DELETE RESTRICT,
  CONSTRAINT chk_user_favorite_position CHECK (position BETWEEN 1 AND 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```
- 다대다를 대리키 매핑 엔티티로 승격(프로젝트 규칙) — `UserFavoriteMovie` `@ManyToOne(LAZY)` 둘, cascade 없음.
- `→ movie RESTRICT`(사용자 콘텐츠 보호, A-1 방향) · `→ user CASCADE`(탈퇴 시 자동 삭제).

## 3. API

### 3-1. `PUT /api/users/me/profile` (인증 필수) — PR-1

```json
{ "nickname": "영화보는인웅", "bio": "SF와 다큐를 좋아해요", "privacySetting": "FRIENDS",
  "favoriteMovieIds": [12, 345, 678], "coverMovieId": 345 }
```

| 순서 | 검증 · 동작 | 실패 |
|---|---|---|
| 1 | 닉네임이 **현재 값과 다를 때만** PR-6 규칙(`NicknamePolicy` — 가입·변경·이 API 공용). 같으면 검사하지 않는다 — 20자 초과 기존 닉네임 사용자가 다른 필드를 저장할 수 있어야 한다(PT-5) | 400 `INVALID_INPUT_VALUE`(`errors[]`에 필드) |
| 2 | `bio` PR-2 정규화·검증 | 400 |
| 3 | `favoriteMovieIds` — 0~3개, 중복 없음 → `movieRepository.findAllById` 개수 일치 | 400 / 404 `MOVIE_NOT_FOUND` |
| 4 | `coverMovieId` — `null`이거나 **3의 목록에 있고** `backdropPath != null` | 400 `INVALID_COVER_MOVIE` |
| 5 | `user.updateProfile(nickname, bio, privacySetting)` | |
| 6 | 커버: `coverMovieId`가 있으면 `changeCoverToMovie` — **업로드 커버가 있었으면 해제 + 이전 키 삭제 이벤트**. `null`이면 **영화 커버만 해제**(업로드 커버는 이 API로 건드리지 않는다 — 3-2의 DELETE로) | |
| 7 | 인생 영화 교체 — **벌크 JPQL 삭제 → 새 행 `saveAll`**(position 1..n) | |
| 8 | 응답 `UserResponse`(3-4) | |

- ⚠️ 7은 반드시 **벌크 삭제가 먼저 즉시 실행**되게 한다 — 엔티티 `delete()` + `save()`로 하면 Hibernate가 **INSERT를 DELETE보다 먼저 flush**해 `uk_user_favorite_position`·`uk_user_favorite_movie`를 위반한다(잔여 #19와 같은 flush 순서 함정). `clearAutomatically = true` 금지(4-6 사고). 이 트랜잭션에서 삭제 전에 인생 영화 엔티티를 로드하지 않는다.
- ⚠️ `ProfileUpdateRequest.nickname`에는 `@NotBlank`만 — 길이·문자 규칙을 Bean Validation에 넣으면 "바뀌었을 때만"을 표현할 수 없다. 위반은 `NicknamePolicy`가 `INVALID_INPUT_VALUE` + `errors[]`(필드 `nickname`)로 던져 `@Valid` 위반과 **같은 응답 모양**을 유지(필요하면 `BusinessException`을 최소 확장).
- 공개 범위 변경은 기존 `PATCH /me/privacy`와 같은 엔티티 메서드를 쓴다.
- 앱은 업로드 커버가 있을 때 `coverMovieId: null`을 보내도 업로드 커버가 지워지지 않는다(6) — 업로드 커버 제거는 명시적 DELETE만.

### 3-2. 커버 업로드 (PR-4 ②) — Part B와 같은 3종

| 메서드 · 경로 | 동작 |
|---|---|
| `POST /api/users/me/cover-image/upload-url` | 키 `cover/{me}/{UUID}.jpg`, `Content-Type: image/jpeg` 서명, TTL 5분 |
| `PUT /api/users/me/cover-image` `{ key }` | 본인 경로·존재·**≤ 2MB**·`image/jpeg` 확인(실패 `INVALID_COVER_IMAGE`) → `changeCoverToImage(key)` — **영화 커버 해제** · 이전 업로드 키 삭제 이벤트 → `UserResponse` |
| `DELETE /api/users/me/cover-image` | 업로드 커버 해제(+ 키 삭제 이벤트) → `UserResponse` |

- PB의 `MediaStorage`·이벤트·`MediaProperties`를 그대로 쓴다 — 키 접두사와 검증 메시지만 다르다. 사진·커버 업로드를 **같은 내부 서비스(`ImageUploadService` 등, 종류별 정책 record)**로 묶어 코드 중복을 피한다.
- 앱 크기: 16:9, 1280×720 JPEG(앱 스펙). 서버는 비율을 검사하지 않는다(크기·형식만 — P-9와 같은 수준).
- ⚠️ **인프라** — EC2 역할 정책에 `cover/*` 경로 추가(account-integrity **B-5-8**). 탈퇴 리스너가 `cover/{userId}/`도 지운다.

### 3-3. 소셜 로그인 — PR-9 · PR-10 · PR-11

| 대상 | 변경 |
|---|---|
| `OAuthUserInfo` | `(providerId, email)`만 — `nickname`·`profileImage` 제거. 카카오·구글 검증기의 `resolveNickname`·`picture` 읽기 제거(구글 `DEFAULT_NICKNAME_PREFIX`, 카카오 `toHttps` 포함) |
| `UserService.signUpOAuth` | 반환을 `OAuthSignInResult(User user, boolean created)`로. 신규 생성 시 닉네임 `NicknameGenerator.generate()`, 사진 `null` |
| `NicknameGenerator` | `관객` + 6자리 숫자(`SecureRandom`, 100000~999999). 중복 허용이라 검사 없음. 생성값이 PR-6 규칙을 통과하는지 테스트 |
| `POST /api/auth/oauth/{provider}` 응답 | **`OAuthLoginResponse(accessToken, refreshToken, accessTokenExpiresIn, newUser)`** — 이 엔드포인트만. `TokenResponse`(로그인·재발급)는 그대로 — 앱의 재발급 single-flight 경로를 건드리지 않기 위해 |
| `ProfileImageUrlResolver` | V37 이후 `http` 분기 제거(PR-11) — `null` → `null`, 그 외 → `publicBaseUrl + "/" + key` |
| 계정 연결 | 영향 없음(원래 닉네임·사진을 쓰지 않는다). 검증기 반환 타입만 따라 바뀐다 |
| 로컬 가입 `SignUpLocalRequest.nickname` | `@Size(max = 50)` → `NicknamePolicy` |

### 3-4. 응답 확장 · PRIVATE 제한

`UserResponse`(`GET /me`, 3-1·3-2 응답)와 `UserProfileResponse`(`GET /{userId}/profile`)에 같은 필드를 더한다.

```java
String bio,
List<FavoriteMovieResponse> favoriteMovies,   // position 순, 최대 3
ProfileCoverResponse cover,                   // null이면 앱 기본 그라데이션
// UserProfileResponse만:
boolean restricted                            // PR-5 — PRIVATE이고 본인이 아니면 true

// FavoriteMovieResponse(Long movieId, String title, String posterPath, String backdropPath)    title = displayTitle()
// ProfileCoverResponse(CoverType type, String imageUrl, String backdropPath, Long movieId, String title)
//   MOVIE → backdropPath·movieId·title (앱이 TMDB URL 조립) / IMAGE → imageUrl(서버가 완성 URL로)
```

- **`restricted == true`이면** `bio`·`favoriteMovies`·`cover`·`followerCount`·`followingCount`를 **`null`로**(수 필드는 `Long`으로 바꿔 nullable — 앱 타입이 바뀐다). `nickname`·`profileImage`·`privacySetting`·`following`·`me`는 그대로.
  이 판정은 `UserAccessPolicy`가 아니라 **헤더 전용 규칙**이다(FRIENDS 비맞팔도 헤더는 보인다) — `FollowService.getUserProfile`에 둔다.
- 인생 영화는 `UserFavoriteMovieRepository.findByUserIdOrderByPosition`(영화 fetch join) 1쿼리 — `restricted`면 조회하지 않는다.
- `profileImage`·업로드 커버는 `ProfileImageUrlResolver`(사진·커버 공용으로 이름을 일반화해도 된다)를 거친 값.

### 3-5. `ErrorCode` 신규

| 코드 | 상태 | 메시지 |
|---|---|---|
| `INVALID_COVER_MOVIE` | 400 | 커버는 인생 영화 중 배경 이미지가 있는 영화만 선택할 수 있습니다. |
| `INVALID_COVER_IMAGE` | 400 | 커버 이미지가 올바르지 않습니다. |

## 4. 테스트 (`cinemory_test`, 실 DB)

| # | 시나리오 | 기대 |
|---|---|---|
| PT-1 | V37 — 카카오 URL·구글 URL·우리 키(`profile/…`)·`NULL` 사용자 | 앞의 둘만 `NULL`, 우리 키는 그대로 |
| PT-2 | 닉네임 1자 · 21자 · 공백만 · 예약어 포함(`CineMory팬`) · 제어 문자 | 400 (가입·`PUT /profile`·`PATCH /nickname` 세 경로 모두) |
| PT-3 | 다른 사용자와 **같은 닉네임** | 200 — 중복 허용(PR-7) |
| PT-4 | (삭제 — PR-7 개정) | |
| PT-5 | 21자 이상 기존 닉네임 사용자가 bio만 수정 | 200 — 닉네임이 그대로면 규칙을 다시 적용하지 않는다 |
| PT-6 | 신규 카카오·구글 가입 | 닉네임 `관객\d{6}`, `profileImage = null`, `newUser = true` / 두 번째 로그인 `newUser = false` |
| PT-7 | 인생 영화 [A,B,C] → [C,A] → [] | 순서·개수 정확, 제약 위반 없음(flush 순서 함정 회귀 방지) |
| PT-8 | 인생 영화 4편 · 중복 · 없는 영화 | 400 · 400 · 404 |
| PT-9 | 영화 커버가 목록 밖 · 배경 없는 영화 · `null` | 400 · 400 · 200 |
| PT-10 | **PRIVATE 사용자 프로필을 다른 사람이 조회** | `restricted = true`, bio·인생 영화·커버·수 = `null`, 닉네임·사진은 있음. **본인 조회**는 전부 |
| PT-11 | PUBLIC · FRIENDS(비맞팔) 프로필 조회 | `restricted = false`, 헤더 전부 |
| PT-12 | 인생 영화·커버(업로드)가 있는 사용자 탈퇴 | `user_favorite_movie` 0행, `cover/{id}/` 접두 삭제 호출(가짜 `MediaStorage`) |
| PT-13 | 커버 영화 삭제(관리 SQL) | `cover_movie_id = NULL` |
| PT-14 | 업로드 커버 → 영화 커버로 변경 / 영화 커버 → 업로드 | 앞: 업로드 키 해제 + 커밋 후 삭제 호출 · 뒤: 영화 커버 해제 |
| PT-15 | 업로드 커버가 있는 상태에서 `PUT /profile` `coverMovieId: null` | 업로드 커버 유지 |
| PT-16 | 커버 업로드 확정 — 남의 경로 · 2MB 초과 · `image/png` | 400 `INVALID_COVER_IMAGE` |
| PT-17 | `PATCH /me/privacy`와 `PUT /profile`의 공개 범위 변경 | 결과 동일 |

## 5. 패키지

```
domain/user/
  entity/UserFavoriteMovie.java · entity/CoverType.java
  repository/UserFavoriteMovieRepository.java   findByUserIdOrderByPosition · 벌크 삭제(JPQL)
  service/NicknamePolicy.java                   PR-6 — 가입·변경·프로필 공용
  service/NicknameGenerator.java                PR-10
  service/UserProfileService.java               3-1 (또는 UserService — 구현 시 크기를 보고 결정)
  dto/ProfileUpdateRequest · FavoriteMovieResponse · ProfileCoverResponse
global/infra/media/ (PB)                        커버 업로드는 같은 구성 요소 재사용(3-2)
domain/auth/dto/OAuthLoginResponse.java
```

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-10-31 | **✅ P-2 구현 완료(브랜치 `feature/profile-fields`) — 계약은 `controller-layer-spec.md` 5-1-F로 이관.** PR-1~PR-5 그대로: V38(`bio` · `cover_movie_id` · `cover_image`) · V39(`user_favorite_movie`) · `PUT /me/profile`(3-1 순서표 그대로) · 커버 업로드 3종 · `UserResponse`/`UserProfileResponse` 확장 · PRIVATE 제한(`restricted`). **구현 중 확인·결정**: ① **`chk_user_cover_single`은 만들 수 없었다** — 임시 스키마에서 시험하니 MySQL이 `ERROR 3823`(FK 참조 동작 컬럼은 CHECK에 쓸 수 없음)으로 거부. 스펙의 대안대로 CHECK를 빼고 `User.changeCoverToMovie`/`changeCoverToImage`/`clearCover`가 강제, `UserCoverInvariantTest`로 고정(delta에 기록). DB가 막지 못하므로 읽는 쪽은 영화 커버를 우선한다 ② 사진·커버는 `ImageUploadService`(종류별 `Policy(prefix, invalidCode)` record)로 묶고 `ProfileImageService`/`CoverImageService`는 바꾸는 컬럼만 안다 ③ 응답 조립은 `ProfileViewAssembler`(인생 영화 1쿼리·커버·사진 URL)로 모았다 — `UserService`·`ProfileImageService`·`FollowService`가 공유 ④ `UserFavoriteMovie.position`은 `TINYINT`라 `ddl-auto: validate`가 INTEGER로 오해하지 않게 `@JdbcTypeCode(SqlTypes.TINYINT)` ⑤ `bio`는 `BioPolicy`(정규화 150자·줄바꿈 2개, 위반 400 + `errors[]` field `bio`), 인생 영화 개수·중복 위반은 field `favoriteMovieIds` ⑥ 탈퇴 리스너는 `profile/`·`cover/` 접두를 **각각** 시도(한쪽 실패가 다른 쪽을 막지 않음) ⑦ `coverMovieId: null`은 영화 커버만 해제, 업로드 커버는 유지 — 업로드 커버 제거는 `DELETE /me/cover-image`만 ⑧ PT-17은 이번에 완결(`PATCH /privacy`와 `PUT /profile` 결과 동일). **⚠️ `followerCount`/`followingCount`가 nullable `Long`으로 — 앱 `gen:api` 필요.** 테스트 PT-2(`PUT` 경로)·PT-5(bio만 수정)·PT-7~PT-16·PT-17 |
| 2026-10-31 | **✅ P-1 구현 완료(브랜치 `feature/nickname-policy`) — 계약은 `controller-layer-spec.md` 5-1-E로 이관.** PR-6·PR-7(중복 허용 — 고유 제약·409 없음)·PR-9~PR-12 그대로. **V37**(`UPDATE user SET profile_image = NULL WHERE profile_image LIKE 'http%'`, 델타에 사전 점검·"롤백 없음(의도된 삭제)") · `NicknamePolicy`(정적 유틸, 서비스에서 판정 — 어노테이션에는 `@NotBlank`만) · `NicknameGenerator` · 검증기에서 `nickname`/`name`/`picture`·`toHttps`·`DEFAULT_NICKNAME_PREFIX` 제거 · `OAuthUserInfo(providerId, email)` · `signUpOAuth` → `OAuthSignInResult(user, created)` · oauth 엔드포인트만 `OAuthLoginResponse(newUser)` · `ProfileImageUrlResolver`의 `http` 분기 제거. **구현 중 정한 것**: ① 서비스 검증이 필드 사유를 낼 수 있게 `BusinessException`에 `List<FieldError>` 생성자를 더하고 핸들러가 `errors[]`로 실음(비어 있으면 기존 응답과 동일) ② 닉네임 변경은 공백 제거한 값이 현재 값과 같으면 규칙을 적용하지 않음(PT-5) ③ **PT-17은 반쪽만** — `PUT /profile`이 없어(P-2) `PATCH /me/privacy` 결과 확인만 두었고, 두 경로 비교는 P-2에서 ④ 제어 문자는 `isISOControl`만(스펙 문구 그대로) — 서식 문자(U+200B 등 보이지 않는 글자)는 막지 않는다. 필요하면 P-2에서 `FORMAT` 범주 추가를 검토 ⑤ PB의 `ProfileImageService`의 `http` 시작 값 검사는 방어적으로 유지(V37 이후엔 도달하지 않음). 테스트 PT-1·PT-2(가입·`PATCH`, `PUT /profile`은 P-2)·PT-3·PT-5·PT-6 |
| 2026-10-30 | **같은 날 개정 — 사용자 결정 4건.** ① **커버 업로드 추가**(PR-4) — 배경 이미지 없는 영화 대비. Part B 인프라 재사용, 영화/업로드 둘 중 하나(CHECK 또는 엔티티 메서드 — MySQL이 `SET NULL` FK 컬럼의 CHECK를 거부할 수 있어 구현 시 확인), IAM에 `cover/*` 추가 ② **PRIVATE 프로필 제한**(PR-5) — 본인 아닌 조회는 사진·닉네임·"비공개 계정"만, 서버가 `null` + `restricted`. FRIENDS 헤더는 그대로 ③ **닉네임 중복 허용**(PR-7, PR-8 삭제) — 처음엔 숫자 태그를 검토했으나 "태그는 관리자만 쓸 것"이라면 관리자는 이미 id로 구분하므로 태그 없이 중복 허용으로 정리(왓챠피디아 선례). 예약어(PR-6)가 운영자 사칭 방지의 유일한 장치, 일반 사칭은 한계로 기록. P-1에서 고유 제약·중복 정리·409가 빠져 규모가 줄었다 ④ **기존 제공자 사진 일괄 제거**(PR-11, V37) — B-5-4("기본 아바타로"가 기존 사용자의 유일한 삭제 수단)의 전제가 사라지고 `http` 분기를 코드에서 제거 |
| 2026-10-30 | **신설 — PR-1~PR-12 확정.** 프로필 설계 세션: 후보 9개(수정 화면·자기소개·인생 영화·커버·포스터 아바타·사진 업로드·취향 배지·활동 수·닉네임 고유화) 중 **수정 화면·자기소개·인생 영화(3편)·커버·사진 업로드(우선)·닉네임 고유화**를 채택(포스터 아바타는 불필요, 배지·활동 수는 보류 — 사용자 결정). 닉네임 고유화의 비용이 "기존 데이터"가 아니라 **소셜 가입의 제공자 닉네임 충돌**에 있다는 검토에서 **소셜 가입 시 닉네임·사진을 가져오지 않기로**(사용자 제안) — 구글 `name`이 실명이라 새 기본값 FRIENDS + 검색과 겹치면 실명 노출 구조가 되는 개인정보 문제가 함께 풀린다. 대체는 자동 닉네임 + 가입 직후 설정 화면(`newUser`). 닉네임 2~20자로 통일(가입 50/변경 30 불일치 해소, 검색과 일치). 커버는 업로드 없이 **인생 영화 중 하나의 배경 이미지**로 — 앱 API에 배경 이미지가 노출되지 않는 현황에서 범위를 키우지 않는 선택. 기존 중복 닉네임은 수동 정리 대신 **마이그레이션 안에서 결정적으로** 처리(배포 실패·자동 롤백 방지) |
