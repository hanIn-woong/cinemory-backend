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
| 5-7 | 마무리 — 테스트 + 문서화 마감 (**A→B→C→D**, 5-6-C 완료가 선행) | ✅ 확정 |

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

> **⚠️ 2026-08-10 전면 개정.** 초판은 표준 MVC 예외를 아래 표로 **열거**하는 방식이었으나,
> 5-6에서 `MissingServletRequestParameterException`(필수 쿼리 파라미터 누락)이 열거에서 빠져
> Spring 기본 400 포맷으로 새는 것이 실서버 검증에서 발견됐다. 열거 방식은 구멍이
> 생길 때마다 **실제로 그 경로를 호출해야만** 드러나므로, 아래와 같이 상속 방식으로 전환한다.

#### `ResponseEntityExceptionHandler` 상속으로 전환

`GlobalExceptionHandler`가 `ResponseEntityExceptionHandler`를 **상속**하고,
`handleExceptionInternal`을 오버라이드해 바디를 `ErrorResponse`로 교체한다.

```java
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) { … }
}
```

- Spring이 표준 MVC 예외를 **이미 전부 잡고 있으므로**, 바디 변환 지점 한 곳만 우리 포맷으로
  바꾸면 현재·미래의 모든 표준 예외가 자동으로 `ErrorResponse`로 나간다.
- **열거를 유지보수할 필요가 사라지는 것이 이 전환의 핵심이다.** 아래 표는 이제
  "우리가 등록해야 할 목록"이 아니라 **매핑 결과를 확인하는 참조표**다.

| 예외 | HTTP | ErrorCode | 비고 |
|---|---|---|---|
| `MethodArgumentNotValidException` | 400 | `INVALID_INPUT_VALUE` | `@Valid` 실패. 필드별 위반 목록 포함 |
| **`MissingServletRequestParameterException`** | 400 | `INVALID_INPUT_VALUE` | **필수 `@RequestParam` 누락 (5-6에서 발견)** |
| `HttpMessageNotReadableException` | 400 | `MALFORMED_REQUEST_BODY` | JSON 파싱 실패, 요청 바디의 enum 값 오류 포함 |
| `MethodArgumentTypeMismatchException` | 400 | `INVALID_TYPE_VALUE` | 경로/쿼리 변수 타입 변환 실패, 쿼리의 enum 값 오류 포함 |
| `HttpRequestMethodNotSupportedException` | 405 | `METHOD_NOT_ALLOWED` | |
| `NoResourceFoundException` | 404 | `ENDPOINT_NOT_FOUND` | Boot 3.2+ 이후 이름. `NoHandlerFoundException`이 아니다 |
| `HttpMediaTypeNotSupportedException` | 415 | `UNSUPPORTED_MEDIA_TYPE` | `Content-Type` 누락·불일치. RN에서 흔한 실수 |
| `HttpMediaTypeNotAcceptableException` | 406 | `NOT_ACCEPTABLE` | |
| `MissingServletRequestPartException` | 400 | `INVALID_INPUT_VALUE` | 멀티파트 누락 — `seedAll` 도입 시 바로 필요해진다 |
| `MissingPathVariableException` | 500 | `INTERNAL_ERROR` | 경로 변수 누락은 클라이언트가 아니라 **코딩 실수**라 5xx가 맞다 |

**전환 시 주의 — `@Valid` 필드 목록 로직의 이사**

- `MethodArgumentNotValidException`이 **부모 클래스 담당으로 넘어간다.** 초판에서 만든
  필드별 `errors[]` 생성 로직을 `handleMethodArgumentNotValid` 오버라이드(또는
  `handleExceptionInternal` 내 분기)로 **옮겨야** 한다. 그대로 두면 조용히 무시된다.
- `BusinessException` / `IllegalArgumentException` / `DataIntegrityViolationException`
  핸들러는 표준 MVC 예외가 아니므로 **기존 `@ExceptionHandler` 그대로 유지**한다.
- 부모 클래스는 `ResponseEntity<Object>`를 반환한다. 우리 핸들러들의 반환 타입과
  섞이지 않도록 시그니처를 억지로 통일하지 않는다.

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
  - **보류 사유가 2026-08-13에 바뀌었다.** tmdb-sync **D-2 ③ 확정**으로 검색이 우리 DB 단독이
    아니라 **DB + TMDB 병합**이 됐다. 이제는 "동작이 겹쳐서 미룬다"가 아니라
    **응답 계약이 확정되지 않아 미루는 것**이다 — 미등록 항목이 섞여 `movieId`가 nullable이
    되고, 병합이라 `PageResponse`의 `totalElements`가 성립하지 않는다. 5-0에서 정한
    `PageResponse` 규약을 **이 엔드포인트만 벗어나야 할 수 있다**(`Slice` 등).
    자세한 쟁점은 `service-layer-spec.md` 4-2 설계 노트 참고.
- 이 단계가 가장 단순하므로 **5-0에서 정한 `PageResponse`/`@PageableDefault`/Springdoc
  어노테이션 규약의 첫 검증대**로 삼는다. 여기서 규약이 어색하면 5-3 이후로 번지기 전에 고친다.

---

## 5-3. WatchRecord · Review · WishMovie (✅ 확정)

### 5-3-A. `WatchRecordController`

| 메서드 | 경로 | Service | 인증 | 응답 |
|---|---|---|---|---|
| GET | `/api/users/{userId}/records` | `getUserMovieList(viewerId, userId, pageable)` | nullable | 200 `PageResponse<UserMovieListItemResponse>` |
| GET | `/api/users/{userId}/records/movies/{movieId}` | `getWatchLog(viewerId, userId, movieId)` | nullable | 200 `List<WatchRecordResponse>` |
| POST | `/api/records` | `addWatchRecord(userId, request)` | 필수 | **201** + `Location` |
| DELETE | `/api/records/{recordId}` | `deleteWatchRecord` | 필수 | 204 |
| PATCH | `/api/records/{recordId}/representative` | `setRepresentative` | 필수 | 204 |

- `WatchRecordCreateRequest` 검증: **`movieId` `@NotNull`만.** `rating`은 엔티티 검증에 맡긴다
  (4-4에서 확정한 `IllegalArgumentException` 경로).
- **`watchDate`에 `@NotNull`을 걸지 않는다** (2026-08-07 정정). 엔티티에서 nullable로 확정돼
  있으므로(`jpa-entity-spec.md` WatchRecord) DTO가 더 엄격하면 "오래전에 봐서 날짜가 기억나지
  않는 기록"을 허용한 설계가 Controller 단에서 무력화된다. 날짜를 필수로 받는 것이 제품
  의도라면 DTO가 아니라 엔티티/스키마를 not null로 올리는 순서가 맞다.
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

- `ReviewWriteRequest` 검증: `rating` **`@NotNull`**, `content` `@NotBlank @Size(max = 2000)`.
  엔티티가 둘 다 not null이고 `content`는 length 2000이라 스키마와 정확히 일치한다.
  `rating`의 **범위**(0.0~10.0)는 `Review.validateRating()` 소관이며 DTO는 null 여부만 본다(5-0-B).
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
- **`radiusMeters`/`limit`은 `@RequestParam(required = false) Integer`로 받아 `null`을 그대로
  Service에 넘긴다.** 기본값 적용과 상한 검증을 **Service 한 곳**에서 처리한다
  (2026-08-10 개정 — 아래 근거).
