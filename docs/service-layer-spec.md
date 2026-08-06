# CineMory Repository / Service 계층 설계 스펙 (Step4)

이 문서는 `jpa-entity-spec.md`(엔티티 설계)를 기반으로 Repository/Service 계층을
어떻게 구현할지 정리한 스펙이다. **실제 코드는 Claude Code가 이 문서를 보고 작성**하며,
여기서는 패턴/시그니처/설계 결정만 명시한다.

작업 순서: 도메인별 우선순위대로 진행 (아래 로드맵 참고). 각 도메인은
"선행 인프라 → Repository → DTO → Service → 설계 노트" 순으로 작성한다.

---

## 진행 로드맵

| 순서 | 도메인 | 상태 |
|---|---|---|
| 4-0 | 공통 인프라 (ErrorCode, BusinessException, GlobalExceptionHandler) | ✅ 확정 |
| 4-1 | `User` | ✅ 확정 |
| 4-2 | `Movie` + 참조 엔티티(Genre/Country/Person/OttPlatform) 조회 | ✅ 확정 |
| 4-3 | `WatchRecord` | ✅ 확정 |
| 4-4 | `Review`, `WishMovie` | ✅ 확정 |
| 4-5 | `Collection`, `CollectionMovie` | ✅ 확정 |
| 4-6 | `Follow`, `Comment` + 공개범위 접근 제어 | ✅ 확정 |
| 4-7 | `Theater`, `BoxOfficeRecord` (외부 API 연동 배치 포함) | ✅ 확정 |

---

## 공통 설계 원칙

- **예외 처리**: 도메인별 예외 클래스를 따로 만들지 않고 `BusinessException(ErrorCode)` 단일 구조 사용.
  새 예외 상황이 생기면 `ErrorCode` enum에 상수만 추가한다.
- **트랜잭션**: Service 클래스 레벨 `@Transactional(readOnly = true)` 기본,
  쓰기 메서드에만 개별 `@Transactional` 오버라이드.
- **DTO 매핑**: MapStruct 등 프레임워크 도입하지 않음. Response DTO에
  정적 팩토리 `from(Entity entity)`를 두고 Service가 호출. DTO는 `record`로 통일.
- **Repository**: Spring Data JPA 파생 쿼리(derived query) 우선 사용,
  복잡한 조건은 도메인 진행 시점에 `@Query` 추가.
- **Service 반환값**: Entity를 절대 반환하지 않음. 항상 Response DTO(record) 반환.
  Controller는 Entity를 알지 못해야 한다.
- **영속 엔티티 상태 변경 시 `save()` 재호출 금지**: 트랜잭션 내 조회한 엔티티는
  dirty checking으로 커밋 시점에 자동 반영되므로, 단순 필드 변경 후 `save()`를
  다시 호출하는 관용구를 쓰지 않는다. (신규 생성 시에만 `save()` 호출)

---

## 4-0. 공통 인프라 (✅ 확정)

### 패키지
```
global/exception
 ├─ ErrorCode.java
 ├─ BusinessException.java
 ├─ ErrorResponse.java
 └─ GlobalExceptionHandler.java
```

### ErrorCode
- `enum`, 필드: `HttpStatus status`, `String message`
- 도메인 진행하면서 상수를 계속 추가 (예: `USER_NOT_FOUND`, `DUPLICATE_EMAIL`, `INVALID_AUTH_METHOD`)

### BusinessException
- `RuntimeException` 상속, `ErrorCode` 필드 보유
- 생성자 2종: `BusinessException(ErrorCode)` / `BusinessException(ErrorCode, String customMessage)`

### GlobalExceptionHandler
- `@RestControllerAdvice`
- `@ExceptionHandler(BusinessException.class)` → `ErrorCode.status`로 응답, `ErrorResponse.from(errorCode)` 바디
- `@ExceptionHandler(IllegalArgumentException.class)` → HTTP 400, 예외 메시지를 그대로 응답 본문에 사용
  (4-4에서 결정 — 엔티티 레벨 검증(`Review.validateRating()` 등)이 던지는 예외를 잡기 위함,
  특정 `ErrorCode`에 묶이지 않으므로 `ErrorResponse.of(HttpStatus.BAD_REQUEST, e.getMessage())`
  같은 오버로드가 `ErrorResponse`에 필요 — 엔티티가 이미 의미 있는 메시지를 담고 있다고 가정,
  이후 모든 엔티티 레벨 검증에 공통 적용)
- 이후 `MethodArgumentNotValidException`(`@Valid` 검증 실패) 핸들러 추가 예정

---

## 4-1. User 도메인 (✅ 확정)

### 선행 작업
- ~~Spring Security 설정에서 `PasswordEncoder`(`BCryptPasswordEncoder`) Bean 등록 필요~~
  → **해소됨.** `global/config/PasswordEncoderConfig`가 전담하며, `SecurityConfig`로 옮기지 않는다
  (이미 `UserService`가 참조 중이고 "비밀번호 인코딩"과 "필터체인 설정"은 책임이 다르다)

### Repository — `UserRepository`
| 메서드 | 용도 |
|---|---|
| `existsByEmail(String email)` | 회원가입 시 이메일 중복 체크 |
| `findByEmail(String email)` | 로그인 조회 |
| `findByProviderAndProviderId(String provider, String providerId)` | OAuth 로그인/재가입 조회 |

### DTO
- `SignUpLocalRequest(String email, String rawPassword, String nickname)`
- `UserResponse(Long id, String email, String nickname, String profileImage, PrivacySetting privacySetting)`
  - `from(User user)` 정적 팩토리

### Service — `UserService`
| 메서드 | 트랜잭션 | 로직 요약 |
|---|---|---|
| `signUpLocal(SignUpLocalRequest)` | 쓰기 | 이메일 중복 체크(`DUPLICATE_EMAIL`) → 비밀번호 인코딩 → `User.createLocal()` → 저장 |
| `signUpOAuth(email, nickname, profileImage, provider, providerId)` | 쓰기 | `findByProviderAndProviderId` 있으면 기존 유저 반환(멱등 처리). 없으면 **`existsByEmail` 확인 → true면 `EMAIL_ALREADY_REGISTERED_LOCALLY`(409)**, 아니면 `User.createOAuth()` 후 저장 |
| `login(email, rawPassword)` | 읽기 | **Step S 신규.** `findByEmail` → `passwordEncoder.matches` 검증 후 `User` 반환. 실패 시 전부 `INVALID_CREDENTIALS` |
| `changePassword(userId, currentPassword, newPassword)` | 쓰기 | **Step S 신규.** OAuth 계정이면 `INVALID_AUTH_METHOD`, 현재 비밀번호 불일치면 `INVALID_CREDENTIALS`. 성공 시 **해당 유저의 리프레시 토큰 전체 폐기**(`AuthService`가 조율) |
| `getUser(userId)` | 읽기 | 없으면 `USER_NOT_FOUND` |
| `changeNickname(userId, nickname)` | 쓰기 | 조회 → `user.changeNickname()` → dirty checking으로 반영 |
| `changePrivacySetting(userId, privacySetting)` | 쓰기 | 조회 → `user.changePrivacySetting()` |
| `findUserOrThrow(userId)` (private 헬퍼) | - | 반복되는 "조회 후 없으면 예외" 패턴 공통화 |

### 설계 노트
- OAuth 회원가입은 소셜 로그인 재시도가 흔하므로 존재 시 기존 유저를 반환하는 멱등 구조로 설계.
  컨트롤러에서 사전 존재 체크 로직을 중복시키지 않기 위함.
- **이메일 충돌을 명시적 예외로 응답하는 이유** — `uk_user_email`과 `uk_user_provider`는 서로 독립이라,
  같은 이메일로 로컬 가입한 계정이 있으면 멱등 분기를 타지 않고 INSERT로 진입해
  `DataIntegrityViolationException`(409 `DUPLICATE_REQUEST`)이 나간다. 원인을 알 수 없는 응답이므로
  사전 체크로 전환했다. `login`이 계정 존재 여부를 숨기는 것과 방향이 반대인데, 로그인은
  *공격자가 계정을 탐색*하는 상황이고 여기는 *본인이 자기 계정으로 들어오려는* 상황이기 때문이다.
- **로컬/소셜 계정 통합은 지원하지 않는다.** `chk_user_auth_method`(로컬 XOR OAuth) 제약상
  한 유저가 양쪽을 겸할 수 없어 구조적으로 불가하다. 자동 연동을 시도하지 말 것.
- `login`/`changePassword`가 `UserService`에 있는 이유 — 자격증명 검증은 User 도메인의 책임이고,
  토큰 발급·폐기는 `AuthService`가 조율한다. 두 책임을 한 서비스에 합치지 않는다.
- `findUserOrThrow` 같은 "조회 후 없으면 BusinessException" 패턴은 이후 모든 도메인 Service에서
  반복되므로, 도메인마다 private 헬퍼로 두거나 필요시 공통 유틸로 추출 검토 (Step4 진행하며 판단).

---

## 4-2. Movie + 참조 엔티티 조회 (✅ 확정)

### 선행 작업
- 없음. `MovieSyncService`(TMDB 연동)는 시그니처만 확정하며 실제 구현은 별도의
  TMDB 연동/배치 설계 세션에서 진행 (본 섹션 범위 밖).

### 화면별 조회 요구사항 정리

| 화면 | 필요한 데이터 | 연관관계 포함 여부 |
|---|---|---|
| 영화 상세 (1건) | movie + genre + country + actor(person) + director(person) 전부 | 포함 |
| 내 영화 목록 (M건) | movie + genre + country (배우/감독 제외) | 포함 (벌크) |
| 순수 검색/추천 목록 | movie 컬럼만 | 미포함 |

### N+1 회피 전략

`movie_genre`/`movie_country`/`movie_actor`/`movie_director`는 전부 `movie` 기준
`List` 컬렉션이라 한 쿼리에서 2개 이상을 동시에 fetch join하면
`MultipleBagFetchException` 또는 카테시안 곱이 발생한다. 따라서 관계별로 쿼리를
분리하고 조합은 Service 레이어(메모리)에서 수행한다.

- **상세 조회**: 관계별 개별 쿼리 4개 + movie 1개 = 고정 5쿼리. 각 쿼리는 자신의
  참조 엔티티(`genre`/`country`/`person`)만 `@EntityGraph`로 fetch join.
- **목록 조회(장르/국가 포함)**: 영화 개수(M)만큼 상세 조회를 반복 호출하면
  `5 × M` 쿼리가 되는 진짜 N+1이 되므로 금지. 대신 관계별 `IN`절 벌크 조회로
  페이지당 고정 3쿼리(movie 1 + genre 벌크 1 + country 벌크 1)를 유지하고,
  `movieId`를 키로 그룹핑해 각 movie에 매칭한다.
  - 그룹핑 키 추출(`movieGenre.getMovie().getId()`)은 LAZY 프록시 상태에서도
    식별자만 읽는 연산이라 추가 쿼리를 유발하지 않는다.
