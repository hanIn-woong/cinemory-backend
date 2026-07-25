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
| 4-4 | `Review`, `WishMovie` | 예정 |
| 4-5 | `Collection`, `CollectionMovie` | 예정 |
| 4-6 | `Follow`, `Comment` | 예정 |
| 4-7 | `Theater`, `BoxOfficeRecord` (외부 API 연동 배치 포함, 별도 설계 필요) | 예정 |

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
- 이후 `MethodArgumentNotValidException`(`@Valid` 검증 실패) 핸들러 추가 예정

---

## 4-1. User 도메인 (✅ 확정)

### 선행 작업
- Spring Security 설정에서 `PasswordEncoder`(`BCryptPasswordEncoder`) Bean 등록 필요
  (아직 `SecurityConfig` 미착수 — Step4 진행 중 별도로 다룰 것)

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
| `signUpOAuth(email, nickname, profileImage, provider, providerId)` | 쓰기 | `findByProviderAndProviderId` 있으면 기존 유저 반환(멱등 처리), 없으면 `User.createOAuth()` 후 저장 |
| `getUser(userId)` | 읽기 | 없으면 `USER_NOT_FOUND` |
| `changeNickname(userId, nickname)` | 쓰기 | 조회 → `user.changeNickname()` → dirty checking으로 반영 |
| `changePrivacySetting(userId, privacySetting)` | 쓰기 | 조회 → `user.changePrivacySetting()` |
| `findUserOrThrow(userId)` (private 헬퍼) | - | 반복되는 "조회 후 없으면 예외" 패턴 공통화 |

### 설계 노트
- OAuth 회원가입은 소셜 로그인 재시도가 흔하므로 존재 시 기존 유저를 반환하는 멱등 구조로 설계.
  컨트롤러에서 사전 존재 체크 로직을 중복시키지 않기 위함.
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
}

// 참조 테이블 — 커스텀 쿼리 불필요
public interface GenreRepository extends JpaRepository<Genre, Long> {}
public interface CountryRepository extends JpaRepository<Country, Long> {}
public interface PersonRepository extends JpaRepository<Person, Long> {}
```

> `MovieActor`/`MovieDirector`는 목록(벌크) 조회 대상에서 제외되므로 `findByMovieIdIn`을
> 두지 않는다. 추후 목록 화면에 출연진 노출 요구가 생기면 그때 벌크 메서드를 추가한다.

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

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-07-23 | 4-0(공통 인프라), 4-1(User) 설계 확정. 이후부터 코드 구현은 Claude Code에 위임, 본 문서는 스펙만 관리 |
| 2026-07-23 | 4-2(Movie + 참조 엔티티 조회) 설계 확정. N+1 회피 전략(상세: 관계별 개별 쿼리 5방 / 목록: 관계별 IN절 벌크 조회 3방 + Service 그룹핑)을 표준 패턴으로 채택, 이후 도메인에 재사용 예정. `MovieSyncService`는 시그니처만 확정 |
| 2026-07-23 | 4-2 구현 중 조정: `getMovieList`/`searchMovies`에서 미설계 타입인 `MovieSearchCondition` 파라미터 제거(Pageable만 사용). `MovieSyncService`는 스펙 명시대로 파일 미생성. 부수적으로 flat 패키지 `MovieRepository` 삭제 및 `domain.movie.repository`로 이전, 이전 세션 회귀로 깨져있던 `MovieRepositoryTest` 수정 |
| 2026-07-23 | 4-3(WatchRecord) 설계 확정. 대표 기록 삭제 시 자동 재선정, 수동 재지정(`setRepresentative`) API 추가, `watchType`↔`ottPlatform` 정합성은 Service 레벨 검증으로 확정(DB CHECK 미도입). "내 영화" 목록은 4-2 벌크 조회 표준 패턴 재사용. 소유자 검증 패턴(`WATCH_RECORD_ACCESS_DENIED`)을 이후 개인 기록 도메인의 표준으로 채택 |
| 2026-07-23 | 4-3 구현 중 조정: 스펙 누락분 발견 — `ottPlatformId` 검증 누락으로 `getReferenceById()` 사용 중이던 것을 `findById().orElseThrow(OTT_PLATFORM_NOT_FOUND)`로 수정, `ErrorCode` 목록에 반영. 사용자 입력 FK는 `getReferenceById()` 금지 원칙을 이후 도메인 공통 원칙으로 명문화. (별건) `WatchRecord` 엔티티 필드명이 Step3 스펙(`note`)과 달리 `review`로 구현돼 있던 걸 발견 — 별도 리팩터링 커밋으로 엔티티 필드명 수정 필요 (코드만 수정, 문서 변경 없음) |