- `targetDate`는 `@DateTimeFormat(iso = DATE)`, **null 허용**(4-7에서 "null이면 최신 집계일"로 확정).
  `radiusMeters`/`limit`의 null 처리와 같은 형태다.
- 페이징 없이 `List`를 반환하는 유일한 구간이다. 반경 검색은 `limit`으로 이미 잘려 있고
  박스오피스는 고정 10~20건이라 페이징 개념이 없다.

**Controller가 설정값을 읽지 않는 이유** (초판의 `@RequestParam(defaultValue = "${cinemory.…}")` 철회)

1. **플레이스홀더는 요청 시점에 해석된다.** 프로퍼티 키에 오타가 있어도 기동은 정상이고
   **첫 호출에서 `Could not resolve placeholder`로 500**이 난다. 5-3의 `isRepresentative`와
   똑같이 "컴파일·기동을 통과하고 그 경로를 실제로 태울 때까지 숨는" 유형의 실패다.
   정상 경로 curl 검증으로는 잡히지 않는다.
2. **같은 값의 출처가 둘로 갈린다.** 4-7에서 반경 상한을 Service가 `INVALID_SEARCH_RADIUS`로
   검증하도록 확정했으므로 Service는 이미 `cinemory.*` 설정을 들고 있다. Controller가 별도
   플레이스홀더로 기본값을 끌어쓰면 **키를 한쪽만 바꿨을 때 조용히 어긋난다.**
3. 5-0-B의 "값에 대한 판단은 Service 소관" 원칙과도 일치한다. 기본값 선택은 형식 검증이 아니라
   값에 대한 판단이다.

> **이 규칙은 이후 모든 Controller에 적용한다.** Controller는 `application.yml`을 알지 못한다.

### 5-6-B. `AdminController` — **박스오피스 / 극장 시드 분리** (2026-08-10 개정)

`seedAll`의 입력 방식이 미확정이라, **박스오피스 2종을 먼저 떼고 극장 시드는 보류**한다.

**① 지금 구현 — 박스오피스**

| 메서드 | 경로 | Service | 응답 |
|---|---|---|---|
| POST | `/api/admin/box-office/sync` (`?targetDate=`) | `boxOfficeSyncService.syncDaily` | 200 `{ "saved": n }` |
| POST | `/api/admin/box-office/rematch` (`?limit=`) | `rematchUnlinked` | 200 `{ "matched": n }` |

**② 보류 — 극장 시드**

| 메서드 | 경로 | Service | 상태 |
|---|---|---|---|
| POST | `/api/admin/theaters/seed` | `theaterSeedService.seedAll` | ⏸ **잔여 #5** |

- ⚠️ `seedAll(List<TheaterSeedData>)`의 입력 방식이 미확정이다. CSV 멀티파트 업로드로
  받을지, 서버 리소스 경로의 파일을 읽을지 정해지지 않았다. **좌표계 확인
  (WGS84 vs EPSG:5174)이 선행**돼야 하므로 엔드포인트를 만들지 않는다.
- 멀티파트로 확정되면 5-0-C의 `MissingServletRequestPartException` 매핑이 그 시점에
  실제로 쓰인다 — 상속 전환 덕분에 별도 조치는 필요 없다.

**공통**

- 인가는 `SecurityConfig`의 `hasRole('ADMIN')`이 전담한다. **Controller에
  `@PreAuthorize`를 중복으로 달지 않는다** — 두 곳에 두면 어느 쪽이 진실인지 갈린다.
- **스케줄러와 동일한 Service 메서드를 호출한다** (4-7 확정). 관리자용 별도 로직을 만들지 않는다.
- `targetDate`/`limit`은 5-6-A와 동일하게 **`required = false` + null 전달**로 받는다.
  Controller가 설정값을 읽지 않는다는 규칙이 관리자 엔드포인트에도 그대로 적용된다.

**⏰ 박스오피스 2종을 5-7보다 먼저 떼는 이유**

S-6에서 *"`AccessDeniedHandler`가 실제로 타는 경로는 일반 유저의 `/api/admin/**` 호출이
사실상 유일하다"*고 확정했다. 즉 **`AdminController`가 없으면 5-7 통합 테스트에서 403 경로를
검증할 수단이 없다.** 지금 만들어두면 5-7의 화이트리스트 회귀 테스트가
`permitAll` / `authenticated` / `hasRole` 세 갈래를 모두 덮는다.

---

### 5-6-C. 5-6 잔여 작업 (2026-08-10 확정 / ✅ 완료)

5-6 구현 및 실서버 검증에서 도출된 항목. **아래 3건을 마친 뒤 5-7에 착수한다.**

| # | 작업 | 범위 | 상태 |
|---|---|---|---|
| 1 | **`GlobalExceptionHandler` → `ResponseEntityExceptionHandler` 상속 전환** | `global/exception` | ✅ |
| 2 | **`@RequestParam` 기본값을 Service로 이관** | `TheaterController`, `BoxOfficeController` | ✅ |
| 3 | **`AdminController` 신규 — 박스오피스 2종만** (`seedAll` 제외) | `domain/boxoffice/controller` | ✅ |

**세부**

1. 5-0-C 개정판대로 상속 전환. `@Valid` 필드 목록 생성 로직을 오버라이드로 이전하는 것이
   유일한 실질 작업이며, `BusinessException` 계열 핸들러는 그대로 둔다.
   - 신규 `ErrorCode` 2건 추가 필요: `UNSUPPORTED_MEDIA_TYPE`(415), `NOT_ACCEPTABLE`(406)
   - 5-6에서 임시로 추가한 `MissingServletRequestParameterException` 개별 핸들러는
     **상속 전환 시 제거**한다(부모가 담당하므로 중복).
2. `radiusMeters`/`limit`을 `Integer` + `required = false`로 바꾸고 기본값 적용을
   `TheaterQueryService`로 이동. `application.yml`의 `cinemory.theater.*` 키는 그대로 두되
   **참조 지점이 Service 하나로 줄어드는지** 확인한다.
3. `AdminController`는 `domain/boxoffice/controller`에 둔다. 경로가 `/api/admin/**`이라고
   별도 패키지를 만들지 않는다 — 호출하는 Service가 박스오피스 도메인이기 때문이며,
   5-1에서 `UserController`가 `FollowService`를 주입한 것과 같은 기준(**패키지는 Service 소유,
   경로는 별개**)이다. 극장 시드가 붙을 때 `domain/theater/controller`에 두 번째
   Admin 컨트롤러가 생기는 것을 허용한다.

**순서** — 1 → 2 → 3. 1과 2는 **5-7 테스트가 검증할 대상 자체를 바꾸는 변경**이므로
테스트 작성보다 먼저 끝나야 한다. 순서를 뒤집으면 곧 바뀔 포맷·시그니처를 대상으로
테스트를 쓰게 된다.

---

## 5-7. 마무리 — 테스트 + 문서화 마감 (✅ 확정 / 2026-08-10 개정)

**선행 조건: 5-6-C 3건 완료** ✅. 특히 ③(`AdminController`)이 없으면 아래 A가
`hasRole('ADMIN')` 분기를 덮지 못해 두 갈래짜리 테스트가 되고, 나중에 세 번째 갈래를
붙이면서 테스트 구조를 다시 손대야 한다.