- **순수 목록 조회**: 연관관계 없이 movie 컬럼만 projection.

### 참조 테이블(Genre/Country/Person/OttPlatform) Service 미생성
- 사용자에게 직접 노출되는 CRUD API가 없고, `MovieGenreRepository`/`MovieCountryRepository`가
  `@EntityGraph`로 이미 참조 엔티티까지 가져와 `MovieQueryService`에 전달하는 구조이므로,
  별도 Service는 위임만 하는 불필요한 계층이 되어 생성하지 않는다. Repository만 존재.
- `Person`도 동일한 이유로 별도 Service 없이 `MovieActor`/`MovieDirector` 경유로만 노출.
- `OttPlatform`은 `movie`가 아닌 `watch_record` 종속이라 4-3에서 동일 원칙 적용 예정.

### Repository

```java
public interface MovieRepository extends JpaRepository<Movie, Long> {
    Optional<Movie> findByTmdbId(Long tmdbId);
    boolean existsByTmdbId(Long tmdbId);
}

public interface MovieGenreRepository extends JpaRepository<MovieGenre, Long> {
    @EntityGraph(attributePaths = "genre")
    List<MovieGenre> findByMovieId(Long movieId);

    @EntityGraph(attributePaths = "genre")
    List<MovieGenre> findByMovieIdIn(List<Long> movieIds);
}

public interface MovieCountryRepository extends JpaRepository<MovieCountry, Long> {
    @EntityGraph(attributePaths = "country")
    List<MovieCountry> findByMovieId(Long movieId);

    @EntityGraph(attributePaths = "country")
    List<MovieCountry> findByMovieIdIn(List<Long> movieIds);
}

public interface MovieActorRepository extends JpaRepository<MovieActor, Long> {
    @EntityGraph(attributePaths = "person")
    List<MovieActor> findByMovieIdOrderByRoleTierAsc(Long movieId);
}

public interface MovieDirectorRepository extends JpaRepository<MovieDirector, Long> {
    @EntityGraph(attributePaths = "person")
    List<MovieDirector> findByMovieId(Long movieId);

    @EntityGraph(attributePaths = "person")
    List<MovieDirector> findByMovieIdIn(List<Long> movieIds); // 4-5(Collection 상세 목록)에서 필요해져 추가
}

// 참조 테이블 — 커스텀 쿼리 불필요
public interface GenreRepository extends JpaRepository<Genre, Long> {}
public interface CountryRepository extends JpaRepository<Country, Long> {}
public interface PersonRepository extends JpaRepository<Person, Long> {}
```

> `MovieActor`는 여전히 목록(벌크) 조회 대상에서 제외되어 `findByMovieIdIn`을 두지 않는다.
> `MovieDirector`는 4-5(Collection 상세 목록에 감독명 표시)에서 필요해져 벌크 메서드를 추가했다.
> 추후 목록 화면에 출연진 노출 요구가 생기면 그때 `MovieActor`에도 동일하게 추가한다.

### DTO

| DTO | 용도 | 포함 필드 |
|---|---|---|
| `MovieDetailResponse` | 상세 화면 | movie 전체 컬럼 + `List<GenreResponse>` + `List<CountryResponse>` + `List<ActorResponse>` + `List<DirectorResponse>` |
| `MovieListItemResponse` | 장르/국가 표시가 필요한 목록 (예: 내 영화 목록) | movie 요약 컬럼(title/poster_path/release_date 등) + `List<GenreResponse>` + `List<CountryResponse>` |
| `MovieSummaryResponse` | 순수 검색/추천 목록 | movie 요약 컬럼만, 연관관계 없음 |
| `GenreResponse` / `CountryResponse` / `ActorResponse` / `DirectorResponse` | 하위 항목 | 참조 엔티티 최소 필드(id, name 등). `ActorResponse`는 `characterName`, `roleTier` 포함 |

- 전부 `record` + 정적 팩토리 `from(Entity)` (공통 설계 원칙 유지).

### Service — `MovieQueryService`

| 메서드 | 트랜잭션 | 로직 요약 |
|---|---|---|
| `getMovieDetail(movieId)` | 읽기 | movie 조회(없으면 `MOVIE_NOT_FOUND`) → genre/country/actor/director 각각 개별 조회(고정 5쿼리) → `MovieDetailResponse.from(...)` 조합 |
| `getMovieList(pageable)` | 읽기 | movie 페이지 조회 → movieIds 추출 → genre/country를 `findByMovieIdIn`으로 벌크 조회 후 `movieId` 기준 `Map`으로 그룹핑(페이지당 고정 3쿼리) → 각 movie에 매칭해 `MovieListItemResponse` 반환 |
| `searchMovies(pageable)` | 읽기 | movie만 조회, 연관관계 없이 `MovieSummaryResponse::from`으로 매핑 |

### Service — `MovieSyncService` (시그니처만 확정, 구현은 별도 세션)

```java
public interface MovieSyncService {
    Movie syncFromTmdb(Long tmdbId);
    void syncGenres(Movie movie, List<TmdbGenreDto> genres);
    void syncCountries(Movie movie, List<TmdbCountryDto> countries); // weight 공식 (N+1)/(N²+1) 적용 예정
    void syncCast(Movie movie, List<TmdbCastDto> cast); // role_tier 산출 로직 포함 예정
    void syncCrew(Movie movie, List<TmdbCrewDto> crew);
}
```

### 설계 노트
- `getMovieList`(장르/국가 포함 목록)와 `searchMovies`(순수 목록)는 내부적으로
  같은 movie 조회를 쓰더라도 반환 DTO와 부가 조회 유무가 다르므로 하나로 합치지 않고
  메서드를 분리한다.
- 두 메서드 모두 `Pageable`만 받고 `MovieSearchCondition` 등 검색 조건 파라미터는
  구현 시점에 제외했다 — 해당 타입 자체가 아직 설계되지 않았으므로 존재하지 않는
  타입을 시그니처에 미리 넣지 않는다. 검색 조건 설계가 확정되면 그때 오버로드
  추가 또는 파라미터 확장 여부를 판단한다 (기존 호출부 영향 고려).
- **"관계별 IN절 벌크 조회 + Service 그룹핑" 패턴은 이번 도메인만의 해법이 아니라
  향후 모든 "N건 목록 + 연관관계 표시" 화면에 적용할 표준 패턴**이다. 4-3 WatchRecord
  이후 "내 영화" 목록, 4-5 Collection 목록 등에서 매번 새로 설계하지 않고 이 패턴을
  그대로 재사용한다.
- 조회(Read)와 동기화(Write)를 `MovieQueryService`/`MovieSyncService`로 분리한 이유는
  TMDB 연동 배치 로직(가중치 계산, 매칭 전략 등 아직 확정되지 않은 세부사항이 많음)이
  사용자 대상 조회 API의 안정성에 영향을 주지 않도록 책임을 나누기 위함이다.

---

## 4-3. WatchRecord (✅ 확정)

### 선행 작업
- `ErrorCode`에 `WATCH_RECORD_NOT_FOUND`, `WATCH_RECORD_ACCESS_DENIED`,
  `INVALID_WATCH_TYPE_OTT_COMBINATION`, `OTT_PLATFORM_NOT_FOUND`(누락분, 아래 참고) 상수 추가 필요.

### 전제 (jpa-entity-spec.md에서 이미 확정된 사항)
- `representative`(`is_representative`)는 항상 `false`로 생성되며, 상태 전환은
  엔티티의 `markAsRepresentative()`/`unmarkAsRepresentative()`로만 수행. 단일성
  보장(같은 user·movie 조합에 대표 최대 1건)은 DB로 강제할 수 없어 Service가 조율.
- 동시성(경쟁 조건)은 캡스톤 스코프상 낙관적으로 수용, 비관적 락은 향후 개선 과제.
- "몇 번째 시청인지"는 컬럼 저장 없이 조회 시점 계산(`ROW_NUMBER()`) 원칙만 확정되어
  있으며, 실제 집계/리포트 기능은 별도 도메인(추후 통계 세션) 소관이라 본 섹션 범위 밖.

### 이번 세션에서 결정한 사항

| 쟁점 | 결정 | 근거 |
|---|---|---|
| 대표 기록 삭제 시 재선정 | 자동 재선정 — 남은 기록 중 `id` 기준 최신을 대표로 승격 | "가장 최근 기록이 대표"라는 기존 정책을 삭제 시점에도 동일 적용. 별도 정책 신설 아님 |
| 수동 대표 재지정 | 필요 — `setRepresentative(watchRecordId)` API 별도 추가 | 사용자가 특정 회차를 대표로 지정하고 싶은 경우 지원 |
| `watchType`↔`ottPlatform` 정합성 검증 | Service 레벨 검증 유지 (DB `CHECK` 미도입) | 진입 경로가 `WatchRecordService` 사실상 단일 경로이고, DB 제약을 추가해도 `BusinessException` 일원화 체계상 `GlobalExceptionHandler`에 별도 매핑이 필요해 검증 로직이 두 곳으로 나뉘는 결과가 됨. `watchType`이 nullable 3분류 enum이라 SQL 3치 논리 실수 여지도 있어 Service 검증이 더 안전 |

### Repository — `WatchRecordRepository`

```java
public interface WatchRecordRepository extends JpaRepository<WatchRecord, Long> {

    Optional<WatchRecord> findByUserIdAndMovieIdAndRepresentativeTrue(Long userId, Long movieId);

    // 대표 삭제 후 재선정 대상 조회 겸, 특정 영화의 전체 시청 기록(회차별) 조회에도 사용
    List<WatchRecord> findByUserIdAndMovieIdOrderByIdDesc(Long userId, Long movieId);

    // "내 영화" 목록 — 대표 기록만, movie는 @EntityGraph로 함께 로딩(N+1 회피)
    @EntityGraph(attributePaths = "movie")
    Page<WatchRecord> findByUserIdAndRepresentativeTrue(Long userId, Pageable pageable);
}
```

### DTO

| DTO | 용도 | 포함 필드 |
|---|---|---|
| `WatchRecordCreateRequest` | 시청 기록 등록 | `movieId, watchDate, watchType, placeDetail, ottPlatformId, rating, note` |
| `WatchRecordResponse` | 단건 응답 (등록/수정/전체 시청 기록 조회) | `id, movieId, watchDate, representative, watchType, placeDetail, ottPlatform(OttPlatformResponse), rating, note` — `from(WatchRecord)` |
| `MyMovieListItemResponse` | "내 영화" 목록 (대표 기록 기준) | movie 요약 컬럼 + `List<GenreResponse>` + `List<CountryResponse>`(4-2 벌크 조회 재사용) + 대표 기록의 `watchDate, rating, watchType` |

### Service — `WatchRecordService`

