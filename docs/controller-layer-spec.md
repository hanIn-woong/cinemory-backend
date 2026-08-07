# CineMory Controller 계층 설계 스펙 (Step5)

`service-layer-spec.md`(Step4)와 `security-spec.md`(Step S)를 기반으로 Controller 계층을
어떻게 구현할지 정리한 스펙이다. **실제 코드는 Claude Code가 이 문서를 보고 작성**하며,
여기서는 패턴/경로/시그니처/설계 결정만 명시한다.

Step S가 선행 완료됐으므로 Step5는 **새로 설계하는 단계가 아니라 이미 확정된 계약을
HTTP 표면으로 회수하는 단계**다. 특히 S-4 화이트리스트가 URL을 이미 못박아 뒀으므로,
경로 설계는 자유 설계가 아니라 **화이트리스트와의 대조 작업**에 가깝다.

---

## 진행 로드맵

| 순서 | 범위 | 상태 |
|---|---|---|
| 5-0 | 공통 인프라 (validation · MVC 예외 · 응답/페이징 규약 · URL 규칙 · Springdoc 배선) | ✅ 확정 |
| 5-1 | `UserController` + **비밀번호 변경**(S-9 A-6 이관분) | ✅ 확정 |
| 5-2 | `MovieController` (공개 조회 전용) | ✅ 확정 |
| 5-3 | `WatchRecordController` · `ReviewController` · `WishMovieController` | ✅ 확정 |
| 5-4 | `CollectionController` | ✅ 확정 |
| 5-5 | `FollowController` · `CommentController` | ✅ 확정 |
| 5-6 | `TheaterController` · `BoxOfficeController` · `AdminController` | ✅ 확정 |
| 5-7 | 마무리 — 문서화 마감 + 통합/회귀 테스트 | ✅ 확정 |

> `AuthController`(`/api/auth/**`)는 **Step S에서 이미 구현 완료**됐으므로 Step5 범위 밖이다.
> 본 문서는 참조만 하며, 유일한 예외가 5-1의 비밀번호 변경(A-6 이관분)이다.

---

## 공통 설계 원칙

- **Controller는 위임만 한다.** 조건 분기, 값 조합, 계산을 Controller에 두지 않는다.
  분기가 필요해지면 그것은 Service로 내려가야 할 로직이다.
- **복수 Service 주입은 허용한다.** Controller는 조율 계층이므로 한 화면을 구성하는 데
  두 Service가 필요하면 둘 다 주입한다(예: `UserController` ← `UserService` + `FollowService`).
  금지 대상은 주입 개수가 아니라 Controller 안의 로직이다.
- **Entity를 알지 못한다.** Step4에서 "Service는 Entity를 반환하지 않는다"를 확정했으므로
  Controller는 구조적으로 Entity에 닿을 수 없다. 이 선을 Step5에서도 유지한다.
- **프레임워크 타입을 응답에 노출하지 않는다.** `Page`를 그대로 반환하지 않고
  `PageResponse<T>`로 감싼다(5-0 참고). Service가 Entity를 감춘 것과 같은 이유다.
- **인증 주체는 `@AuthUser`로만 받는다.** `@AuthenticationPrincipal`, `SecurityContextHolder`
  직접 접근 금지 (S-5 확정).
- **Service 시그니처는 변경하지 않는다.** 4-6-E에서 `(viewerId, targetUserId, …)` 컨벤션을
  미리 정리해 뒀으므로 Controller가 `@AuthUser`로 받아 그대로 넘기면 된다.

---

## 5-0. 공통 인프라 (✅ 확정)

### 5-0-A. 의존성 추가

```gradle
implementation 'org.springframework.boot:spring-boot-starter-validation'
implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3'
```

- `spring-boot-starter-validation`은 4-6 구현 중 "Step5로 이월"로 남겨둔 항목이다.
- **springdoc은 `3.0.3` 이상이어야 한다.** 2.x 계열은 Boot 3 전용이라 Boot 4에서 동작하지 않는다.
  Boot 4용 문서는 `https://springdoc.org/v4`에 별도로 있다.

### 5-0-B. 요청 검증 — `@Valid`와 Service 검증의 경계

**두 계층에 같은 검증을 중복해서 두지 않는다.** 4-3에서 `watchType`↔`ottPlatform` 정합성을
DB `CHECK` 대신 Service에 둔 것과 같은 원칙 — 검증 로직이 두 곳으로 갈라지는 것을 막는다.

| 계층 | 담당 | 예 |
|---|---|---|
| **Controller (`@Valid`)** | **형식** — null/공백, 길이, 숫자 범위, 컬렉션 비어있음 | `@NotBlank`, `@Size(max = 50)`, `@NotEmpty` |
| **Service** | **비즈니스 규칙** — 다른 값과의 정합성, 외부 상태 의존 | `validateWatchTypeConsistency`, `INVALID_SEARCH_RADIUS` |

- **`@RequestParam` 레벨 Bean Validation(`@Validated` + `@Min` 등)은 도입하지 않는다.**
  값 범위 검증은 전부 Service 소관이므로 `ConstraintViolationException` 핸들러도 불필요해진다.
  Controller는 필수 여부와 타입 변환만 프레임워크에 맡긴다.