**진행 순서: A → B → C → D.**

| | 작업 | 성격 | 상태 |
|---|---|---|---|
| **A** | 화이트리스트 대조 회귀 테스트 | 전수·횡단 | ✅ |
| **B** | `/v3/api-docs` 스모크 체크 | 설계 검증 (10분) | ✅ |
| **C** | 통합 테스트 — **C-0 횡단 / C-1 `@Valid` / C-2 viewer 플래그 / C-3 접근 제어** | `@SpringBootTest` + MockMvc | ✅ |
| **D** | 문서화 마감 | | ✅ |

---

### A. 화이트리스트 대조 회귀 테스트 — **최우선**

**5-7의 핵심 산출물이다.** `RequestMappingHandlerMapping`에서 등록된 전체 엔드포인트를
뽑아 `SecurityConfig`의 공개 경로 상수와 대조한다.

- 검증 내용: **화이트리스트에 없는 경로가 인증 없이 200을 반환하지 않는다.**
  `permitAll` / `authenticated` / `hasRole('ADMIN')` **세 갈래를 모두** 덮는다.
- `RANDOM_PORT`로 실제 톰캣을 띄운다. S-4의 `SecurityErrorDispatchTest`가 이미 그 방식이며,
  MockMvc는 컨테이너의 ERROR 디스패치를 재현하지 못한다.

**왜 이것부터인가** — Step5에서 실제로 터진 버그 3건이 전부 같은 유형이었다.

| 건 | 공통점 |
|---|---|
| `WatchRecord.isRepresentative` (5-3) | 컴파일·기동 통과, 그 경로를 실제로 호출할 때까지 숨음 |
| `MissingServletRequestParameterException` (5-6) | 5-2~5-5에 필수 쿼리 파라미터 사례가 없어 우연히 안 걸림 |
| 화이트리스트 Ant 패턴 2건 (5-0) | 기능 테스트를 전부 통과하면서 보호만 빠짐 |

셋 다 **"그 경로를 밟아야만 드러나는" 실패**이고, A는 그 층을 전수로 훑는 유일한 테스트다.
**A가 구멍을 알려준 뒤에 C를 써야** 어디에 깊이를 투자할지 정해진다. 순서를 뒤집으면
테스트를 다 써놓고 나서 경로 설정이 틀린 것을 알게 된다.

### B. `/v3/api-docs` 스모크 체크 — C보다 먼저

`PageResponse<T>` 제네릭이 OpenAPI 스키마로 제대로 렌더링되는지 **눈으로 한 번 확인**한다.
(`PageResponse<UserMovieListItemResponse>`가 별도 스키마로 잡히는지, `content` 배열의
아이템 타입이 살아 있는지)

- 문서 마감의 일부가 아니라 **설계 검증**이다. 여기서 깨지면 5-0-D의 자체 DTO 결정이
  Springdoc과 충돌한다는 뜻이므로 D의 문제가 아니라 **5-7 계획 자체가 달라진다.**
- D로 미루면 `@Operation` 40여 개를 다 단 뒤에 발견하게 된다. 지금은 10분이면 끝난다.

### C. 통합 테스트 — **4개 그룹으로 분류** (2026-08-10 재분류 / 2026-08-11 재개정)

> **⚠️ 개정 이력 2회.**
> - **초판** — *"도메인별 MockMvc 슬라이스로 4종"* 이라고만 적어 4 × 11 = 44개로 읽혔다.
>   4종은 성격이 균질하지 않아 그대로 곱하면 대부분이 복붙이 된다.
> - **2026-08-11 (5-7-C 진행 중)** — 재분류판의 *"viewer 의존 플래그 — Follow · Comment ·
>   Review · Collection · WatchRecord · Wish"* 가 **서로 다른 두 가지를 한 칸에 묶은 것**임이
>   드러났다. "viewer 플래그를 응답에 담는 도메인"과 "viewer 기준 접근 제어를 받는 도메인"은
>   겹치지 않는데 후자 목록을 적어놨다. 아래 **C-1과 C-2로 분리**한다.

| 그룹 | 성격 | 작성 범위 |
|---|---|---|
| — | 미인증 → 401 `UNAUTHORIZED` | **A가 전수로 덮으므로 별도 작성하지 않는다** |
| **C-0** | 404/405/415 → `ErrorResponse` 포맷 (5-0-C 회귀) | **횡단, 총 2~3개.** 컨트롤러마다 쓰지 않는다 |
| **C-1** | `@Valid` 위반 → 400 `INVALID_INPUT_VALUE` + `errors[]` | **도메인별** — User · WatchRecord · Review · Collection · Comment |
| **C-2** | **viewer 의존 플래그** → 비로그인 시 전부 `false` | **엔드포인트 3개만** (아래) |
| **C-3** | **공개범위 접근 제어** (`UserAccessPolicy`) | **호출부 9개 지점** (아래) |

#### C-2. viewer 의존 플래그 — 대상은 3개뿐

응답 DTO에 viewer 기준 계산값을 담는 것은 아래가 전부다.

| DTO | 필드 | 엔드포인트 |
|---|---|---|
| `FollowUserResponse` | `following` | `GET /api/users/{userId}/followers`, `/followings` |
| `UserProfileResponse` | `following`, `me` | `GET /api/users/{userId}/profile` |
| `CommentResponse` | `editable`, `deletable` | `GET /api/comments` |

- `WishToggleResponse.wished`도 호출자 의존이지만 `/api/wishes/me/{movieId}`가 **인증 전용**이라
  "비로그인 → false" 케이스 자체가 성립하지 않는다. 제외한다.
- `Review`/`Collection`/`WatchRecord`/`Wish`는 `viewerId`를 **응답 필드가 아니라 조회 가능 여부
  판정에만** 쓴다(4-6-E 확정). 따라서 C-2가 아니라 **C-3** 소관이다.

#### C-3. 공개범위 접근 제어 — **삭제가 아니라 재분류**

C-2에서 빠진 도메인들을 목록에서 지우면 안 된다. `UserAccessPolicy` 호출부는 아래 9개이고,
**이것이 4-6-E 소급 작업의 산출물 전부**다. C-2만 남기면 이 중 3개(Follow 2 + Comment 1)만
우연히 덮이고 **나머지 6개는 검증 없이 남는다.** 하필 그 6개가 "비공개 계정의 시청기록·
위시리스트·컬렉션이 남에게 보이는가"라는, 틀렸을 때 가장 치명적인 속성이다.

| Service | 호출부 | 단언 |
|---|---|---|
| `CollectionService` | `getCollections`, `getCollectionMovies` | `PRIVATE` → **403 `ACCESS_DENIED`** |
| `CommentService` | `createComment`, `getComments` | 동일 |
| `FollowService` | `getFollowers`, `getFollowings` | 동일 |
| `WatchRecordService` | `getUserMovieList`, `getWatchLog` | 동일 |
| `WishMovieService` | `getUserWishList` | 동일 |
| `ReviewService` | `getMovieReviews` (`filterViewable`) | **⚠️ 403이 아님 — 아래 참고** |

**판정 조합** — 각 지점에 대해 `PUBLIC` → 200 / `PRIVATE` → 403 / `FRIENDS` → **맞팔일 때만** 200.