| 메서드 | 트랜잭션 | 로직 요약 |
|---|---|---|
| `addWatchRecord(userId, request)` | 쓰기 | user/movie 조회(없으면 각각 `USER_NOT_FOUND`/`MOVIE_NOT_FOUND`) → `watchType == OTT`이면 `ottPlatformRepository.findById(ottPlatformId)` 조회(없으면 `OTT_PLATFORM_NOT_FOUND`; `getReferenceById()` 사용 금지 — FK 위반이 `BusinessException` 체계를 우회해 그대로 노출되는 것 방지) → `validateWatchTypeConsistency()` → 기존 대표 조회(`findByUserIdAndMovieIdAndRepresentativeTrue`) 있으면 `unmarkAsRepresentative()` → 신규 `WatchRecord` 빌더 생성 → `markAsRepresentative()` → 저장 |
| `deleteWatchRecord(userId, watchRecordId)` | 쓰기 | 조회(없으면 `WATCH_RECORD_NOT_FOUND`) → 소유자 검증(`userId` 불일치 시 `WATCH_RECORD_ACCESS_DENIED`) → 대표 여부 기억 → 삭제 → 대표였으면 `findByUserIdAndMovieIdOrderByIdDesc`로 남은 기록 중 최신 1건 조회해 `markAsRepresentative()` (남은 기록 없으면 스킵) |
| `setRepresentative(userId, watchRecordId)` | 쓰기 | 조회 + 소유자 검증 → 이미 대표면 즉시 반환(멱등) → 같은 (userId, movieId) 기존 대표 조회해 `unmarkAsRepresentative()` → 대상 `markAsRepresentative()` |
| `getMyMovieList(userId, pageable)` | 읽기 | `findByUserIdAndRepresentativeTrue`로 대표 기록 페이지 조회(movie fetch join 포함) → movieIds 추출 → `movieGenreRepository`/`movieCountryRepository`의 `findByMovieIdIn`으로 벌크 조회 후 그룹핑(4-2와 동일 패턴, 페이지당 고정 3쿼리) → `MyMovieListItemResponse` 조합 |
| `getWatchLog(userId, movieId)` | 읽기 | `findByUserIdAndMovieIdOrderByIdDesc` → `WatchRecordResponse` 리스트 반환 (회차별 전체 기록) |
| `validateWatchTypeConsistency(watchType, ottPlatformId)` (private 헬퍼) | - | `watchType == OTT`면 `ottPlatformId` 필수, 그 외(`THEATER`/`ETC`/`null`)는 `ottPlatformId`가 존재하면 안 됨 — 위반 시 `INVALID_WATCH_TYPE_OTT_COMBINATION`. `watchType == null` 케이스도 명시적으로 분기 처리(SQL 3치 논리 실수 방지 차원에서 Java 레벨에서는 문제 없지만 분기 누락 방지 목적으로 별도 케이스로 작성) |

### 설계 노트
- `getMyMovieList`는 4-2에서 확립한 "관계별 `IN`절 벌크 조회 + Service 그룹핑" 표준
  패턴을 그대로 재사용한다 — 진입점이 `MovieRepository`가 아니라 `WatchRecordRepository`
  (대표 기록 기준)라는 점만 다르고, movieIds를 추출한 이후 genre/country 벌크 조회
  로직은 4-2와 동일하다.
- `deleteWatchRecord`/`setRepresentative` 모두 "기존 대표 unmark → 대상 mark"라는
  동일한 조율 로직을 반복하므로, 실제 구현 시 `WatchRecordService` 내부에
  `reassignRepresentative(WatchRecord target)` 같은 private 헬퍼로 공통화하는 것을
  권장 (스펙상 강제하지 않음 — 구현 시 판단).
- `watchType`↔`ottPlatform` 검증은 DB `CHECK` 제약을 넣지 않기로 확정했지만, 이는
  "지금 필요 없다"는 판단이지 "영구히 불가능"이 아니다. 향후 관리자 도구나 배치 등
  `WatchRecordService`를 거치지 않는 쓰기 경로가 생기면 그 시점에 DB 제약 도입을
  재검토한다.
- 소유자 검증(`WATCH_RECORD_ACCESS_DENIED`)은 이번 섹션에서 처음 등장하는 패턴이다.
  이후 개인 기록성 도메인(Review, WishMovie, Collection 등)에서도 "본인 것만
  수정/삭제 가능"이 반복될 것이므로, 4-4부터는 이 패턴을 표준으로 재사용한다.
- **외부 입력으로 들어오는 FK는 항상 `findById().orElseThrow()`로 존재를 검증하고
  `getReferenceById()`를 쓰지 않는다.** `getReferenceById()`는 존재가 보장된 ID에
  대해 불필요한 SELECT를 생략하는 성능 최적화 수단일 뿐, 사용자 입력값 검증
  수단이 아니다 — 잘못된 ID를 그대로 넘기면 존재 확인 없이 지연 프록시가 만들어지고,
  실제 위반은 `flush` 시점 FK 제약 오류로 터져 `BusinessException` 체계를 우회한다.
  이 원칙은 이후 모든 도메인에서 사용자 입력 기반 FK(예: `collectionId`, `wishMovieId`
  등)를 참조할 때 동일하게 적용한다.

---

## 4-4. Review, WishMovie (✅ 확정)

### 선행 작업
- `ErrorCode`에 `REVIEW_NOT_FOUND` 추가 필요. (`DUPLICATE_REVIEW`, `WISH_MOVIE_NOT_FOUND`는
  아래 결정에 따라 불필요 — 설계 노트 참고)
- `ErrorResponse`에 `of(HttpStatus status, String message)` 오버로드 추가 필요
  (4-0 섹션에 반영 완료 — `IllegalArgumentException` 핸들러용)

### 이번 세션에서 결정한 사항

| 쟁점 | 결정 | 근거 |
|---|---|---|
| Review 생성/수정 API 구조 | Upsert 방식(`writeReview`) — 있으면 수정, 없으면 생성 | 영화 상세 화면의 별점+한줄평 입력 폼 하나로 작성/수정이 동일하게 동작하는 UX가 자연스러움. 클라이언트가 "이미 리뷰 있는지" 미리 조회해서 분기할 필요 없음 |
| WishMovie 토글 방식 | 단일 `toggleWish` 엔드포인트 | 하트/북마크 아이콘 클릭 UX에 가장 자연스러움. 클라이언트가 현재 상태를 들고 있을 필요 없이 매번 같은 엔드포인트 호출 |
| 엔티티 레벨 검증 예외 처리 | `GlobalExceptionHandler`에 `IllegalArgumentException` 핸들러 추가 | Service마다 try-catch 반복 대신 4-0에서 세운 "예외 처리 일원화" 원칙에 맞게 한 곳에서 처리. 향후 모든 엔티티 레벨 검증(`validateXxx()`)에 재사용 |
| Review 목록 작성자 정보 | 영화 상세의 "이 영화 리뷰 목록"에 작성자 닉네임/프로필 표시 → `user` fetch join 필요 | 화면 요구사항 확인 완료 |

### Repository

```java
public interface ReviewRepository extends JpaRepository<Review, Long> {

    Optional<Review> findByUserIdAndMovieId(Long userId, Long movieId);

    // 영화 상세 화면의 리뷰 목록 — 작성자 정보 함께 로딩
    @EntityGraph(attributePaths = "user")
    Page<Review> findByMovieId(Long movieId, Pageable pageable);

    // existsById()는 JpaRepository 기본 제공 — Comment 도메인이 targetType=REVIEW 대상
    // 존재 검증 시 그대로 사용 (4-2 jpa-entity-spec 확정 사항)
}

public interface WishMovieRepository extends JpaRepository<WishMovie, Long> {

    Optional<WishMovie> findByUserIdAndMovieId(Long userId, Long movieId);

    boolean existsByUserIdAndMovieId(Long userId, Long movieId);

    // "내 위시리스트" — 최근 추가순, movie는 @EntityGraph로 함께 로딩
    @EntityGraph(attributePaths = "movie")
    Page<WishMovie> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);
}
```

### DTO

| DTO | 용도 | 포함 필드 |
|---|---|---|
| `ReviewWriteRequest` | 리뷰 작성/수정(upsert) | `rating, content` |
| `ReviewResponse` | 리뷰 단건/목록 응답 | `id, author(작성자 요약: userId, nickname, profileImage), rating, content, createdAt, updatedAt` — `from(Review)` |
| `WishToggleResponse` | 토글 결과 | `wished(boolean)` — 토글 후 현재 상태 |
| `WishListItemResponse` | 위시리스트 목록 | movie 요약 컬럼 + `List<GenreResponse>` + `List<CountryResponse>`(4-2 벌크 재사용) + `addedAt`(wish 추가일) |

### Service — `ReviewService`

| 메서드 | 트랜잭션 | 로직 요약 |
|---|---|---|
| `writeReview(userId, movieId, request)` | 쓰기 | movie 조회(`findById().orElseThrow(MOVIE_NOT_FOUND)`) → `reviewRepository.findByUserIdAndMovieId` 조회 → 있으면 `review.update(rating, content)`(dirty checking), 없으면 `Review.of(userRef, movie, rating, content)` 생성 후 저장 → `ReviewResponse.from` 반환 |
| `deleteReview(userId, movieId)` | 쓰기 | `findByUserIdAndMovieId` 조회(없으면 `REVIEW_NOT_FOUND`) → 삭제. 조회 자체가 `userId`로 스코프되어 있어 별도 소유자 검증(`REVIEW_ACCESS_DENIED`) 불필요 — `reviewId` 단건으로 접근하는 API가 아니기 때문 |
| `getMyReview(userId, movieId)` | 읽기 | `findByUserIdAndMovieId` → `Optional<ReviewResponse>` 반환(없어도 에러 아님, "아직 리뷰 없음" 정상 상태 — 영화 상세 화면의 작성 폼 프리필 여부 판단용) |
| `getMovieReviews(movieId, pageable)` | 읽기 | `findByMovieId`(user fetch join) → `ReviewResponse` 페이지 반환, 작성자 정보 포함 |

### Service — `WishMovieService`

| 메서드 | 트랜잭션 | 로직 요약 |
|---|---|---|
| `toggleWish(userId, movieId)` | 쓰기 | movie 조회(`findById().orElseThrow(MOVIE_NOT_FOUND)`) → `findByUserIdAndMovieId` 조회 → 있으면 삭제 후 `wished=false`, 없으면 `WishMovie.of(userRef, movie)` 저장 후 `wished=true` → `WishToggleResponse` 반환 |
| `isWished(userId, movieId)` | 읽기 | `existsByUserIdAndMovieId` — 영화 상세 화면의 하트 아이콘 초기 상태 표시용, 엔티티 전체 조회 없이 존재 여부만 확인 |
| `getMyWishList(userId, pageable)` | 읽기 | `findByUserIdOrderByIdDesc`(movie fetch join) → movieIds 추출 → `movieGenreRepository`/`movieCountryRepository`의 `findByMovieIdIn`으로 벌크 조회 후 그룹핑(4-2/4-3과 동일 패턴) → `WishListItemResponse` 조합 |