- **엔티티 레벨 검증(`Review.validateRating()` 등)은 그대로 둔다.** 4-0에서 확정한
  `IllegalArgumentException` 핸들러가 이미 받고 있으며, `@Valid`는 그 앞단에서 형식만 거른다.

### 5-0-C. `GlobalExceptionHandler` 확장

4-0에서 "추가 예정"으로 남겨둔 자리와, DevLog에 Step5 항목으로 적어둔 404/405 포맷 통일을 함께 처리한다.

| 예외 | HTTP | ErrorCode | 비고 |
|---|---|---|---|
| `MethodArgumentNotValidException` | 400 | `INVALID_INPUT_VALUE` | `@Valid` 실패. 필드별 위반 목록 포함 |
| `HttpMessageNotReadableException` | 400 | `MALFORMED_REQUEST_BODY` | JSON 파싱 실패, 요청 바디의 enum 값 오류 포함 |
| `MethodArgumentTypeMismatchException` | 400 | `INVALID_TYPE_VALUE` | 경로/쿼리 변수 타입 변환 실패, 쿼리의 enum 값 오류 포함 |
| `HttpRequestMethodNotSupportedException` | 405 | `METHOD_NOT_ALLOWED` | |
| `NoResourceFoundException` | 404 | `ENDPOINT_NOT_FOUND` | Boot 3.2+ 이후 이름. `NoHandlerFoundException`이 아니다 |

**`ErrorResponse` 확장 — 필드별 위반 목록**

```java
public record ErrorResponse(
    int status, String code, String message,
    List<FieldError> errors   // 검증 실패가 아니면 빈 리스트
) {
    public record FieldError(String field, String reason) {}
}
```

- 기존 `from(ErrorCode)` / `of(HttpStatus, String)` 팩토리는 **시그니처를 유지**하고
  `errors`를 빈 리스트로 채운다. `EntryPoint`/`AccessDeniedHandler`(S-6)가 이미
  `from(errorCode)`를 쓰고 있으므로 여기를 바꾸면 인증 응답 포맷까지 흔들린다.
- 필드 목록을 담는 이유는 클라이언트가 "어느 입력이 틀렸는지"를 폼에 표시해야 하기 때문이다.
  단일 message로 뭉치면 RN 폼에서 다시 파싱해야 한다.

> **전제 확인** — 위 404/405 핸들러가 실제로 도달하려면 S-4의
> `.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()`이 선행돼야 한다.
> **이미 적용 완료**이므로 추가 조치는 없다. 이 규칙이 빠지면 전부 다시 401로 덮인다.

### 5-0-D. 페이징 — `PageResponse<T>`

```java
public record PageResponse<T>(
    List<T> content,
    int page, int size,
    long totalElements, int totalPages,
    boolean first, boolean last
) {
    public static <T> PageResponse<T> from(Page<T> page) { … }
}
```

**`Page`를 그대로 반환하지 않는 이유**

- Spring Data가 런타임 경고를 남긴다 — *"Serializing PageImpl instances as-is is not supported,
  meaning that there is no guarantee about the stability of the resulting JSON structure!"*
  프레임워크가 JSON 구조의 안정성을 **보장하지 않겠다고 명시**한 것이므로 API 계약으로 쓸 수 없다.
- `pageable.sort.sorted/unsorted/empty`, `offset`, `paged` 등 RN 클라이언트가 쓰지 않는 필드가
  응답마다 따라붙는다.
- 프론트 TS 타입이 백엔드 프레임워크 내부 구조를 그대로 베끼게 된다.

**`PagedModel`(`VIA_DTO`) 대신 자체 record를 쓰는 이유**

4-6-E에 문서화된 `getMovieReviews`의 한계 때문이다. `filterViewable`로 조회 후 필터링하는
구조라 **`totalElements`가 실제와 다르고 페이지당 항목 수가 요청 size보다 적을 수 있다.**
자체 DTO면 이 한계를 흡수하거나(커서형 전환 시 `last`/`hasNext` 기반으로 이동) 최소한
격리할 수 있지만, `PagedModel`은 프레임워크가 정한 필드 집합이라 손댈 여지가 없다.

**안전망은 함께 켠다**

```java
@EnableSpringDataWebSupport(pageSerializationMode = PageSerializationMode.VIA_DTO)
```

- 실수로 `Page`를 그대로 반환한 엔드포인트가 생겨도 경고 대신 안정 포맷이 나간다.
- 자체 `PageResponse`와 배타적이지 않다 — 정상 경로에서는 아예 발동하지 않는다.

**Pageable 규약**

- `@PageableDefault(size = 20)`를 모든 페이징 엔드포인트에 명시한다.
- `spring.data.web.pageable.max-page-size = 100` — 클라이언트가 size를 크게 넣어
  벌크 IN절을 부풀리는 것을 막는다.