- **`FRIENDS`는 단방향 팔로우만 있을 때 거부되는지까지 봐야 한다.** 4-6에서 `FRIENDS` =
  상호 팔로우로 확정했으므로, 이 케이스가 빠지면 `FRIENDS`가 사실상 `PUBLIC`으로 동작해도
  아무도 모른다.
- **`getMovieReviews`만 단언이 다르다.** 유일하게 403이 아니라 **목록에서 조용히 빠지는**
  방식(`filterViewable` 벌크 판정 후 필터링)이다. 따라서 "비공개 작성자의 리뷰가 `content`에
  없다"로 검증한다. 4-6-E에 `totalElements` 부정확 한계로 문서화해둔 그 지점이다.

#### 총량

Movie · Theater · BoxOffice는 요청 바디도 viewer 의존 필드도 접근 제어도 없어 C-1~C-3 모두
해당 없음이다(A로 충분). 전체는 **44가 아니라 25개 안팎**이며 전부 서로 다른 내용을 검증한다.

#### ⚠️ 실행 방식 — `@SpringBootTest` + `MockMvc` (2026-08-11 확정)

**`RANDOM_PORT`는 C에 쓰지 않는다.** 톰캣이 별도 스레드·트랜잭션·커넥션으로 요청을 처리하므로
**테스트 메서드의 `@Transactional` 롤백이 닿지 않는다.** C-2·C-3는 팔로우 관계·댓글·비공개
계정 같은 **실제 데이터가 DB에 있어야** 의미가 있는데, 롤백이 안 되면 커밋 후 정리 방식으로
가야 하고 그건 테스트가 중간에 실패할 때 잔여 행이 남아 다음 실행을 오염시킨다
(`uk_follow` 등 유니크 제약이 걸린 도메인이라 특히 잘 터진다).

```java
@SpringBootTest                 // 슬라이스가 아니므로 실제 SecurityConfig·JwtAuthenticationFilter 로드
@AutoConfigureMockMvc
@Transactional                  // MockMvc는 같은 스레드에서 실행 → 롤백이 실제로 적용된다
class CollectionAccessControlTest { … }
```

**⚠️ 선행 조건 — Boot 4에서는 테스트 스타터를 따로 넣어야 한다 (2026-08-11 확인)**

Boot 4 모듈화로 **`@SpringBootTest`만으로는 MockMvc 지원이 제공되지 않는다.**
`@AutoConfigureMockMvc`가 `spring-boot-starter-test`에서 분리됐다.

```gradle
testImplementation 'org.springframework.boot:spring-boot-starter-webmvc-test'
```

- **하위 모듈(`spring-boot-webmvc-test`)이 아니라 `starter-`가 붙은 쪽을 쓴다.** Boot 4의
  명명 규칙은 "모듈 = 자동 구성 코드만 / 스타터 = 모듈 + 필요한 전이 의존"이다. 모듈만 넣으면
  애노테이션이 해석돼 **컴파일은 통과하지만** 지원 기능 일부가 빠진 채 `starter-test`에 우연히
  기대는 상태가 된다. 공식 마이그레이션 가이드도 *"각 메인 스타터마다 test counterpart를
  추가하라"* 로 **스타터**를 지목한다.
- **`spring-boot-autoconfigure-classic` / `spring-boot-starter-test-classic`은 쓰지 않는다.**
  Boot 3 동작을 통째로 복원하는 마이그레이션 탈출구라 모듈화의 이점을 전부 잃는다.
  필요한 모듈만 짚어 넣는다.
- 슬라이스를 쓰지 않으므로 `spring-boot-starter-data-jpa-test` 등 다른 테스트 스타터는
  현재 불필요하다(기존 테스트에 `@DataJpaTest`·`@WebMvcTest` 사용처가 없음).

| | `@WebMvcTest` | **`@SpringBootTest` + MockMvc** | `RANDOM_PORT` |
|---|---|---|---|
| 우리 `SecurityConfig` 로드 | ❌ 별도 `@Import` 필요 | ✅ | ✅ |
| `@Transactional` 롤백 | ✅ | ✅ | ❌ |
| 컨테이너 ERROR 디스패치 | ❌ | ❌ | ✅ |

- **`@WebMvcTest` 슬라이스는 쓰지 않는다.** 우리 `SecurityConfig`를 자동 로드하지 않아
  Boot 기본 체인이 뜨고, `JwtAuthenticationFilter`의 협력 객체도 따로 채워야 한다.
  그 상태에서는 인증 테스트가 **통과하면서 아무것도 검증하지 않는다.**
  > 재분류판이 이 경고를 *"MockMvc를 쓰지 말라"* 로 읽히게 적었으나, 문제는 **슬라이스**이지
  > MockMvc가 아니다. 전체 컨텍스트 위의 MockMvc는 필터체인이 그대로 돌아 인증이 실제로 성립한다.
- 토큰은 `JwtTokenProvider` 빈을 주입받아 테스트 안에서 실제로 발급해 `Authorization` 헤더에 넣는다.
- **`RANDOM_PORT`는 A(화이트리스트)와 C-0(404/405 포맷)에만 남긴다.** 그 둘만 컨테이너 ERROR
  디스패치가 필요하다(S-4에서 확인된 사항, `SecurityErrorDispatchTest` 방식 재사용).

### D. 문서화 마감 (✅ 완료 / 2026-08-11)

- `/v3/api-docs` 산출물로 `openapi-typescript`(또는 `orval`) TS 타입 생성이 실제로 되는지 확인.
  Springdoc 도입의 1순위 실익이므로 여기서 검증하지 않으면 도입 이유가 사라진다.
  (B에서 스키마 형태는 이미 확인했으므로 여기서는 생성 파이프라인만 본다)
  - `npx openapi-typescript http://localhost:8080/v3/api-docs` 정상 생성 확인(0.8~0.9초, 에러 없음).
  - **🐛 생성 결과를 열어보다가 발견 — `@AuthUser` 파라미터가 공개 쿼리 파라미터로 새고 있었다.**
    `viewerId`/`authorId`/`followerId`뿐 아니라 쓰기 엔드포인트에서 `@AuthUser`를 `userId`로
    받은 경우까지 합쳐 **15개 이상의 오퍼레이션**에서, 클라이언트가 절대 채워선 안 되는 인증 주체
    값이 TS 타입상 필수/선택 쿼리 파라미터로 노출되고 있었다(예: `getWatchLog`의
    `query: { viewerId: number }`). Springdoc이 `@AuthUser`를 커스텀 인증 리졸버로 인식하지 못하고
    일반 파라미터로 스캔한 것이 원인. `OpenApiConfig`에 `SpringDocUtils.getConfig()
    .addAnnotationsToIgnore(AuthUser.class)`를 static 블록으로 추가해 해결 — 등록이 컨텍스트
    초기화보다 먼저 반영돼야 해서 `@PostConstruct`가 아니라 static 블록을 썼다. 수정 후 재생성해
    해당 파라미터가 전부 사라지고(`query?: never`), `@PathVariable userId` 같은 정상 경로
    변수는 그대로 남아 있음을 확인(부수피해 없음).
