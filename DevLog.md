# CineMory Backend DevLog

작업 완료 후 세션 단위로 진행 상황을 기록한다. 상세 스펙은 `docs/jpa-entity-spec.md`,
공통 규칙은 `CLAUDE.md` 참고.

---

## 2026-07-23

### Step2 — 매핑/로그 엔티티 (1~7) 구현 완료

- `MovieGenre` (`domain.movie.entity`) — `movie`/`genre` FK, `weight` decimal(4,3), `uk_movie_genre`
- `MovieCountry` (`domain.movie.entity`) — `movie`/`country` FK, `weight` decimal(4,3), `uk_movie_country`
- `MovieActor` (`domain.movie.entity`) — `movie`/`person` FK, `characterName`, `roleTier`, `uk_movie_actor`
  - `RoleTier` enum(`LEAD 0.5` / `SUPPORTING 0.4` / `MINOR 0.1`) 애플리케이션 상수로 별도 생성
- `MovieDirector` (`domain.movie.entity`) — `movie`/`person` FK, `uk_movie_director`
- `WishMovie` (`domain.wish.entity`) — `user`/`movie` FK, `BaseTimeEntity`, `uk_wish_movie`
- `BoxOfficeRecord` (`domain.boxoffice.entity`) — `movie` FK nullable(TMDB 미매칭 선적재 지원), `movieTitleSnapshot`은 수정 메서드 없이 불변 유지, `uk_box_office_record(target_date, rank_type, kofic_movie_cd)`
  - `RankType{DAILY,WEEKLY,WEEKEND}` enum 별도 생성
- `CollectionMovie` (`domain.collection.entity`) — `Collection` 구현 후 진행, `BaseTimeEntity`(다른 매핑 엔티티와 달리 `updated_at` 있음), `uk_collection_movie`

### Step3 — 사용자 활동 엔티티 구현 완료

- `Follow` (`domain.follow.entity`) — `follower`/`following` FK, `uk_follow`, DB 체크 제약 `chk_follow_not_self`를 `of()` 팩토리에서 `IllegalArgumentException`으로 선반영
- `Collection` (`domain.collection.entity`) — `user`/`name`/`description`
- `Comment` (`domain.comment.entity`) — `targetType`/`targetId`는 연관관계 매핑 없이 순수 컬럼(Enum+Long)으로 유지 (다형성 A안 확정)
  - `TargetType{COLLECTION,REVIEW}` enum 별도 생성
- `Review` (`domain.review.entity`) — `uk_review(user_id, movie_id)`, `rating`/`content` not null
- `WatchRecord` (`domain.watch.entity`) — `markAsRepresentative()` / `unmarkAsRepresentative()` 상태 전환 메서드만 엔티티에 노출, 같은 (user, movie) 내 기존 대표 기록 해제 조율 로직은 추후 `WatchRecordService`에서 처리 예정 (미구현)
  - `WatchType{THEATER,OTT,ETC}` enum 별도 생성

### 정리 작업

- `domain.common.entity`에 잘못 위치해 있던 기존 `Collection`/`CollectionMovie`/`WatchRecord`/`WishMovie` stub 삭제
  (패키지 위치·Base Entity·필드 구성이 스펙과 불일치했음 — Step1/Step2 이전 세션에서 임시로 만들어진 것으로 추정)
- 위 stub을 참조하던 `CollectionRepository`/`CollectionMovieRepository`/`WatchRecordRepository`/`WishMovieRepository`의 import 경로를 새 패키지로 갱신 (리포지토리 로직 자체는 미변경)
- `./gradlew compileJava` 통과 확인

### Step4 — Repository/Service 계층 착수 (`docs/service-layer-spec.md` 기준)

**4-0. 공통 인프라** (`global.exception`)
- `ErrorCode`(`USER_NOT_FOUND`, `DUPLICATE_EMAIL`, `MOVIE_NOT_FOUND`) / `BusinessException` / `ErrorResponse` / `GlobalExceptionHandler(@RestControllerAdvice)` 구현