### 설계 노트

- **Review는 `WATCH_RECORD_ACCESS_DENIED` 같은 소유자 검증 패턴이 필요 없다.** `writeReview`/`deleteReview`/`getMyReview` 모두 `(userId, movieId)` 조합으로 조회하는 구조라 애초에 호출자 본인 것만 접근 가능하도록 스코프되어 있다. 반면 `getMovieReviews`(영화 상세의 리뷰 목록)는 공개 조회라 누구나 볼 수 있는 게 맞으므로 소유자 검증 자체가 불필요한 영역이다. 4-3의 패턴을 기계적으로 재사용하지 않고 도메인 접근 방식에 맞게 판단했다.
- **인증된 사용자 자신의 `userId`는 신뢰값으로 취급 — `getReferenceById()` 사용 가능.** 4-3에서 세운 "사용자 입력 FK는 `findById`로 검증" 원칙은 요청 바디로 들어오는 식별자(`movieId` 등)에 대한 것이고, 인증 필터를 통과한 호출자 자신의 `userId`는 이미 존재가 보장된 신뢰값이므로 여기서는 `userRepository.getReferenceById(userId)`로 참조만 걸어도 무방하다. (4-3의 `addWatchRecord`가 `userId`도 `findById`로 조회한 것은 틀린 결정은 아니지만 다소 보수적인 선택이었다 — 이미 확정된 스펙이라 되돌리지는 않되, 4-4부터는 이 구분을 표준으로 삼는다.)
- `toggleWish`/`writeReview` 모두 movie 존재 검증이 필요하지만, `Movie` 엔티티 자체를 조회(`findById`)하는 이유는 신규 생성(`Review.of`/`WishMovie.of`)에 실제 `Movie` 참조가 필요하기 때문이지 존재 검증이 목적이 아니다 — 즉 존재 검증과 연관관계 설정이 한 번의 조회로 동시에 해결되는 자연스러운 경우.
- `getMyWishList`는 4-2에서 확립하고 4-3에서 재사용한 "관계별 IN절 벌크 조회 + Service 그룹핑" 패턴의 세 번째 재사용 사례다 — 이제 이 패턴은 "N건의 movie 관련 목록 + 연관관계 표시" 화면 전반의 표준으로 굳어졌다고 봐도 된다.

---

## 4-5. Collection, CollectionMovie (✅ 확정)

### 선행 작업
- `ErrorCode`에 `COLLECTION_NOT_FOUND`, `COLLECTION_ACCESS_DENIED`, `COLLECTION_MOVIE_NOT_FOUND` 추가 필요.
- `MovieDirectorRepository`에 `findByMovieIdIn` 벌크 메서드 추가 (4-2 섹션에 반영 완료).