- **클라이언트 `sort` 파라미터는 지원하지 않는다.** Step4의 Repository 메서드들이
  `OrderByIdDesc` 등으로 정렬을 이미 고정하고 있어, 외부에서 `sort`가 들어오면
  인덱스를 타지 않는 정렬이 조용히 만들어진다. Springdoc 문서에서도 노출하지 않는다.

### 5-0-E. 응답 규약

**성공 응답 래퍼(`ApiResponse<T>`)는 도입하지 않는다.**

- `204 No Content`와 구조적으로 충돌한다 — 바디 없는 응답에 래퍼를 씌울 수 없어
  예외 케이스가 생긴다. `unfollow`, `deleteComment` 등 204가 자연스러운 엔드포인트가 이미 다수다.
- HTTP 상태코드와 래퍼 내부 `success`/`code`가 같은 정보를 두 번 표현해 진실의 출처가 갈린다.
- 이 프로젝트는 `ErrorCode` 상수 30여 개 + `GlobalExceptionHandler` + `EntryPoint`/
  `AccessDeniedHandler`로 상태코드 체계가 이미 촘촘하다. 래퍼가 더할 정보가 없다.
- 래퍼는 보통 "상태코드를 제대로 쓰지 않고 200에 `success:false`를 담는" 설계에서 필요해진다.
  그 반대이므로 도입 실익이 없다.

**상태코드 표**

| 상황 | 코드 | 바디 |
|---|---|---|
| 조회 | 200 | Response DTO |
| 리소스 생성 | **201** + `Location` 헤더 | 생성된 Response DTO |
| 멱등 갱신(upsert) | 200 | Response DTO |
| 결과 바디가 있는 상태 변경 | 200 | `WishToggleResponse` 등 |
| 바디 없는 변경·삭제 | **204** | 없음 |

- 201을 쓰는 곳은 `POST /api/records`, `POST /api/collections`, `POST /api/comments` 셋뿐이다.
  나머지 POST는 "생성"이 아니라 상태 전이(토글, 팔로우)이므로 200/204를 쓴다.

### 5-0-F. URL 규칙 — S-4 화이트리스트와의 대조

**이 절이 5-0의 핵심이다.** 경로가 화이트리스트와 어긋나면
**테스트는 통과하는데 보호만 빠지는** 형태로 실패한다.

| 성격 | 경로 규칙 | 화이트리스트 상 위치 |
|---|---|---|
| 공개 콘텐츠 조회 | `/api/movies/**`, `/api/theaters/**`, `/api/box-office/**` | `permitAll` **GET만** |
| 타인 조회 가능 (사용자 스코프) | `/api/users/{userId}/…` | `permitAll` **GET만** |
| **본인 상태 조회 + 쓰기** | `/api/{resource}/…` (주체를 경로에 넣지 않음) | 열거 없음 → 기본 `authenticated` |
| 계정 설정 | `/api/users/me/…` | 열거 없음 → 기본 `authenticated` |
| 관리자 | `/api/admin/**` | `hasRole('ADMIN')` |

**쓰기 경로에 주체를 넣지 않는 이유** — 인증된 호출자가 곧 소유자이므로 URL에 다시 적는 것은
중복이고, "경로의 userId와 토큰의 userId가 다르면?"이라는 검증 지점을 불필요하게 만든다.
`deleteWatchRecord(userId, watchRecordId)`처럼 PK 기반 단건 API는 `movieId`조차 모르므로
소유자 세그먼트가 아무 식별 역할을 하지 못한다.

**⚠️ 본인 상태 조회를 `/api/movies/**` 아래에 두지 않는다**

`GET /api/movies/**`가 통짜로 `permitAll`이므로, 그 아래에 `GET .../wish`(본인 위시 여부)나
`GET .../review/me`(본인 리뷰)를 두면 **필터체인 보호가 사라지고 `@AuthUser(required = true)`
하나에만 의존**하게 된다. 방어가 한 겹뿐인 구조를 만들지 않는다.

> **규칙**: `/api/movies/**` 아래에는 **공개 조회 GET만** 둔다. 쓰기(PUT/DELETE/POST)는
> 화이트리스트가 GET만 열어놨으므로 두어도 안전하고, 리소스 소속이 자연스러우면 둔다.

**⚠️ 화이트리스트 수정이 필요한 2건** (5-0에서 함께 처리)

| 현재 | 문제 | 수정안 |
|---|---|---|
| `GET /api/users/*/records` | Ant 패턴상 세그먼트 1개만 매칭. `/records/movies/{movieId}`(회차 조회)가 걸리지 않아 **비로그인 공개 조회가 401로 막힌다** | `GET /api/users/*/records/**` |
| (없음) | `GET /api/collections/*/movies`는 4-5에서 **공개 조회**로 확정됐으나 화이트리스트에 없어 기본값 `authenticated`에 걸린다 | `GET /api/collections/*/movies` 추가 |

- 두 경로 모두 `UserAccessPolicy`가 `viewerId == null`을 정상 입력으로 처리하도록 이미
  설계돼 있으므로, 필터 단에서 막히면 4-6에서 확정한 정책이 무력화된다.