- 운영 프로파일에서 Swagger UI가 실제로 닫히는지 확인 → `security-spec.md` S-11
  *"배포 전 반드시 처리할 것"* 항목과 대조.
  - **신규 `application-prod.yml` 작성**(`springdoc.api-docs.enabled=false`,
    `springdoc.swagger-ui.enabled=false`) — L-12가 지적한 대로 운영 프로파일 파일 자체가
    없던 상태였다.
  - `SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun`로 실기동 확인: 로그에 `"secret", "prod"`
    두 프로파일이 활성화됐고 springdoc 자동배선 경고 로그 자체가 안 찍힘(설정만 숨긴 게 아니라
    자동구성 자체가 꺼짐), `GET /v3/api-docs`·`GET /swagger-ui/index.html` 둘 다 404,
    일반 API(`GET /api/movies`)는 그대로 200 — 화이트리스트가 아니라 엔드포인트 자체가 꺼졌음을
    구분해서 확인했다. `security-spec.md` L-12 완료 처리.
- 각 도메인 작성 시 붙인 `@Operation` 누락분 점검. **여기서 몰아서 달지 않는다**(5-0-G).
  - 컨트롤러별 매핑 수 대 `@Operation` 수를 기계적으로 대조 — Step5에서 작성한 11개 도메인
    컨트롤러 전부 1:1로 일치, 누락 없음. `AuthController`(9개 매핑, `@Operation` 0개)만 예외인데
    Step S(Springdoc 도입 이전)에 작성돼 **이 문서 범위 밖으로 이미 명시**돼 있어(문서 서두 참고)
    이번에 손대지 않았다.

---

## ErrorCode 추가분

| 상수 | HTTP | 용도 |
|---|---|---|
| `INVALID_INPUT_VALUE` | 400 | `@Valid` 검증 실패 |
| `INVALID_TYPE_VALUE` | 400 | 경로/쿼리 변수 타입 변환 실패 (enum 포함) |
| `MALFORMED_REQUEST_BODY` | 400 | 요청 바디 JSON 파싱 실패 (enum 포함) |
| `METHOD_NOT_ALLOWED` | 405 | 지원하지 않는 HTTP 메서드 |
| `ENDPOINT_NOT_FOUND` | 404 | 존재하지 않는 경로 |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | **5-6-C ①** — `Content-Type` 누락·불일치 |
| `NOT_ACCEPTABLE` | 406 | **5-6-C ①** — `Accept` 불일치 |

> `INVALID_AUTH_METHOD`(4-1), `INVALID_CREDENTIALS`(S-6), `UNAUTHORIZED`/`ACCESS_DENIED`(4-6),
> `DUPLICATE_REQUEST`(4-6)는 기존 상수 재사용.

---

## 잔여 확인 항목

| # | 항목 | 처리 시점 |
|---|---|---|
| 1 | ~~화이트리스트 수정 2건 — `GET /api/users/*/records/**`, `GET /api/collections/*/movies` 추가~~ | ✅ **완료** (5-0) |
| 2 | ~~`springdoc` `3.0.3`의 Boot 4 실동작 확인~~ | ✅ **완료** (5-0) |
| 3 | **`searchMovies` 엔드포인트 노출 — `MovieSearchCondition` 설계 확정 후.** tmdb-sync **D-2 ③ 확정(2026-08-13)** 으로 보류 사유가 바뀌었다: 검색이 **DB + TMDB 병합**이 되어 `movieId` nullable + `tmdbId` 병기, `totalElements` 불성립(`PageResponse` 규약 예외), TMDB 장애 시 DB 결과만 응답 등 **응답 계약을 먼저 확정**해야 한다 | **온디맨드 경로 착수 전 (필수)** — 더 이상 선택 사항이 아니다 |
| 4 | 컬렉션 **단건 조회** Service 메서드 부재 — 딥링크 요구 확인 후 추가 | 프론트 라우팅 확정 시 |
| 5 | `TheaterSeedService` 입력 방식(멀티파트 vs 서버 파일) + **좌표계 확인** → `POST /api/admin/theaters/seed` 보류 중 (5-6-B ②) | 4-7 잔여 항목과 함께 |
| 6 | ~~`MyMovieListItemResponse` → `UserMovieListItemResponse` 리네임~~ | ✅ **완료** (5-3) |
| 7 | 알림 도메인 Controller — 도메인 설계 자체가 미착수 | 알림 설계 세션 이후 |
| 8 | **`Notification.isRead` → `read` 필드명 정정** — 5-3에서 발견한 `isRepresentative`와 동일한 버그가 잠재. 리포지토리·쿼리가 아직 없는 지금이 무비용 시점 | **알림 도메인 착수 전 (필수)** |
| 9 | **`spring-boot-starter-web` → `spring-boot-starter-webmvc` 리네임** — Boot 4 모듈화로 MVC 스타터 이름이 바뀌었다(공식 마이그레이션 표). 4.0.5에서 현재 이름으로도 기동·실서버 검증이 통과하고 있어 **급하지 않지만**, 전이 의존 구성이 달라질 수 있어 검증 가능한 시점에 교체한다 | Step5 종료 후 |
| 10 | **`GET /api/movies/{id}/cast` 신설 + 상세 응답의 cast 제한** — tmdb-sync **D-1 확정(2026-08-13)** 으로 cast를 자르지 않고 전량 저장한다. 그대로 두면 `getMovieDetail`이 영화당 최대 수백 행(+`Person` 조인)을 응답에 싣는다. 상세는 `displayOrder <= 20`으로 제한하고 전체 출연진은 페이징 엔드포인트로 분리한다. `MovieDetailResponse`에 `hasMoreCast` 등 더보기 신호가 필요한지 함께 확정할 것 | **Step6 `syncCast` 구현과 동시** (실데이터가 들어오기 전에는 증상이 없다) |
| 11 | **`POST /api/movies/sync` 신설** (온디맨드 진입점) — 검색 결과에서 미등록 영화를 고르면 `syncFromTmdb(tmdbId)` 후 `movieId`를 반환한다. ⚠️ **`syncFromTmdb`의 반환 타입이 `Movie` 엔티티**이므로 컨트롤러가 그대로 내보내면 CLAUDE.md의 "Entity 직접 노출 금지" 위반이다 — `movieId`만 뽑아 응답 DTO로 변환할 것. 또 이 경로는 `existsByTmdbId` **사전 필터를 하지 않는다**(사용자가 명시 요청한 것이므로 최신화가 맞다 — 시드와 계약이 다르다, 6-4). **인증 필수**로 두어야 한다. 미인증 공개 경로면 임의 `tmdbId`로 우리 DB를 채우는 통로가 된다 → 5-0 화이트리스트와 5-7 A 회귀 테스트에 함께 반영 | 온디맨드 경로 구현 시 |
| 12 | **`POST /api/admin/movies/seed/box-office` · `/seed/discover` 신설** — D-2 ①② 확정. `AdminController`에 추가하며 `TheaterSeedService` 시드 패턴을 따른다. 2개로 분리하는 이유는 실패 양상과 결과 DTO가 다르기 때문(역방향=제목 매칭 `skipped` 집계 / discover=페이지 순회) | Step6 시드 구현 시 |