**4-1. User 도메인**
- `UserRepository`(`domain.user.repository`), `SignUpLocalRequest`/`UserResponse`(`domain.user.dto`), `UserService`(`domain.user.service`) — 스펙 표에 정의된 메서드 전부 구현
- `signUpLocal` 비밀번호 인코딩을 위해 `PasswordEncoder` 빈이 필요했는데, 아직 `SecurityConfig`(필터체인/로그인)는 착수 전이라 `spring-security-crypto`(웹 보안 자동설정 없는 순수 crypto 모듈)만 `build.gradle`에 추가하고 `PasswordEncoderConfig`에 `BCryptPasswordEncoder` 빈만 등록. 이후 진짜 `SecurityConfig` 만들 때 통합 필요

**4-2. Movie + 참조 엔티티 조회**
- `domain.movie.repository`(`MovieRepository`/`MovieGenreRepository`/`MovieCountryRepository`/`MovieActorRepository`/`MovieDirectorRepository`, `@EntityGraph`로 참조 엔티티 fetch join) + `domain.genre/country/person.repository`(참조 테이블용 빈 리포지토리)
- `domain.movie.dto` — `GenreResponse`/`CountryResponse`/`ActorResponse`/`DirectorResponse`(하위 항목) + `MovieDetailResponse`/`MovieListItemResponse`/`MovieSummaryResponse`
- `MovieQueryService.getMovieDetail` — movie 1 + 관계별 4개 개별 조회 = 고정 5쿼리
- `MovieQueryService.getMovieList` — `findByMovieIdIn` 벌크 조회 + `movieId` 기준 Service 레이어 그룹핑으로 N+1 회피(페이지당 고정 3쿼리). 이 패턴은 이후 "내 영화" 목록(4-3) 등에서도 재사용 예정
- `getMovieList`/`searchMovies`의 검색 조건(`condition`) 필드가 스펙에 명시되어 있지 않아 확인 후, 지금은 필터 없이 `Pageable`만 받는 것으로 확정 (실제 필터 요구사항 나오면 `MovieSearchCondition` 추가 예정)
- `MovieSyncService`(TMDB 연동)는 시그니처가 아직 존재하지 않는 `TmdbGenreDto` 등을 참조하고 스펙 자체가 "별도 세션에서 구현"이라 명시해서 이번엔 파일로 만들지 않음

### 정리 작업 (2차)

- 이전 세션에서 `domain.common.entity` stub을 지우면서 이를 참조하던 `MovieRepositoryTest`가 깨져 있었음(`compileJava`만 확인하고 `compileTestJava`는 놓쳤던 회귀). 엔티티의 현재 팩토리 API(`of()`/`@Builder`)에 맞게 테스트를 다시 맞추고 `User` 생성 로직을 추가해서 수정
- 기존 flat 패키지의 `com.project.cinemory.repository.MovieRepository`를 `domain.movie.repository.MovieRepository`로 이전(스펙에 정의된 파생 쿼리 2개 추가), 참조하던 테스트 import 갱신
- `./gradlew compileJava`, `compileTestJava` 통과 확인

### 다음 작업 후보

- Step4 로드맵: 4-3 `WatchRecord`, 4-4 `Review`/`WishMovie`, 4-5 `Collection`/`CollectionMovie`, 4-6 `Follow`/`Comment`, 4-7 `Theater`/`BoxOfficeRecord`(외부 API 배치)
- `WatchRecordService`의 대표 기록(`is_representative`) 단일성 보장 로직 — 4-3에서 다룰 예정
- `SecurityConfig`(인증/인가, 필터체인) 정식 설계 — 현재는 `PasswordEncoder` 빈만 임시로 존재
- `MovieSyncService` 구현 — TMDB 연동 DTO/배치 설계가 선행되어야 함
- Controller 계층은 아직 미착수 (Repository/DTO/Service까지만 진행된 상태)