- **경로 상수는 `SecurityConfig`와 Controller가 공유**한다. S-4의 `shouldNotFilter`가
  이미 같은 원칙을 쓰고 있다 — 두 곳에 따로 적으면 엔드포인트 추가 시 목록이 갈라진다.

### 5-0-G. Springdoc 배선

- `OpenApiConfig`에 `@SecurityScheme(type = HTTP, scheme = "bearer", bearerFormat = "JWT")`를
  등록해 Swagger UI에서 Authorize 버튼으로 토큰을 한 번만 넣게 한다. Step S 검증에서 하던
  curl 반복이 여기서 사라진다.
- **화이트리스트 추가**: `/swagger-ui/**`, `/v3/api-docs/**` → `permitAll`.
- **⚠️ 운영 노출 차단**: 운영 프로파일에서 `springdoc.api-docs.enabled = false`,
  `springdoc.swagger-ui.enabled = false`. `security-spec.md` S-11
  *"배포 전 반드시 처리할 것"*에 항목으로 추가한다.
- **어노테이션은 최소한만** — `@Operation(summary = …)`과, 자동 추론이 불가능한 경우의
  `@Parameter`/`@Schema`만 단다. 나머지는 record 필드와 Bean Validation에서 자동 추론된다.
- **각 도메인 컨트롤러를 작성하는 시점에 함께 단다.** 5-7로 미루면 엔드포인트 40여 개를
  다시 훑어야 한다. 5-7은 마감 점검만 담당한다.

---

## 5-1. UserController (✅ 확정)

### 선행 작업

- **`UserService.changePassword(userId, currentPassword, newPassword)` 신규.**
  S-J에서 만든 `UserService.updatePassword(User, raw)`(비밀번호 갱신 + 세션 전체 폐기)를
  그대로 재사용하고 **앞에 현재 비밀번호 검증만 붙인다.** A-6 철회 시점에 예고된 형태다.
  - OAuth 계정이면 `INVALID_AUTH_METHOD`(4-1 기존 상수)
  - 현재 비밀번호 불일치면 `INVALID_CREDENTIALS`
  - 성공 시 `revokeAllByUserId(userId, now, RevokedReason.PASSWORD_CHANGED)`
- **비밀번호 정책 상수는 S-10과 공유한다.** 재설정(S-J)과 변경(Step5)에 다른 규칙을 두면
  약한 쪽으로 우회가 가능해진다. 검증 어노테이션과 상수를 한 곳에서 참조한다.

### 엔드포인트

| 메서드 | 경로 | Service | 인증 | 응답 |
|---|---|---|---|---|
| GET | `/api/users/{userId}/profile` | `followService.getUserProfile(viewerId, userId)` | `@AuthUser`(nullable) | 200 `UserProfileResponse` |
| GET | `/api/users/me` | `userService.getUser(userId)` | 필수 | 200 `UserResponse` |
| PATCH | `/api/users/me/nickname` | `changeNickname` | 필수 | 200 `UserResponse` |
| PATCH | `/api/users/me/privacy` | `changePrivacySetting` | 필수 | 200 `UserResponse` |
| PATCH | `/api/users/me/password` | `changePassword` | 필수 | **204** |

### DTO (신규)

| DTO | 필드 | 검증 |
|---|---|---|
| `NicknameChangeRequest` | `nickname` | `@NotBlank @Size(max = 30)` |
| `PrivacyChangeRequest` | `privacySetting` | `@NotNull` |
| `PasswordChangeRequest` | `currentPassword`, `newPassword` | `@NotBlank` + S-10 정책 공유 |

### 설계 노트

- **`getUserProfile`이 `FollowService`에 있어 `UserController`가 두 Service를 주입한다.**
  4-6에서 "팔로우 수/여부가 프로필 헤더의 본질적 구성요소이고 `UserService`가
  `FollowRepository`를 알게 되는 것보다 의존 방향이 자연스럽다"고 확정한 결과다.
  Controller가 조율하는 것은 정상이며, 여기서 로직이 생기지 않는 한 문제가 없다.
- **프로필은 비공개 계정이어도 노출된다** (4-6 확정). 화이트리스트에도 별도로 열려 있다.
  차단 대상은 시청기록/컬렉션/리뷰 등 콘텐츠다.
- **비밀번호 변경 성공은 204이며, 클라이언트는 이 응답을 받으면 저장된 토큰을 폐기하고
  재로그인 화면으로 보내야 한다.** 서버가 리프레시 토큰을 전부 끊었으므로 다음 재발급이
  반드시 실패한다. 이 계약을 프론트 과제로 명시한다.
- `/api/users/me/**`는 화이트리스트에 없어 기본값 `authenticated`로 보호된다.
  `GET /api/users/*/profile`이 `permitAll`이지만 `me` 하위 경로와는 세그먼트 수가 달라
  겹치지 않는다.

---

## 5-2. MovieController (✅ 확정)

### 엔드포인트