---

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-08-13 | **D-2 확정에 따른 잔여 3건 갱신·등록 (#3 성격 변경 / #11 · #12 신규).** ① **잔여 #3(`searchMovies` 노출)의 보류 사유가 바뀌었다** — 기존에는 *"`MovieSearchCondition`이 없어 `getMovieList`와 동작이 동일해진다"* 였으나, D-2 ③으로 검색이 **DB + TMDB 병합**이 되면서 이제는 **응답 계약 자체가 미확정**인 것이 사유다. 미등록 항목이 섞여 `movieId`가 nullable이 되고, 병합이라 전체 건수를 알 수 없어 **5-0에서 정한 `PageResponse` 규약을 이 엔드포인트만 벗어나야 할 수 있다**(`Slice` 등). 성격도 "검색 설계 세션"에서 **"온디맨드 경로 착수 전 필수"** 로 승격 — 온디맨드는 검색 없이 성립하지 않는다. ② **#11 `POST /api/movies/sync`** — 온디맨드 진입점. **인증 필수**로 못 박았다. 미인증 공개 경로면 임의 `tmdbId`로 우리 DB를 채우는 통로가 되므로 5-0 화이트리스트와 5-7 A 회귀 테스트에 함께 반영해야 한다. ③ **#12 시드 엔드포인트 2종** — `/seed/box-office`와 `/seed/discover`를 합치지 않은 이유는 실패 양상과 결과 DTO가 달라서다(역방향은 제목 매칭 실패가 정상 범주라 `skipped` 집계, discover는 페이지 순회라 이어받기 지점이 다르다) |
| 2026-08-13 | **잔여 #10 등록 — 영화 상세의 cast 응답 분리.** tmdb-sync **D-1 확정**으로 cast를 상위 20명에서 자르지 않고 전량 저장하기로 했다(컷은 사용자의 출연진 정보 확인을 제약한다). 저장 측 오염은 `EXTRA(0.0)` tier로 닫았으나, **응답 측은 닫히지 않는다** — `getMovieDetail`이 `movie_actor` 전량을 `Person` 조인과 함께 그대로 내려보내므로 출연진 200명 영화의 상세 응답이 수백 행이 된다. 상세는 `displayOrder <= 20`으로 제한하고 전체 출연진은 `GET /api/movies/{id}/cast`(페이징)로 분리한다. **Step5 잔여가 아니라 Step6 `syncCast` 구현과 동시에 처리한다** — 실데이터가 들어오기 전에는 현재 코드로도 아무 증상이 없어 검증할 대상 자체가 없다 |
| 2026-08-11 | **5-7-C 구현 중 조정 — Boot 4 테스트 스타터 선행 조건 명시.** `@AutoConfigureMockMvc`가 `spring-boot-starter-test`에 없어 테스트가 뜨지 않는 것을 발견. Boot 4 **모듈화**로 MockMvc 테스트 지원이 분리됐고, **`@SpringBootTest`만으로는 MockMvc가 더 이상 제공되지 않는다**(공식 마이그레이션 가이드 확정 사항). 좌표를 **`spring-boot-starter-webmvc-test`** 로 확정 — 처음 프로브로 찾은 하위 모듈 `spring-boot-webmvc-test`는 애노테이션이 해석돼 컴파일은 통과하지만, Boot 4 명명 규칙상 **모듈 = 자동 구성 코드만 / 스타터 = 모듈 + 전이 의존**이라 지원 기능 일부가 빠진 채 `starter-test`에 우연히 기대는 상태가 된다. 가이드도 *"각 메인 스타터마다 test counterpart 추가"* 로 스타터를 지목한다. `autoconfigure-classic`/`starter-test-classic`(Boot 3 동작 통째 복원)은 모듈화 이점을 잃으므로 채택하지 않는다. 슬라이스를 쓰지 않으므로 `starter-data-jpa-test` 등은 불필요(기존 테스트에 `@DataJpaTest`·`@WebMvcTest` 사용처 없음). 부수 발견으로 **`spring-boot-starter-web` → `spring-boot-starter-webmvc` 리네임을 잔여 #9로 등록**(4.0.5에서 현재 이름으로도 동작 중이라 Step5 종료 후 처리) |
| 2026-08-11 | **5-7 D(문서화 마감) 완료 — Step5(Controller 계층) 전체 완료.** ① `openapi-typescript` 생성 파이프라인 정상 확인(0.8~0.9초, 에러 없음). ② **🐛 생성 결과를 직접 열어보다가 발견 — `@AuthUser`가 공개 쿼리 파라미터로 새고 있었다.** Springdoc이 커스텀 인증 리졸버를 인식 못 해 `viewerId`/`authorId`/`followerId`뿐 아니라 쓰기 엔드포인트의 `@AuthUser userId`까지 포함해 **15개 이상 오퍼레이션**에서 "클라이언트가 절대 채워선 안 되는 값"이 필수/선택 쿼리 파라미터로 노출되고 있었다. `OpenApiConfig`에 `SpringDocUtils.getConfig().addAnnotationsToIgnore(AuthUser.class)`를 static 블록으로 추가해 해결(컨텍스트 초기화보다 먼저 반영돼야 해서 `@PostConstruct`가 아니라 static). 재생성 후 해당 파라미터 전부 사라짐, `@PathVariable` 경로 변수는 정상 유지 확인 — B(스모크 체크)가 스키마 형태만 봤지 파라미터 노출까지는 못 잡는 한계가 있었다는 뜻이기도 하다. ③ **신규 `application-prod.yml`** — `security-spec.md` L-12가 지적한 대로 운영 프로파일 파일 자체가 없었다. `springdoc.api-docs.enabled=false`/`springdoc.swagger-ui.enabled=false` 추가 후 `SPRING_PROFILES_ACTIVE=prod`로 실기동해 `/v3/api-docs`·`/swagger-ui/index.html` 둘 다 404, 일반 API는 200, springdoc 자동배선 경고 로그 자체가 안 찍힘(자동구성 자체가 꺼짐)을 확인 — `security-spec.md` L-12 완료 처리. ④ **`@Operation` 누락분 점검** — 컨트롤러별 매핑 수 대 `@Operation` 수 기계적 대조, Step5에서 작성한 11개 도메인 컨트롤러 전부 1:1 일치. `AuthController`(매핑 9·`@Operation` 0)만 예외지만 Step S(Springdoc 이전) 작성물로 이 문서 범위 밖이라 원문 그대로 둠. **검증** — `./gradlew compileJava`/`test`(전체 121건) 통과 |
| 2026-08-11 | **5-7 C(C-0~C-3) 구현 완료 — 총 25개 테스트, 4개 신규 파일.** ⚠️ **`build.gradle` 수정 필요 발견** — Boot 4.0.5에서 `@AutoConfigureMockMvc`가 `spring-boot-starter-test`의 전이 의존에 없다. Boot 4가 MockMvc 테스트 지원을 `org.springframework.boot:spring-boot-webmvc-test`라는 **별도 모듈로 분리**했고(패키지도 `org.springframework.boot.test.autoconfigure.web.servlet`에서 `org.springframework.boot.webmvc.test.autoconfigure`로 이동), Maven Central 검색 인덱스에도 아직 안 잡혀 좌표를 직접 프로브해서 찾았다(`testImplementation 'org.springframework.boot:spring-boot-webmvc-test'` 추가). **C-0**(`ErrorResponseFormatTest`, `global/exception`, `RANDOM_PORT` 3종) — 404/405/415가 상태 코드뿐 아니라 `ErrorResponse` 바디(`status`/`code`/`errors`)까지 맞는지 확인, 5-6-C ①의 `ResponseEntityExceptionHandler` 전환이 실제로 동작함을 처음 실측 검증. **C-1**(`RequestValidationTest`, `global/exception`, `@SpringBootTest`+MockMvc+`@Transactional` 6종) — User·WatchRecord·Review·Collection·Comment 5개 도메인의 `@Valid` 위반이 `INVALID_INPUT_VALUE`+`errors[]`로 나가는지 확인, **필터체인이 실제로 도는지 검증하는 카나리아 테스트**(토큰 없이 호출 시 401)를 추가해 "인증이 통과하는 척하며 의미 없이 초록불" 상태를 배제. **C-2**(`ViewerFlagTest`, `global/access`, 3종) — `FollowUserResponse.following`/`UserProfileResponse.following·me`/`CommentResponse.editable·deletable`이 비로그인 조회에서 `false`인지 실제 팔로우 관계·댓글 행을 커밋해(트랜잭션 롤백으로 정리) 확인. **C-3**(`AccessControlTest`, `global/access`, 13종) — `UserAccessPolicy` 호출부 9개(`PRIVATE`→403) + `FRIENDS` 단방향/맞팔 구분(대표: 컬렉션 목록) + `PUBLIC`→200(대표: 시청기록) + `getMovieReviews`의 예외 케이스(403이 아니라 비공개 작성자 리뷰만 `content`에서 조용히 제외)까지 전부 통과. 계획 총량(25개 안팎)과 실제 구현(3+6+3+13=25)이 정확히 일치. **검증** — `./gradlew test` 전체 121건(기존 93 + A 3 + B 없음 + C-0 3 + C-1 6 + C-2 3 + C-3 13 = 121) 전부 통과 |
| 2026-08-11 | **5-7-C 재개정 — 4개 그룹(C-0~C-3)으로 분리.** 5-7-C 진행 중, 재분류판의 *"viewer 의존 플래그 — Follow·Comment·Review·Collection·WatchRecord·Wish"* 가 **서로 다른 두 가지를 한 칸에 묶은 것**임이 드러났다("viewer 플래그를 응답에 담는 도메인" ≠ "viewer 기준 접근 제어를 받는 도메인"인데 후자 목록을 적어놨음). ① **C-2(viewer 플래그)는 엔드포인트 3개뿐** — 응답 DTO에 viewer 계산값을 담는 것은 `FollowUserResponse.following` / `UserProfileResponse.following·me` / `CommentResponse.editable·deletable`이 전부다. `WishToggleResponse.wished`는 인증 전용 경로라 "비로그인 → false" 케이스가 성립하지 않아 제외. ② **그러나 나머지 4개 도메인을 목록에서 삭제하지 않고 C-3(공개범위 접근 제어)으로 재분류** — `UserAccessPolicy` 호출부 9개가 **4-6-E 소급 작업의 산출물 전부**이고, C-2만 남기면 그중 3개만 우연히 덮이고 **6개가 검증 없이 남는다.** 단언은 `PRIVATE`→403 / `PUBLIC`→200 / `FRIENDS`→맞팔일 때만 200이며, **단방향 팔로우 거부 케이스가 빠지면 `FRIENDS`가 사실상 `PUBLIC`으로 동작해도 드러나지 않는다.** `getMovieReviews`만 403이 아니라 목록에서 빠지는 방식이라 단언이 다르다. 총량 15 → **25개 안팎.** ③ **실행 방식을 `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Transactional`로 확정** — `RANDOM_PORT`는 톰캣이 별도 스레드·트랜잭션으로 처리해 **롤백이 닿지 않아**, 실제 데이터가 필요한 C-2·C-3가 커밋 후 정리 방식으로 밀리고 실패 시 잔여 행이 다음 실행을 오염시킨다. 재분류판의 `@WebMvcTest` 경고가 *"MockMvc를 쓰지 말라"* 로 읽히게 적혀 있었으나 **문제는 슬라이스이지 MockMvc가 아니다** — 전체 컨텍스트 위의 MockMvc는 필터체인이 그대로 돈다. `RANDOM_PORT`는 A와 C-0에만 남긴다 |
| 2026-08-11 | **5-7 B(`/v3/api-docs` 스모크 체크) 완료.** `bootRun`으로 실서버를 띄워 `/v3/api-docs`를 직접 받아 확인. **`PageResponse<T>` 제네릭이 타입별로 별도 스키마로 잡힌다** — `PageResponseUserMovieListItemResponse`·`PageResponseCollectionResponse`·`PageResponseCommentResponse` 등 8종이 각각 독립 스키마로 생성됐고, 각 `content` 필드는 `{"type":"array","items":{"$ref":".../UserMovieListItemResponse"}}` 형태로 **아이템 타입을 그대로 유지**한다(타입 소거 없음). `Page`/`PagedModel`/`PageImpl` 이름의 스키마는 하나도 없어 `VIA_DTO` 안전망이 발동한 흔적도 없다(정상 경로에서만 자체 `PageResponse`를 씀). 문서화된 경로 41개, 5-0-D의 설계 결정이 Springdoc과 충돌 없이 동작함을 확인 — D(문서화 마감)로 넘어가도 되는 상태. 확인 후 `bootRun` 프로세스는 정리(kill)함 |
| 2026-08-11 | **5-7 A(화이트리스트 대조 회귀 테스트) 구현 완료** — `WhitelistRegressionTest`(`global/security`, `RANDOM_PORT`). `RequestMappingHandlerMapping`에서 전체 (메서드, 경로)를 뽑아 `SecurityConfig.PUBLIC_GET_ENDPOINTS`/`PUBLIC_POST_ENDPOINTS`(패키지 접근으로 열어 단일 출처 유지)와 대조. **매칭 엔진**은 `AntPathMatcher`가 아니라 `PathPatternParser`+`PathContainer`를 썼다 — Spring Security 6+가 MVC 환경에서 `requestMatchers(String...)`에 실제로 쓰는 것과 같은 엔진이라 오탐/누락 위험이 없다. 경로 변수(`{userId}` 등)는 더미값 `1`로 치환한 뒤 그 **구체 경로**를 화이트리스트와 대조한다(패턴끼리 비교하지 않음). 테스트 3종: ① 화이트리스트 밖 경로는 미인증 200 금지(핵심 산출물, `/api/admin/**` 포함이라 authenticated·hasRole 두 갈래를 함께 덮음) ② 화이트리스트 GET은 미인증이어도 401 아님(반대쪽 회귀) ③ `/api/admin/**`는 일반 유저 토큰으로 403(hasRole('ADMIN') 갈래를 명시적으로 고정 — ①만으로는 "인증됐지만 ADMIN 아님"을 구분 못함). **부수효과 없음을 설계로 보장** — ①·③이 실제로 때리는 요청은 전부 인증/인가 단계에서 필터가 차단해 컨트롤러 본문(DB 쓰기·KOFIC 외부 API 호출)에 도달하지 않는다. permitAll POST(회원가입·로그인·재발급 등)는 실제 부수효과가 날 수 있어 ②를 GET만으로 제한 — ADMIN 토큰으로 실제 호출까지 통과시키는 포지티브 테스트는 만들지 않았다(`/api/admin/box-office/sync`가 실제 KOFIC API를 호출하기 때문). hasRole 회귀는 ①(permitAll로 완화되는 경우)과 ③(authenticated로 완화되는 경우) 조합만으로 충분히 잡힌다 |
| 2026-08-10 | **5-6-C 3건 완료 → 5-7 범위 재편(A~D).** ① **A(화이트리스트 대조 회귀 테스트)를 최우선**으로 확정 — Step5에서 실제로 터진 버그 3건(`isRepresentative` / `MissingServletRequestParameter` / 화이트리스트 Ant 패턴)이 전부 **"그 경로를 밟아야만 드러나는" 동일 유형**이었고, A가 그 층을 전수로 훑는 유일한 테스트이기 때문. A가 구멍을 알려준 뒤에 C를 써야 깊이를 투자할 지점이 정해진다. 5-6-C ③(`AdminController`)이 선행돼야 `hasRole('ADMIN')` 분기까지 세 갈래를 덮는다. ② **B(`/v3/api-docs` 스모크 체크)를 C 앞으로 이동** — `PageResponse<T>` 제네릭의 스키마 렌더링 확인은 문서 마감이 아니라 **설계 검증**이라, 깨지면 5-0-D 결정과 Springdoc이 충돌한다는 뜻이고 `@Operation` 40여 개를 단 뒤에 발견하면 늦다. ③ **C를 횡단/도메인별로 재분류** — 초판의 *"도메인별 4종"* 표현이 4×11=44개로 읽혔으나 4종은 성격이 균질하지 않다. 미인증 401은 **A가 전수로 덮으므로 삭제**, 404/405/415 포맷은 **총 2~3개**의 횡단 테스트, `@Valid`와 viewer 플래그만 도메인별(각 5~6개). Movie·Theater·BoxOffice는 요청 바디도 viewer 의존 필드도 없어 해당 없음 → **총량 44 → 15개 안팎.** ④ **`@WebMvcTest` 슬라이스 표현 정정** — 우리 `SecurityConfig`를 자동 로드하지 않아 인증 테스트가 *통과하면서 아무것도 검증하지 않는* 상태가 된다. A와 viewer 플래그 테스트는 `RANDOM_PORT`(`SecurityErrorDispatchTest` 방식 재사용), 순수 DTO 검증만 슬라이스로 |
| 2026-08-10 | **5-6 구현 완료 및 잔여 3건 확정(5-6-C 신설).** 실서버 curl 검증에서 `GET /api/box-office`의 `rankType` 누락이 `MissingServletRequestParameterException`으로 **`ErrorResponse` 포맷을 우회**하는 것을 발견 — 5-0-C가 표준 MVC 예외를 손으로 열거하는 구조라 생긴 구멍이며, 5-2~5-5에 필수 쿼리 파라미터 사례가 없어 우연히 드러나지 않았던 것. ① **5-0-C를 `ResponseEntityExceptionHandler` 상속으로 전면 개정** — 열거 유지보수를 없애 415/406/멀티파트 등 남은 누출까지 한 번에 닫는다(`@Valid` 필드 목록 로직을 오버라이드로 이전하는 것이 유일한 실질 작업). `UNSUPPORTED_MEDIA_TYPE`·`NOT_ACCEPTABLE` ErrorCode 2건 추가. ② **Controller가 설정값을 읽지 않는다는 규칙 신설** — 초판의 `@RequestParam(defaultValue = "${cinemory.…}")`를 철회하고 `required = false` + null 전달로 바꿔 기본값 적용을 Service로 이관. 플레이스홀더가 **요청 시점에 해석돼 키 오타가 첫 호출에서야 500으로 터지는** 문제(5-3 `isRepresentative`와 동일 유형)와, Service의 상한 검증 설정과 출처가 이원화되는 문제 때문. ③ **`AdminController`를 박스오피스 2종/극장 시드로 분리** — `seedAll`은 좌표계 확인이 선행이라 계속 보류하되, 박스오피스는 먼저 뗀다. S-6에서 확정한 *"`AccessDeniedHandler`가 타는 유일한 경로는 일반 유저의 `/api/admin/**` 호출"* 때문에 **`AdminController`가 없으면 5-7이 403 경로를 검증할 수단이 없다.** 잔여 3건은 전부 **5-7 착수 전** 완료 — 테스트가 검증할 대상 자체를 바꾸는 변경이라 순서를 뒤집으면 곧 폐기될 테스트를 쓰게 된다 |
| 2026-08-07 | **5-3 구현 중 조정 4건.** ① **`WatchRecord.representative` 필드명 드리프트 수정** — 구현이 `isRepresentative`로 돼 있어 파생 쿼리 `…AndRepresentativeTrue`가 `UnknownPathException`을 던졌다. 원인은 FIELD 접근이라 **JPA 메타모델 속성은 `isRepresentative`인데 JavaBean 프로퍼티는 `representative`** 로 갈린 것 — Spring Data는 후자로 경로를 해석해 통과시키고 Hibernate가 전자로 조회해 실패한다. 리포지토리 메서드명을 바꾸는 대신 **엔티티 필드를 스펙대로 `representative`로 되돌렸다**(`@Column(name = "is_representative")` 유지). 메서드명을 맞추면 이 호출부만 닫히고 `Sort.by("representative")`·JPQL·`Specification`에 같은 함정이 남기 때문. ② **`WatchRecord.rating` 범위 검증 추가**(0.0~10.0, `Review`와 동일) — 기존에 검증이 아예 없었다. nullable이므로 null은 통과시키며, `@Builder` 대상 생성자 내부에서 호출한다. ③ `MyMovieListItemResponse` → **`UserMovieListItemResponse`** 리네임(잔여 #6). ④ DTO 검증 정정 — `ReviewWriteRequest.rating`에 `@NotNull` 추가(엔티티 not null인데 누락돼 있었음), **`WatchRecordCreateRequest.watchDate`의 `@NotNull`은 철회**(엔티티가 nullable이라 DTO가 더 엄격했던 스펙 오류). 잔여 #1·#2 완료, **#8 신규**(`Notification.isRead`에 ①과 동일한 버그 잠재) |
| 2026-08-07 | Step5 설계 확정. 로드맵 5-0~5-7 확정. **① 페이징은 자체 `PageResponse<T>`**(`PageImpl` 직렬화 비보장 + `getMovieReviews`의 `totalElements` 부정확 한계를 흡수할 여지 확보, `VIA_DTO`는 안전망으로 병행) **② 성공 응답 래퍼 미도입**(204와 구조적 충돌 + 상태코드 체계와 정보 중복) **③ URL 규칙 = 조회는 소유자 스코프 경로 / 쓰기·본인 상태 조회는 리소스 경로 / `me`는 계정 설정 전용**(S-4 화이트리스트와 무수정 정합) **④ Springdoc 도입, 5-0에 배선하고 어노테이션은 도메인 작성 시 동시 부착**. `/api/movies/**` GET permitAll 하위에 본인 상태 조회를 두지 않는 규칙 신설. **화이트리스트 결함 2건 발견** — `/api/users/*/records`가 Ant 패턴상 회차 조회를 매칭하지 못해 공개 조회가 401로 막히고, 4-5에서 공개로 확정한 `GET /api/collections/*/movies`가 화이트리스트에 누락. 비밀번호 변경(A-6 이관분)은 S-J의 `updatePassword`에 현재 비밀번호 검증만 덧붙이는 형태로 5-1에 배치 |