### 스키마 이슈 — `collection_movie.collection_id`가 `RESTRICT`
- 컬렉션에 영화가 하나라도 들어있으면 DB가 `collection` 삭제 자체를 막는다. 원칙("사용자
  생성 콘텐츠는 RESTRICT")을 컬렉션→영화 방향에는 맞게 적용했지만, `collection_movie`는
  성격상 `movie_genre`처럼 부모(`collection`) 없이는 의미 없는 순수 소속 관계라 원래는
  CASCADE가 더 일관됐을 관계다. 다만 이미 확정된 스키마를 되돌리는 실익이 적어 **스키마는
  그대로 두고 Service가 명시적으로 순서를 보장**하기로 확정 (`deleteCollection`에서
  `collection_movie`를 먼저 정리한 뒤 `collection`을 삭제).

### 이번 세션에서 결정한 사항

| 쟁점 | 결정 |
|---|---|
| 컬렉션-영화 추가/제거 API 구조 | **벌크 추가 + 단건 제거**로 분리 (토글 아님). 여러 영화를 한 번에 담는 UX가 필요해, 토글 하나로는 "이미 있는 항목을 실수로 빼는" 위험이 생기므로 add는 벌크·idempotent(이미 있으면 조용히 스킵), remove는 단건 명시적 삭제로 분리 |
| 컬렉션 목록 영화 개수 | 필요 — 컬렉션별 개별 카운트 쿼리 반복 금지, `collection_movie`를 `collectionId` 기준 벌크 그룹 카운트(JPQL `GROUP BY`)로 조회 후 그룹핑 |
| 컬렉션 상세(영화 목록) 표시 필드 | 그리드/리스트 두 형태 지원. 장르·국가 **미표시**. 리스트 뷰 기준 포스터, 제목, 개봉년도, 감독명만 필요 → genre/country 벌크 조회 불필요, 대신 director 벌크 조회 필요 |
| 컬렉션 상세 조회 공개 여부 | **공개 조회** — 소유자 검증 없음. `Comment.targetType`에 `COLLECTION`이 포함된다는 게 이미 확정돼 있어(4-4 이전 jpa-entity-spec 확정 사항), 컬렉션은 타인이 보고 댓글 달 수 있는 공개 콘텐츠라는 전제가 이미 깔려 있음. 반대로 생성·수정·삭제·영화 추가/제거는 소유자 검증 필요 |

### Repository

```java
public interface CollectionRepository extends JpaRepository<Collection, Long> {
    // "내 컬렉션" 목록 및 타인의 프로필에서 컬렉션 목록 조회에 공용으로 사용 (공개 조회)
    Page<Collection> findByUserId(Long userId, Pageable pageable);
}

public interface CollectionMovieRepository extends JpaRepository<CollectionMovie, Long> {

    Optional<CollectionMovie> findByCollectionIdAndMovieId(Long collectionId, Long movieId);

    // 벌크 추가 시 이미 담겨있는 movieId를 걸러내기 위한 조회
    List<CollectionMovie> findByCollectionIdAndMovieIdIn(Long collectionId, List<Long> movieIds);

    // 컬렉션 상세(영화 목록) — movie는 @EntityGraph로 함께 로딩
    @EntityGraph(attributePaths = "movie")
    Page<CollectionMovie> findByCollectionId(Long collectionId, Pageable pageable);

    // 컬렉션 삭제 시 RESTRICT 대응 — 하위 행 명시적 정리
    void deleteAllByCollectionId(Long collectionId);

    // "내 컬렉션" 목록의 영화 개수 표시 — 컬렉션별 반복 쿼리 대신 벌크 그룹 카운트
    @Query("""
        SELECT cm.collection.id AS collectionId, COUNT(cm) AS count
        FROM CollectionMovie cm
        WHERE cm.collection.id IN :collectionIds
        GROUP BY cm.collection.id
        """)
    List<CollectionMovieCountProjection> countGroupByCollectionIdIn(List<Long> collectionIds);
}

public interface CollectionMovieCountProjection {
    Long getCollectionId();
    Long getCount();
}
```

### DTO

| DTO | 용도 | 포함 필드 |
|---|---|---|
| `CollectionCreateRequest` / `CollectionUpdateRequest` | 컬렉션 생성/수정 | `name, description` |
| `CollectionResponse` | 컬렉션 단건/목록 응답 | `id, name, description, movieCount, createdAt, updatedAt` — `from(Collection, long movieCount)` |
| `AddMoviesToCollectionRequest` | 영화 벌크 추가 | `List<Long> movieIds` |
| `AddMoviesToCollectionResponse` | 벌크 추가 결과 | `addedCount, skippedCount`(이미 담겨있어 건너뛴 개수) |
| `CollectionMovieListItemResponse` | 컬렉션 상세(그리드/리스트 공용) | `movieId, posterPath, title, releaseYear, directorNames`(공동 감독 시 콤마로 join) — 그리드 뷰는 프론트에서 `posterPath` 등 필요한 필드만 선택적으로 사용 |

### Service — `CollectionService`

| 메서드 | 트랜잭션 | 로직 요약 |
|---|---|---|
| `createCollection(userId, request)` | 쓰기 | `userRepository.getReferenceById(userId)`(인증된 본인, 신뢰값) → `Collection.of(userRef, name, description)` 저장 |
| `updateCollection(userId, collectionId, request)` | 쓰기 | `getOwnedCollectionOrThrow(userId, collectionId)` → `collection.update(name, description)` (dirty checking) |
| `deleteCollection(userId, collectionId)` | 쓰기 | `getOwnedCollectionOrThrow` → `collectionMovieRepository.deleteAllByCollectionId(collectionId)`(RESTRICT 대응, 먼저 정리) → `collectionRepository.delete(collection)` |
| `getCollections(userId, pageable)` | 읽기 | `findByUserId` 페이지 조회 → collectionIds 추출 → `countGroupByCollectionIdIn`으로 벌크 카운트 후 `Map`으로 그룹핑 → `CollectionResponse.from(collection, count)` 조합. 소유자 검증 없음(본인/타인 모두 동일 메서드로 조회, 공개 조회) |
| `addMoviesToCollection(userId, collectionId, request)` | 쓰기 | `getOwnedCollectionOrThrow` → `movieRepository.findAllById(movieIds)` 조회, 조회된 개수가 요청 개수와 다르면 `MOVIE_NOT_FOUND`(요청 전체 롤백) → `findByCollectionIdAndMovieIdIn`으로 이미 존재하는 movieId 집합 조회 → 미존재 movie만 필터링해 `CollectionMovie.of(collectionRef, movie)` 목록 생성 후 `saveAll` → `addedCount`/`skippedCount` 반환 |
| `removeMovieFromCollection(userId, collectionId, movieId)` | 쓰기 | `getOwnedCollectionOrThrow` → `findByCollectionIdAndMovieId` 조회(없으면 `COLLECTION_MOVIE_NOT_FOUND`) → 삭제 |
| `getCollectionMovies(collectionId, pageable)` | 읽기 | `findByCollectionId`(movie fetch join) → movieIds 추출 → `movieDirectorRepository.findByMovieIdIn`으로 벌크 조회 후 `movieId` 기준 그룹핑, 감독명 콤마 join → `CollectionMovieListItemResponse` 조합. 소유자 검증 없음(공개 조회) |
| `getOwnedCollectionOrThrow(userId, collectionId)` (private 헬퍼) | - | `collectionRepository.findById()`(없으면 `COLLECTION_NOT_FOUND`) → `collection.getUser().getId().equals(userId)` 아니면 `COLLECTION_ACCESS_DENIED` — 쓰기 메서드 전반에서 반복 사용 |

### 설계 노트
- `addMoviesToCollection`은 이미 담긴 영화를 다시 요청해도 에러 없이 건너뛰는 **idempotent 벌크 추가**로 설계했다 — 벌크 선택 UX(예: 위시리스트에서 여러 개 골라 담기)에서 "이미 담긴 것도 섞여 들어왔다"고 에러를 내는 건 사용자 경험상 불필요한 마찰이기 때문이다. 반면 요청에 포함된 `movieId` 중 하나라도 실존하지 않으면 전체를 롤백한다 — 부분 성공을 허용하면 클라이언트가 "몇 번째까지 성공했는지" 추적해야 해 복잡도가 커진다.
- `getCollections`/`getCollectionMovies`는 소유자 검증이 없는 공개 조회, 나머지 쓰기 메서드는 전부 `getOwnedCollectionOrThrow`를 거친다 — 같은 도메인 안에서도 "조회는 공개, 쓰기는 소유자 전용"이 명확히 구분된다는 걸 강조해둔다(4-4 Review의 "조회는 공개, 쓰기는 자기 것만"과 동일한 원칙의 재적용).
- 컬렉션 개수 집계(`countGroupByCollectionIdIn`)는 지금까지의 "관계별 IN절 벌크 조회 + Service 그룹핑" 패턴을 **엔티티 전체가 아니라 집계값(count)에 적용한 변형**이다 — 패턴의 핵심(반복 쿼리 대신 벌크 1방 + 그룹핑)은 동일하게 유지된다.
- 컬렉션 상세 리스트 뷰의 감독명은 공동 감독이 있을 경우 콤마로 join하는 것으로 처리한다 — 별도로 확인받지 않은 세부 표시 규칙이므로, 실제 화면에서 다른 형태(예: "외 1명")를 원하면 프론트/DTO 조정으로 대응 가능.

---

## 4-6. Follow, Comment + 공개범위 접근 제어 (✅ 확정)

### 선행 작업

- 없음. 단, 본 절에서 도입하는 `UserAccessPolicy`는 **인증된 호출자 `viewerId`가 Service 파라미터로
  전달된다는 전제**를 갖는다. Spring Security 도입 전까지는 Controller가 임시로 `viewerId`를 받고,
  Security 적용 시 `@AuthenticationPrincipal`로 대체한다. (Service 시그니처는 변하지 않음)

### 이번 세션에서 결정한 사항

1. **`privacy_setting`의 `FRIENDS` = 상호 팔로우(맞팔)**. 단방향 팔로우만으로 비공개 데이터가
   노출되는 것을 막기 위함.
2. **`follow`/`unfollow` 엔드포인트 분리** (4-4 `toggleWish`와 다른 선택). 팔로우는 상대에게
   노출되는 관계 행위라 네트워크 재시도로 의도치 않게 해제되는 토글 방식을 배제.
   대신 양쪽 모두 **멱등**으로 처리해 클라이언트의 사전 상태 조회를 강제하지 않는다.
3. **댓글 수정은 작성자만, 삭제는 작성자 + 대상(컬렉션/리뷰) 소유자**.
4. **비로그인(`viewerId == null`) 조회 허용**. null을 예외가 아닌 정상 입력으로 다룬다.

---

### 4-6-A. 공개범위 접근 제어 — `UserAccessPolicy`

4-6은 **타인의 데이터를 조회하는 첫 도메인**이다. 판정 로직이 Follow·Comment·Collection·Review·
WatchRecord에 모두 필요하므로 도메인마다 중복 구현하지 않고 공통 컴포넌트(`global/access`)로 분리한다.

> `global`이 `domain.follow.repository`에 의존하게 되지만, 접근 제어는 특정 도메인의 책임이 아니라
> 애플리케이션 전역 정책이므로 `domain.follow` 하위에 두지 않는다. 의존은 단방향이며 역참조는 금지.

#### 판정 규칙

| viewer 조건 | 결과 |
|---|---|
| `viewerId.equals(targetUserId)` | 항상 허용 (본인) |
| `PUBLIC` | 허용 |
| `FRIENDS` | **양방향 Follow 레코드가 모두 존재할 때만** 허용 |
| `PRIVATE` | 본인 외 전부 거부 |
| `viewerId == null` (비로그인) | PUBLIC만 허용 (null을 정상 입력으로 취급) |

**비로그인 허용의 파급 효과**

- 모든 메서드는 null이면 본인 판정·맞팔 판정을 **건너뛴 뒤** `PUBLIC` 여부만으로 결정한다.
  (null 상태로 Follow 쿼리를 날리지 않도록 가드 절을 최상단에 둔다)
- 조회 결과의 viewer 의존 플래그(`following`/`editable`/`deletable`/`me`)는 전부 `false` 고정.
- **쓰기 계열은 null 불허** — Service 진입부에서 `UNAUTHORIZED`(401). Security 도입 후에는
  `SecurityFilterChain`이 선차단하므로 이 방어 코드는 이중 방어로 남는다.

#### 시그니처

```java
@Component
public class UserAccessPolicy {
    public void validateCanView(Long viewerId, Long targetUserId); // 거부 시 ACCESS_DENIED
    public boolean canView(Long viewerId, Long targetUserId);
    public Set<Long> filterViewable(Long viewerId, Collection<Long> targetUserIds);
}
```

#### 쿼리 비용

- **단건**: `user` 1방 + (FRIENDS인 경우에만) 맞팔 판정 1방 = 최대 2쿼리.
  맞팔 판정은 `existsBy...` 2회가 아니라 `countMutual` 단일 쿼리 결과가 `2`인지로 확인한다.
- **벌크(`filterViewable`)**: `findAllById` 1방 → PUBLIC/본인 즉시 통과 → 남은 FRIENDS 후보에만
  "내가 팔로우한 id" 1방 + "나를 팔로우한 id" 1방을 조회해 메모리에서 교집합(`retainAll`).
  **최대 3쿼리 고정** — 4-2에서 확정한 *IN절 벌크 조회 + Service 조합* 표준 패턴의 재사용.
  상관 서브쿼리 방식보다 실행계획이 단순하고 디버깅이 쉬워 채택. PRIVATE 대상은 쿼리 이전에
  탈락시켜 IN절 크기를 줄인다.

#### 응답 정책

- 거부 시 **403 `ACCESS_DENIED`** (404로 존재 자체를 숨기지 않음).
  트레이드오프: 403은 유저 존재를 노출하지만, 닉네임 검색으로 이미 확인 가능한 서비스라 은닉 실익이 없고,
  404 통일은 클라이언트가 "없는 유저"와 "비공개 유저"를 구분해 안내하지 못하게 만든다.

---

### 4-6-B. Follow 도메인

#### Repository — `FollowRepository`

| 메서드 | 용도 |
|---|---|
| `existsByFollowerIdAndFollowingId` | 중복 팔로우 사전 체크, 프로필의 팔로우 여부 |
| `deleteByFollowerIdAndFollowingId` (`long`) | 언팔로우. `uk_follow`로 최대 1행 적중이라 파생 삭제로 충분 |
| `countByFollowerId` / `countByFollowingId` | 팔로잉 수 / 팔로워 수 |
| `findByFollowingId(Pageable)` `@EntityGraph("follower")` | 팔로워 목록 |
| `findByFollowerId(Pageable)` `@EntityGraph("following")` | 팔로잉 목록 |
| `countMutual(userA, userB)` `@Query` | 맞팔 단건 판정 (2면 맞팔) |
| `findFollowingIdsIn(viewerId, targetIds)` `@Query` | 벌크: viewer가 팔로우 중인 id |
| `findFollowerIdsIn(viewerId, targetIds)` `@Query` | 벌크: viewer를 팔로우 중인 id |

#### DTO

| DTO | 용도 | 필드 |
|---|---|---|
| `FollowUserResponse` | 팔로워/팔로잉 목록 항목 | `userId`, `nickname`, `profileImage`, `following` |
| `UserProfileResponse` | 프로필 화면 헤더 | `userId`, `nickname`, `profileImage`, `privacySetting`, `followerCount`, `followingCount`, `following`, `me` |

- `following`은 엔티티가 알 수 없는 "조회자 기준" 계산값이므로 `from(User, boolean following)`으로
  `from(Entity)` 단일 인자 규칙의 최소 예외를 둔다.

#### Service — `FollowService`

| 메서드 | 트랜잭션 | 로직 요약 |
|---|---|---|
| `follow(followerId, followingId)` | 쓰기 | ① 인증 확인 ② 자기 자신이면 `CANNOT_FOLLOW_SELF` ③ `findById(followingId)`(없으면 `USER_NOT_FOUND`) ④ 이미 존재하면 **아무 것도 하지 않고 정상 종료(멱등)** ⑤ `Follow.of(getReferenceById(followerId), following)` → `save` |
| `unfollow(followerId, followingId)` | 쓰기 | 파생 삭제 호출. 0건이어도 예외 없이 정상 종료(멱등) |
| `getFollowers(viewerId, targetUserId, pageable)` | 읽기 | `validateCanView` → `findByFollowingId`(@EntityGraph) → 페이지 내 userId를 `findFollowingIdsIn` 1방으로 벌크 판정 → 매핑 |
| `getFollowings(viewerId, targetUserId, pageable)` | 읽기 | 위와 대칭 |
| `getUserProfile(viewerId, targetUserId)` | 읽기 | user 조회 + 팔로워/팔로잉 수 + `following`/`me` 판정. **공개범위와 무관하게 노출** |

#### 설계 노트

- **FK 검증 규칙 적용**: `followingId`는 사용자 입력값이므로 `findById().orElseThrow()`,
  `followerId`는 인증된 호출자이므로 `getReferenceById()`. (4-3/4-4에서 명문화한 전역 원칙)
- **프로필 헤더는 비공개여도 노출**: 헤더까지 막으면 팔로우 요청을 보낼 화면 자체가 없어진다.
  차단 대상은 시청기록/컬렉션/리뷰 등 **콘텐츠**다.
- **`getUserProfile`의 위치**: 팔로우 수/여부가 프로필 헤더의 본질적 구성요소이고,
  `Follow → User` 참조가 이미 단방향으로 존재하므로 `UserService`가 `FollowRepository`를
  알게 되는 쪽보다 `FollowService`에 두는 편이 의존 방향이 자연스럽다.
- **동시성**: `exists` 사전 체크로 일반적인 중복 요청은 걸러지지만, 진짜 경합 시에는
  `uk_follow` 위반이 발생한다. 자세한 처리 방식은 아래 *구현 중 조정* 참고.

---

### 4-6-C. Comment 도메인

#### 다형 대상 처리 — `CommentTargetResolver`

Step3에서 확정한 **A안**(연관관계 매핑 없이 순수 컬럼 유지)의 구체화. `targetType` 분기가
(1) 존재 검증 (2) 소유자 확인 (3) 공개범위 판정 세 지점으로 흩어지는 것을 막기 위해,
**"대상의 소유자 userId를 반환한다"** 는 단일 책임 인터페이스로 통합한다.

```java
public interface CommentTargetResolver {
    TargetType supports();
    Long findOwnerIdOrThrow(Long targetId); // 대상 없으면 BusinessException
}
```

| 구현체 | 쿼리 | 실패 시 |
|---|---|---|
| `CollectionCommentTargetResolver` | `select c.user.id from Collection c where c.id = :id` | `COLLECTION_NOT_FOUND` |
| `ReviewCommentTargetResolver` | `select r.user.id from Review r where r.id = :id` | `REVIEW_NOT_FOUND` |

- **id 프로젝션만 조회**하므로 엔티티 로딩 없이 1쿼리로 존재 검증 + 소유자 조회가 동시에 끝난다.
  (`existsById` + 소유자 조회 2쿼리 방식보다 유리)
- `CommentService`는 `List<CommentTargetResolver>`를 생성자 주입받아 **생성자 내부에서**
  `EnumMap<TargetType, …>`으로 변환한다. Spring의 `Map` 자동 주입은 키가 *빈 이름(String)* 이라
  `TargetType` 키로 쓸 수 없고, 생성자에서 변환하면 필드를 `final`로 유지할 수 있다.
- 미지원 타입은 `UNSUPPORTED_COMMENT_TARGET`으로 즉시 실패 → ENUM 확장 시 구현체 누락을 바로 드러낸다.

#### Repository — `CommentRepository`

| 메서드 | 비고 |
|---|---|
| `findByTargetTypeAndTargetIdOrderByIdAsc(Pageable)` `@EntityGraph("user")` | `idx_comment_target` 적중. 정렬은 `created_at`이 아닌 **`id` 오름차순** |
| `countByTargetTypeAndTargetId` | 단건 댓글 수 |
| `countGroupByTargetIdIn` `@Query` → `CommentCountProjection` | 목록 화면 댓글 수 배지 (벌크 그룹 카운트) |
| `deleteByTarget(targetType, targetId)` `@Modifying` | 대상 삭제 시 고아 댓글 정리 |

- 정렬을 `id` 기준으로 두는 근거는 v4의 대표 리뷰 판정 기준과 동일하다(동일 시각 생성 시 순서 불안정 방지).
  추후 커서 페이징 전환에도 그대로 쓸 수 있다.

#### DTO

| DTO | 용도 | 필드 |
|---|---|---|
| `CommentCreateRequest` | 작성 | `targetType`, `targetId`, `content` |
| `CommentUpdateRequest` | 수정 | `content` |
| `CommentAuthorResponse` | 작성자 | `userId`, `nickname`, `profileImage` |
| `CommentResponse` | 목록/단건 | `commentId`, `author`, `content`, `createdAt`, `updatedAt`, `editable`, `deletable` |

- `editable`/`deletable`은 viewer 기준 계산값이므로 `from(Comment, viewerId, targetOwnerId)`로 받는다.
  클라이언트가 권한 판정을 재구현하지 않게 하기 위함.

#### Service — `CommentService`

| 메서드 | 트랜잭션 | 로직 요약 |
|---|---|---|
| `createComment(authorId, request)` | 쓰기 | resolver로 `ownerId` 조회(= 대상 존재 검증) → `validateCanView` → `Comment.builder()` → `save` |
| `getComments(viewerId, targetType, targetId, pageable)` | 읽기 | resolver → `validateCanView` → 페이지 조회(@EntityGraph) → 매핑. **고정 3쿼리**(owner 1 + count 1 + 본문 1) |
| `editComment(viewerId, commentId, content)` | 쓰기 | 조회(`COMMENT_NOT_FOUND`) → **작성자 본인만** 아니면 `NO_COMMENT_PERMISSION` → `editContent()` (dirty checking) |
| `deleteComment(viewerId, commentId)` | 쓰기 | 조회 → 작성자면 즉시 통과 / 아니면 resolver로 `ownerId` 확인 → 그 외 `NO_COMMENT_PERMISSION` → `delete` |

#### 설계 노트

- **수정은 작성자만, 삭제는 작성자 + 대상 소유자**: 타인이 남의 글 내용을 바꾸는 것은 위조지만,
  자기 컬렉션/리뷰에 달린 악성 댓글 제거는 정당한 관리 권한이다. 권한 판정을 "작성자 우선 →
  소유자 fallback" 순으로 두면 일반적인 경우(자기 댓글 삭제)에 resolver 쿼리가 발생하지 않는다.
- **비공개 대상에는 댓글 작성/조회 불가**: 대상 소유자의 `privacy_setting`을 그대로 따르며,
  댓글에 별도 공개 설정을 두지 않는다(캡스톤 스코프).
- **하드 삭제 채택**: 대댓글(`parent_id`) 구조가 없어 삭제 시 트리가 끊길 위험이 없으므로
  "삭제된 댓글입니다" 표시용 soft delete 컬럼을 추가하지 않는다.
- **⚠️ 고아 댓글 문제 (4-6에서 발견, 소급 반영)**: `comment.target_id`는 다형 참조라 FK가 없다.
  `Collection`/`Review`가 삭제돼도 댓글이 남아, **재사용된 AUTO_INCREMENT id에 과거 댓글이
  붙어 보이는 데이터 오염**이 발생할 수 있다. 4-5에서 `RESTRICT` FK를 서비스 레이어에서 푼 것과
  동일한 원칙으로 대응한다.
  - `CollectionService.deleteCollection()` → `deleteByTarget(COLLECTION, id)` 선행 호출
  - `ReviewService.deleteReview()` → `deleteByTarget(REVIEW, review.getId())` 선행 호출
  - `user` 삭제 시 댓글은 `fk_comment_user ON DELETE CASCADE`로 자동 정리되므로 추가 조치 불필요

---

### 4-6-D. ErrorCode 추가분

| 상수 | HTTP |
|---|---|
| `UNAUTHORIZED` | 401 |
| `ACCESS_DENIED` | 403 |
| `CANNOT_FOLLOW_SELF` | 400 |
| `COMMENT_NOT_FOUND` | 404 |
| `NO_COMMENT_PERMISSION` | 403 |
| `UNSUPPORTED_COMMENT_TARGET` | 400 |
| `DUPLICATE_REQUEST` | 409 |

> `USER_NOT_FOUND`(4-1), `COLLECTION_NOT_FOUND`(4-5), `REVIEW_NOT_FOUND`(4-4)는 기존 상수 재사용.

---

### 4-6-E. 후속 소급 과제 (✅ 완료)

`UserAccessPolicy` 확정으로, **4-2~4-5의 조회 메서드가 "본인 데이터만 조회"를 암묵적으로
전제하고 있던 문제**가 드러났다. 4-7 착수 전에 아래를 처리했다.

| 대상 | 조치 | 상태 |
|---|---|---|
| `WatchRecordService.getMyMovieList` → `getUserMovieList` | 시그니처 `(viewerId, targetUserId, …)` + `validateCanView` | ✅ |
| `WatchRecordService.getWatchLog` | 동일 | ✅ |
| `WishMovieService.getMyWishList` → `getUserWishList` | 동일 | ✅ |
| `CollectionService.getCollections` | 동일 | ✅ |
| `CollectionService.getCollectionMovies` | `findOwnerIdById`로 소유자 확인 후 `validateCanView` | ✅ |
| `ReviewService.getMovieReviews` | 작성자가 다수라 `filterViewable` 벌크 판정 후 필터링 | ✅ |
| `WatchRecord.note` 필드명 정리 | 확인 결과 **이미 반영돼 있었음**(DevLog 표기가 stale) | ✅ |
| `com.project.cinemory.repository` 잔존 패키지 | 중복 정의 삭제, `MovieRepositoryTest` import 정정 | ✅ |

**시그니처 컨벤션 확정**: 타인 조회가 가능한 모든 Service 조회 메서드는
`(Long viewerId, Long targetUserId, …)` 순서를 따른다. 본인 전용 메서드(쓰기 계열,
`isWished`/`getMyReview` 등 호출자 자신의 상태 조회)는 기존대로 `(Long userId, …)`를 유지해
의미를 구분한다. 메서드명의 `My` 접두사도 타인 조회가 가능해진 경우 `getUser…`로 바꾼다.

> **`getMovieReviews`의 한계**: 조회 후 필터링 방식이라 페이지당 실제 항목 수가 요청 size보다
> 적을 수 있고 `totalElements`에 가려진 리뷰가 포함된다. 정확한 페이징이 필요해지면 공개범위
> 조건을 쿼리로 내려야 한다(작성자 조인 + 맞팔 서브쿼리). 캡스톤 스코프에서는 단순성을 우선한다.

---

### 4-6-F. 잔여 확인 항목

1. ~~비로그인 조회 허용 여부~~ → **✅ 확정: 허용(null 정상 입력)**
2. ~~팔로워/팔로잉 명단의 공개범위 적용 여부~~ → **✅ 확정: 현행 유지** (2026-07-29, Step S 세션).
   팔로워 *수*는 프로필 헤더에서 공개하되 *명단* 열람은 `validateCanView`를 따른다.
   비공개 계정의 인간관계가 드러나지 않게 하기 위함이며, 인스타그램 비공개 계정과 동일한 동작이라
   사용자에게 익숙하다.
3. ~~댓글 알림(notification) 기능 포함 여부~~ → **✅ 확정: 도입** (2026-07-29, Step S 세션).
   `notification` 테이블을 스키마 v9에 포함한다(현행 스냅샷 `docs/schema/cinemory_backup_v10.sql` 참고).
   단 테이블만 확정된 상태이고 **알림 도메인 설계는 Step S 구현 이후 별도 절**로 진행한다.
   - 알림 생성 지점이 `FollowService.follow()` / `CommentService.createComment()` 안에 들어가므로
     기존 도메인 서비스에 손이 닿는다.
   - ⚠️ `notification`도 `comment`와 동일한 다형 참조 구조라 **고아 알림 문제가 그대로 재현된다.**
     `CollectionService.deleteCollection()` / `ReviewService.deleteReview()`에서 댓글을 정리하는
     **바로 그 자리**에 알림 정리도 함께 호출해야 한다.
   - `comment`의 `TargetType`과 값이 다르다(알림은 팔로우 대상 `USER` 포함).
     enum을 재사용하지 말고 `NotificationTargetType`으로 분리할 것.

---

## 4-7. Theater, BoxOfficeRecord (✅ 확정)

CineMap(주변 극장 지도)과 박스오피스 화면을 담당하는 도메인. 앞선 도메인과 달리
**사용자 소유 데이터가 아니라 외부 API에서 수집한 공용 데이터**라 다음 두 가지가 다르다.

- 공개범위(`UserAccessPolicy`) 적용 대상이 아니다. `viewerId`를 받지 않는다.
- 조회(Read)와 수집(Sync)의 책임을 분리한다 — 4-2에서 `MovieQueryService`/`MovieSyncService`를
  나눈 것과 동일한 원칙.

### 이번 세션에서 결정한 사항

1. **주변 극장 검색 = Bounding Box 1차 필터 + Service Haversine 정밀 계산**
2. **극장 데이터 = 1회성 시드 적재** (주기 배치 미도입)
3. **박스오피스 배치 = `@Scheduled` 자동 + 관리자 수동 트리거 병행**
4. **TMDB 미매칭 레코드 = 스냅샷으로 노출 + 별도 재매칭 배치로 재시도**

---

### 4-7-A. Theater — 주변 극장 조회 (CineMap)

#### 반경 검색 전략

`theater`에는 `idx_theater_lat_lng (latitude, longitude)` B-Tree 인덱스만 있고 공간 인덱스는 없다.
스키마를 그대로 두고 **2단계**로 처리한다.

1. **Bounding Box (DB)** — 위경도 `BETWEEN`으로 사각형 범위를 잘라 후보를 좁힌다.
   ```
   위도 1도  ≈ 111.32 km
   경도 1도  ≈ 111.32 × cos(위도) km
   minLat = lat - r/111.32          maxLat = lat + r/111.32
   minLng = lng - r/(111.32·cos φ)  maxLng = lng + r/(111.32·cos φ)
   ```
2. **Haversine (Service)** — 사각형 모서리에 걸린 항목을 반경 밖으로 제외하고,
   거리 오름차순 정렬 후 `limit` 적용.

**설계 노트**

- MySQL 복합 인덱스는 **첫 range 조건 이후 컬럼을 인덱스 탐색에 쓰지 못한다.** 즉
  `latitude BETWEEN …`이 range라 `longitude`는 탐색 키로 동작하지 않는다. 다만 Index Condition
  Pushdown으로 인덱스 레벨 필터링은 되므로 테이블 접근량은 줄어든다. 전국 상영관이 수백 개
  규모라 이 정도로 충분하며, 데이터가 크게 늘면 그때 `POINT` 컬럼 + `SPATIAL` 인덱스(스키마 v9)로
  전환한다.
- 한국은 위도 33~38도 구간이라 **극지방/날짜변경선 경계 처리가 불필요**하다. 위 공식을
  분기 없이 그대로 쓴다. (전 세계 서비스로 확장할 때만 경도 wrap-around 처리가 필요)
- 정렬·거리 계산을 SQL이 아닌 Service에서 하는 이유: 거리 계산식을 `ORDER BY`에 넣으면
  인덱스를 못 타고 전체 정렬이 발생한다. 후보를 좁힌 뒤 메모리에서 정렬하는 편이 싸다.

#### Repository — `TheaterRepository`

| 메서드 | 용도 |
|---|---|
| `findWithinBoundingBox(minLat, maxLat, minLng, maxLng)` `@Query` | 반경 검색 1차 필터 |
| `findBySourceCode(String)` | 시드 적재 시 upsert 판정 |
| `existsBySourceCode(String)` | 동일 |

#### DTO

| DTO | 필드 |
|---|---|
| `TheaterResponse` | `theaterId`, `name`, `chainName`, `address`, `latitude`, `longitude`, `screenCount`, `seatCount`, `distanceMeters` |

- `distanceMeters`는 조회 기준점에 따라 달라지는 계산값이므로 `from(Theater, double distanceMeters)`.
  (4-6 `FollowUserResponse.from(User, boolean)`과 동일한 예외 패턴)

#### Service — `TheaterQueryService`

| 메서드 | 트랜잭션 | 로직 요약 |
|---|---|---|
| `getNearbyTheaters(latitude, longitude, radiusMeters, limit)` | 읽기 | 반경 상한 검증 → Bounding Box 조회 → Haversine 필터·정렬 → `limit` → `TheaterResponse` 매핑 |

- **인증 불필요**. 공용 데이터이므로 `viewerId`를 받지 않는다.
- `radiusMeters` 상한을 둔다(기본 5,000m / 최대 50,000m). 무제한을 허용하면 Bounding Box가
  전국을 덮어 1차 필터가 무의미해진다. 초과 시 `INVALID_SEARCH_RADIUS`.

#### Service — `TheaterSeedService` (1회성, 시그니처만 확정)

```java
public interface TheaterSeedService {
    int seedAll(List<TheaterSeedData> rows); // sourceCode 기준 upsert, 반환값 = 신규 적재 건수
}
```

- 공공데이터포털 *전국영화상영관표준데이터* CSV를 파싱해 1회 적재한다. 극장은 개·폐점이 드물어
  주기 배치의 실익이 적으므로 스케줄러를 두지 않는다. 재적재가 필요하면 같은 진입점을 다시 호출한다.
- `uk_theater_source_code` 덕분에 재실행해도 중복이 생기지 않는다(멱등).
- **⚠️ 적재 전 확인 필요**: 표준데이터의 좌표 컬럼이 WGS84 위경도인지 EPSG:5174(중부원점 TM)인지
  실제 파일로 확인한다. EPSG:5174라면 적재 시점에 WGS84로 변환해야 한다
  (`theater.latitude/longitude`는 WGS84 전제).

---

### 4-7-B. BoxOfficeRecord — 수집 배치 + 조회

#### 외부 API 클라이언트

```
global/infra/kofic
 ├─ KoficClient.java            (RestClient 기반)
 ├─ KoficProperties.java        (@ConfigurationProperties, API 키)
 └─ dto/…                       (KOFIC 응답 전용 DTO)
```

- API 키는 `application-secret.yml`에 두고 `@ConfigurationProperties`로 바인딩한다
  (이미 secret 분리 구조가 있으므로 그대로 따른다).
- 외부 호출 실패는 `EXTERNAL_API_ERROR`로 감싸 던지되, **스케줄러 진입점에서는 예외를 잡아
  로깅만 하고 삼킨다.** 수집 실패가 애플리케이션 전체에 전파되면 안 되기 때문이다.
- `global` 하위에 `infra` 패키지를 신규로 둔다(기존 `config`/`exception`에 이어). TMDB 클라이언트도
  같은 위치에 들어올 예정이라 도메인 패키지에 넣지 않는다.

#### Repository — `BoxOfficeRecordRepository`

| 메서드 | 용도 |
|---|---|
| `findByTargetDateAndRankTypeOrderByBoxOfficeRankAsc` `@EntityGraph("movie")` | 박스오피스 화면 조회 (포스터까지 1쿼리) |
| `findLatestTargetDate(rankType)` `@Query MAX(targetDate)` | 날짜 미지정 조회 시 최신 집계일 |
| `findKoficMovieCdsByTargetDateAndRankType` `@Query` | **멱등 수집용** — 이미 적재된 코드 집합 |
| `findByMovieIsNull(Pageable)` | 재매칭 배치 대상 |

- `MovieRepository`에 `findByKoficMovieCdIn(Collection<String>)` 추가 (1순위 매칭 벌크 조회).
- `BoxOfficeRecord`에 `linkMovie(Movie movie)` 비즈니스 메서드 추가 — 재매칭 시 사용.
  `movieTitleSnapshot`은 **불변 스냅샷이므로 수정 메서드를 만들지 않는다**(기존 규칙 유지).

#### 수집 배치 — `BoxOfficeSyncService`

| 메서드 | 트랜잭션 | 로직 요약 |
|---|---|---|
| `syncDaily(LocalDate targetDate)` | 쓰기 | KOFIC 호출 → 기존 `koficMovieCd` 집합 1쿼리 조회 → **신규만** 저장 |
| `syncWeekly(LocalDate)` / `syncWeekend(LocalDate)` | 쓰기 | 시그니처만 확정, 구현은 일별 안정화 후 |
| `rematchUnlinked(int limit)` | 쓰기 | `movie_id IS NULL` 행을 대상으로 재매칭 시도 |

**멱등성 확보** — `uk_box_office_record (target_date, rank_type, kofic_movie_cd)`가 있어 재실행 시
중복 INSERT가 유니크 위반을 낸다. 삭제 후 재적재가 아니라, **저장 전에 해당
`(targetDate, rankType)`의 기존 코드 집합을 1쿼리로 읽어 신규만 `saveAll`** 한다.
4-5 `addMoviesToCollection`에서 쓴 "기존 키 집합 조회 → 차집합만 저장" 패턴의 재사용이다.

**TMDB ↔ KOFIC 매칭** (기획노트 확정 전략의 구현 배분)

| 순위 | 방법 | 수행 위치 |
|---|---|---|
| 1 | `koficMovieCd` 직접 매칭 (`findByKoficMovieCdIn` 벌크 1쿼리) | **수집 배치** |
| 2 | 제목 완전 일치 + **후보가 유일할 때만** 연결 | **재매칭 배치만** |
| 3 | 실패 → `movie_id = NULL` 유지 (원본 보존) | 공통 |

> **2순위가 "제목 + 개봉연도"가 아닌 이유**: `box_office_record`에 KOFIC의 `openDt`(개봉일)를
> 담을 컬럼이 없어 개봉연도로 후보를 좁힐 수 없다. 대신 제목이 정확히 일치하고 후보가
> **1건일 때만** 연결하고, 2건 이상(동명 영화)이면 오매칭을 피해 보류한다.
> 정확도를 더 올리려면 스키마에 `open_date` 컬럼 추가(v9)가 선행돼야 한다.
>
> 재매칭에 성공하면 해당 `Movie`에 `linkKoficCode()`로 KOFIC 코드를 역으로 채워, 다음 수집부터는
> 1순위 매칭이 바로 걸리게 한다. (단 `uk_movie_kofic_cd` 위반을 피해 비어 있을 때만)

> 수집 배치에서 fuzzy 매칭을 하지 않는 이유: 수집 경로는 **빠르고 결정적**이어야 재실행·복구가
> 쉽다. 비용이 크고 오매칭 위험이 있는 휴리스틱은 언제든 다시 돌릴 수 있는 별도 배치로 분리한다.

**스케줄링**

- `global/config/SchedulingConfig`에 `@EnableScheduling` 추가.
- 일별: KOFIC이 전일 데이터를 익일 제공하므로 `cron = "0 0 5 * * *"` → `syncDaily(어제)`.
- 재매칭: 일별 수집 이후 `cron = "0 30 5 * * *"` → `rematchUnlinked(limit)`.
- **단일 인스턴스 전제**. 다중 인스턴스로 확장하면 중복 실행 방지(ShedLock 등)가 필요하며,
  이는 캡스톤 범위 밖으로 둔다.
- 수동 트리거는 스케줄러와 **동일한 서비스 메서드를 호출**한다. 배치 로직을 두 벌로 만들지 않는다.

#### 조회 — `BoxOfficeQueryService`

| 메서드 | 트랜잭션 | 로직 요약 |
|---|---|---|
| `getBoxOffice(rankType, targetDate)` | 읽기 | `targetDate`가 null이면 `findLatestTargetDate`로 대체 → 조회(@EntityGraph) → 없으면 `BOX_OFFICE_NOT_FOUND` |

#### DTO

| DTO | 필드 |
|---|---|
| `BoxOfficeResponse` | `targetDate`, `rankType`, `List<BoxOfficeItemResponse>` |
| `BoxOfficeItemResponse` | `rank`, `rankChange`, `isNew`, `movieTitleSnapshot`, `movieId`, `posterPath`, `audienceCount`, `audienceAcc`, `salesAmount`, `linked` |

- **미매칭 레코드도 목록에 노출**한다. `movieTitleSnapshot`으로 제목을 보여주고
  `linked = (movieId != null)`로 클라이언트가 상세 화면 링크 활성 여부를 판단한다.
  포스터가 없는 항목이 섞이는 것을 감수하더라도, 순위 목록에 구멍이 나는 편이 더 나쁘다.

---

### 4-7-C. ErrorCode 추가분

| 상수 | HTTP |
|---|---|
| `THEATER_NOT_FOUND` | 404 |
| `INVALID_SEARCH_RADIUS` | 400 |
| `BOX_OFFICE_NOT_FOUND` | 404 |
| `EXTERNAL_API_ERROR` | 502 |

---

### 4-7-D. 잔여 확인 항목

1. ~~관리자 전용 API 인가 방식~~ → **✅ 확정: `user.role` 컬럼 추가 + `hasRole('ADMIN')`**
   (2026-07-29, Step S 세션). 스키마 v9 델타에 포함되며 상세는 `docs/security-spec.md` 참고.
   관리자 엔드포인트는 `/api/admin/**` 하위로 모은다.
2. **극장 표준데이터 좌표계** — WGS84인지 EPSG:5174인지 실제 CSV로 확인 후 변환 필요 여부 결정.
3. **주간/주말 박스오피스 도입 시점** — 일별 수집 안정화 이후로 미룬다.

---

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-07-30 | **Step S 반영 — 4-1(User) 갱신.** `PasswordEncoder` 선행 과제 해소(`PasswordEncoderConfig` 유지, `SecurityConfig`로 이동하지 않음). `signUpOAuth`에 **이메일 충돌 사전 체크**(`EMAIL_ALREADY_REGISTERED_LOCALLY` 409) 추가 — `uk_user_email`/`uk_user_provider`가 독립이라 로컬 가입 이메일과 겹치면 원인 불명의 409 `DUPLICATE_REQUEST`가 나가던 문제. `login(email, rawPassword)`·`changePassword(...)` 신규(자격증명 검증은 User 도메인, 토큰 발급·폐기 조율은 `AuthService`로 책임 분리). 인증 관련 `ErrorCode` 9건은 `security-spec.md` S-6 참고 |
| 2026-07-23 | 4-0(공통 인프라), 4-1(User) 설계 확정. 이후부터 코드 구현은 Claude Code에 위임, 본 문서는 스펙만 관리 |
| 2026-07-23 | 4-2(Movie + 참조 엔티티 조회) 설계 확정. N+1 회피 전략(상세: 관계별 개별 쿼리 5방 / 목록: 관계별 IN절 벌크 조회 3방 + Service 그룹핑)을 표준 패턴으로 채택, 이후 도메인에 재사용 예정. `MovieSyncService`는 시그니처만 확정 |
| 2026-07-23 | 4-2 구현 중 조정: `getMovieList`/`searchMovies`에서 미설계 타입인 `MovieSearchCondition` 파라미터 제거(Pageable만 사용). `MovieSyncService`는 스펙 명시대로 파일 미생성. 부수적으로 flat 패키지 `MovieRepository` 삭제 및 `domain.movie.repository`로 이전, 이전 세션 회귀로 깨져있던 `MovieRepositoryTest` 수정 |
| 2026-07-23 | 4-3(WatchRecord) 설계 확정. 대표 기록 삭제 시 자동 재선정, 수동 재지정(`setRepresentative`) API 추가, `watchType`↔`ottPlatform` 정합성은 Service 레벨 검증으로 확정(DB CHECK 미도입). "내 영화" 목록은 4-2 벌크 조회 표준 패턴 재사용. 소유자 검증 패턴(`WATCH_RECORD_ACCESS_DENIED`)을 이후 개인 기록 도메인의 표준으로 채택 |
| 2026-07-23 | 4-3 구현 중 조정: 스펙 누락분 발견 — `ottPlatformId` 검증 누락으로 `getReferenceById()` 사용 중이던 것을 `findById().orElseThrow(OTT_PLATFORM_NOT_FOUND)`로 수정, `ErrorCode` 목록에 반영. 사용자 입력 FK는 `getReferenceById()` 금지 원칙을 이후 도메인 공통 원칙으로 명문화. (별건) `WatchRecord` 엔티티 필드명이 Step3 스펙(`note`)과 달리 `review`로 구현돼 있던 걸 발견 — 별도 리팩터링 커밋으로 엔티티 필드명 수정 필요 (코드만 수정, 문서 변경 없음) |
| 2026-07-23 | 4-4(Review, WishMovie) 설계 확정. Review는 upsert 방식(`writeReview`), WishMovie는 단일 토글(`toggleWish`)로 결정. `GlobalExceptionHandler`에 `IllegalArgumentException` 핸들러 추가(엔티티 레벨 검증 예외 처리 일원화). Review는 소유자 검증 패턴이 구조적으로 불필요함을 확인, 인증된 본인 `userId`는 `getReferenceById()` 사용 가능하다는 원칙 추가(4-3의 보수적 선택은 유지, 4-4부터 표준화). "내 위시리스트"는 4-2/4-3 벌크 조회 패턴 세 번째 재사용 사례 |
| 2026-07-29 | 4-6(Follow, Comment) 설계 확정. `UserAccessPolicy` 공통 컴포넌트 도입(`global/access`) — `FRIENDS` = 상호 팔로우, 비로그인(`viewerId == null`) 조회 허용. `follow`/`unfollow` 분리 + 양쪽 멱등(4-4 `toggleWish`와 다른 선택, 근거 명시). `CommentTargetResolver`(id 프로젝션 1쿼리로 존재검증+소유자조회 통합)로 다형 대상 분기 통합. 댓글 수정=작성자, 삭제=작성자+대상 소유자. **고아 댓글 문제 발견** — `comment.target_id`에 FK가 없어 대상 삭제 시 잔존, `deleteCollection`/`deleteReview`에 선행 삭제 소급 반영. 4-2~4-5 조회 메서드의 `viewerId` 소급 적용 과제 도출 |
| 2026-07-29 | 4-6 구현 중 조정 3건. ① 동시 팔로우 경합 멱등화를 서비스 내부 `try/catch`로 설계했으나 **JPA IDENTITY 전략에서는 `save()` 시점에 INSERT가 즉시 실행되고 트랜잭션이 rollback-only로 마킹되어 catch해도 커밋이 실패**함을 확인 → `GlobalExceptionHandler`에 `DataIntegrityViolationException` 핸들러(409 `DUPLICATE_REQUEST`)를 두는 방식으로 변경. ② `CommentRepository.deleteByTarget`에 `@Modifying(clearAutomatically = true)`를 적용하면 `deleteCollection`에서 앞선 `deleteAllByCollectionId`의 **미flush remove가 폐기되어 FK RESTRICT 위반**이 발생 → `clearAutomatically` 미사용으로 확정(주석에 근거 기록). ③ `CommentResponse`의 작성자 필드를 flat이 아닌 중첩 `CommentAuthorResponse`로 변경(`ReviewResponse`/`ReviewAuthorResponse` 기존 스타일과 통일). `Comment` 생성은 CLAUDE.md의 "필드 4개 이상 → `@Builder`" 규칙에 따라 기존 `@Builder` 유지(`of()` 미추가), `editContent()`만 신규 추가. DTO의 Bean Validation은 `spring-boot-starter-validation` 미도입 상태라 Step5(Controller + `@Valid`)로 이월 |
| 2026-07-29 | 4-6-E 소급 과제 완료. 조회 메서드 6종에 `(viewerId, targetUserId, …)` 시그니처 + `validateCanView` 적용(`getMyMovieList`→`getUserMovieList`, `getMyWishList`→`getUserWishList` 리네임 포함). `getCollectionMovies`는 `findOwnerIdById` 프로젝션으로 소유자 확인 후 검증. `getMovieReviews`는 작성자가 다수라 `filterViewable` 벌크 판정 후 필터링(페이지 size/totalElements 부정확 한계 문서화). `com.project.cinemory.repository` 중복 패키지 삭제 + `MovieRepositoryTest` import 정정. `WatchRecord.note`는 확인 결과 이미 반영된 상태였음 |
| 2026-07-29 | 4-7 구현 완료 및 구현 중 조정 3건. ① **재매칭 2순위 전략 축소** — 기획상 "한글 제목 + 개봉연도" 매칭이었으나 `box_office_record`에 KOFIC `openDt`를 담을 컬럼이 없어 개봉연도로 후보를 좁힐 수 없음을 확인 → **제목 완전 일치 + 후보 유일할 때만 연결**로 축소(2건 이상은 오매칭 방지를 위해 보류). 정확도 개선은 `open_date` 컬럼 추가(v9) 선행 필요. ② 재매칭 성공 시 `Movie.linkKoficCode()`로 KOFIC 코드를 역으로 채워 다음 수집부터 1순위 매칭이 걸리게 함(단, `uk_movie_kofic_cd` 위반 방지를 위해 비어 있을 때만). ③ 외부 데이터를 신뢰하지 않기 위해 NOT NULL 대상(`rank`/`movieCd`/`movieNm`) 누락 항목을 저장 전 필터링하는 `hasRequiredFields` 추가. 설정값(cron/반경/limit)은 `application.yml`의 `cinemory.*`로 외부화, `kofic.api-key` 미설정 시 스케줄러가 경고 로그만 남기고 건너뜀 |
| 2026-07-29 | 4-7(Theater, BoxOfficeRecord) 설계 확정. 주변 극장은 **Bounding Box 1차 필터 + Service Haversine** 2단계(스키마 무변경, 복합 인덱스의 range 이후 컬럼 한계와 한국 위도 범위 특성을 근거로 채택). 극장은 `sourceCode` 기준 **1회성 시드 upsert**(주기 배치 미도입). 박스오피스는 `@Scheduled` 자동 + 관리자 수동 트리거가 **동일 서비스 메서드**를 공유, 멱등성은 "기존 `koficMovieCd` 집합 조회 → 차집합만 저장"(4-5 패턴 재사용)으로 확보. TMDB 매칭은 **수집 배치=코드 직접 매칭만 / 재매칭 배치=fuzzy 포함**으로 분리해 수집 경로를 결정적으로 유지. 미매칭 레코드는 `movieTitleSnapshot` + `linked` 플래그로 노출. `global/infra/kofic` 패키지 신설, `@EnableScheduling`은 단일 인스턴스 전제 |
| 2026-07-23 | 4-5(Collection, CollectionMovie) 설계 확정. `collection_movie→collection` FK가 RESTRICT임을 발견 — 스키마는 유지하고 `deleteCollection`에서 하위 행 명시적 정리로 대응. 영화 추가는 벌크·idempotent, 제거는 단건으로 분리(토글 아님). 컬렉션 영화 개수는 벌크 그룹 카운트로 조회. 컬렉션 상세/목록 조회는 공개(소유자 검증 없음), 쓰기는 소유자 검증 — `Comment.targetType=COLLECTION` 확정 사항에 근거. `MovieDirectorRepository`에 벌크 조회 메서드 추가(4-2에서 예고했던 확장) |