| 메서드 | 경로 | Service | 인증 | 응답 |
|---|---|---|---|---|
| GET | `/api/movies` | `movieQueryService.getMovieList(pageable)` | 불필요 | 200 `PageResponse<MovieListItemResponse>` |
| GET | `/api/movies/{movieId}` | `getMovieDetail(movieId)` | 불필요 | 200 `MovieDetailResponse` |

### 설계 노트

- `viewerId`를 받지 않는다. 영화는 사용자 소유 데이터가 아니라 `UserAccessPolicy` 적용 대상이
  아니다(4-7의 Theater/BoxOffice와 동일한 성격).
- **`searchMovies`는 이번 단계에서 엔드포인트로 노출하지 않는다.** 4-2에서 `MovieSearchCondition`
  미설계를 이유로 `Pageable`만 받도록 확정했기 때문에, 지금 노출하면 `getMovieList`와
  **동작이 완전히 동일한 두 엔드포인트**가 생긴다. 검색 조건 설계가 확정되는 시점에
  `GET /api/movies/search`로 추가한다. (5-7 잔여 항목)
- 이 단계가 가장 단순하므로 **5-0에서 정한 `PageResponse`/`@PageableDefault`/Springdoc
  어노테이션 규약의 첫 검증대**로 삼는다. 여기서 규약이 어색하면 5-3 이후로 번지기 전에 고친다.

---

## 5-3. WatchRecord · Review · WishMovie (✅ 확정)

### 5-3-A. `WatchRecordController`

| 메서드 | 경로 | Service | 인증 | 응답 |
|---|---|---|---|---|
| GET | `/api/users/{userId}/records` | `getUserMovieList(viewerId, userId, pageable)` | nullable | 200 `PageResponse<…>` |
| GET | `/api/users/{userId}/records/movies/{movieId}` | `getWatchLog(viewerId, userId, movieId)` | nullable | 200 `List<WatchRecordResponse>` |
| POST | `/api/records` | `addWatchRecord(userId, request)` | 필수 | **201** + `Location` |
| DELETE | `/api/records/{recordId}` | `deleteWatchRecord` | 필수 | 204 |
| PATCH | `/api/records/{recordId}/representative` | `setRepresentative` | 필수 | 204 |

- `WatchRecordCreateRequest` 검증: `movieId` `@NotNull`, `watchDate` `@NotNull`,
  `rating`은 엔티티 검증에 맡긴다(4-4에서 확정한 `IllegalArgumentException` 경로).
- **`watchType`↔`ottPlatformId` 정합성은 Controller에서 검증하지 않는다.** 4-3에서 Service
  단일 지점 검증으로 확정한 사항이며, `@Valid`로 흉내 내면 검증이 두 곳으로 갈라진다.
- `setRepresentative`가 PATCH인 이유 — 리소스 일부 상태 전이이고 **멱등**이다
  (4-3에서 "이미 대표면 즉시 반환"으로 확정).

### 5-3-B. `ReviewController`

| 메서드 | 경로 | Service | 인증 | 응답 |
|---|---|---|---|---|
| GET | `/api/movies/{movieId}/reviews` | `getMovieReviews(viewerId, movieId, pageable)` | nullable | 200 `PageResponse<ReviewResponse>` |
| PUT | `/api/movies/{movieId}/review` | `writeReview(userId, movieId, request)` | 필수 | 200 `ReviewResponse` |
| DELETE | `/api/movies/{movieId}/review` | `deleteReview(userId, movieId)` | 필수 | 204 |
| GET | `/api/reviews/me` (`?movieId=`) | `getMyReview(userId, movieId)` | 필수 | 200 `ReviewResponse` / **204** |

- **PUT을 쓰는 이유** — 4-4에서 upsert(`writeReview`)로 확정했고, "같은 요청을 반복해도 같은
  상태"라는 PUT의 의미와 정확히 일치한다. POST면 두 번 호출 시 중복 생성을 기대하게 된다.
- **쓰기가 `/api/movies/**` 아래에 있어도 안전한 이유** — 화이트리스트는 이 경로의 **GET만**
  열었다. PUT/DELETE는 기본값 `authenticated`에 걸린다.
- **`getMyReview`만 `/api/reviews/me`로 뺀 이유** — GET이라 `/api/movies/**` 아래에 두면
  `permitAll`에 걸려 필터 보호가 사라진다(5-0-F). 없을 때 **204**를 반환해 "아직 리뷰 없음"이
  정상 상태임을 표현한다(4-4에서 `Optional` 반환으로 확정한 의미를 HTTP로 옮긴 것).
- **`getMovieReviews`의 페이징은 부정확할 수 있다.** 4-6-E에서 `filterViewable` 후 필터링
  방식을 택했으므로 `totalElements`에 가려진 리뷰가 포함되고 페이지당 항목 수가 size보다
  적을 수 있다. **Springdoc `@Operation` 설명에 이 한계를 명시**해 프론트가 무한스크롤을
  `content.isEmpty()`가 아니라 `last` 기준으로 구현하게 한다.

### 5-3-C. `WishMovieController`

| 메서드 | 경로 | Service | 인증 | 응답 |
|---|---|---|---|---|
| GET | `/api/users/{userId}/wishes` | `getUserWishList(viewerId, userId, pageable)` | nullable | 200 `PageResponse<WishListItemResponse>` |
| POST | `/api/movies/{movieId}/wish` | `toggleWish(userId, movieId)` | 필수 | 200 `WishToggleResponse` |
| GET | `/api/wishes/me/{movieId}` | `isWished(userId, movieId)` | 필수 | 200 `WishToggleResponse` |

- 토글은 **생성이 아니므로 201이 아니라 200**이다. 같은 엔드포인트가 추가/삭제 양쪽을
  수행하므로 `wished` 플래그가 담긴 바디가 응답의 본체다.
- `isWished`를 `/api/wishes/me/…`로 뺀 근거는 `getMyReview`와 동일하다.
  응답 DTO는 `WishToggleResponse`를 재사용한다 — 필드가 `wished` 하나로 같고,
  클라이언트가 하트 아이콘 상태를 갱신하는 용도도 동일하다.

---

## 5-4. CollectionController (✅ 확정)

| 메서드 | 경로 | Service | 인증 | 응답 |
|---|---|---|---|---|
| GET | `/api/users/{userId}/collections` | `getCollections(viewerId, userId, pageable)` | nullable | 200 `PageResponse<CollectionResponse>` |
| GET | `/api/collections/{collectionId}/movies` | `getCollectionMovies(viewerId, collectionId, pageable)` | nullable | 200 `PageResponse<…>` |
| POST | `/api/collections` | `createCollection` | 필수 | **201** + `Location` |
| PATCH | `/api/collections/{collectionId}` | `updateCollection` | 필수 | 200 `CollectionResponse` |
| DELETE | `/api/collections/{collectionId}` | `deleteCollection` | 필수 | 204 |
| POST | `/api/collections/{collectionId}/movies` | `addMoviesToCollection` | 필수 | 200 `AddMoviesToCollectionResponse` |
| DELETE | `/api/collections/{collectionId}/movies/{movieId}` | `removeMovieFromCollection` | 필수 | 204 |

### DTO 검증

- `CollectionCreateRequest` / `CollectionUpdateRequest`: `name` `@NotBlank @Size(max = 50)`,
  `description` `@Size(max = 500)`
- `AddMoviesToCollectionRequest`: `movieIds` **`@NotEmpty`** + `@Size(max = 50)`
  - 상한을 두는 이유는 `findAllById`/`findByCollectionIdAndMovieIdIn`의 IN절 크기를 제한하기
    위함이다. 5-0의 `max-page-size`와 같은 성격의 방어다.

### 설계 노트

- **`POST /api/collections/{id}/movies`가 200인 이유** — 4-5에서 idempotent 벌크 추가로
  확정했고, 응답의 본체가 `addedCount`/`skippedCount`다. 생성된 단일 리소스를 가리킬
  `Location`이 없으므로 201이 성립하지 않는다.
- **컬렉션 단건 조회 엔드포인트는 두지 않는다.** Step4에 대응 Service 메서드가 없고
  (`getCollections`는 목록 전용), 컬렉션 상세 화면은 목록에서 받은 `CollectionResponse`와
  `getCollectionMovies`로 구성 가능하다. 딥링크 진입이 필요해지면 그때 Service에
  `getCollection(viewerId, collectionId)`를 추가한다. (잔여 항목)
- `GET /api/collections/*/movies`는 **화이트리스트 추가 대상**이다(5-0-F). 4-5에서
  공개 조회로 확정했는데 화이트리스트에 없어 현재는 필터가 먼저 막는다.

---

## 5-5. Follow · Comment (✅ 확정)

### 5-5-A. `FollowController`

| 메서드 | 경로 | Service | 인증 | 응답 |
|---|---|---|---|---|
| POST | `/api/users/{userId}/follow` | `follow(followerId, userId)` | 필수 | 204 |
| DELETE | `/api/users/{userId}/follow` | `unfollow(followerId, userId)` | 필수 | 204 |
| GET | `/api/users/{userId}/followers` | `getFollowers(viewerId, userId, pageable)` | nullable | 200 `PageResponse<FollowUserResponse>` |
| GET | `/api/users/{userId}/followings` | `getFollowings(…)` | nullable | 200 `PageResponse<…>` |

- **양쪽 모두 204이고 멱등이다** (4-6 확정). 이미 팔로우 중이어도 200이 아니라 204를 반환해
  클라이언트가 사전 상태 조회를 하지 않아도 되게 한다.
- 쓰기가 `/api/users/{userId}/…` 아래에 있지만 **POST/DELETE이므로 화이트리스트(GET만)에
  걸리지 않는다.** 여기서 `{userId}`는 소유자가 아니라 **대상**이므로 5-0-F의
  "쓰기 경로에 주체를 넣지 않는다"에 위배되지 않는다.
- 진짜 동시 팔로우 경합은 `uk_follow` 위반 → `GlobalExceptionHandler`의
  `DataIntegrityViolationException` 핸들러가 409 `DUPLICATE_REQUEST`로 처리한다
  (4-6 구현 중 조정 ①에서 확정).

### 5-5-B. `CommentController`

| 메서드 | 경로 | Service | 인증 | 응답 |
|---|---|---|---|---|
| GET | `/api/comments` (`?targetType=&targetId=`) | `getComments(viewerId, targetType, targetId, pageable)` | nullable | 200 `PageResponse<CommentResponse>` |
| POST | `/api/comments` | `createComment(authorId, request)` | 필수 | **201** + `Location` |
| PATCH | `/api/comments/{commentId}` | `editComment(viewerId, commentId, content)` | 필수 | 204 |
| DELETE | `/api/comments/{commentId}` | `deleteComment(viewerId, commentId)` | 필수 | 204 |

- `CommentCreateRequest`: `targetType` `@NotNull`, `targetId` `@NotNull`,
  `content` `@NotBlank @Size(max = 500)`. `CommentUpdateRequest`도 동일 `content` 제약.
- **enum 바인딩 실패 경로가 두 갈래로 갈린다** — 5-0-C의 두 핸들러가 각각 받는다.
  - 쿼리 파라미터(`GET ?targetType=XXX`) → `MethodArgumentTypeMismatchException` → 400
  - 요청 바디(`POST {"targetType":"XXX"}`) → `HttpMessageNotReadableException` → 400
  둘 다 400이지만 잡히는 예외가 달라 **핸들러를 하나만 만들면 한쪽이 500으로 샌다.**
- `UNSUPPORTED_COMMENT_TARGET`(400)은 **enum에는 있으나 Resolver 구현체가 없는 경우**로,
  위 두 바인딩 오류와 다른 상황이다. `NotificationTargetType` 도입 시 값이 갈라지므로
  이 구분이 유지돼야 한다.
- 수정이 204인 이유 — 4-6에서 작성자만 수정 가능하고 반환값이 명시되지 않았다.
  변경된 `content`는 클라이언트가 이미 알고 있으므로 바디를 돌려줄 필요가 없다.

---

## 5-6. Theater · BoxOffice · Admin (✅ 확정)

### 5-6-A. 조회

| 메서드 | 경로 | Service | 인증 | 응답 |
|---|---|---|---|---|
| GET | `/api/theaters/nearby` | `getNearbyTheaters(latitude, longitude, radiusMeters, limit)` | 불필요 | 200 `List<TheaterResponse>` |
| GET | `/api/box-office` (`?rankType=&targetDate=`) | `getBoxOffice(rankType, targetDate)` | 불필요 | 200 `BoxOfficeResponse` |

- **`viewerId`를 받지 않는다** (4-7 확정 — 공용 데이터).
- `radiusMeters`/`limit`은 `@RequestParam(defaultValue = …)`로 기본값만 주고 **범위 검증은
  하지 않는다.** 상한 초과는 Service가 `INVALID_SEARCH_RADIUS`로 처리한다(5-0-B 원칙).
  기본값 자체는 `application.yml`의 `cinemory.*`에 외부화돼 있으므로 `@Value`로 주입한다.
- `targetDate`는 `@DateTimeFormat(iso = DATE)`, **null 허용**(4-7에서 "null이면 최신 집계일"로 확정).
- 페이징 없이 `List`를 반환하는 유일한 구간이다. 반경 검색은 `limit`으로 이미 잘려 있고
  박스오피스는 고정 10~20건이라 페이징 개념이 없다.

### 5-6-B. `AdminController`

| 메서드 | 경로 | Service | 응답 |
|---|---|---|---|
| POST | `/api/admin/box-office/sync` (`?targetDate=`) | `boxOfficeSyncService.syncDaily` | 200 `{ "saved": n }` |
| POST | `/api/admin/box-office/rematch` (`?limit=`) | `rematchUnlinked` | 200 `{ "matched": n }` |
| POST | `/api/admin/theaters/seed` | `theaterSeedService.seedAll` | 200 `{ "seeded": n }` |

- 인가는 `SecurityConfig`의 `hasRole('ADMIN')`이 전담한다. **Controller에
  `@PreAuthorize`를 중복으로 달지 않는다** — 두 곳에 두면 어느 쪽이 진실인지 갈린다.
- **스케줄러와 동일한 Service 메서드를 호출한다** (4-7 확정). 관리자용 별도 로직을 만들지 않는다.
- ⚠️ **`seedAll(List<TheaterSeedData>)`의 입력 방식이 미확정이다.** CSV 멀티파트 업로드로
  받을지, 서버 리소스 경로의 파일을 읽을지 정해지지 않았다. 좌표계 확인(WGS84 vs EPSG:5174)도
  선행돼야 하므로 **5-6에서는 엔드포인트를 만들지 않고 잔여 항목으로 남긴다.**

---

## 5-7. 마무리 — 문서화 마감 + 테스트 (✅ 확정)

### 회귀 테스트 — 화이트리스트 대조

**이 단계의 핵심 산출물이다.** `RequestMappingHandlerMapping`에서 등록된 전체 엔드포인트를
뽑아 `SecurityConfig`의 공개 경로 상수와 대조하는 테스트를 둔다.

- 검증 내용: **화이트리스트에 없는 경로가 인증 없이 200을 반환하지 않는다.**
- 필요한 이유 — 경로 오타나 새 엔드포인트 추가로 보호가 빠지는 실패는 **기능 테스트를
  전부 통과하면서** 발생한다. 사람이 대조로 잡을 수 있는 종류의 실수가 아니다.
- S-4의 `SecurityErrorDispatchTest`가 이미 `RANDOM_PORT`로 실제 톰캣을 띄우고 있으므로
  같은 방식을 재사용한다. MockMvc는 컨테이너 동작을 재현하지 못한다.

### 통합 테스트

도메인별 MockMvc 슬라이스 테스트로 아래 4종을 고정한다.

1. 인증 필요 경로 미인증 호출 → 401 `UNAUTHORIZED`
2. `@Valid` 위반 → 400 `INVALID_INPUT_VALUE` + `errors` 배열
3. 없는 경로/잘못된 메서드 → 404/405가 **`ErrorResponse` 포맷**으로 (5-0-C 회귀)
4. 비로그인 공개 조회 → 200이며 viewer 의존 플래그(`following`/`editable`/`deletable`)가 전부 `false`

### 문서화 마감

- `/v3/api-docs` 산출물로 `openapi-typescript`(또는 `orval`) TS 타입 생성이 실제로 되는지 확인.
  이것이 Springdoc 도입의 1순위 실익이므로 여기서 검증하지 않으면 도입 이유가 사라진다.
- 운영 프로파일에서 Swagger UI가 실제로 닫히는지 확인.

---

## ErrorCode 추가분

| 상수 | HTTP | 용도 |
|---|---|---|
| `INVALID_INPUT_VALUE` | 400 | `@Valid` 검증 실패 |
| `INVALID_TYPE_VALUE` | 400 | 경로/쿼리 변수 타입 변환 실패 (enum 포함) |
| `MALFORMED_REQUEST_BODY` | 400 | 요청 바디 JSON 파싱 실패 (enum 포함) |
| `METHOD_NOT_ALLOWED` | 405 | 지원하지 않는 HTTP 메서드 |
| `ENDPOINT_NOT_FOUND` | 404 | 존재하지 않는 경로 |

> `INVALID_AUTH_METHOD`(4-1), `INVALID_CREDENTIALS`(S-6), `UNAUTHORIZED`/`ACCESS_DENIED`(4-6),
> `DUPLICATE_REQUEST`(4-6)는 기존 상수 재사용.

---

## 잔여 확인 항목

| # | 항목 | 처리 시점 |
|---|---|---|
| 1 | **화이트리스트 수정 2건** — `GET /api/users/*/records/**`, `GET /api/collections/*/movies` 추가 | **5-0 (필수)** |
| 2 | `springdoc` `3.0.3`의 Boot 4 실동작 확인 (기동 + `/v3/api-docs` 생성) | 5-0 |
| 3 | `searchMovies` 엔드포인트 노출 — `MovieSearchCondition` 설계 확정 후 | 검색 설계 세션 |
| 4 | 컬렉션 **단건 조회** Service 메서드 부재 — 딥링크 요구 확인 후 추가 | 프론트 라우팅 확정 시 |
| 5 | `TheaterSeedService` 입력 방식(멀티파트 vs 서버 파일) + **좌표계 확인** | 4-7 잔여 항목과 함께 |
| 6 | `MyMovieListItemResponse` 리네임 — 4-6-E에서 메서드는 `getUserMovieList`로 바꿨으나 DTO명에 `My`가 남아 있음 | 5-3 진행 중 |
| 7 | 알림 도메인 Controller — 도메인 설계 자체가 미착수 | 알림 설계 세션 이후 |

---

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-08-07 | Step5 설계 확정. 로드맵 5-0~5-7 확정. **① 페이징은 자체 `PageResponse<T>`**(`PageImpl` 직렬화 비보장 + `getMovieReviews`의 `totalElements` 부정확 한계를 흡수할 여지 확보, `VIA_DTO`는 안전망으로 병행) **② 성공 응답 래퍼 미도입**(204와 구조적 충돌 + 상태코드 체계와 정보 중복) **③ URL 규칙 = 조회는 소유자 스코프 경로 / 쓰기·본인 상태 조회는 리소스 경로 / `me`는 계정 설정 전용**(S-4 화이트리스트와 무수정 정합) **④ Springdoc 도입, 5-0에 배선하고 어노테이션은 도메인 작성 시 동시 부착**. `/api/movies/**` GET permitAll 하위에 본인 상태 조회를 두지 않는 규칙 신설. **화이트리스트 결함 2건 발견** — `/api/users/*/records`가 Ant 패턴상 회차 조회를 매칭하지 못해 공개 조회가 401로 막히고, 4-5에서 공개로 확정한 `GET /api/collections/*/movies`가 화이트리스트에 누락. 비밀번호 변경(A-6 이관분)은 S-J의 `updatePassword`에 현재 비밀번호 검증만 덧붙이는 형태로 5-1에 배치 |
