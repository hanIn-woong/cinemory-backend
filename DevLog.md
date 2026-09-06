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

---

## 2026-07-25

### Step4-3 — WatchRecord Repository/Service 구현 완료 (`docs/service-layer-spec.md` 4-3 기준)

- `ErrorCode`에 `WATCH_RECORD_NOT_FOUND`/`WATCH_RECORD_ACCESS_DENIED`/`INVALID_WATCH_TYPE_OTT_COMBINATION`/`OTT_PLATFORM_NOT_FOUND` 추가
- `domain.ott.repository.OttPlatformRepository` — 4-2와 동일하게 참조 테이블용 빈 리포지토리만 생성 (Service 없음)
- `domain.watch.repository.WatchRecordRepository` — 4-2 이전 패턴대로 기존 flat 패키지(`com.project.cinemory.repository`)의 stub을 삭제하고 신규 이전, 스펙에 정의된 파생 쿼리 3개(`findByUserIdAndMovieIdAndRepresentativeTrue`, `findByUserIdAndMovieIdOrderByIdDesc`, `findByUserIdAndRepresentativeTrue` + `@EntityGraph("movie")`) 구현
- `domain.watch.dto` — `WatchRecordCreateRequest`/`WatchRecordResponse`/`MyMovieListItemResponse`/`OttPlatformResponse`(참조 엔티티 응답, `movie/dto`의 `GenreResponse`/`CountryResponse`와 동일한 위치 원칙)
- `WatchRecordService`(`domain.watch.service`) — `addWatchRecord`/`deleteWatchRecord`(대표 삭제 시 최신 기록으로 자동 재선정)/`setRepresentative`(멱등)/`getMyMovieList`(4-2 벌크 조회+그룹핑 패턴 재사용)/`getWatchLog` 전부 구현. 대표 재조율 공통 로직은 `unmarkCurrentRepresentative()` private 헬퍼로 분리
- `getMyMovieList`는 `WatchRecordRepository`를 진입점으로 하되 `movieGenreRepository`/`movieCountryRepository`의 `findByMovieIdIn` 벌크 조회는 4-2 것을 그대로 재사용 (페이지당 고정 3쿼리 유지)

### 스펙 갱신에 따른 후속 수정

- 사용자가 `service-layer-spec.md`에 4-3 확정 내용을 반영하면서 초기 구현의 누락분 2가지를 지적:
  1. `addWatchRecord`에서 `ottPlatformId` 존재 검증 없이 `ottPlatformRepository.getReferenceById()`를 쓰고 있던 것을 `findById().orElseThrow(OTT_PLATFORM_NOT_FOUND)`로 수정. 잘못된 FK를 그대로 넘기면 지연 프록시가 생성되고 실제 위반은 flush 시점 FK 제약 오류로 터져 `BusinessException` 체계를 우회하는 문제였음
     - 스펙 표 문구는 "watchType == OTT이면 조회 → validateWatchTypeConsistency()" 순서였지만, 그대로 구현하면 `watchType == OTT`인데 `ottPlatformId`가 `null`인 케이스에서 `findById(null)`이 `INVALID_WATCH_TYPE_OTT_COMBINATION`보다 먼저 기술적 예외를 던지는 문제가 있어 `validateWatchTypeConsistency()`를 먼저 실행하도록 순서 조정 (의도한 두 에러 모두 정상 발생하는 것 확인)
     - "사용자 입력 FK는 `findById().orElseThrow()`만 사용, `getReferenceById()` 금지" 원칙이 스펙에 이후 도메인 공통 원칙으로 명문화됨
  2. `WatchRecord` 엔티티 필드명이 Step3 스펙(`note`)과 다르게 `review`로 구현되어 있던 것을 발견 → 필드명만 `note`로 리팩터링(컬럼명은 기존 `review` 유지, `@Column(name = "review")`). `WatchRecordResponse`/`WatchRecordService`/`MovieRepositoryTest`의 참조 전부 갱신
- `./gradlew compileJava compileTestJava` 통과 확인

### 다음 작업 후보 (갱신)

- Step4 로드맵: 4-4 `Review`/`WishMovie`, 4-5 `Collection`/`CollectionMovie`, 4-6 `Follow`/`Comment`, 4-7 `Theater`/`BoxOfficeRecord`(외부 API 배치)
- 4-3에서 확정된 "소유자 검증(`XXX_ACCESS_DENIED`)"과 "사용자 입력 FK는 `findById().orElseThrow()`만 사용" 두 원칙을 4-4부터 표준으로 재사용
- `SecurityConfig`(인증/인가, 필터체인) 정식 설계 — 현재는 `PasswordEncoder` 빈만 임시로 존재
- `MovieSyncService` 구현 — TMDB 연동 DTO/배치 설계가 선행되어야 함
- Controller 계층은 아직 미착수 (Repository/DTO/Service까지만 진행된 상태)

---

### Step4-4 — Review/WishMovie Repository/Service 구현 완료 (`docs/service-layer-spec.md` 4-4 기준)

- `ErrorCode`에 `REVIEW_NOT_FOUND` 추가. `ErrorResponse`에 `of(HttpStatus, String)` 오버로드 추가, `GlobalExceptionHandler`에 `IllegalArgumentException` 핸들러 추가(HTTP 400 + 예외 메시지 그대로 응답) — 엔티티 레벨 검증(`Review.validateRating()` 등) 예외를 일원화된 방식으로 처리하기 위함
- **엔티티 보정**: `Review`가 Step3에서 이미 `jpa-entity-spec.md`에 `of()` 팩토리 + `validateRating()`(0.0~10.0) + `update()` 재검증으로 확정돼 있었는데 실제로는 `@Builder`만 있고 검증 로직이 전혀 없었던 것을 발견 (4-3의 `note` 필드명 누락과 같은 성격의 엔티티-스펙 불일치) → `@Builder` 제거하고 스펙대로 `of()`/`update()`/`validateRating()` 구현
- `domain.review.repository.ReviewRepository` — `findByUserIdAndMovieId`, `findByMovieId`(`@EntityGraph("user")`, 영화 상세 리뷰 목록의 작성자 정보 로딩용)
- `domain.wish.repository.WishMovieRepository` — 4-2/4-3과 동일하게 기존 flat 패키지(`com.project.cinemory.repository`)의 stub 삭제 후 신규 이전, `findByUserIdAndMovieId`/`existsByUserIdAndMovieId`/`findByUserIdOrderByIdDesc`(`@EntityGraph("movie")`) 구현
- `domain.review.dto` — `ReviewWriteRequest`/`ReviewResponse`(+ 작성자 요약 `ReviewAuthorResponse`), `domain.wish.dto` — `WishToggleResponse`/`WishListItemResponse`(4-2 `GenreResponse`/`CountryResponse` 재사용)
- `ReviewService`(`domain.review.service`) — `writeReview`(upsert: 있으면 `update()`로 dirty checking, 없으면 `Review.of()` 생성 후에만 `save()`)/`deleteReview`/`getMyReview`(`Optional` 반환, 리뷰 없음은 정상 상태)/`getMovieReviews`
- `WishMovieService`(`domain.wish.service`) — `toggleWish`(있으면 삭제, 없으면 저장)/`isWished`(`existsBy`만 사용, 엔티티 조회 없음)/`getMyWishList`(4-2/4-3 벌크 조회+그룹핑 패턴 세 번째 재사용 사례)
- 스펙에서 새로 정한 원칙 적용: 인증된 본인 `userId`는 신뢰값이므로 `userRepository.getReferenceById(userId)` 사용(4-3의 `findById` 방식보다 완화), `movieId`처럼 요청으로 들어오는 FK는 계속 `findById().orElseThrow()` 유지
- `./gradlew compileJava compileTestJava` 통과 확인

### 다음 작업 후보 (갱신 2차)

- Step4 로드맵: 4-5 `Collection`/`CollectionMovie`, 4-6 `Follow`/`Comment`, 4-7 `Theater`/`BoxOfficeRecord`(외부 API 배치)
- 4-4에서 정리된 "본인 `userId`는 `getReferenceById()`, 요청 기반 FK는 `findById().orElseThrow()`" 구분 원칙을 4-5부터 표준으로 재사용
- 엔티티가 `jpa-entity-spec.md`와 실제 구현이 어긋나는 사례(4-3 `note`, 4-4 `Review.of()`/`validateRating()`)가 반복 발견됨 — 아직 Service 구현에 안 들어간 나머지 엔티티(`Collection`/`CollectionMovie`/`Follow`/`Comment`/`Theater`/`BoxOfficeRecord`)도 착수 전 스펙 대조 확인 필요
- `SecurityConfig`(인증/인가, 필터체인) 정식 설계 — 현재는 `PasswordEncoder` 빈만 임시로 존재
- `MovieSyncService` 구현 — TMDB 연동 DTO/배치 설계가 선행되어야 함
- Controller 계층은 아직 미착수 (Repository/DTO/Service까지만 진행된 상태)

---

## 2026-07-29

### Step4-5 — Collection/CollectionMovie Repository/Service 구현 완료 (`docs/service-layer-spec.md` 4-5 기준)

- `ErrorCode`에 `COLLECTION_NOT_FOUND`/`COLLECTION_ACCESS_DENIED`/`COLLECTION_MOVIE_NOT_FOUND` 추가
- **엔티티 보정**: `Collection`이 `jpa-entity-spec.md`에 `update(String name, String description)` 비즈니스 메서드로 이미 확정돼 있었는데 실제로는 구현이 빠져 있었음(4-3 `note`, 4-4 `Review.of()`/`validateRating()`과 동일한 성격의 엔티티-스펙 불일치) → 스펙대로 `update()` 추가
- `MovieDirectorRepository`에 `findByMovieIdIn`(`@EntityGraph("person")`) 벌크 조회 추가 — 컬렉션 상세(영화 목록)의 감독명 표시용(4-2 섹션에 예고됐던 확장)
- `domain.collection.repository` — `CollectionRepository`(`findByUserId`), `CollectionMovieRepository`(`findByCollectionIdAndMovieId`/`findByCollectionIdAndMovieIdIn`/`findByCollectionId`(`@EntityGraph("movie")`)/`deleteAllByCollectionId`/`countGroupByCollectionIdIn`(`@Query` + `GROUP BY`)), `CollectionMovieCountProjection`
- `domain.collection.dto` — `CollectionCreateRequest`/`CollectionUpdateRequest`/`CollectionResponse`(`from(Collection, long movieCount)`)/`AddMoviesToCollectionRequest`/`AddMoviesToCollectionResponse`/`CollectionMovieListItemResponse`(감독명은 콤마 join, `releaseYear`는 `releaseDate` null 가능성 방어)
- `CollectionService`(`domain.collection.service`) — `createCollection`/`updateCollection`/`deleteCollection`(RESTRICT 대응으로 `collection_movie` 먼저 정리)/`getCollections`(4-2/4-3/4-4 벌크 조회+그룹핑 패턴을 집계값에 적용한 변형)/`addMoviesToCollection`(벌크·idempotent, 미존재 movieId 있으면 전체 롤백)/`removeMovieFromCollection`(단건)/`getCollectionMovies`(공개 조회, director 벌크 조회+그룹핑) — `getOwnedCollectionOrThrow` private 헬퍼로 소유자 검증 공통화
- `getCollections`/`getCollectionMovies`는 소유자 검증 없는 공개 조회, 나머지 쓰기 메서드는 `getOwnedCollectionOrThrow`를 거치도록 스펙대로 구현
- `./gradlew compileJava compileTestJava` 통과 확인

### 다음 작업 후보 (갱신 3차)

- Step4 로드맵: 4-6 `Follow`/`Comment`, 4-7 `Theater`/`BoxOfficeRecord`(외부 API 배치)
- 엔티티-스펙 불일치가 4-3/4-4/4-5에서 연달아 발견됨 — 4-6 착수 전 `Follow`/`Comment` 엔티티도 `jpa-entity-spec.md` 대조 확인 필요
- `SecurityConfig`(인증/인가, 필터체인) 정식 설계 — 현재는 `PasswordEncoder` 빈만 임시로 존재
- `MovieSyncService` 구현 — TMDB 연동 DTO/배치 설계가 선행되어야 함
- Controller 계층은 아직 미착수 (Repository/DTO/Service까지만 진행된 상태)

---

### Step4-6 — Follow/Comment + 공개범위 접근 제어 구현 완료 (`docs/service-layer-spec.md` 4-6 기준)

4-6은 **타인의 데이터를 조회하는 첫 도메인**이라, 설계 단계에서 `user.privacy_setting` 접근 제어를 범위에 포함시켰다.

**설계 확정 사항 (대화로 결정)**

- `FRIENDS` = **상호 팔로우(맞팔)**. 단방향 팔로우만으로 비공개 데이터가 노출되는 것을 막기 위함
- `follow`/`unfollow` **엔드포인트 분리**(4-4 `toggleWish`와 다른 선택) — 팔로우는 상대에게 노출되는 관계 행위라 네트워크 재시도로 의도치 않게 해제되는 토글 방식을 배제. 대신 **양쪽 모두 멱등**으로 처리
- 댓글 **수정은 작성자만, 삭제는 작성자 + 대상(컬렉션/리뷰) 소유자**
- 비로그인(`viewerId == null`) 조회 **허용** — null을 예외가 아닌 정상 입력으로 취급, PUBLIC만 통과

**구현**

- `ErrorCode` 추가: `UNAUTHORIZED`/`ACCESS_DENIED`/`CANNOT_FOLLOW_SELF`/`COMMENT_NOT_FOUND`/`NO_COMMENT_PERMISSION`/`UNSUPPORTED_COMMENT_TARGET`/`DUPLICATE_REQUEST`
- `global.access.UserAccessPolicy` 신규 — `validateCanView`/`canView`/`filterViewable`. 접근 제어는 특정 도메인 책임이 아닌 전역 정책이라 `domain.follow` 하위에 두지 않음(의존은 `global.access → domain.*.repository` 단방향)
  - 단건 최대 2쿼리(user 1 + FRIENDS일 때만 `countMutual` 1). 맞팔 판정은 `existsBy` 2회가 아니라 `countMutual` 결과가 `2`인지로 확인
  - 벌크 `filterViewable`은 최대 3쿼리 고정 — PUBLIC/본인은 쿼리 없이 통과, 남은 FRIENDS 후보에만 "내가 팔로우한 id"/"나를 팔로우한 id"를 각각 1쿼리로 읽어 메모리 교집합(4-2 벌크 패턴의 재사용)
- `domain.follow` — `FollowRepository`(`existsBy`/`deleteBy`/`countByFollowerId`/`countByFollowingId`/`findByFollowingId`·`findByFollowerId`(`@EntityGraph`)/`countMutual`/`findFollowingIdsIn`/`findFollowerIdsIn`), `FollowUserResponse`/`UserProfileResponse`, `FollowService`(`follow`/`unfollow`/`getFollowers`/`getFollowings`/`getUserProfile`)
  - 프로필 헤더(`getUserProfile`)는 **비공개여도 노출**. 헤더까지 막으면 팔로우 요청을 보낼 화면 자체가 없어지므로, 차단 대상은 시청기록/컬렉션/리뷰 등 콘텐츠로 한정
- `domain.comment` — `CommentRepository`(`findByTargetTypeAndTargetIdOrderByIdAsc`(`@EntityGraph("user")`)/`countByTargetTypeAndTargetId`/`countGroupByTargetIdIn`/`deleteByTarget`), `CommentCountProjection`, `CommentTargetResolver` + 구현 2종, DTO 4종, `CommentService`
  - **다형 대상 분기 통합**: `targetType` 분기가 (1)존재 검증 (2)소유자 확인 (3)공개범위 판정 세 지점으로 흩어지는 걸 막기 위해 "대상의 소유자 userId를 반환한다"는 단일 책임 인터페이스로 통합. id 프로젝션만 조회하므로 **1쿼리로 존재 검증 + 소유자 조회 동시 처리**(`existsById` + 소유자 조회 2쿼리보다 유리)
  - `List<CommentTargetResolver>`를 주입받아 **생성자에서** `EnumMap`으로 변환(Spring의 `Map` 자동 주입은 키가 빈 이름이라 `TargetType` 키로 못 씀)
  - 삭제 권한 판정을 "작성자 우선 → 소유자 fallback" 순으로 둬서, 일반적인 경우(자기 댓글 삭제)에는 resolver 쿼리가 아예 발생하지 않음
- **엔티티 보정**: `Comment`에 `jpa-entity-spec.md`상 있어야 할 `editContent()`가 빠져 있어 추가(4-3 `note`, 4-4 `Review.of()`, 4-5 `Collection.update()`에 이은 네 번째 불일치). 생성 팩토리는 스펙의 `of()` 대신 **기존 `@Builder` 유지** — CLAUDE.md의 "필드 4개 이상 → `@Builder`" 규칙이 우선

**⚠️ 신규 발견 — 고아 댓글 (소급 반영)**

`comment.target_id`는 다형 참조라 FK가 없다. `Collection`/`Review`를 삭제해도 댓글이 DB에 남아, **재사용된 AUTO_INCREMENT id에 과거 댓글이 붙어 보이는 데이터 오염**이 발생할 수 있음. 4-5에서 `RESTRICT` FK를 서비스 레이어에서 푼 것과 동일한 원칙으로 `CollectionService.deleteCollection()`/`ReviewService.deleteReview()`에 댓글 선행 삭제를 추가.

**구현 중 스펙 조정 3건**

1. **팔로우 멱등화 방식 변경** — 서비스 내부 `try/catch`로 유니크 위반을 흡수하려 했으나, **JPA IDENTITY 전략에서는 `save()` 시점에 INSERT가 즉시 실행되고 트랜잭션이 rollback-only로 마킹**되어 catch해도 커밋이 실패함을 확인. `GlobalExceptionHandler`에 `DataIntegrityViolationException` 핸들러(409 `DUPLICATE_REQUEST`)를 두는 방식으로 변경
2. **`@Modifying(clearAutomatically = true)` 제거** — `deleteCollection`에서 앞선 `deleteAllByCollectionId`의 **미flush remove가 clear로 폐기**되어 `collection_movie` 행이 남고 FK RESTRICT 위반이 발생. 호출 지점에서 Comment 엔티티를 들고 있지 않아 stale 위험도 없으므로 `clearAutomatically` 미사용으로 확정
3. `CommentResponse`의 작성자 필드를 flat이 아닌 중첩 `CommentAuthorResponse`로 변경(`ReviewResponse`/`ReviewAuthorResponse` 기존 스타일과 통일). DTO Bean Validation은 `spring-boot-starter-validation` 미도입이라 Step5로 이월

---

### Step4-6-E — 조회 메서드 공개범위 소급 적용 (`refactor`/`chore`)

`UserAccessPolicy`가 확정되면서 **4-2~4-5의 조회 메서드가 "본인 데이터만 조회"를 암묵적으로 전제하고 있던 문제**가 드러나 일괄 정리.

- **시그니처 컨벤션 확정**: 타인 조회가 가능한 조회 메서드는 `(Long viewerId, Long targetUserId, …)` 순서. 본인 전용 메서드(쓰기 계열, `isWished`/`getMyReview`처럼 호출자 자신의 상태 조회)는 기존 `(Long userId, …)` 유지해 의미를 구분. 메서드명의 `My` 접두사도 타인 조회가 가능해지면 `getUser…`로 변경
- `WatchRecordService.getMyMovieList` → `getUserMovieList`, `getWatchLog` — `validateCanView` 적용
- `WishMovieService.getMyWishList` → `getUserWishList` — 동일
- `CollectionService.getCollections` — 동일. `getCollectionMovies`는 대상 사용자 id를 인자로 안 받으므로 `CollectionRepository.findOwnerIdById`(id 프로젝션) 추가해 소유자 확인 후 검증
- `ReviewService.getMovieReviews` — 유일하게 **작성자가 다수**라 단건 판정을 반복할 수 없음 → `filterViewable` 벌크 판정 후 필터링. **한계**: 조회 후 필터링이라 페이지당 실제 항목 수가 요청 size보다 적을 수 있고 `totalElements`에 가려진 리뷰가 포함됨. 정확한 페이징이 필요해지면 공개범위 조건을 쿼리로 내려야 함(작성자 조인 + 맞팔 서브쿼리) — 캡스톤 스코프에서는 단순성 우선
- `com.project.cinemory.repository` 잔존 패키지 삭제(`CollectionRepository`/`CollectionMovieRepository` 중복 정의) + `MovieRepositoryTest` import 정정 — 4-2에서 `MovieRepository`를 정리했던 것과 동일 조치
- `WatchRecord.note` 필드명 정리는 **확인 결과 이미 반영된 상태**였음(DevLog의 미해결 표기가 stale)

---

### Step4-7 — Theater/BoxOfficeRecord 구현 완료 (`docs/service-layer-spec.md` 4-7 기준)

앞선 도메인과 달리 **사용자 소유 데이터가 아니라 외부 API에서 수집한 공용 데이터**라, `UserAccessPolicy` 적용 대상이 아니며 `viewerId`를 받지 않는다. 조회(Query)와 수집(Sync)의 책임 분리는 4-2 `MovieQueryService`/`MovieSyncService` 원칙을 그대로 따랐다.

**설계 확정 사항 (대화로 결정)**

- 주변 극장 = **Bounding Box 1차 필터 + Service Haversine 정밀 계산** (스키마 무변경)
- 극장 데이터 = **1회성 시드 적재** (주기 배치 미도입)
- 박스오피스 배치 = **`@Scheduled` 자동 + 관리자 수동 트리거 병행**
- TMDB 미매칭 레코드 = **스냅샷으로 노출 + 별도 재매칭 배치로 재시도**

**구현**

- `ErrorCode` 추가: `THEATER_NOT_FOUND`/`INVALID_SEARCH_RADIUS`/`BOX_OFFICE_NOT_FOUND`/`EXTERNAL_API_ERROR`
- `global.config.SchedulingConfig`(`@EnableScheduling`) — **단일 인스턴스 전제**. 다중 인스턴스 확장 시 ShedLock 등 분산 락 필요(캡스톤 범위 밖)
- `global.infra.kofic` 신규 패키지 — `KoficProperties`(`@ConfigurationProperties`, `isConfigured()`)/`KoficConfig`(RestClient 빈)/`KoficClient`/`KoficDailyBoxOfficeResponse`. TMDB 클라이언트도 같은 위치에 들어올 예정이라 도메인 패키지에 두지 않음
- `domain.theater` — `GeoUtils`(`boundingBox`/`haversineMeters`), `TheaterRepository`(`findWithinBoundingBox`/`findSourceCodesIn`), `TheaterResponse`/`TheaterSeedData`, `TheaterQueryService`(`getNearbyTheaters`), `TheaterSeedService`(`seedAll`)
  - 거리 계산식을 SQL `ORDER BY`에 넣으면 인덱스를 못 타고 전체 정렬이 발생하므로, 후보를 좁힌 뒤 메모리에서 정렬
  - MySQL 복합 인덱스는 **첫 range 조건 이후 컬럼을 탐색 키로 쓰지 못하므로** `idx_theater_lat_lng`에서 latitude만 범위 탐색, longitude는 ICP 필터로 동작. 전국 상영관 수백 개 규모라 충분하며, 커지면 POINT + SPATIAL 인덱스(v9)로 전환
  - 한국은 위도 33~38도라 극지방/날짜변경선 경계 처리 불필요 → 분기 없는 단순 공식 사용
  - 반경 상한(기본 5,000m / 최대 50,000m)을 둔 이유: 무제한이면 Bounding Box가 전국을 덮어 1차 필터가 무의미해짐
  - 시드는 `sourceCode` 차집합만 저장(재실행해도 중복 없음). 기존 행 갱신은 하지 않음 — 갱신을 지원하려면 Theater에 수정 메서드를 열어야 해서 불변성이 약해지므로
- `domain.boxoffice` — `BoxOfficeRecordRepository`(`findByTargetDateAndRankTypeOrderByBoxOfficeRankAsc`(`@EntityGraph("movie")`)/`findLatestTargetDate`/`findKoficMovieCds`/`findByMovieIsNull`), `BoxOfficeResponse`/`BoxOfficeItemResponse`, `BoxOfficeQueryService`, `BoxOfficeSyncService`, `BoxOfficeScheduler`
  - **멱등성**: `uk_box_office_record(target_date, rank_type, kofic_movie_cd)` 때문에 재실행 시 중복 INSERT가 유니크 위반을 냄. 기존 행 삭제 후 재적재가 아니라 **이미 적재된 코드 집합을 1쿼리로 읽어 차집합만 저장**(4-5 `addMoviesToCollection` 패턴 재사용)
  - 조회 시 `targetDate`가 null이면 `findLatestTargetDate`로 대체 — 수집 배치가 아직 안 돈 시각에 클라이언트가 '오늘'을 넘겨 빈 화면을 보는 것을 방지
  - 미매칭 레코드도 목록에 노출. `movieTitleSnapshot`으로 제목을 보여주고 `linked = (movieId != null)`로 상세 링크 활성 여부 판단 — 포스터 없는 항목이 섞이는 것보다 순위 목록에 구멍이 나는 편이 더 나쁘다고 판단
  - 스케줄러는 **시각 결정과 예외 격리만** 담당. 수동 트리거도 같은 서비스 메서드를 호출해 배치 로직 이원화를 막음. 수집 실패는 잡아서 로깅만 하고 삼킴(수집이 멱등하므로 수동 재수집으로 복구)
  - `BoxOfficeRecord.linkMovie()` 추가. `movieTitleSnapshot`은 불변 스냅샷이라 수정 메서드 미제공(기존 규칙 유지)
  - `MovieRepository`에 `findByKoficMovieCdIn`(1순위 벌크 매칭)/`findByTitle`(2순위) 추가
- 설정값(cron/반경/limit)은 `application.yml`의 `cinemory.*`로 외부화. `kofic.api-key` 미설정 시 스케줄러가 경고 로그만 남기고 건너뜀

**구현 중 스펙 조정 3건**

1. **재매칭 2순위 전략 축소** — 기획상 "한글 제목 + 개봉연도" 매칭이었으나 `box_office_record`에 KOFIC `openDt`를 담을 컬럼이 없어 개봉연도로 후보를 좁힐 수 없음을 확인 → **제목 완전 일치 + 후보가 유일할 때만 연결**로 축소(2건 이상은 동명 영화이므로 오매칭 방지를 위해 보류). 정확도 개선은 `open_date` 컬럼 추가(v9) 선행 필요
2. 재매칭 성공 시 `Movie.linkKoficCode()`로 KOFIC 코드를 역으로 채워 다음 수집부터 1순위 매칭이 걸리게 함(단 `uk_movie_kofic_cd` 위반 방지를 위해 비어 있을 때만)
3. 외부 데이터를 신뢰하지 않기 위해 NOT NULL 대상(`rank`/`movieCd`/`movieNm`) 누락 항목을 저장 전 걸러내는 `hasRequiredFields` 추가

**부수 정리**

- `application.yml`의 `ddl-auto`를 `update` → `validate`로 수정 (CLAUDE.md의 SoT 원칙과 충돌하고 있던 것)
- `application-secret.yml`에 `kofic.api-key` 추가

### 다음 작업 후보 (갱신 4차)

- **Step4 전체 완료** (4-0~4-7). 다음은 Step5 Controller 계층 + `@Valid` 검증 또는 `SecurityConfig` 중 택일
- `SecurityConfig` 착수 시 함께 결정해야 할 4-6/4-7 잔여 항목
  - 팔로워/팔로잉 **명단**에도 공개범위를 적용할지 (현재 적용 중 — "수는 공개 / 명단은 비공개"가 의도한 바인지 확인 필요)
  - 댓글 알림(notification) 기능 포함 여부 (ERD v8에 테이블 없음 — 포함 시 v9 논의 선행)
  - **관리자 전용 API 인가 방식** — 박스오피스 수동 트리거/극장 시드 엔드포인트 보호. 현재 `user` 테이블에 role 컬럼이 없어 `ROLE_ADMIN` 도입 여부 결정 필요
- `spring-boot-starter-validation` 도입 — Step5에서 DTO Bean Validation(`@NotBlank`/`@Size`)과 `MethodArgumentNotValidException` 핸들러를 함께 처리
- 극장 표준데이터 CSV의 좌표계(WGS84 vs EPSG:5174) 실제 파일 확인 — EPSG:5174면 적재 전 변환 필요
- `MovieSyncService` 구현 — TMDB 연동 DTO/배치 설계가 선행되어야 함
- 스키마 v9 후보 누적: `box_office_record.open_date`(재매칭 정확도), `theater` POINT 컬럼 + SPATIAL 인덱스(데이터 증가 시), `notification` 테이블(알림 도입 시)

---

### Step S — Spring Security 설계 확정 (`docs/security-spec.md` 신규)

Step5(Controller)보다 먼저 착수. Controller 시그니처가 "인증된 호출자를 어떻게 받는가"에 직접 의존하기 때문.
**이번 세션은 설계와 스키마 적용까지만 진행했고 구현 코드는 아직 없다.**

**설계 확정 사항 (대화로 결정)**

- **토큰 = Access + Refresh, Refresh는 DB 저장** (회전 + 재사용 감지 포함). Access 30분 / Refresh 14일
- **소셜 로그인 = 클라이언트 SDK가 받은 ID 토큰을 서버가 검증**하고 자체 JWT 발급
  (RN에서 서버 리다이렉트 방식은 브라우저 왕복 + 딥링크 처리가 무거움. 4-1 `signUpOAuth` 멱등 설계와 그대로 맞물림)
- **관리자 권한 = `user.role` 컬럼** + `hasRole('ADMIN')`, 관리자 엔드포인트는 `/api/admin/**`
- **팔로워/팔로잉 명단 = 현행 유지** (수는 공개, 명단은 공개범위 적용) — 4-6 잔여 항목 해소
- **알림 = `notification` 테이블을 v9에 포함** — 4-6부터 이월된 미결 항목 해소

**설계 문서에 남긴 주요 판단**

- 리프레시 토큰은 **SHA-256 해시로 저장**. BCrypt를 쓰지 않는 이유는 토큰이 이미 고엔트로피 랜덤값이라
  사전공격 대상이 아니고, salt가 붙으면 **인덱스 조회 자체가 불가능**해지기 때문
- 리프레시 토큰은 **JWT가 아닌 불투명 랜덤값**. 어차피 DB를 조회하므로 자체 검증 능력이 불필요하고,
  JWT로 만들면 유출 시 노출면만 넓어짐
- **필터 단계 예외는 `@RestControllerAdvice`가 잡지 못한다** (DispatcherServlet 앞에서 발생) →
  `AuthenticationEntryPoint`/`AccessDeniedHandler`에서 직접 `ErrorResponse` JSON을 write
- `TOKEN_EXPIRED`를 `INVALID_TOKEN`과 분리 — 클라이언트가 "재발급 시도"와 "로그인 화면 이동"을
  구분하지 못하면 재발급 실패 루프에 빠짐
- **`@AuthenticationPrincipal` 대신 `@AuthUser` 신설**. 익명 요청의 principal은 `"anonymousUser"` String이라
  커스텀 타입으로 받으면 null이 들어오긴 하지만 `errorOnInvalidType=false` 기본값에 기댄 코드다.
  4-6에서 null을 정상 입력으로 설계한 이상 그 계약을 명시적으로 표현하는 편이 낫다
- **Service 계층은 변경 없음** — 4-6-E에서 `(viewerId, targetUserId, …)` 시그니처를 미리 정리해둔 것이 여기서 회수됨
- `OAuthIdTokenVerifier`는 4-6 `CommentTargetResolver`와 동일한 전략 패턴 재사용
  (`List` 주입 → 생성자에서 `EnumMap` 변환)

---

### 스키마 v9 적용 완료 (19 → 21 테이블)

`docs/schema/v9-delta.sql` 작성 후 실제 DB에 적용. S-8 잔여 항목 6건을 **스키마 영향 여부로 분류**한 결과
DDL이 필요한 건 2건뿐임을 확인하고, 나머지는 부록에 근거만 기록.

| 변경 | 내용 |
|---|---|
| `user.role` 추가 | `ENUM('USER','ADMIN') NOT NULL DEFAULT 'USER'` |
| `refresh_token` 신설 | `token_hash`(SHA-256 hex, UNIQUE) / `expires_at` / `revoked_at` / `user_id` FK CASCADE |
| `notification` 신설 | 수신자 `user_id`(CASCADE) + 행위자 `actor_id`(SET NULL) + 다형 target(FK 없음) |

- `password_reset_token`은 **v10으로 분리** — SMTP 인프라 없이는 테이블만 있고 동작하지 않음.
  반면 비밀번호 **변경**(로그인 상태)은 스키마 없이 구현 가능하므로 먼저 넣기로 함
- 만료 토큰 정리는 **MySQL EVENT가 아닌 `@Scheduled`** 로 결정 — `event_scheduler`가 기본 OFF라
  서버 설정에 의존하고, 정리 이력이 앱 로그에 남지 않으며, 비즈니스 로직이 SoT 밖으로 새어 나감
- Rate limiting은 `login_attempt` 테이블을 만들지 않음 — 로그인 시도마다 쓰기가 발생. 단일 인스턴스에서는
  인메모리로 충분하고, 캡스톤 범위 밖으로 두되 보고서에 한계로 명시
- **적용 시 경고 `1681 Integer display width is deprecated`는 정상.** `is_read tinyint(1)`에서 발생하며,
  MySQL에서 `BOOLEAN`은 `TINYINT(1)`의 동의어라 boolean 컬럼을 만드는 한 피할 수 없다.
  폭을 떼면 Connector/J의 `tinyInt1isBit`(기본 true)가 동작하지 않아 오히려 손해.
  v8의 `is_new`/`is_active`/`is_representative`도 모두 동일 — 근거를 델타 파일 주석에 기록
- `docs/schema/v9-delta.sql`에 **롤백 스크립트(부록 B)** 와 **재덤프 명령어(부록 C)** 포함

### 문서/설정 정리

- `docs/jpa-entity-spec.md` — 기준 스키마를 v8(18) → **v9(20)** 로 갱신, **Step4(인증/알림 엔티티) 스펙 신규**
  - `User.role`: 팩토리가 권한을 인자로 받지 않고 `USER` 고정, **권한 변경 비즈니스 메서드 없음**
    (승격 API를 안 만들기로 한 이상 엔티티에 경로를 열어둘 이유가 없음)
  - `RefreshToken`: 해시만 보관(원문은 서비스 책임), `revoke()`는 **이미 폐기된 경우 시각을 갱신하지 않음**
    (최초 폐기 시점이 재사용 감지의 근거), `isExpired(now)`는 현재 시각을 **인자로 주입**(테스트에서 시간 고정 가능)
  - `Notification`: 문구 스냅샷 미보유(조회 시점 조합), `NotificationTargetType`을 `Comment.TargetType`과 분리
- `.gitignore` — 스키마 덤프 제외가 `cinemory_backup_v8.sql` **파일명으로 박혀 있어** v9 덤프가 그대로
  커밋될 상황이었음 → `docs/schema/cinemory_backup_*.sql` 패턴으로 변경.
  델타 스크립트는 리뷰 대상이므로 계속 추적되는지 `git check-ignore`로 확인
- mysqldump는 **`>` 리다이렉션 대신 `--result-file`** 사용을 명시.
  (a) Windows 리다이렉션이 개행을 `\n` → `\r\n`으로 바꿔 덤프를 오염시키고,
  (b) PowerShell 5.1의 `>`는 기본 인코딩이 UTF-16LE라 파일이 통째로 깨짐

### 🐛 `@EnableJpaAuditing` 중복 — `validate` 기동 확인 중 발견

`CinemoryApplicationTests.contextLoads()` 실행 시
`BeanDefinitionOverrideException`(`jpaAuditingHandler`)으로 컨텍스트 로딩 실패.

- 원인: `@EnableJpaAuditing`이 `CinemoryApplication`과 `global.config.JpaAuditingConfig` **양쪽에** 선언돼 있어
  `JpaAuditingRegistrar`가 같은 빈을 두 번 등록. Spring Boot 2.1부터 빈 오버라이딩이 기본 차단이라 예외로 이어짐
- 조치: `global/config` 패키지 컨벤션에 맞춰 `JpaAuditingConfig` 쪽을 남기고 애플리케이션 클래스에서 제거,
  재발 방지 주석 추가
- **`compileJava`로는 잡히지 않고 컨텍스트를 실제로 띄울 때만 드러나는 유형**이라 그동안 잠복해 있었음.
  v9/Security와 무관한 기존 문제이며, `validate` 기동 확인 절차가 제 역할을 한 사례
- 수정 후 `contextLoads` 통과 확인 (= v9 적용이 기존 18개 엔티티를 깨지 않음을 검증)

> **주의**: 이번 `validate` 통과는 "v9 스키마 전체 검증"이 아니다. `validate`는 **엔티티 → 스키마** 단방향으로만
> 검증하므로 `RefreshToken`/`Notification` 엔티티가 없는 현재는 두 신규 테이블이 검증 대상이 아니다.
> 또한 **UNIQUE/FK/인덱스는 검증하지 않는다** — 제약조건 확인은 `information_schema` 조회로 별도 수행할 것.

### 다음 작업 후보 (갱신 5차)

- **Step S 구현 착수** — 의존성 추가(`spring-boot-starter-security`, jjwt) 시점부터 전 엔드포인트가 기본 차단되므로
  화이트리스트 정의가 첫 작업
  1. Step4 엔티티 3건 구현(`User.role`, `RefreshToken`, `Notification`) 후 `validate` 재확인
  2. `JwtTokenProvider` / `JwtAuthenticationFilter` / `SecurityConfig`
  3. `AuthService`(로그인/재발급/로그아웃) + `OAuthIdTokenVerifier`
- **Step S 착수 전 결정 필요** (구현을 막지는 않음)
  - 소셜 provider 우선순위 — 앱스토어 배포 계획이 있으면 애플이 심사 요구사항이라 필수, 시연만이면 후순위
  - 만료 리프레시 토큰 정리 배치 도입 여부 (쿼리는 v9 델타 부록 A-1에 준비)
- **`TestController` 삭제 권장** — 인증 없이 열린 TMDB 프록시라 호출 쿼터를 임의 소진시킬 수 있음.
  4-2 `MovieQueryService`로 역할이 대체됨
- **알림 도메인 설계는 Step S 구현 이후 별도 절** — 생성 지점이 `FollowService.follow()` /
  `CommentService.createComment()` 안이라 기존 도메인 서비스에 손이 닿음.
  ⚠️ `notification`도 다형 참조라 **고아 알림 문제가 4-6 고아 댓글과 동일하게 재현됨** — 삭제 경로 정리 필수
- Step5 Controller + `@Valid` (`spring-boot-starter-validation` 도입)
- 스키마 v10 후보: `password_reset_token`(SMTP 도입 시), `box_office_record.open_date`(재매칭 정확도),
  `theater` POINT + SPATIAL 인덱스(데이터 증가 시)

---

## 2026-07-30

### Step S 착수 전 설계 재검토 — S-9 확정 결정 12건

설계가 "✅ 확정"으로 표시돼 있어도 **케이스 자체가 비어 있어 구현을 시작하면 막히는 지점**이 있었다.
결정 없이 넘기면 임의로 채워지고 되돌리기 비싼 것들만 골라 A-1~A-7(착수 전 결정), B-1~B-5(명세 보강)로 정리했다.

| # | 결정 | 핵심 근거 |
|---|---|---|
| A-1 | 카카오 이메일 **필수 동의** (비즈앱 전환/본인인증 선행) | `user.email`이 `NOT NULL`인데 카카오 이메일은 선택 동의면 클레임 자체가 안 온다. 플레이스홀더 이메일은 `uk_user_email`에 가짜 데이터를 남겨 v10에서 터진다 |
| A-2 | 로컬/소셜 이메일 충돌 → `EMAIL_ALREADY_REGISTERED_LOCALLY`(409) | `uk_user_email`과 `uk_user_provider`가 독립이라 멱등 분기를 못 타고 원인 불명의 409 `DUPLICATE_REQUEST`가 나가던 문제 |
| A-3 | `permitAll` 경로여도 무효 토큰이면 **항상 401** | 조용한 익명 강등은 "로그인했는데 안 보임"을 만들고 로그도 남지 않는다 |
| A-4 | 회전 오탐 → 프론트 mutex + 서버 30초 유예 | 동시 재발급 2건 중 두 번째가 재사용으로 판정돼 정상 사용자가 강제 로그아웃되는 문제 |
| A-5 | `logout`만 `authenticated()` | 토큰 소유자 일치 검증이 가능해진다 |
| A-6 | 비밀번호 변경을 Step S 범위에 포함 | 변경 시 리프레시 토큰 전체 폐기가 필요한데 그 로직이 `AuthService`에 이미 생긴다 |
| A-7 | `TestController` 삭제 | 인증 없는 TMDB 프록시 (4-6부터 이월된 항목 해소) |

- B-1 `ROLE_` 접두사 / B-2 CORS / B-3 `RoleType` 위치 / B-5 `jwt.secret` 관리는 명세 보강
- **B-4는 검토 과정에서 한 번 뒤집혔다.** `RefreshToken`을 `Long userId`로 두자고 제안했다가
  `@ManyToOne(LAZY)`로 되돌렸다. "조인을 아낀다"는 근거가 사실이 아니었고(LAZY는 조인하지 않으며
  `getUser().getId()`도 프록시라 쿼리가 없다) CLAUDE.md 규칙만 깨는 선택이었다.
  원인은 **프로젝트 지식에 동기화된 `jpa-entity-spec.md` 사본이 잘려 있어** 해당 절을 못 본 것 —
  저장소 원본에는 이미 `@ManyToOne`으로 명시돼 있었다

### S-A·S-B — 의존성 + `SecurityConfig` 골격 + 엔티티 3건

- `spring-boot-starter-security` + jjwt 0.12.6. **스타터 추가 즉시 전 엔드포인트가 차단**되므로
  `SecurityConfig` 골격을 같은 커밋에 넣고 JWT 필터는 미배선 상태로 뒀다
- 기존 `spring-security-crypto` 단독 선언은 스타터에 포함되므로 제거
- `User.role` / `RefreshToken` / `Notification` + enum 3종. 컬럼 구성을 v9 덤프와 대조해 **차이 0** 확인 후
  `validate` 기동 통과 — 엔티티가 없는 테이블은 검증 대상이 아니므로 **이제야 v9 신규 테이블이 검증 범위에 들어왔다**
- 구현 중 조정 4건은 `docs/jpa-entity-spec.md` 변경 이력 참고
  (`of()`→`issue()`, `revoke()`→`revoke(now)`, `read`→`isRead`, `isWithinReuseGrace` 추가)

### S-C — `JwtTokenProvider` (+ `JwtTokenProviderTest`)

- `JwtProperties`(record) 컴팩트 생성자에서 secret 32바이트·TTL 양수 검증 →
  **첫 로그인이 아니라 기동 시점에 실패**한다
- HS256 키 알고리즘 문제를 잡는 과정에서 테스트가 만들어졌다. 이 테스트가 고정하는 불변식 6건을
  `security-spec.md` S-2에 표로 남겨 **정리 대상으로 오해해 삭제하는 것을 방지**했다
  - 특히 `TOKEN_EXPIRED`/`INVALID_TOKEN` 분리는 `catch`를 한 줄로 합쳐도 컴파일이 통과하고,
    증상이 **앱의 재발급 무한 루프**로만 드러난다. S-9 프론트 계약이 이 분리에 의존한다
- **잔여 2건 도출** — ① HS256 고정을 검증하는 단정이 없다(라운드트립은 양쪽이 HS512여도 통과하므로
  정작 이 파일이 만들어진 계기가 고정되지 않았다) ② `Thread.sleep(50)` → `Clock` 주입.
  `RefreshToken.isExpired(now)`와 같은 이유로 만든 규칙인데 `JwtTokenProvider`만
  `Instant.now()`를 내부 호출하고 있어 컨벤션이 어긋난다
  → **같은 날 해소** (아래 "S-C 잔여 2건 해소" 절)

### ⚠️ A-3이 만든 부작용 — 구현 전에 발견 (S-9 C-1)

`/api/auth/login`은 `permitAll`인데 A-3을 그대로 적용하면 **만료 토큰을 헤더에 단 채 재로그인을
시도할 때 401로 막힌다.** 재발급도 마찬가지라 앱을 지우기 전엔 복구가 안 되는 상태가 된다.

- 조치: auth 4경로(`signup`/`login`/`oauth/*`/`reissue`)만 `shouldNotFilter`로 제외.
  **`logout`은 A-5 때문에 반드시 포함시키지 않으며**, `/api/auth/**` 통짜 패턴을 쓰면 안 되는 이유가 이것이다
- 안전성 근거: **인가는 이 필터가 아니라 체인 뒤쪽 `AuthorizationFilter`가 판정**하므로
  오류 방향이 fail-closed다. 제외 목록이 잘못돼도 열리는 게 아니라 401이 난다
- 네 경로 모두 인증 주체를 쓰지 않는다 — `reissue`조차 자격증명이 헤더의 Access가 아니라 **body의 리프레시 토큰**이다

> `login`/`reissue`에 rate limiting이 없다는 사실은 그대로다(S-8 #6). C-1이 악화시키지는 않지만
> 무차별 대입 방어가 비어 있다는 점은 보고서에 한계로 명시할 것.

### S-D — 인증 필터 + 예외 핸들러

- `JwtAuthenticationException`(사유 전달자) / `JwtAuthenticationFilter` /
  `SecurityErrorResponseWriter` / `JwtAuthenticationEntryPoint` / `JwtAccessDeniedHandler` 신규
- **A-3은 필터가 체인을 끊도록 강제한다.** 오류만 기록하고 계속 태우면 `permitAll` 경로가
  200으로 통과해 A-3이 무력화된다. 게다가 필터가 던진 예외는 `ExceptionTranslationFilter`가
  잡지 못하므로(그 필터는 체인 뒤쪽이라 하류 예외만 처리) **`EntryPoint.commence()`를 직접 호출**한다
- 구현 중 조정 ① — **필터를 빈으로 만들지 않는다.** Spring Boot는 `Filter` 타입 빈을 서블릿 컨테이너
  필터 체인에도 자동 등록하므로 `@Component`를 붙이면 Security 체인 **밖에서** 한 번 더 돈다.
  `SecurityConfig`에서 직접 생성해 배선했고, C-1의 경로 상수 공유는 필터가 `SecurityConfig`를
  참조하는 대신 **생성자로 주입받는 방향**으로 구현해 의존 방향을 한쪽으로 유지
- Boot 4는 Jackson 3이 기본이라 `JsonMapper`(`tools.jackson`)가 `@Primary`다.
  `com.fasterxml` `ObjectMapper`를 임포트하면 빈이 없어 **기동이 실패**한다.
  응답에 `charset=UTF-8`을 명시하는 이유는 `ErrorCode` 메시지가 한글이기 때문

**검증** — 실제 HTTP 4건 통과. 컨트롤러가 0개라 상태 코드만으로는 판정이 안 돼
**응답 바디의 `code` 값으로 구분**했다.

| 요청 | 결과 |
|---|---|
| 토큰 없이 `POST /api/auth/logout` | 401 `UNAUTHORIZED` — A-5 + 핸들러/Writer/charset 동작 확인 |
| `Bearer not-a-jwt` + `GET /api/movies/1` | 401 `INVALID_TOKEN` — `permitAll`에서도 필터가 돌고 체인을 끊는다 |
| `Bearer not-a-jwt` + `POST /api/auth/login` | `INVALID_TOKEN` 아님 — C-1 동작 확인 |
| 토큰 없이 `GET /api/movies/1` | 차단되지 않음 |

> `TOKEN_EXPIRED` 경로는 **미검증**이다. 발급 엔드포인트가 없어 실제 만료 토큰을 만들 수 없었다.
> provider 단은 `JwtTokenProviderTest`가 고정하고 필터는 `ErrorCode`를 그대로 넘기기만 하므로
> 위험은 낮지만, **S-F에서 로그인 API가 생기는 시점에 반드시 확인할 것.**

### S-E — `@AuthUser` + 리졸버

- `AuthUser` / `AuthUserArgumentResolver` / `WebConfig` 신규.
  인자 리졸버는 MVC 관심사라 `SecurityConfig`가 아닌 `global/config/WebConfig`에 등록
- `required` 기본값을 **`true` → `false`** 로 변경(C-2). `authenticated()` 경로는 이미
  `AuthorizationFilter`가 막아 null이 도달할 수 없고, `permitAll` 조회가 다수라
  기본값이 `true`면 흔한 쪽이 매번 예외 표기를 지는 역전이 생긴다
- 구현 중 조정 ② — `supportsParameter`가 **타입을 보지 않는다.** 타입 불일치로 `false`를 반환하면
  Spring이 그 파라미터를 요청 파라미터로 해석하려 들어 엉뚱한 자리에서 실패하므로,
  받아서 `resolveArgument`가 `IllegalStateException`으로 거부한다.
  특히 primitive `long`은 null을 담을 수 없어 "비로그인 = null" 계약과 애초에 양립하지 않는다
- **`authentication != null`로 판정하면 안 된다** — `AnonymousAuthenticationFilter`가 익명 요청에도
  `AnonymousAuthenticationToken`(principal = `"anonymousUser"` String)을 채우므로 그 조건은 항상 참이다.
  `principal instanceof AuthUserPrincipal`로 판정한다

> **S-E는 미검증이다.** 컨트롤러가 없어 리졸버를 태울 경로가 없다. 확인한 것은 컴파일과 기동까지.
> 리졸버 미등록이나 위 판정 실수는 **예외 없이 조용히 틀린 값**이 들어오는 유형이라,
> S-F에서 `required=true`의 401과 `permitAll` 경로의 `viewerId == null` 주입을 함께 볼 것.

### S-C 잔여 2건 해소 — `Clock` 주입 + `alg` 단정

**① HS256 고정 단정** — 64바이트 secret으로 발급한 토큰의 헤더를 디코드해 `"alg":"HS256"`을 확인한다.
64바이트를 고른 이유는 `Keys.hmacShaKeyFor()`였다면 **HS512가 선택됐을 길이**이자
운영 `jwt.secret`과 같은 길이라, 실제로 문제가 났을 시나리오를 그대로 태우기 때문이다.

**② `Clock` 주입** — `global/config/ClockConfig` 신설, `JwtTokenProvider`가 생성자로 주입받는다.

> **`createAccessToken`만 바꿔서는 만료 테스트가 고정되지 않는다.**
> jjwt 파서가 `exp` 검증에 자기 시스템 시계를 쓰기 때문에
> `Jwts.parser().clock(...)`으로 파서 쪽에도 같은 시간 소스를 물려야 발급·검증 양쪽이 고정된다.
> 이걸 빠뜨리면 "시간을 주입했는데 만료 테스트는 여전히 실시간에 의존"하는 상태가 된다.

- `Clock.systemDefaultZone()`을 쓴다. JPA Auditing(`@CreatedDate`)이 JVM 기본 시간대를 따르므로
  여기서만 시간대를 고정하면 **같은 행의 `created_at`과 애플리케이션이 계산한 `expires_at`이
  서로 다른 기준**을 갖게 된다. 배포 시엔 `TZ` 또는 `-Duser.timezone`으로 한 곳에서 맞춘다
- S-F의 `AuthService`도 만료 시각 계산과 A-4 유예 판정에 같은 빈을 쓴다
- 테스트 6건 → **9건**. 시간이 고정되면서 경계값 테스트(`만료_직전_토큰은_아직_유효하다`)가
  가능해졌고, `Refresh_Token은_JWT가_아니다`를 추가해 S-2의 "불투명 값" 결정을 직접 고정했다

**검증 상태** — 변경 후 S-D의 HTTP 4건은 재현 확인.
⚠️ **`./gradlew test`는 아직 실행하지 않았다.** 9건 통과 여부 미확인.

**남은 열린 질문** — 알고리즘 가드가 두 겹이다(`SecretKeySpec`의 JCA 이름 /
`signWith`의 명시적 `Jwts.SIG.HS256` 인자). 추가한 단정은 "고정됐다"만 확인할 뿐
**어느 쪽이 실효인지는 구분하지 못한다.** `SecretKeySpec` 우회를 `Keys.hmacShaKeyFor()`로
잠시 되돌려 테스트를 돌리면 판별되며, 여전히 통과하면 그 우회와 주석은 걷어낼 수 있다.

### 스키마 v10 적용 완료 (21 → 22 테이블)

S-F 검증 중 발견한 **로그아웃 무효화 문제**가 v10을 연 직접적 계기다.

`revoked_at`은 회전·로그아웃·재사용감지·비밀번호변경 **네 경로**에서 찍히는데 유예 판정이 시각만
보고 있었다. 그래서 **로그아웃 직후 30초 안에 같은 리프레시 토큰으로 재발급하면 세션이 되살아났다.**

**v10에 반영 (3건)**

- `refresh_token.revoked_reason` — `ENUM('ROTATED','LOGOUT','REUSE_DETECTED','PASSWORD_CHANGED')`.
  유예 창을 `ROTATED`에만 적용해 문제를 닫는다. `PASSWORD_CHANGED`는 S-H·S-J 양쪽에서 쓴다
- `password_reset_token` 신설 — **SMTP 도입을 확정**해 포함. `refresh_token`과 같은 골격
- `box_office_record.open_date` — 4-7 재매칭 2순위 전략("한글 제목 + 개봉연도") 복원용

**넣지 않은 것**

- `replaced_by_id` — 유예를 정석("직전 발급분 반환")으로 바꾸려면 토큰 **원문**이 필요한데
  우리는 해시만 저장한다. **목적을 달성하지 못해 제외** — 검토 초반에 넣기로 했다가 되돌린 항목이다.
  유예 창의 한계는 컬럼을 넣어도 남고, 근본 방어는 클라이언트 mutex라는 결론이 그대로다
- `family_id` — 재사용 감지 시 전체 폐기는 S-3의 의도적 결정이라 범위 밖
- `theater` POINT + SPATIAL — 발동 조건("데이터 증가 시")이 아직 아님

**적용 시 주의했던 것**

- **CHECK 제약은 컬럼 추가 → 기존 행 백필 → CHECK 추가 순서여야 한다.** 먼저 걸면
  `revoked_at`이 채워진 기존 행이 제약을 위반해 `ALTER` 자체가 실패한다
- Workbench는 safe update mode라 비인덱스 컬럼만 쓰는 `UPDATE`가 Error 1175로 거부된다.
  Preferences 대신 구문 범위에서만 `SQL_SAFE_UPDATES`를 껐다 복원하도록 델타에 반영
- `validate`가 검증하지 않는 UNIQUE/FK/인덱스/CHECK는 `information_schema` 조회로 직접 확인

### 🐛 문서의 테이블 수가 전부 하나씩 틀려 있었다

델타를 쓰려고 v9 덤프를 세어보니 `CREATE TABLE`이 **21개**인데 문서는 전부 20이라고 적고 있었다.

- 실제: v6 16개 + `ott_platform`·`theater`·`box_office_record` = **v8 19개** → v9에서 2개 추가 = **21개**
- `CineMory_기획노트.md`만 v8을 19개로 **올바르게** 적고 있었고, 오차는 2026-07-22 v7/v8 문서화
  시점에 생겨 이후 모든 문서로 전파됐다
- 진실의 원천은 덤프이므로 문서 6곳을 정정

같이 드러난 문제로, **`v9-delta.sql`이 git에 커밋된 적이 없다.** 만들어 적용하고 지운 탓에
문서 9곳의 참조가 끊긴 상태였다(`docs/schema/` 전체가 추적되지 않고 있었다).
v10 델타는 **반드시 커밋할 것.**

### 문서 정비

- `docs/security-spec.md` — **S-9 신설**(A/B/C 12건), S-4에 필터 동작 규약(세 갈래 분기표),
  S-5에 리졸버 구현 요건, S-6에 응답 작성 규약. S-4 화이트리스트 표를
  **`requestMatchers`에 그대로 옮길 수 있는 최종 목록**으로 교체
  (중괄호 축약 `{records,collections}`은 경로 매칭 문법이 아니라 경로 변수 캡처로 해석된다)
- `docs/jpa-entity-spec.md` — Step4를 구현 완료로 전환, 불일치 4건 반영
- `docs/service-layer-spec.md` — 4-1의 `PasswordEncoder` 선행 과제 해소,
  `signUpOAuth` 이메일 충돌 분기, `login`/`changePassword` 신규 명세.
  **S-7 표의 "`signUpOAuth`는 변경 없음"은 A-2로 무효화**
- ⚠️ **프로젝트 지식의 문서 사본이 저장소보다 오래됐다** (`jpa-entity-spec.md` 263 vs 342줄,
  `service-layer-spec.md` 238 vs 997줄). B-4 착오의 원인이었으므로 재동기화 필요

### Step S 범위 조정 — S-H(비밀번호 변경)를 Step5로 이관

**A-6에서 비밀번호 변경을 Step S에 넣은 근거가 해소돼 이관한다.**

당시 근거는 "변경 시 리프레시 토큰 전체 폐기가 필요한데 그 로직이 `AuthService`에 이미 생긴다"였다.
그런데 v10 반영으로 **`revokeAllByUserId(userId, now, reason)`도 `RevokedReason.PASSWORD_CHANGED`도
이미 만들어졌다.** 재사용할 로직이 존재하므로 Step5에서 호출만 하면 되고, 조기 구현의 이점이 사라졌다.

**경로 의미로도 Step5가 맞다.**

| | 상태 | 경로 | 소관 |
|---|---|---|---|
| 비밀번호 **변경** | 로그인 상태 | `/api/users/me/password` | `UserController` (**Step5**) |
| 비밀번호 **재설정** | 비로그인 | `/api/auth/password-reset/*` | `AuthController` (S-J) |

변경을 Step S에 두면 `/api/auth/password` 같은 어색한 경로가 생기거나 `UserController`를 조기에
만들어야 한다. 둘을 다른 단계에 두는 편이 도메인 경계와 일치한다.

> **주의** — 재설정(S-J)과 변경(Step5)은 "비밀번호 갱신 + 세션 전체 폐기"라는 같은 로직을 공유한다.
> S-J가 먼저 구현되므로 그때 `UserService`에 공통 내부 메서드를 두고,
> 변경은 거기에 "현재 비밀번호 검증"만 앞에 붙이는 형태로 간다.

이로써 **Step S는 인증 코어(S-G → S-I → S-J)만 남는다.**

### 다음 작업 후보 (갱신 6차)

- **S-F 검증** → **완료** (2026-08-02 절 참고)
- **알고리즘 가드 중복 판별** — `SecretKeySpec` 우회를 `Keys.hmacShaKeyFor()`로 되돌려 테스트를 돌려보고,
  통과하면 우회와 주석 제거
- **S-G(카카오)** → S-I(정리) → S-J(재설정 + SMTP). **S-H는 Step5로 이관**
- **코드 외 선행 작업**
  - 카카오 콘솔 — 비즈앱 전환/본인인증 → `account_email` 활성화 → **필수 동의** 설정 (A-1)
  - 프론트 axios 인터셉터 2건 — 재발급 **mutex**(A-4), **401 전역 처리**(A-3에 따라
    공개 조회에서도 만료 시 401이 온다. `TOKEN_EXPIRED`는 재발급 후 재시도,
    `INVALID_TOKEN`은 토큰 삭제 후 로그인 화면)
- **문서 정리 과제** — `security-spec.md`가 633줄이 되면서 S-9와 각 절이 서로를 참조하는 구조가 됐다.
  Step S 완료 후 **S-9 내용을 각 절 본문에 흡수시키고 S-9는 결정 목록만 남기는** 정리를 한 번 할 것
- 알림 도메인 설계 (Step S 이후 별도 절, 고아 알림 정리 필수)
- Step5 Controller + `@Valid`

---

## 2026-08-02

### S-F 검증 완료 — 누적 부채 5건 해소

`./gradlew test` **13건 통과**(`JwtTokenProviderTest` 9건 포함, 실패 0), 이어서 실제 HTTP로 확인.

| 검증 항목 | 결과 |
|---|---|
| 로그인 / 재발급 회전 | 200, 새 access·refresh 발급 및 RT 교체 |
| 회전 직후 유예 창 재요청 (A-4) | 200 — 정상 사용자 강제 로그아웃 없음 |
| **로그아웃 직후 재발급** | **401 `REFRESH_TOKEN_REUSED`** |
| `@AuthUser` 로그아웃 | 본인 204 / 남의 refreshToken 403 / 미인증 401 |
| `TOKEN_EXPIRED` (TTL PT5S 오버라이드) | 401 `TOKEN_EXPIRED` — `INVALID_TOKEN`과 분리 확인 |
| 만료 토큰 헤더 + `reissue` (C-1) | 200 — 필터 제외라 재로그인/재발급이 막히지 않음 |

세 번째 항목이 **v10을 연 이유였고, 목적을 달성했다.** DB `revoked_reason` 실측에서도
`ROTATED`/`LOGOUT`/`REUSE_DETECTED`가 각각 제 자리에 기록됐고, 로그아웃된 행이 `LOGOUT`으로
남아 **`revoke()`의 멱등성(사유 미덮어쓰기)** 도 함께 확인됐다.

TTL은 `--jwt.access-token-ttl=PT5S` 커맨드라인 오버라이드로 바꿨으므로 `application.yml`은 손대지 않았다.

> 이로써 **S-E와 `TOKEN_EXPIRED` 경로의 미검증 상태가 해소**됐다. 컨트롤러가 없어 미뤄뒀던
> 항목들이 `AuthController` 등장과 함께 한 번에 정리된 것으로, "검증 가능한 시점까지 부채를
> 명시적으로 들고 간다"는 방식이 실제로 작동한 사례다.

### 🔧 환경 이슈 — `bootRun` 고아 프로세스가 8080을 점유

래퍼 프로세스를 외부에서 종료해도 **다음 기동이 "Port 8080 was already in use"로 실패**했다.

- 원인: `bootRun`은 Gradle이 **별도 JVM을 fork**해 앱을 띄운다. 래퍼를 죽여도 자식 JVM은
  부모와 무관하게 살아남아 소켓을 쥐고 있다. **애플리케이션 코드와 무관한 Gradle 동작이다**
- 즉시 해결: `netstat -ano | findstr :8080` → `taskkill /PID <PID> /F`
- 재발 방지: **IntelliJ의 Run으로 띄운다.** IDE는 JVM을 직접 실행하므로 Stop이 그 프로세스를
  정확히 종료하고, 래퍼라는 중간 계층이 없어 고아가 생기지 않는다.
  `bootRun`을 쓴다면 포그라운드에서 `Ctrl+C`로 끝낼 것

> ⚠️ **테스트 결과를 오도할 수 있다.** 고아 프로세스가 남아 있으면 **옛 코드가 8080에서 계속
> 응답한다.** 새 기동이 실패한 걸 놓치고 요청을 보내면 이전 빌드가 답하는데 겉보기엔 정상이라
> "분명 고쳤는데 그대로다"가 된다. **기동 성공 로그를 확인한 뒤 요청할 것.**

### S-G 설계 확정 — nonce 검증 도입 (S-9 E-1)

카카오 공식 검증 항목은 `iss`/`aud`/`exp`/**`nonce`** 넷인데 설계에는 앞의 셋만 있었다.
**도입하기로 확정**했다.

**흐름이 2단계가 된다**

```
① POST /api/auth/nonce  → 서버가 nonce 발급·보관
② 앱이 카카오 SDK 로그인 시 nonce 전달 → nonce 담긴 ID 토큰 수령
③ POST /api/auth/oauth/{provider} {idToken, nonce} → 대조 후 즉시 소비(1회용)
```

**결정 근거 — 비용이 지금은 작고 나중에는 계단식으로 커진다.**
서버 추가분은 발급 엔드포인트와 인메모리 캐시뿐이라 **스키마 변경이 없다.** 실제 비용은
클라이언트 계약 변경인데, **설치 기반이 0인 지금은 로그인 화면 한 곳**이면 되고
배포 후에는 구버전 호환 창이 생긴다. 즉 **지금이 싸게 넣을 수 있는 마지막 시점**이다.

**구현 시 주의 3가지**

- **경로는 `/api/auth/nonce`.** `/api/auth/oauth/nonce`로 두면 `{provider}` 경로 변수와 겹쳐
  `nonce`가 provider 이름처럼 보인다. Spring MVC는 정확 매칭을 우선해 동작은 하지만
  provider가 늘어날 때 실수를 부른다
- **`INVALID_NONCE`(401)를 `INVALID_OAUTH_TOKEN`과 분리한다.** nonce 만료는 "다시 받아 재시도",
  토큰 검증 실패는 "로그인 실패"로 클라이언트 분기가 다르다 — `TOKEN_EXPIRED`/`INVALID_TOKEN`을
  나눈 것과 같은 논리다
- **저장소는 Caffeine `expireAfterWrite`.** 스스로 만료시키므로 **정리 배치가 필요 없다.**
  `PUBLIC_POST_ENDPOINTS`에 `/api/auth/nonce`를 추가하면 상수 공유로 **C-1 필터 제외까지 자동 반영**된다

> 스펙이 외부 문서(카카오)의 요구사항을 부분적으로만 옮겨온 사례다.
> **S-J의 SMTP 연동도 외부 스펙을 참조하는 절이므로 항목 누락 여부를 한 번 더 대조할 것.**

### 🐛 스펙의 `aud` 값이 틀려 있었다 — 구현 전에 발견

S-G를 설명하려고 카카오 문서를 다시 대조하다 발견. 스펙에 **"`aud` == 앱 REST API 키"** 로
적혀 있었는데, **카카오는 로그인한 플랫폼에 따라 `aud` 값이 다르다.**

| 로그인 경로 | ID 토큰의 `aud` |
|---|---|
| **네이티브 앱 SDK** (우리 경우) | **네이티브 앱 키** |
| 웹 | REST API 키 |

우리는 RN + 카카오 네이티브 SDK라 **네이티브 앱 키가 온다.** 그대로 구현했으면
**모든 소셜 로그인이 `INVALID_OAUTH_TOKEN`으로 실패**했을 것이고, 서명·`iss`·`exp`가 전부
통과한 뒤 `aud`에서만 막히는 형태라 원인 추적도 까다로웠을 유형이다.

- 조치: 스펙을 정정하고 **허용 목록(`List<String>`)으로 두도록** 변경.
  나중에 웹 로그인을 붙이면 키를 추가만 하면 된다

### 정정 — "ID 토큰 수명이 짧다"는 근거는 틀렸다

nonce 도입을 논의할 때 "위험도는 낮다, ID 토큰 수명이 짧으니까"라고 판단했는데,
**카카오 ID 토큰은 약 2시간짜리다.** 짧지 않다.

nonce가 없으면 탈취된 ID 토큰이 그 2시간 내내 로그인에 쓰일 수 있다.
**결과적으로 nonce 도입 결정은 당시 제시한 근거보다 실제로 더 타당했다.**
S-9 E-1의 근거 서술도 함께 정정했다.

> **외부 스펙을 부분적으로만 옮겨온 문제가 이걸로 두 번째**다(첫 번째는 nonce 항목 누락).
> 둘 다 같은 카카오 문서에서 나왔고, 둘 다 **구현 전 재대조에서** 잡혔다.
> S-J의 SMTP 연동도 외부 스펙 참조 절이므로 착수 전 항목 대조를 반드시 거칠 것.

### S-G 구현 완료 — 카카오 소셜 로그인 (테스트 74건)

세 덩어리로 나눠 진행했다. **컴파일 검증을 할 수 없는 환경**이라 한 번에 쓰면 오류가 겹쳐
원인 분리가 어렵고, 각 덩어리가 독립적으로 검증 가능한 지점에서 끊었다.

| 단계 | 내용 | 테스트 |
|---|---|---|
| S-G-1 | nonce 인프라 (Caffeine, `OAuthNonceService`, `POST /api/auth/nonce`) | `OAuthNonceServiceTest` 12 |
| S-G-2a | JWKS 인프라 (`global/infra/kakao`) | `CachingKakaoJwkSourceTest` 12 / `KakaoOAuthPropertiesTest` 8 |
| S-G-2b | 검증기 + 엔드포인트 | `KakaoIdTokenVerifierTest` 14 / `AuthServiceOAuthLoginTest` 9 |

**구현 중 조정 2건**

- `UserService.signUpOAuth` 반환 타입 `UserResponse` → **`User`**.
  Access Token에 담을 `role`이 `UserResponse`에 없다. `login()`과 대칭이고,
  Controller까지 나가지 않는 서비스 간 호출이라 DTO 원칙에 어긋나지 않는다
- `AuthService`에서 `@RequiredArgsConstructor` 제거 후 명시 생성자 —
  `List<OAuthIdTokenVerifier>` → `EnumMap` 변환이 필요하다 (4-6 `CommentService`와 동일한 형태)

### 테스트를 JWT 빌더 없이 조립한 이유

`KakaoIdTokenVerifierTest`는 라이브러리 빌더 대신 **JDK 표준 `Signature`로 토큰을 직접 조립**한다.

- 라이브러리 버전에 따라 빌더 API가 바뀌어도 테스트가 흔들리지 않는다
- **`aud`를 배열로 만들거나 클레임을 통째로 빼는 조작이 자유롭다** — 빌더로는 오히려 번거롭다
- 실제 카카오가 보내는 JSON에 더 가깝다

> **실제 카카오 토큰으로는 "정상 케이스"밖에 만들 수 없다.** 자체 RSA 키쌍으로 서명하면
> `aud` 불일치 / 서명 위조 / 만료 / nonce 불일치처럼 **실제로 위험한 분기**를 전부 태울 수 있다.
> 오늘 발견한 `aud` 오기(REST API 키 ↔ 네이티브 앱 키)도 이런 테스트가 있으면 구현 시점에 잡힌다.

### ⚠️ 부품이 아니라 "부품을 엮는 순서"가 비어 있었다

검증기·nonce·JWKS 각각의 테스트가 65건이나 됐는데, **`AuthService.oauthLogin`이 그것들을
어떤 순서로 부르는지**를 고정하는 테스트가 없었다.

```java
nonceService.consumeOrThrow(request.nonce());          // ①
OAuthUserInfo info = verifier.verify(idToken, nonce);  // ②
```

**①②가 뒤바뀌어도 기존 65건은 전부 통과한다.** 그런데 뒤바뀌면 검증 실패 시 nonce가 캐시에 남아
**같은 nonce로 토큰만 바꿔가며 반복 시도**할 수 있고, nonce를 넣은 이유 자체가 무력화된다.
`AuthServiceOAuthLoginTest`(9건)로 이 순서를 고정했다.

> 단위 테스트가 촘촘해도 **조율 로직은 별도로 봐야 한다**는 사례다.
> 부품별 커버리지가 높을수록 오히려 "다 봤다"고 착각하기 쉽다.

### 다음 작업 후보 (갱신 7차)

- **카카오 실토큰 E2E** — 단위 테스트는 우리가 정한 값끼리 대조하므로
  **`application-secret.yml`의 네이티브 앱 키가 실제 `aud`와 맞는지**는 실기기 로그인으로만 확인된다.
  선행: 카카오 콘솔 **플랫폼 등록**(Android 패키지명 + 키 해시 / iOS 번들 ID) — 프론트 작업과 맞물림
- **알고리즘 가드 중복 판별** — `SecretKeySpec` 우회 제거 후 `alg` 테스트로 판별
- **S-I(정리)** → S-J(재설정 + SMTP). S-I는 `TestController` 삭제가 끝나 **문서 정리만 남았다**
- 프론트 axios 인터셉터 2건 (재발급 mutex / 401 전역 처리) + **소셜 로그인 2단계 흐름**
- **Step5 항목** — 404/405 응답이 Boot 기본 에러 JSON이라 `ErrorResponse` 포맷과 다르다.
  `@Valid` 핸들러를 손보는 김에 `NoHandlerFoundException`/`HttpRequestMethodNotSupportedException`도 통일
- **문서 정리 과제** — Step S 완료 후 S-9를 각 절에 흡수
- 알림 도메인 설계 / Step5 Controller + 비밀번호 변경(A-6 이관분)

---

## 2026-08-05

### S-J 구현 완료 — 비밀번호 재설정 (테스트 93건, 신규 19건)

S-10 스펙과 S-9 F-1~F-4를 그대로 코드로 옮겼다. **Step S의 마지막 기능 단위**다.

| 계층 | 산출물 |
|---|---|
| Controller | `AuthController`에 `password-reset/{request,verify,confirm}` 3개 |
| Service | **`PasswordResetService` 신설** |
| Repository | `PasswordResetTokenRepository` (해시 조회 / 최신 `created_at` / 미사용 삭제) |
| 공통 | `UserService.updatePassword` + `User.changePassword` — **Step5의 변경과 공유** |
| 메일 | `global/infra/mail` 3건 + `spring.mail` 설정(타임아웃 포함) |

`AuthService`에 넣지 않은 이유는 **재설정이 토큰을 발급하지 않기 때문**이다(F-4).
저쪽은 "자격증명 → 세션 토큰 발급"을 조율하는 클래스라 목적이 다르고, 협력자도 겹치지 않는다.

### 테스트가 고정한 것은 로직이 아니라 **순서**였다

신규 19건 중 절반이 "누가 언제 불리는가"를 못 박는다. 셋 다 **뒤집혀도 컴파일이 통과하고
나머지 테스트도 전부 통과**한다는 공통점이 있다.

| 순서 | 뒤집히면 |
|---|---|
| 억제 판정 **→** 미사용 토큰 삭제 (F-1) | 판정 근거인 `created_at`이 함께 지워져 **재요청 억제가 사라진다** |
| 비밀번호 변경 **→** 세션 폐기 (S-10 ③-④) | 폐기는 `REQUIRES_NEW`라 즉시 커밋 — 뒤가 롤백되면 **"로그아웃됐는데 비밀번호는 그대로"** |
| 사전 검증은 **소비하지 않음** (F-3) | 링크가 확인 한 번에 죽어 정작 재설정을 못 한다 |

> S-G의 nonce 소비 순서와 **같은 유형이 또 나왔다.** 각 규칙은 맞는데 조합이 틀리는 실수는
> 부품 단위 테스트로는 절대 잡히지 않는다. 조율 클래스는 별도로 순서 테스트를 쓸 것.

### ⚖️ 메일 발송 실패 — 삼킬 수도, 안 삼킬 수도 없는 자리

F-2는 "발송 실패 시 롤백"을 요구한다. 그러려면 예외를 **삼키면 안 된다.**
그런데 요청 엔드포인트의 계약은 "이메일 열거 방지를 위해 **항상 200**"이다. 둘이 부딪힌다.

- **롤백을 택했다.** 삼키면 토큰만 남아, 사용자는 메일을 못 받은 채 재요청해도
  **억제 창에 걸려 아무것도 할 수 없는 상태**가 된다 — F-2가 막으려던 바로 그 상황이다
- 대신 `EXTERNAL_API_ERROR`(502)로 전파한다. 새 ErrorCode를 만들지 않고 기존 상수를 재사용했다

> **알려진 한계** — SMTP 장애 중에는 "로컬 가입 계정 → 502 / 미가입·소셜 계정 → 200"으로 갈려
> **그 시간 동안 이메일 열거가 가능하다.** 상시가 아니라 장애 한정이고, 반대쪽 선택은
> 정상 상황에서 사용자를 가두므로 이쪽을 택했다. 보고서에 한계로 명시한다.

### 🐛 기존 테스트 1건이 이미 깨져 있었다 (S-J와 무관)

`SecurityErrorDispatchTest`의 "permitAll인데 핸들러가 없으면 404"가 **400을 받고 있었다.**

- 이 테스트는 S-D 시점(핸들러가 없던 때)에 `POST /api/auth/oauth/nonce`로 그 상황을 만들었다
- 그런데 **S-G에서 `@PostMapping("/oauth/{provider}")`가 생기면서 이 경로에 핸들러가 생겼다.**
  본문 없는 요청이라 404가 아니라 400(본문 파싱 실패)이 온다
- 조치: 매핑이 생길 여지가 없는 다중 세그먼트 경로(`/api/movies/no-such-handler/nope/nope`)로 옮겼다

> **테스트가 전제한 "없음"이 나중 커밋에서 "있음"이 되는 유형**이다. 경로 존재를 전제로 하는
> 단정은 그 전제가 언제 깨지는지 주석에 남겨야 한다.

### S-I 문서 정리 — 재구조화 대신 진입점

Step S가 끝나며 `security-spec.md`가 1000줄을 넘었다. 원래 계획은 **S-9를 각 절에 흡수시키고
결정 목록만 남기는 재구조화**였는데, 실제로 들여다보니 **각 절 본문에 이미 상세 서술이 있어
중복이 심하지 않았다.** 재배치하면서 근거를 빠뜨릴 위험이 이득보다 컸다.

대신 **내비게이션을 얹었다.**

- **S-9 결정 인덱스** — A~F **24건**을 한 표로 요약하고 각 결정의 상세가 어느 절에 있는지 표기.
  "무엇을 정했고 어디를 보면 되는지"의 진입점
- **S-11 알려진 한계** — 각 절에 흩어져 있던 한계·범위 밖 항목을 **L-1~L-11**로 집약.
  미도입 / 구조적 한계 / 미검증 / 배포 전 필수 4분류

> S-11은 **보고서의 "한계 및 향후 과제"에 그대로 옮길 수 있는 형태**를 의도했다.
> 각 항목에 "왜 남겼는지"와 "해결하려면 무엇이 필요한지"를 함께 적어,
> 나중에 착수할 때 재조사가 필요 없게 했다.

### 📌 한계 기록 — 재설정 요청의 타이밍 사이드채널 (L-5)

S-J 검증에서 나온 실측이다.

| 경로 | 응답 시간 |
|---|---|
| 로컬 가입 계정 (메일 실제 발송) | **5,650ms** |
| 미가입 / 소셜 계정 / 억제 창 안 | **12~17ms** |

D-2에서 **응답 본문**을 항상 동일한 200으로 통일해 이메일 열거를 막았는데,
F-2에 따라 발송이 트랜잭션 안에 있어 **응답 시간이 갈린다.** 약 376배 차이라
타이밍만으로 "이 주소가 로컬 계정으로 가입돼 있다"를 판별할 수 있다.

**억제 창(3분)도 방어가 되지 않는다** — 이메일당 한 번씩만 조회하면 매번 느린 경로를 탄다.
오히려 억제에 걸린 두 번째 요청이 빨라져 "아까 그 계정이 맞다"를 확인해주는 셈이다.

해결 방향은 `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`로 발송을 빼는 것인데,
그러면 **F-2의 원래 근거**(발송 실패 시 토큰만 남아 억제에 걸려 사용자가 갇힘)가 되살아난다.
**발송 실패 시 해당 토큰 삭제**로 해소되며, 전용 스레드 풀과 `AsyncUncaughtExceptionHandler`도 필요하다.

> **캡스톤 평가 비중을 고려해 F-2를 유지하고 한계로 남긴다.**
> 측정값과 해결 방향까지 확보된 상태라 착수하면 스펙 변경 없이 구현만 하면 된다.

### 다음 작업 후보 (갱신 8차)

- **실제 SMTP 발송 E2E** — `application-secret.yml`에 `spring.mail.username` /
  `spring.mail.password`(Gmail **앱 비밀번호**, 2단계 인증 선행)를 넣어야 발송이 성립한다.
  **없어도 기동은 되고 발송 시점에만 실패**하므로, 조용히 넘어가지 않도록 실제로 한 번 보낼 것
- **카카오 실토큰 E2E** — 콘솔 플랫폼 등록 선행(프론트 작업과 맞물림)
- **알고리즘 가드 중복 판별** — `SecretKeySpec` 우회 제거 후 `alg` 테스트로 판별
- **프론트** — 딥링크 수신(`cinemory://reset-password`) + 진입 시 `verify` 먼저 호출,
  axios 인터셉터 2건(재발급 mutex / 401 전역 처리), 소셜 로그인 2단계 흐름
- ~~**문서 정리 과제** — S-9를 각 절 본문에 흡수~~ → **완료(S-I).** 흡수 대신 결정 인덱스(S-9)·
  한계 목록(S-11) 추가로 방향을 바꿔 마무리했다
- 알림 도메인 설계 / Step5 Controller + **비밀번호 변경**(`UserService.updatePassword` 앞에
  현재 비밀번호 검증만 붙이면 된다)

---

## 2026-08-07

### Step5 착수 — Controller 계층 (`docs/controller-layer-spec.md` 확정)

S-4 화이트리스트가 URL을 이미 못박아 둔 상태라 Step5는 새로 설계하는 단계가 아니라
**이미 확정된 계약을 HTTP 표면으로 회수하는 단계**로 정리됐다. 로드맵 5-0~5-7 확정.
설계 판단 근거(페이징 자체 DTO 채택 이유, 응답 래퍼 미도입 이유, URL 규칙 등)는
문서 본문과 변경 이력에 상세히 남아 있어 여기서는 반복하지 않는다.

### 5-0 — 공통 인프라 구현 완료

- `build.gradle`에 springdoc `3.0.3` 추가(validation은 4-6 때 이미 추가돼 있었음)
- `GlobalExceptionHandler` 확장 — `HttpMessageNotReadableException`/`MethodArgumentTypeMismatchException`/
  `HttpRequestMethodNotSupportedException`/`NoResourceFoundException` 4종 신규, `@Valid` 실패 핸들러는
  필드별 위반 목록을 담도록 재작성
- `ErrorResponse`에 `status`/`errors: List<FieldError>` 필드 추가. 기존 `from(ErrorCode)`/`of(HttpStatus, String)`
  시그니처는 유지해 `EntryPoint`/`AccessDeniedHandler`(S-6) 쪽 포맷을 건드리지 않음.
  `ErrorCode.INVALID_INPUT` → **`INVALID_INPUT_VALUE`로 개명**(`MethodArgumentNotValidException` 전용임을 명확히),
  `INVALID_TYPE_VALUE`/`MALFORMED_REQUEST_BODY`/`METHOD_NOT_ALLOWED`/`ENDPOINT_NOT_FOUND` 신규
- `global.dto.PageResponse<T>` 신설 — `Page` 직렬화 비보장 경고 및 `getMovieReviews`의 `totalElements`
  부정확 한계를 흡수할 여지 확보 목적(문서 5-0-D 근거)
- `SecurityConfig` 화이트리스트 결함 2건 수정 — `GET /api/users/*/records` → `/records/**`(회차 조회 매칭 안 되던 문제),
  `GET /api/collections/*/movies` 신규 추가(4-5에서 공개 조회로 확정됐으나 누락돼 있었음). `/swagger-ui/**`·`/v3/api-docs/**`도 추가
- `global.config.OpenApiConfig` 신설 — `bearerAuth` `@SecurityScheme` 등록

**🐛 Boot 4의 `@EnableSpringDataWebSupport` 자동구성 위치 변경 — `max-page-size`가 조용히 무시됨**

`WebConfig`에 `@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)`를 직접 선언했더니
`spring.data.web.pageable.max-page-size: 100`이 적용되지 않았다(size=500 요청이 그대로 500으로 응답).

- 원인: Boot 4는 이 자동구성을 `spring-boot-data-commons` 모듈의 `DataWebAutoConfiguration`으로 옮기고
  `pageSerializationMode`/`max-page-size`를 `spring.data.web.pageable.*` 프로퍼티로 노출한다.
  앱에서 어노테이션을 직접 선언하면 프로퍼티를 읽지 않는 별도 리졸버가 만들어져 설정이 씹힌다
- 조치: `WebConfig`의 어노테이션 제거, `application.yml`에 `spring.data.web.pageable.serialization-mode: VIA_DTO` 추가로 대체
- 5-2(`MovieController`) 실서버 curl 검증 중 발견 — `size=500` 요청이 100으로 clamp되는지 직접 확인하지 않았다면 조용히 넘어갔을 유형

**검증** — 실서버 기동 후 `/v3/api-docs`가 유효한 OpenAPI 3.1.0 JSON 생성 확인(`bearerAuth` 스킴 등록 포함),
`/swagger-ui/index.html` 200, curl로 404(`ENDPOINT_NOT_FOUND`)/400(`MALFORMED_REQUEST_BODY`)/400(`INVALID_INPUT_VALUE`+`errors[]`) 포맷 확인

### 5-1 — `UserController` 구현 완료

- `GET /profile`, `GET /me`, `PATCH /me/{nickname,privacy,password}` 5개 엔드포인트
- `UserService.changePassword(userId, currentPassword, newPassword)` 신규 — OAuth 계정이면
  `INVALID_AUTH_METHOD`, 현재 비밀번호 불일치면 `INVALID_CREDENTIALS`, 성공 시 `updatePassword`(S-J 재사용) +
  `revokeAllByUserId`(세션 전체 폐기)
- **`INVALID_AUTH_METHOD`는 스펙 3곳(`controller-layer-spec.md`/`security-spec.md`/`service-layer-spec.md`)이
  "4-1 기존 상수"라고 전제했지만 실제 `ErrorCode`에는 없었다** — 4-1 시점에 예고만 되고 실제로 추가되지 않은 채
  남아 있던 항목. 이번에 신설
- `PasswordPolicy`(`domain.user.dto`) 상수 클래스 신설 — 8~64자 규칙이 `SignUpLocalRequest`/
  `PasswordResetConfirmRequest`/`PasswordChangeRequest` 세 곳에 리터럴로 중복돼 있던 것을 한 곳으로 모음
  (문서가 "정책 상수는 S-10과 공유한다"고 요구한 항목)
- **검증** — 실서버로 회원가입 → 로그인 → 닉네임/공개범위/비밀번호 변경 전체 플로우 확인.
  비밀번호 변경 후 **이전 refresh token으로 재발급 시도 시 `REFRESH_TOKEN_REUSED`** 로 막히는 것 확인(세션 폐기 동작)

### 5-2 — `MovieController` 구현 완료

- `GET /api/movies`(목록), `GET /api/movies/{movieId}`(상세) 2개. `searchMovies`는 스펙대로 미노출
- 5-0에서 도입한 `PageResponse`/`@PageableDefault`/Springdoc 규약의 첫 검증대 — 여기서 위 `max-page-size` 버그가 드러남

### 5-3 — `WatchRecordController` · `ReviewController` · `WishMovieController` 구현 완료

8개 엔드포인트. `WatchRecordController`는 조회(`/api/users/{userId}/records/**`)와 쓰기(`/api/records/**`)의
경로 프리픽스가 달라 클래스 레벨 `@RequestMapping` 없이 메서드별 전체 경로를 명시했다.

**🐛 `WatchRecordRepository` 파생 쿼리가 `UnknownPathException`을 던짐 — 실제로 호출해보고서야 드러난 버그**

`addWatchRecord`를 처음 curl로 호출했을 때 `findByUserIdAndMovieIdAndRepresentativeTrue`가
`Could not resolve attribute 'representative'`로 500(→400)을 던졌다.

- 원인: 엔티티가 FIELD 접근이라 JPA 메타모델 속성명은 실제 필드명 그대로인데, 구현이 `isRepresentative`로
  돼 있어 **JPA 메타모델 속성(`isRepresentative`)과 파생 쿼리가 기대하는 JavaBean 프로퍼티(`representative`)가
  갈려 있었다.** `jpa-entity-spec.md`는 원래부터 필드명을 `representative`로 명시하고 있어, 4-3 구현 때
  이미 스펙에서 드리프트한 사례였다(4-3/4-4/4-5/4-6에서 반복됐던 것과 같은 유형)
- 1차 조치(내 판단): 리포지토리 메서드명을 `...IsRepresentativeTrue`로 바꿔 호출부만 닫음
- **사용자가 스펙 문서를 직접 수정해 재지시** — 메서드명을 맞추면 이 호출부만 닫히고 `Sort.by(...)`/JPQL/
  `Specification` 등 같은 필드를 참조하는 다른 자리에 동일한 함정이 남는다는 이유로, **엔티티 필드 자체를
  스펙대로 `representative`로 되돌리고 리포지토리/서비스는 원래 이름(`...RepresentativeTrue`)으로 원복**하는
  방향으로 정정. `@Column(name = "is_representative")`는 유지, Lombok이 `isRepresentative()` 게터를 그대로
  생성해 기존 호출부(`watchRecord.isRepresentative()`)는 전혀 수정할 필요가 없었다
- 같은 세션에서 **`WatchRecord.rating` 범위 검증이 아예 없던 것도 발견** — `Review`와 달리 0~10 검증이 없어
  사용자에게 확인 후 `Review`와 동일한 `validateRating()`을 추가(nullable이라 null은 통과)

**DTO 검증 정정 (스펙 문서 수정에 따른 후속)**

- `WatchRecordCreateRequest.watchDate`의 `@NotNull` **철회** — 엔티티가 nullable로 확정돼 있어(오래된 기록은
  날짜 미상일 수 있음) DTO가 더 엄격하면 그 설계가 무력화된다는 이유
- `ReviewWriteRequest.rating`에 `@NotNull` **추가** — 엔티티가 not null인데 처음 구현 시 누락돼 있었음
- `ReviewWriteRequest.content` `@NotBlank @Size(max=2000)`, `WatchRecordCreateRequest.movieId` `@NotNull`은 그대로 유지

**잔여 항목 정리**

- `MyMovieListItemResponse` → `UserMovieListItemResponse` 리네임 완료(`controller-layer-spec.md` 잔여 #6)
- **`BoxOfficeRecord.isNew` 점검** — `WatchRecord`와 같은 클래스의 버그(FIELD 접근 + `is` 프리픽스)가 잠재하는지
  확인 요청을 받아 검토. 결론은 다름: `new`가 Java 예약어라 "is"를 뗀 필드명 자체를 만들 수 없고,
  `jpa-entity-spec.md`/`service-layer-spec.md` 둘 다 필드명을 이미 `isNew`로 명시하고 있어 **코드가 스펙에서
  드리프트한 사례가 아니었다.** 리네임 대상이 없으므로 코드는 그대로 두고, 이 필드로 파생 쿼리를 추가할 때는
  `NewTrue`가 아니라 `IsNewTrue`를 써야 한다는 경고 주석만 필드에 남김(잔여 #9로 완료 처리)
- `Notification.isRead`는 아직 리포지토리·쿼리가 없어 **잠재 위험으로만 잔여 #8에 남아 있음** — 알림 도메인
  착수 전 반드시 먼저 확인할 것

> 세 번째로 반복된 패턴이다: `is` 프리픽스 boolean 필드 + FIELD 접근 조합은 파생 쿼리를 추가하는 순간에만
> 터지는 버그라 컴파일로 잡히지 않는다. 앞으로 이런 필드에 파생 쿼리를 새로 달 때는 **먼저 필드명 그대로
> 써지는지(즉 "is"를 뗀 이름이 필드명과 다른지) 확인하고 시작할 것.**

**검증** — `./gradlew test` 11개 클래스(5-0부터 누적) 전 구간 통과 유지. 실서버 curl로 시청기록 등록(201+Location,
대표 지정/해제 재확인)/삭제, 리뷰 작성/조회/삭제, 위시 토글/조회, 공개범위(`PRIVATE`→403, `PUBLIC`→200) 전부 확인

### 다음 작업 후보 (갱신 9차)

- **5-4** `CollectionController` → **5-5** `FollowController`/`CommentController` →
  **5-6** `TheaterController`/`BoxOfficeController`/`AdminController`(`TheaterSeedService` 입력 방식 잔여) →
  **5-7** 화이트리스트 대조 회귀 테스트 + 통합 테스트 + 문서화 마감
- **`Notification.isRead` → `read` 필드명 정정** — 알림 도메인 착수 전 무비용 시점에 반드시 먼저 처리(잔여 #8)
- `searchMovies` 엔드포인트 노출 — `MovieSearchCondition` 설계 확정 후(검색 설계 세션)
- 컬렉션 단건 조회 Service 메서드 — 프론트 라우팅 확정 시
- `TheaterSeedService` 입력 방식(멀티파트 vs 서버 파일) + 좌표계 확인(4-7 잔여 항목)

---

## 2026-08-10

### Step5-4 — `CollectionController` 구현 완료 (`docs/controller-layer-spec.md` 5-4 기준)

7개 엔드포인트. `WatchRecordController`/`ReviewController`와 동일하게 조회(`/api/users/{userId}/collections`,
공개 조회인 `/api/collections/{collectionId}/movies`)와 나머지 쓰기(`/api/collections/**`)의 경로 프리픽스가
갈려 클래스 레벨 `@RequestMapping` 없이 메서드별 전체 경로를 명시했다.

- `CollectionCreateRequest`/`CollectionUpdateRequest`에 `name` `@NotBlank @Size(max=50)`, `description`
  `@Size(max=500)` 추가 — 4-6까지는 `spring-boot-starter-validation` 미도입이라 두 DTO 모두 검증 어노테이션이
  없는 상태로 남아 있었음
- `AddMoviesToCollectionRequest.movieIds`에 `@NotEmpty @Size(max=50)` 추가 — 상한은 5-0 문서 근거대로
  `findAllById`/`findByCollectionIdAndMovieIdIn`의 IN절 크기 방어용
- `POST /api/collections/{id}/movies`는 idempotent 벌크 추가라 생성된 단일 리소스를 가리킬 `Location`이
  없으므로 200(4-5 확정 유지), `POST /api/collections`만 201+`Location`
- `GET /api/collections/*/movies`·`GET /api/users/*/collections` 화이트리스트는 5-0에서 이미 반영돼 있어
  추가 조치 없음(`SecurityConfig` 확인만 수행)
- 컬렉션 단건 조회 엔드포인트는 스펙대로 미노출 상태 유지(잔여 #4, 대응 Service 메서드 부재)

**검증** — `./gradlew compileJava` / `./gradlew test` (5-0~5-3 누적 11개 클래스) 통과 확인. 실서버 curl 검증은
이번 세션에서 수행하지 않음 — 다음 세션에서 5-5 착수 전 또는 5-7 통합 테스트 단계에서 함께 확인 필요.

### 다음 작업 후보 (갱신 10차)

- **5-5** `FollowController`/`CommentController` → **5-6** `TheaterController`/`BoxOfficeController`/
  `AdminController`(`TheaterSeedService` 입력 방식 잔여) → **5-7** 화이트리스트 대조 회귀 테스트 + 통합 테스트 + 문서화 마감
- 5-4 `CollectionController` 실서버 curl 검증 미수행 — 컬렉션 생성/수정/삭제, 영화 추가/제거, 목록·공개범위
  (`PRIVATE`→403, `PUBLIC`→200) 확인 필요
- **`Notification.isRead` → `read` 필드명 정정** — 알림 도메인 착수 전 무비용 시점에 반드시 먼저 처리(잔여 #8)
- `searchMovies` 엔드포인트 노출 — `MovieSearchCondition` 설계 확정 후(검색 설계 세션)
- `TheaterSeedService` 입력 방식(멀티파트 vs 서버 파일) + 좌표계 확인(4-7 잔여 항목)

### Step5-5 — `FollowController` · `CommentController` 구현 완료 (`docs/controller-layer-spec.md` 5-5 기준)

8개 엔드포인트. `FollowController`는 전 엔드포인트가 `/api/users/{userId}/**` 아래라 `UserController`처럼
클래스 레벨 `@RequestMapping("/api/users/{userId}")`을 썼다. 여기서 `{userId}`는 소유자가 아니라 **대상**이라
5-0-F "쓰기 경로에 주체를 넣지 않는다" 규칙에 위배되지 않는다(follower는 `@AuthUser`로 받는다) — 스펙 설계 노트 그대로.

- `CommentCreateRequest`에 `targetType`/`targetId` `@NotNull`, `content` `@NotBlank @Size(max=500)`,
  `CommentUpdateRequest`에 동일 `content` 제약 추가 — 4-6 구현 당시 `spring-boot-starter-validation` 미도입으로
  주석("Step5에서 추가한다")만 남아 있던 것을 이번에 채움
- `editComment`는 Service가 `CommentResponse`를 반환하지만 Controller에서 버리고 204로 응답 —
  변경된 content는 요청한 클라이언트가 이미 알고 있어 바디가 불필요하다는 스펙 근거 그대로
- `GET /api/comments`의 `targetType` 쿼리 파라미터는 `@RequestParam TargetType`으로 직접 바인딩 —
  잘못된 값이 들어오면 `MethodArgumentTypeMismatchException`(5-0-C 기존 핸들러)이 400으로 받는다.
  `POST` 바디 쪽 enum 오류는 `HttpMessageNotReadableException` 경로로 갈라지므로 핸들러를 따로 추가하지 않음
- 화이트리스트(`/api/users/*/followers`, `/api/users/*/followings`, `GET /api/comments`)는 5-0에서 이미
  반영돼 있어 `SecurityConfig` 확인만 수행, 추가 조치 없음

**검증** — `./gradlew compileJava` / `./gradlew test` (5-0~5-4 누적) 통과 확인. 실서버 curl 검증은 이번 세션에서도
수행하지 않음 — 5-4분과 함께 5-6 착수 전이나 5-7 통합 테스트 단계에서 일괄 확인 필요.

### 다음 작업 후보 (갱신 11차)

- **5-6** `TheaterController`/`BoxOfficeController`/`AdminController`(`TheaterSeedService` 입력 방식 잔여) →
  **5-7** 화이트리스트 대조 회귀 테스트 + 통합 테스트 + 문서화 마감
- 5-4/5-5 실서버 curl 검증 일괄 미수행 — 컬렉션 CRUD, 팔로우/언팔로우(멱등 재확인), 댓글 CRUD(작성자/대상
  소유자 권한 분기), 공개범위(`PRIVATE`→403, `PUBLIC`→200) 확인 필요
- **`Notification.isRead` → `read` 필드명 정정** — 알림 도메인 착수 전 무비용 시점에 반드시 먼저 처리(잔여 #8)
- `searchMovies` 엔드포인트 노출 — `MovieSearchCondition` 설계 확정 후(검색 설계 세션)
- `TheaterSeedService` 입력 방식(멀티파트 vs 서버 파일) + 좌표계 확인(4-7 잔여 항목)

### Step5-6 — `TheaterController` · `BoxOfficeController` 구현 완료 (`docs/controller-layer-spec.md` 5-6-A 기준)

2개 엔드포인트. `AdminController`(5-6-B)는 스펙대로 `TheaterSeedService.seedAll` 입력 방식 미확정으로 이번 단계에서
제외(잔여 #5 유지) — `box-office/sync`·`rematch`만 있는 관리자 엔드포인트는 조회 컨트롤러 2개와 성격이 달라 별도
세션에서 함께 처리하는 편이 낫다고 판단해 이번엔 손대지 않았다.

- `TheaterController.getNearbyTheaters` — `radiusMeters`/`limit`은 `@RequestParam(defaultValue = "${cinemory.theater.*}")`로
  `application.yml`의 기본값을 그대로 끌어썼다. Spring이 어노테이션 속성의 `${…}`를 런타임에 해석해주는 기능을
  이용한 것으로, 프로젝트에서 이 패턴을 쓴 첫 사례라 실서버로 직접 확인했다(아래 검증 참고)
- `BoxOfficeController.getBoxOffice` — `rankType`은 필수, `targetDate`는 `@DateTimeFormat(iso = DATE)` + `required = false`(4-7 확정대로 null이면 Service가 최신 집계일로 대체)
- 둘 다 `viewerId`를 받지 않는다(4-7 확정 — 공용 데이터). 화이트리스트(`/api/theaters/**`, `/api/box-office/**`)는 5-0에서 이미 반영돼 있어 확인만 수행

**🐛 필수 `@RequestParam` 누락이 `ErrorResponse` 포맷을 우회 — 실서버 curl 검증 중 발견**

`GET /api/box-office`를 `rankType` 없이 호출하면 `MissingServletRequestParameterException`이 던져지는데, 5-0-C가
정리한 5종 핸들러 목록에 이 예외가 빠져 있어 `GlobalExceptionHandler`가 못 잡고 Spring Boot 기본
`{"timestamp":...,"error":"Bad Request"}` 포맷으로 새고 있었다.

- 5-2~5-5까지는 필수 파라미터가 전부 경로 변수(`@PathVariable`)나 요청 바디였고, `@RequestParam`은 이미 있는
  `movieId`(`GET /api/reviews/me`) 정도라 우연히 이 경로를 밟지 않았던 것으로 보인다(`rankType`이 첫 필수·
  기본값 없는 쿼리 파라미터 사례)
- 조치: `GlobalExceptionHandler`에 `MissingServletRequestParameterException` 핸들러 추가, `@Valid` 실패와 같은
  성격(요청에서 필수값이 빠짐)이라 동일하게 `INVALID_INPUT_VALUE` + 필드명을 담은 `errors[]`로 응답
  (`docs/controller-layer-spec.md` 5-0-C 표에는 아직 반영 안 함 — 다음 문서 정리 시점에 6번째 행으로 추가 필요)
- **`@RequestParam(defaultValue = "${…}")` 자체는 정상 동작 확인** — `radiusMeters`/`limit` 생략 시 200, 상한
  초과 시 여전히 `INVALID_SEARCH_RADIUS` 400으로 Service가 받음

**검증** — 실서버(`bootRun`)로 직접 curl: `GET /api/theaters/nearby`(기본값 200, `radiusMeters=999999`→400
`INVALID_SEARCH_RADIUS`), `GET /api/box-office`(rankType 누락→400 `INVALID_INPUT_VALUE`, `rankType=NOPE`→400
`INVALID_TYPE_VALUE`, `rankType=DAILY`만→404 `BOX_OFFICE_NOT_FOUND`, 시드 데이터 없음이라 정상) 전부 확인.
`GlobalExceptionHandler` 수정 후 `./gradlew compileJava` / `./gradlew test`(5-0~5-5 누적) 통과 유지.

### 다음 작업 후보 (갱신 12차)

- **5-6 잔여** `AdminController`(`box-office/sync`·`rematch`·`theaters/seed`) — `seedAll` 입력 방식(멀티파트 vs
  서버 파일) + 좌표계 확인 선행 필요(잔여 #5). `sync`/`rematch`는 입력 방식 이슈가 없어 먼저 떼어내 구현할 수도 있음
- **5-7** 화이트리스트 대조 회귀 테스트 + 통합 테스트 + 문서화 마감
- `controller-layer-spec.md` 5-0-C 표에 `MissingServletRequestParameterException` 행 추가 — 문서 정리 시점에 반영
- 5-4/5-5 실서버 curl 검증 일괄 미수행 — 컬렉션 CRUD, 팔로우/언팔로우(멱등 재확인), 댓글 CRUD(작성자/대상
  소유자 권한 분기), 공개범위(`PRIVATE`→403, `PUBLIC`→200) 확인 필요
- **`Notification.isRead` → `read` 필드명 정정** — 알림 도메인 착수 전 무비용 시점에 반드시 먼저 처리(잔여 #8)
- `searchMovies` 엔드포인트 노출 — `MovieSearchCondition` 설계 확정 후(검색 설계 세션)

## 2026-08-11

### Step5-6 잔여 — `AdminController` 구현 완료 (`docs/controller-layer-spec.md` 5-6-B 기준)

- 2개 엔드포인트: `POST /api/admin/box-office/sync`(`targetDate` 필수), `POST /api/admin/box-office/rematch`
  (`limit` 기본값 `${cinemory.boxoffice.rematch-limit}`) — 둘 다 `BoxOfficeSyncService`를 그대로 호출해
  스케줄러(`BoxOfficeScheduler`)와 배치 로직을 공유한다(4-7/5-6-B 확정, 관리자용 별도 로직 없음)
- 응답은 스펙대로 `{ "saved": n }` / `{ "matched": n }` — Service가 `int`만 반환하므로 Controller가
  신규 DTO `BoxOfficeSyncResponse`/`BoxOfficeRematchResponse`(`domain/admin/dto`)로 감쌌다
- 인가는 `SecurityConfig`의 `hasRole('ADMIN')`이 전담하므로 Controller에 `@PreAuthorize`를 중복으로 달지 않음
  (5-6-B 확정)
- `theaters/seed`는 스펙대로 이번에도 제외 — `TheaterSeedService.seedAll` 입력 방식(멀티파트 vs 서버 파일) +
  좌표계 확인이 선행돼야 함(잔여 #5 유지)
- 새 패키지 `domain/admin/{controller,dto}` 신설 — Admin은 자체 엔티티/Service가 없고 다른 도메인 Service를
  그대로 위임 호출만 하므로 controller/dto만 둠

**검증** — `./gradlew compileJava` / `./gradlew test` 통과 확인. 실서버 curl 검증은 미수행(5-4/5-5와 함께 5-7
통합 테스트 단계에서 일괄 확인 예정).

### 다음 작업 후보 (갱신 13차)

- **5-7** 화이트리스트 대조 회귀 테스트 + 통합 테스트 + 문서화 마감 — 5-6 전체(A+B) 완료로 착수 가능
- `controller-layer-spec.md` 5-0-C 표에 `MissingServletRequestParameterException` 행 추가 — 문서 정리 시점에 반영
- 5-4/5-5/5-6 실서버 curl 검증 일괄 미수행
- **`Notification.isRead` → `read` 필드명 정정** — 알림 도메인 착수 전 무비용 시점에 반드시 먼저 처리(잔여 #8)
- `searchMovies` 엔드포인트 노출 — `MovieSearchCondition` 설계 확정 후(검색 설계 세션)
- `TheaterSeedService` 입력 방식(멀티파트 vs 서버 파일) + 좌표계 확인(잔여 #5)

### Step5-6-C — 5-7 선행 3건 완료 (`docs/controller-layer-spec.md` 2026-08-10 개정판 기준)

`controller-layer-spec.md` 5-7이 개정되면서 착수 조건으로 5-6-C 3건이 먼저 필요해졌다. 위 "갱신 13차"
시점의 `AdminController`(패키지 `domain/admin`, `targetDate` 필수, `limit` 플레이스홀더 기본값)는
개정 전 스펙 기준이라 이번 3건에서 함께 갱신됐다 — **과거 기록을 정정하는 대신 이 항목으로 남긴다.**

**① `GlobalExceptionHandler` → `ResponseEntityExceptionHandler` 상속 전환**

- `MethodArgumentNotValidException`/`HttpMessageNotReadableException`/
  `MissingServletRequestParameterException`/`HttpRequestMethodNotSupportedException`/
  `NoResourceFoundException`에 달려 있던 개별 `@ExceptionHandler`를 전부 제거하고, 부모의 동명
  `protected` 메서드를 오버라이드해 바디만 기존 `ErrorResponse`로 바꿔치기하는 형태로 전환
  (`handleExceptionInternal(ex, body, headers, status, request)` 재사용)
- `MethodArgumentTypeMismatchException` 전용 핸들러는 상위 타입 `TypeMismatchException`을 오버라이드
  (`handleTypeMismatch`)하는 것으로 대체 — 서브클래스라 부모의 `handleException` 디스패치가 그대로 잡는다
- 신규 `ErrorCode` 2건 추가: `UNSUPPORTED_MEDIA_TYPE`(415), `NOT_ACCEPTABLE`(406) — 각각
  `handleHttpMediaTypeNotSupported`/`handleHttpMediaTypeNotAcceptable` 오버라이드에서 사용
- `BusinessException`/`IllegalArgumentException`/`DataIntegrityViolationException` 3종은
  `ResponseEntityExceptionHandler`가 다루는 목록 밖이라 기존 `@ExceptionHandler` 그대로 유지
- Spring Boot 4.0.5(Spring Framework 7.0.6) 기준으로 `javap`으로 실제 오버라이드 시그니처
  (`HttpStatusCode`, 4-parameter) 확인 후 작성 — 문서 표현(`HttpStatus`)과 실제 타입이 다를 수 있어 직접 확인함

**② `TheaterController`의 `@RequestParam(defaultValue = "${cinemory.…}")` 제거**

- `radiusMeters`/`limit`을 `Integer` + `required = false`로만 받고 `null`을 그대로
  `TheaterQueryService`에 전달하도록 변경. `TheaterQueryService.resolveRadius`/`resolveLimit`은
  이미 `null` 처리가 구현돼 있어 Service 쪽 변경은 없었음 — Controller만 뒤따라가지 못했던 상태

**③ `AdminController` 재배치 + null 기본값을 Service로 이관**

- `domain/admin/controller` → **`domain/boxoffice/controller`**로 이동(패키지는 호출하는
  Service가 소유한다는 5-1 기준), DTO도 `domain/boxoffice/dto`로 함께 이동
- `syncBoxOffice`의 `targetDate`, `rematchBoxOffice`의 `limit` 모두 `required = false`로 바꿔
  `null`을 그대로 Service에 전달
- `BoxOfficeSyncService.syncDaily`가 `targetDate == null`이면 `LocalDate.now().minusDays(1)`(KOFIC이
  전일 데이터를 익일 제공하는 특성)을 기본값으로 쓰도록 변경. `rematchUnlinked`는 `int` → `Integer`로
  바꾸고 `null`이면 신규 `@Value`(`cinemory.boxoffice.rematch-limit`)를 기본값으로 씀
- `BoxOfficeScheduler`가 갖고 있던 동일 키의 `@Value rematchLimit` 필드를 제거하고
  `rematchUnlinked(null)`로 호출 — 같은 설정값을 두 곳에서 읽으면 한쪽만 바뀔 때 조용히 어긋난다는
  5-6-A 개정 근거를 재매칭 상한에도 동일 적용. 일별 수집의 '어제' 계산은 그대로 스케줄러가 명시적으로
  넘기도록 유지(실패 로그에 날짜를 남겨야 해서 유지가 더 유용하다고 판단)

**검증** — `./gradlew compileJava` / `./gradlew test`(누적) 통과. 실서버 curl 검증은 5-7 A(화이트리스트
회귀 테스트) 작성 시 함께 확인 예정.

### 다음 작업 후보 (갱신 14차)

- **5-7** 착수 가능 — 진행 순서 A(화이트리스트 대조 회귀 테스트, 최우선) → B(`/v3/api-docs` 스모크 체크)
  → C(통합 테스트, 횡단+도메인별 재분류) → D(문서화 마감)
- `controller-layer-spec.md` 5-0-C 표에 `MissingServletRequestParameterException` 행 추가 — 문서 정리 시점에 반영
- 5-4/5-5/5-6 실서버 curl 검증 일괄 미수행
- **`Notification.isRead` → `read` 필드명 정정** — 알림 도메인 착수 전 무비용 시점에 반드시 먼저 처리(잔여 #8)
- `searchMovies` 엔드포인트 노출 — `MovieSearchCondition` 설계 확정 후(검색 설계 세션)
- `TheaterSeedService` 입력 방식(멀티파트 vs 서버 파일) + 좌표계 확인(잔여 #5)

### Step5-7-A — 화이트리스트 대조 회귀 테스트 구현 완료 (`docs/controller-layer-spec.md` 5-7 A 기준)

신규 `WhitelistRegressionTest`(`global/security`, `@SpringBootTest(RANDOM_PORT)` — `SecurityErrorDispatchTest`와
같은 방식, MockMvc는 컨테이너 인가/디스패치를 재현 못해 제외). `RequestMappingHandlerMapping`에 등록된 전체
(메서드, 경로)를 뽑아 `SecurityConfig`의 화이트리스트와 대조하는 3개 테스트로 구성.

- **매칭 엔진으로 `PathPatternParser`+`PathContainer`를 선택**(`AntPathMatcher` 아님) — Spring Security
  6+가 MVC 환경에서 `requestMatchers(String...)`에 실제로 쓰는 것과 같은 엔진이라 오탐/누락이 없다.
  경로 변수(`{userId}` 등)는 더미값 `1`로 치환해 **구체 경로**로 만든 뒤 화이트리스트와 대조한다
  (패턴 문자열끼리 비교하지 않음 — `/v3/api-docs` vs `/v3/api-docs/**`처럼 엔진마다 판정이 갈릴 수 있는
  경계 케이스가 있어서다)
- `SecurityConfig.PUBLIC_GET_ENDPOINTS`를 `private` → 패키지 접근으로 열어 `PUBLIC_POST_ENDPOINTS`와
  같은 방식으로 테스트가 직접 참조하게 함 — 화이트리스트를 테스트 쪽에 별도로 다시 적으면 출처가 갈린다
- 테스트 3종: ① **핵심 산출물** — 화이트리스트(permitAll GET/POST) 밖 경로는 미인증 호출로 200을 반환하면
  안 된다. `/api/admin/**`도 화이트리스트 밖이라 이 스윕에 포함되어 authenticated·hasRole 두 갈래를
  함께 덮는다 ② 반대쪽 회귀 — 화이트리스트 **GET**은 미인증이어도 401이면 안 된다(POST 쪽 permitAll인
  회원가입/로그인/재발급 등은 실제 부수효과가 날 수 있어 GET만 확인) ③ `/api/admin/**`는 일반 유저
  토큰으로 403이어야 한다 — hasRole('ADMIN') 갈래를 명시적으로 고정. ①만으로는 "인증은 됐지만 ADMIN이
  아닌 경우"를 구분하지 못하기 때문(둘 다 401만 보므로)
- **부수효과 없음을 설계로 보장** — ①·③이 실제로 때리는 요청은 전부 인증/인가 필터 단계에서 차단돼
  컨트롤러 본문(DB 쓰기·`BoxOfficeSyncService`의 KOFIC 외부 API 호출 등)에 도달하지 않는다.
  **"ADMIN 토큰으로 실제 호출까지 통과" 포지티브 테스트는 의도적으로 만들지 않았다** —
  `POST /api/admin/box-office/sync`가 진짜 KOFIC API를 호출하기 때문. hasRole 회귀(실수로
  `permitAll()`/`authenticated()`로 완화되는 경우)는 ①·③ 조합만으로 충분히 잡힌다는 점을 근거로 스킵함
- 총 96건(기존 93 + 신규 3) 전부 통과 확인

**검증** — `./gradlew compileJava` / `./gradlew compileTestJava` / `./gradlew test`(전체) 통과.

### 다음 작업 후보 (갱신 15차)

- **5-7 B** — `/v3/api-docs` 스모크 체크(설계 검증, `PageResponse<T>` 제네릭 스키마 렌더링 확인)
- **5-7 C** — 통합 테스트(횡단 2~3개 + 도메인별 `@Valid`/viewer 플래그, 총 15개 안팎), B 이후 착수
- **5-7 D** — 문서화 마감(`openapi-typescript` 생성 파이프라인, 운영 프로파일 Swagger 차단 확인)
- `controller-layer-spec.md` 5-0-C 표에 `MissingServletRequestParameterException` 행 추가 — 문서 정리 시점에 반영
- 5-4/5-5/5-6 실서버 curl 검증 일괄 미수행
- **`Notification.isRead` → `read` 필드명 정정** — 알림 도메인 착수 전 무비용 시점에 반드시 먼저 처리(잔여 #8)
- `searchMovies` 엔드포인트 노출 — `MovieSearchCondition` 설계 확정 후(검색 설계 세션)
- `TheaterSeedService` 입력 방식(멀티파트 vs 서버 파일) + 좌표계 확인(잔여 #5)

### Step5-7-B — `/v3/api-docs` 스모크 체크 완료 (`docs/controller-layer-spec.md` 5-7 B 기준)

`./gradlew bootRun`으로 실서버를 띄워 `GET /v3/api-docs`를 직접 받아 확인(문서 마감이 아니라 5-0-D
설계 결정이 Springdoc과 실제로 맞물리는지 확인하는 **설계 검증** 단계라 B가 C보다 먼저 배치됨).

- **`PageResponse<T>` 제네릭이 타입 인자별로 독립 스키마로 잡힌다** — `PageResponseUserMovieListItemResponse`,
  `PageResponseCollectionResponse`, `PageResponseCollectionMovieListItemResponse`,
  `PageResponseCommentResponse`, `PageResponseFollowUserResponse`, `PageResponseMovieListItemResponse`,
  `PageResponseReviewResponse`, `PageResponseWishListItemResponse` 8종이 각각 별도 컴포넌트로 생성됨
- 각 `content` 필드가 `{"type":"array","items":{"$ref":".../UserMovieListItemResponse"}}` 형태로
  **아이템 타입을 그대로 유지** — 제네릭 소거로 `Object[]`가 되는 등의 문제 없음
- `Page`/`PagedModel`/`PageImpl` 이름의 스키마가 전무 — `@EnableSpringDataWebSupport(VIA_DTO)` 안전망이
  발동한 흔적이 없다(정상 경로에서 전부 자체 `PageResponse`를 쓰고 있다는 방증)
- 문서화된 경로 41개 확인. `bootRun` 기동 로그에 springdoc 기본 경고("운영에서 비활성화 필요")가
  찍히는 것도 확인 — 5-7 D 항목(운영 프로파일 Swagger 차단)과 그대로 연결됨
- 확인 후 `bootRun` 프로세스는 `taskkill`로 정리 — DevLog에 기록된 "고아 프로세스가 8080 점유" 이슈
  재발 방지

**검증** — `curl http://localhost:8080/v3/api-docs` 200, `jq`로 스키마 구조 직접 확인. 5-0-D 결정과
Springdoc이 충돌 없이 동작함을 확인했으므로 계획 변경 없이 C로 진행 가능.

### 다음 작업 후보 (갱신 16차)

- **5-7 C** — 통합 테스트(횡단 2~3개: 404/405/415 `ErrorResponse` 포맷 / 도메인별: `@Valid` 위반 —
  User·WatchRecord·Review·Collection·Comment, viewer 플래그 — Follow·Comment·Review·Collection·
  WatchRecord·Wish), 총 15개 안팎
- **5-7 D** — 문서화 마감(`openapi-typescript` 생성 파이프라인, 운영 프로파일 Swagger 차단 확인,
  `@Operation` 누락분 점검)
- `controller-layer-spec.md` 5-0-C 표에 `MissingServletRequestParameterException` 행 추가 — 문서 정리 시점에 반영
- 5-4/5-5/5-6 실서버 curl 검증 일괄 미수행
- **`Notification.isRead` → `read` 필드명 정정** — 알림 도메인 착수 전 무비용 시점에 반드시 먼저 처리(잔여 #8)
- `searchMovies` 엔드포인트 노출 — `MovieSearchCondition` 설계 확정 후(검색 설계 세션)
- `TheaterSeedService` 입력 방식(멀티파트 vs 서버 파일) + 좌표계 확인(잔여 #5)

### Step5-7-C — 통합 테스트 4개 그룹(C-0~C-3) 구현 완료 (`docs/controller-layer-spec.md` 5-7 C 재개정판 기준)

착수 전 "viewer 의존 플래그"로 뭉뚱그려져 있던 6개 도메인이 실제로는 **응답에 viewer 계산값을
담는 도메인**(Follow·Comment 뿐)과 **viewer 기준 접근 제어만 받는 도메인**(Review·Collection·
WatchRecord·Wish)으로 서로 다른 두 가지였음을 확인 — C-2/C-3로 분리해 스펙 문서에 먼저 반영한
뒤 구현. 신규 파일 4개, 테스트 25개(계획 25개 안팎과 정확히 일치), 전부 `@SpringBootTest`
컨텍스트 위에서 실행(A/B와 달리 슬라이스 아님).

**⚠️ 빌드 설정 수정 필요 — Boot 4.0.5에서 `@AutoConfigureMockMvc`가 안 잡힘**

`spring-boot-starter-test`만으로는 `@AutoConfigureMockMvc`를 import할 수 없었다. Boot 4가 MockMvc
테스트 지원을 **`spring-boot-webmvc-test`라는 별도 모듈로 분리**했고 패키지도
`org.springframework.boot.test.autoconfigure.web.servlet` → `org.springframework.boot.webmvc.test.autoconfigure`로
옮겼다. Maven Central 검색 인덱스에도 아직 안 걸려(`solrsearch` API가 0건) `repo1.maven.org`에
좌표 후보를 직접 HEAD 요청으로 프로브해서 찾았다. `build.gradle`에
`testImplementation 'org.springframework.boot:spring-boot-webmvc-test'` 추가.

**C-0 — `ErrorResponseFormatTest`** (`global/exception`, `RANDOM_PORT`, 3종)

404/405/415가 상태 코드뿐 아니라 `ErrorResponse` 바디(`status`/`code`/`errors`)까지 스펙대로
나가는지 확인. 415는 `POST /api/collections`에 `Content-Type: text/plain`으로 호출해 유발 —
5-6-C ①에서 만든 `ResponseEntityExceptionHandler` 전환이 처음으로 실측 검증됨.

**C-1 — `RequestValidationTest`** (`global/exception`, `@SpringBootTest`+MockMvc+`@Transactional`, 6종)

User(닉네임)·WatchRecord(`movieId`)·Review(`rating`)·Collection(`name`)·Comment(`targetType`)
5개 도메인의 `@Valid` 위반 1건씩 + **카나리아 테스트 1건**(토큰 없이 호출 → 401) 추가 — 이 카나리아가
없으면 나머지 5개가 "필터체인이 실제로 안 도는데 우연히 통과하는" 상태여도 못 잡는다(5-7-C
재개정판이 경고한 "인증이 통과하는 척하며 아무것도 검증하지 않는" 실패 유형).

**C-2 — `ViewerFlagTest`** (`global/access`, 3종)

`FollowUserResponse.following`/`UserProfileResponse.following·me`/`CommentResponse.editable·deletable`이
비로그인 조회에서 `false`인지 확인. 실제 팔로우 관계·댓글 행이 있어야 의미가 있어 `User`/`Follow`/
`Comment`/`Collection`(댓글 대상)을 직접 커밋 — `RANDOM_PORT`가 아니라 MockMvc라 `@Transactional`
롤백이 그대로 적용돼 잔여 행이 안 남는다.

**C-3 — `AccessControlTest`** (`global/access`, 13종)

`UserAccessPolicy` 호출부 9개(컬렉션 목록/영화목록, 댓글 작성/목록, 팔로워/팔로잉, 시청기록
목록/회차조회, 위시리스트) 전부 `PRIVATE`→403 확인 + `FRIENDS` 단방향 팔로우는 403·맞팔이면 200
구분(대표: 컬렉션 목록, `FollowRepository.countMutual`이 2일 때만 통과하는 로직을 직접 검증) +
`PUBLIC`→200(대표: 시청기록) + `getMovieReviews`의 예외 케이스(403이 아니라 비공개 작성자 리뷰만
`content`에서 조용히 빠짐, `totalElements`는 그대로 — 4-6-E에 문서화된 한계와 일치).

- `WatchRecordService.getWatchLog`는 접근 판정이 movieId 존재 여부보다 먼저 실행돼 가짜 movieId로도
  403을 확인할 수 있음을 사전에 코드로 확인 후 활용(실제 시청 기록/영화 행 없이 테스트 가능)
- `Movie`/`Review` 픽스처는 `getMovieReviews` 테스트 1건에만 필요 — `Movie.builder().tmdbId().title()`,
  `Review.of(user, movie, rating, content)`

**검증** — `./gradlew compileJava` / `./gradlew compileTestJava` / `./gradlew test`(전체) 통과.
총 121건(기존 93 + A 3 + C-0 3 + C-1 6 + C-2 3 + C-3 13) 전부 통과, 실패·에러 0.

### 다음 작업 후보 (갱신 17차)

- **5-7 D** — 문서화 마감: `openapi-typescript`(또는 `orval`) TS 타입 생성 파이프라인 확인,
  운영 프로파일에서 Swagger UI/`/v3/api-docs` 실제 차단 확인(`security-spec.md` S-11과 대조),
  `@Operation` 누락분 점검. 이게 끝나면 Step5(Controller 계층) 전체 완료
- `controller-layer-spec.md` 5-0-C 표에 `MissingServletRequestParameterException` 행 추가 — 문서 정리 시점에 반영
- 5-4/5-5/5-6 실서버 curl 검증 일괄 미수행
- **`Notification.isRead` → `read` 필드명 정정** — 알림 도메인 착수 전 무비용 시점에 반드시 먼저 처리(잔여 #8)
- `searchMovies` 엔드포인트 노출 — `MovieSearchCondition` 설계 확정 후(검색 설계 세션)
- `TheaterSeedService` 입력 방식(멀티파트 vs 서버 파일) + 좌표계 확인(잔여 #5)

### Step5-7-D — 문서화 마감 완료, **Step5(Controller 계층) 전체 완료** (`docs/controller-layer-spec.md` 5-7 D 기준)

3갈래 전부 실기동으로 검증. 이 항목으로 로드맵 5-0~5-7이 모두 끝났다.

**① `openapi-typescript` 생성 파이프라인**

`npx openapi-typescript http://localhost:8080/v3/api-docs` 정상 생성(0.8~0.9초, 에러 0). B에서
확인한 `PageResponse<T>` 제네릭도 TS 쪽에서 `content?: components["schemas"]["Xxx"][]`로 올바르게
좁혀짐을 재확인.

**🐛 생성 결과를 직접 열어보다가 발견 — `@AuthUser`가 공개 쿼리 파라미터로 새고 있었다**

Springdoc이 `@AuthUser`를 커스텀 인증 리졸버로 인식하지 못하고 일반 파라미터로 스캔해,
`viewerId`/`authorId`/`followerId`뿐 아니라 쓰기 엔드포인트에서 `@AuthUser`를 `userId`라는 이름으로
받은 경우까지 합쳐 **15개 이상의 오퍼레이션**에서 "클라이언트가 절대 채워선 안 되는 인증 주체 값"이
TS 타입상 쿼리 파라미터로 노출되고 있었다(예: `getWatchLog`가 `query: { viewerId: number }`를 가짐 —
실제로는 헤더의 JWT에서 채워지는 값인데 클라이언트가 직접 넣을 수 있는 것처럼 보임).

- **원인** — Springdoc이 `@RequestParam`/`@PathVariable` 등 표준 파라미터 어노테이션이 없는
  매개변수도 기본적으로 스캔한다. `@AuthenticationPrincipal` 같은 Spring Security 표준 타입은
  Springdoc이 알아서 무시하지만, 우리가 만든 커스텀 `@AuthUser`는 등록해주지 않으면 모른다.
- **조치** — `OpenApiConfig`에 `SpringDocUtils.getConfig().addAnnotationsToIgnore(AuthUser.class)`를
  **static 블록**으로 추가(`@PostConstruct`가 아닌 이유: 이 등록은 Springdoc이 컨트롤러를 스캔하기
  전에 반영돼야 하므로 빈 생명주기보다 이른 시점이 필요하다).
- **검증** — 수정 후 재생성해 해당 파라미터가 전부 `query?: never`로 사라짐, `@PathVariable userId`
  같은 정상 경로 변수는 `in: path`로 그대로 남아 있음을 `/v3/api-docs` JSON에서 직접 대조 확인
  (부수피해 없음).
- **의미** — B(스모크 체크)는 스키마 형태(`PageResponse<T>` 렌더링)만 봤지 파라미터 노출까지는
  보지 않아 이 문제를 못 잡았다. "TS 타입이 실제로 생성되는지"와 "생성된 타입이 클라이언트 관점에서
  올바른지"는 다른 검증이라는 뜻 — D가 단순 마감이 아니라 여기서도 진짜 버그를 잡을 수 있었다.

**② 운영 프로파일 — Springdoc 차단 (`security-spec.md` L-12 완료)**

`security-spec.md` L-12가 "운영 프로파일 파일 자체가 없어 미처리"라고 적어둔 상태였다.

- **신규 `application-prod.yml`** — `springdoc.api-docs.enabled=false`, `springdoc.swagger-ui.enabled=false`.
- **실기동 검증** — `SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun`. 로그에 `"secret", "prod"` 두
  프로파일이 활성화됐음을 확인. `GET /v3/api-docs`·`GET /swagger-ui/index.html` 둘 다 404,
  일반 API(`GET /api/movies`)는 그대로 200. springdoc 자동배선 경고 로그(기본 프로파일에서는
  찍히는 "SpringDoc ... endpoint is enabled by default")가 **아예 안 찍혀서**, 화이트리스트로
  숨긴 게 아니라 자동구성 자체가 꺼졌음을 구분해서 확인했다.
- `security-spec.md` L-12를 완료로 표기, 두 문서에 상호 참조 추가.

**③ `@Operation` 누락분 점검**

컨트롤러 파일별 `@GetMapping`/`@PostMapping`/... 매핑 수와 `@Operation` 수를 기계적으로 대조.
Step5에서 작성한 **11개 도메인 컨트롤러 전부 1:1 일치**, 누락 없음(5-0-G "도메인 작성 시 함께
단다" 원칙이 실제로 지켜졌음을 확인). 유일한 예외는 `AuthController`(매핑 9 / `@Operation` 0)인데,
Step S(Springdoc 도입 이전)에 작성된 컨트롤러라 이 문서 서두에서 이미 범위 밖으로 명시돼 있어
이번에 손대지 않았다.

**검증** — `./gradlew compileJava` / `./gradlew test`(전체) 통과, 121건 유지(문서 작업이라
테스트 수 변화 없음). `bootRun` 2회(기본/prod 프로파일)는 모두 확인 후 `taskkill`로 정리.

### 다음 작업 후보 (갱신 18차)

- **Step5(Controller 계층) 전체 완료** — 5-0~5-7 로드맵 종료. 다음 큰 단위는 알림 도메인 또는
  잔여 항목 정리
- **`Notification.isRead` → `read` 필드명 정정** — 알림 도메인 착수 전 무비용 시점에 반드시 먼저
  처리해야 하는 **유일한 필수 선행 작업**(잔여 #8)
- `controller-layer-spec.md` 5-0-C 표에 `MissingServletRequestParameterException` 행 추가 — 문서 정리 시점에 반영
- 5-4/5-5/5-6 실서버 curl 검증 일괄 미수행
- `searchMovies` 엔드포인트 노출 — `MovieSearchCondition` 설계 확정 후(검색 설계 세션)
- `TheaterSeedService` 입력 방식(멀티파트 vs 서버 파일) + 좌표계 확인(잔여 #5)
- 알림 도메인 Controller — 도메인 설계 자체가 미착수(잔여 #7)

---

## 2026-08-13 ~ 08-24 — Step6(TMDB 연동) 요약 · **브리지 기록**

> ⚠️ **이 구간은 세션 단위 기록을 남기지 못했다.** Step5 종료(08-11) 직후 Step6에 바로
> 들어가면서 DevLog가 12일간 비었다. 아래는 공백을 메우기 위한 **요약**이고,
> **판정과 근거의 단일 출처는 각 스펙 문서의 변경 이력이다** — 그쪽이 설명 대상 옆에
> 붙어 있어 더 정확하고, 여기에 옮겨 적으면 두 벌을 동기화해야 한다.
>
> | 무엇을 찾을 때 | 어디를 볼 것 |
> |---|---|
> | TMDB 연동 설계·판정 전체 (6-0~6-9) | `docs/tmdb-sync-spec.md` (변경 이력 21건) |
> | 엔티티 변경과 근거 | `docs/jpa-entity-spec.md` (9건) |
> | Service/Controller 파급 | `docs/service-layer-spec.md`(5건) / `docs/controller-layer-spec.md`(4건) |
> | 남은 일 전체 | `docs/tmdb-sync-spec.md` 잔여 표 (#1~#27) |

### 무엇을 했나

| 단위 | 내용 |
|---|---|
| 6-0 (08-13) | 착수 전 미결 4건 확정 — D-1 `role_tier` 경계값(절대 순번 0~4/5~9/10~20/21~), D-2 적재 전략, D-3 대표 제작국, D-4 `overview` 타입 |
| 6-1~6-6 (08-20) | 참조 테이블 적재 · `TmdbClient` · 도메인 매핑 · `MovieSyncService` · 시드 전략 · ErrorCode |
| 6-7 (08-20) | 최초 시드 **60편** 실측 — 잔여 #4·#8·#11 판정 |
| 6-8 (08-23) | 영화 검색 `GET /api/movies/search` 설계·구현 |
| 6-9 (08-23~24) | `movie` 메타데이터 보강(v13) + `POST /api/admin/movies/resync` |
| 6-7-b (08-24) | 본 시드 **4,609편** 실측 — 잔여 #8·#10·#11 종결 |

**스키마** v10 → **v11**(`display_order`/`role_tier` EXTRA/`overview` 확장) → **v12**(정오·롤백)
→ **v13**(`original_title`·`backdrop_path`·`vote_average`·`vote_count`) → **v14**(`character_name` 확장).

**최종 데이터 규모** — `movie` 4,609 / `movie_actor` 186,717 / `person` 113,909 / `box_office_record` 140.

### 남길 가치가 있는 것 — 뒤집은 판정 4건

이 구간에서 **한 번 확정한 것을 되돌린 일이 네 번** 있었다. 되돌린 이유가 요점이다.

1. **`overview` `varchar(4000)` → `1000` 롤백 (v11→v12)** — 확장 근거였던 *"TMDB overview가
   1000자를 넘을 수 있다"* **가 사실이 아니었다.** TMDB가 입력 자체를 1000자로 제한한다.
   원래 값은 임의값이 아니라 **외부 API 계약을 반영한 값**이었는데 확인 없이 넓히면서
   그 정보를 지웠다. → 이후 길이 초과 4곳을 **"추정으로 넓히지 말고 절단+WARN 후 실측"** 으로
   정책화했고, 이 정책이 v14에서 처음으로 결론을 냈다.
2. **`display_order smallint` → `int` (v11→v12)** — `Integer`는 `Types.INTEGER`,
   `smallint`는 `Types.SMALLINT`라 **`ddl-auto=validate`가 기동에서 실패**한다.
   여기서 얻은 구분이 이후 계속 쓰였다 — **Hibernate는 타입을 검증하고 길이는 검증하지 않는다.**
3. **검색 C안(TMDB 단독) 철회 → B안** — C안이 쟁점 세 개를 한 번에 없앴지만,
   *"쟁점이 사라진다"가 곧 "설계가 옳다"는 뜻은 아니었다.* 우리 DB가 우리 제품에서
   구경꾼이 되는 구조였다.
4. **`RoleTier` 판정 로직 위치 — 서비스 → enum** — `displayOrder`는 TMDB 개념이 아니라
   이미 우리 필드다. "우리 필드값 → 우리 enum"은 enum 자신의 규칙이다.

### 표본 크기가 판정을 바꾼 사례 (6-7 → 6-7-b)

60편에서 내린 판정 세 개가 4,609편에서 갈렸다. **평균이 아니라 분포를 봐야 했다는 것이 교훈.**

| 항목 | 60편 | 4,609편 | 결과 |
|---|---|---|---|
| 잔여 #8 인덱스 | `EXPLAIN rows` 141 | 테이블 **79배**인데 여전히 **141** | ✅ 판정 유지 — 비용은 테이블 크기가 아니라 매칭 행 수 |
| 잔여 #10 박스오피스 매칭 | 8건뿐 (검증 불가) | 140건 중 127건 **90.7%** | ✅ **D-2의 역방향 시드 선택이 처음으로 실측 검증** |
| 잔여 #11 `character_name` | 최대 30자, 절단 0건 | 최대 **100자(=상한)**, 절단 **29건** | ⚠️ **뒤집힘** → v14 확장 |
| 잔여 #19 인물명 한글화 | 28.8% | **11.9%** | ⚠️ 악화 (외화 비중 상승) |

⚠️ **계측 실패 1건** — 본 시드의 `seed.log`가 유실됐다. PowerShell `*>`가 **덮어쓰기**라
시드 후 `bootRun` 재시작에 날아갔다(`*>>`가 이어쓰기). `SeedResult` 8건과 절단 원본 길이를
확인하지 못했다. → 런북 보강을 **잔여 #26**으로 등록.

---

## 2026-08-27

### v14 코드 반영 · resync 전량 실행 · 인프라 구성 상의

**① 잔여 #27 종결 — v14 코드 반영.** `MovieActor.characterName`의 `@Column(length)`와
`MovieSyncPersister.CHARACTER_NAME_MAX_LENGTH`를 **100 → 255**로 함께 올렸다.
**DB만 넓히면 아무 효과가 없는 유형**이라 별도 항목으로 세워 뒀던 것이다 —
`ddl-auto=validate`가 길이를 검증하지 않아 기동은 통과하고, `truncate()`는 WARN만 남기며
정상 종료해서 **절단이 100자에서 조용히 계속됐을** 상황이었다.

**② `resync` 전량 실행 — 4,609편 전건 처리.** v13 신규 컬럼 NULL 보정과 v14 절단분 복구를
한 번에 해소했다.

```
25 라운드 (200건 커서), 16:36:51 → 17:06:17  ≈ 29분 30초
total_updated=4,587  total_skipped=22        (합 4,609 = 적재 전량과 일치)
stoppedByRateLimit  전 라운드 false, 429 0건
```

- **마지막 라운드가 `updated=0` + 커서 미전진**으로 끝났다 — 6-9가 설계한 종료 신호가
  의도대로 동작했다.
- **`skipped` 22건이 `id` 1970~2370 한 구간에 몰렸다**(라운드 10에 21건, 11에 1건).
  그 라운드만 3분 6초로 다른 라운드(1~1.5분)의 두 배였다. 원인은 확인하지 않았다.
- **토큰 30분 만료가 실제로 발생했다** — 시작 24분 시점(17:00:34)에 재발급.
  잔여 #26이 지적한 그대로이고, 이번 실행은 재발급을 명시적으로 처리해 넘어갔다.
- **이번엔 로그가 살아남았다** — `seed.log`(72MB, ASCII)와 `resync_run.log` 모두 판독 가능.

**③ ⚠️ 새로 드러난 것 — `varchar(255)`로도 부족한 케이스가 3건 있다.**

resync 로그에 절단 WARN이 **3건** 남았다. 확장 전 29건에서 크게 줄었지만 0은 아니다.

```
tmdbId=35    원본 길이=300
tmdbId=35    원본 길이=270
tmdbId=9473  원본 길이=348
```

**6-7이 예상한 유형(다역·성우)이 맞았고, 다만 꼬리가 예상보다 길었다.** v14를 쓸 때
*"실측 원본이 100자를 갓 넘는 수준이라 2.5배면 충분"* 이라 적었는데, 그 "실측 원본"은
**이미 100자로 잘린 값이라 진짜 길이를 알 수 없는 상태**였다. 상한에 걸린 값으로 상한을
정한 셈이고, 이번에 처음으로 **자르지 않은 원본 길이**를 봤다.

**지금은 확장하지 않는다.** 186,717행 중 3건(0.0016%)이고, 절단이 WARN과 함께 graceful하게
처리되며, **348자짜리 배역명은 애초에 UI에서 전부 표시할 값이 아니다** — DB 상한보다
표시 정책이 먼저 걸리는 구간이다. 무엇보다 복구에 **resync 30분이 또 든다.**
다음에 resync를 돌릴 일이 생기면 그때 v15와 함께 처리한다. → **잔여 #28**

**④ discover 프로필 pass-through 코드 반영** (6-5, 08-23 확정분). `TmdbClient.discoverMovies`의
`region=KR` · `sort_by=popularity.desc` **하드코딩을 철회**하고 `lang`/`minVotes`/`sortBy`/`year`를
`queryParamIfPresent`로 받도록 바꿨다(`AdminController` → `MovieSeedService` → `TmdbClient` 3계층).
근거는 6-5에 있다 — `region`은 "한국에서 개봉한"이라 대부분 할리우드이고, `popularity`는
1페이지부터 무명작이 섞인다. **인지도 축은 `vote_count.gte`이고, 임계값은 실행 결과를 보고
조정할 값이라 상수로 박으면 조정마다 빌드가 필요해진다.**

**⑤ 인프라·배포 구성 상의 → `CineMory_기획노트.md` 4-INF 신설.**

지금까지 로컬 서버로만 진행해 왔고, 실서버 시점·구성을 처음으로 정리했다.

- **배포 트리거는 날짜가 아니라 "M2에서 카카오 로그인을 실기기에 붙이는 시점"**(대략 9월 중순).
  실기기에서 `localhost`는 폰 자신을 가리키고, Expo Go는 LAN IP로 우회되지만
  **카카오 redirect URI는 우회가 안 된다.** 거기가 로컬로 버틸 수 있는 한계선이다.
- **구성은 A안**(단일 인스턴스에 Spring Boot + MySQL 동거). 메모리 하한 2GB, `mysqldump` 스케줄 필요.
  **포스터를 경로만 저장한 결정이 여기서 이득으로 돌아왔다** — 이미지를 담았다면
  포스터 4,609 + 인물 113,909로 수 GB가 되어 이 구성 자체가 성립하지 않았다.
- **A→B는 반나절~하루, A→C는 며칠.** A→C가 싼 이유는 **현재 코드에 로컬 파일 I/O가 0건**이기
  때문이다(`MultipartFile`·`Files.`·`new File(` 전부 없음).
- ⚠️ **전환 비용을 실제로 결정하는 것은 이전 작업이 아니라 그 사이에 쌓이는 결합**이다. 2건 식별:
  **`user.profile_image` 저장 위치**(M2 업로드 설계 전에 정해야 함 → security-spec **L-13** 신설),
  **`BoxOfficeScheduler`의 `@Scheduled` 2건**(복제본마다 크론이 발화 → 잔여 #18 범위를 확대).
- **S-11 "배포 전 반드시 처리할 것"에 처리 시점 열을 추가**했다. 한 덩어리로 묶여 있었지만
  실제로는 세 부류였다 — **L-10·L-11은 배포를 기다릴 이유가 없어 8월 말 선행으로 옮겼다.**
  배포 없이 검증되면서, 배포 중에 만나면 진단이 가장 어려운 유형이다(L-10은 기동 실패인데
  로그가 다른 곳을 가리키고, L-11은 KST/UTC 9시간 차가 *"로그인하자마자 만료"* 로 나타난다).

**⑥ 문서 정리** — v14 델타 작성, 6-7-b 절 신설, 잔여 #26·#27·#28 등록 및 번호 재정렬,
`jpa-entity-spec`·`security-spec`·기획노트 반영, M1 **완료** 선언.

**검증** — resync 25라운드 전량 성공(4,587+22=4,609), 429 0건, 기동 1회로 전 구간 단일 JVM.
문서 측은 표 열 수·상호 참조·변경 이력 기록을 스크립트로 대조.

### ✅ 카카오 실토큰 로컬 검증 성공 — `security-spec` L-7 부분 종결

M2 착수 전 선행으로 잡았던 항목이다. **앱 없이 브라우저 웹 플로우만으로** 실토큰을 받아
`POST /api/auth/oauth/kakao`에 넣고, **서명 · `iss` · `aud` · `nonce` 4종 검증을 모두 통과**시켜
계정 생성과 JWT 발급까지 확인했다. 절차는 `docs/kakao-login-runbook.md`로 새로 썼다.

**왜 지금 했나 — 미지수를 한 시점에 몰지 않기 위해서였다.** 이 검증은 구조상 **배포 트리거와
같은 시점에 온다**(4-INF). 9월 중순에 *"카카오 로그인 처음 붙이기 + 실토큰 처음 검증 +
실서버 처음 올리기 + redirect URI 처음 등록"* 이 겹치면 실패 시 층을 가릴 수 없다.

**결과적으로 그 판단이 맞았다.** 통과까지 네 단계를 순서대로 벗겨야 했다.

| 막힌 지점 | 원인 | 배운 것 |
|---|---|---|
| `KOE033` | 런북의 `{REST_API_KEY}` 표기를 보고 **중괄호까지 URL에 넣었다** + REST API 키에 Redirect URI 미등록 | 플레이스홀더 표기를 `<...>`로 통일하고, 주소창에 `%7B`/`%3C`가 보이면 괄호가 딸려온 것이라는 진단을 런북에 넣었다 |
| **401** | **`client_secret` 누락** — 초안에 이 파라미터가 아예 없었다 | 클라이언트 시크릿을 활성화한 앱은 토큰 교환에 필수(`KOE010`) |
| `INVALID_NONCE` | TTL 5분 초과 | **5분은 앱 기준이다.** 사람이 브라우저를 왕복하며 복사하는 수동 절차에는 빠듯하다 |
| `INVALID_OAUTH_TOKEN` | **`aud`가 `allowed-audiences`에 없었다** | 웹 플로우의 `aud`는 **REST API 키**다 |

**⚠️ 진단을 가장 크게 방해한 것 — `INVALID_NONCE`가 두 원인을 구분하지 못한다 (→ L-14 신설).**

`AuthService.oauthLogin`은 `consumeOrThrow`(우리 캐시에 있나) → `verify`(토큰 속 값과 같나)
순으로 검사하는데 **둘 다 같은 코드·같은 "만료되었습니다" 메시지**를 낸다. 게다가 **①이 먼저
nonce를 소비**하므로, 진짜 원인이 ②(값 불일치)여도 재시도는 전부 ①에서 실패해 만료처럼 보인다.
TTL을 늘려도 안 고쳐지는 상황이 만들어진다. **`id_token`을 직접 디코딩해 `nonce` 클레임을
대조하고서야 갈렸다.** 보안상 클라이언트에 상세를 줄 필요는 없지만 서버 로그에서는 구분돼야 한다.

**⚠️ 도구 문제 하나 — PowerShell이 원인을 숨긴다.**

`Invoke-RestMethod`는 4xx/5xx를 예외로 던지며 **응답 본문을 삼킨다**(bash `curl`과 다르다).
`$_.ErrorDetails.Message`를 찍지 않으면 *"401"* 이라는 사실만 알고 `ErrorCode`는 못 본다.
더 나쁜 경우도 겪었다 — **성공 출력을 `try` 안에 두면** 값이 `null`일 때 `.Substring()`이 예외를
던지고, 그건 HTTP 오류가 아니라 `ErrorDetails`가 비어 **catch도 아무것도 찍지 않는 완전한 침묵**이
된다. 응답 스트림을 직접 읽는 폴백까지 넣어야 했다. 잔여 #26의 *"토큰 만료가 `catch`에 삼켜진다"*
와 같은 부류이며, **PowerShell 절차서에는 에러 본문 출력이 기본값이어야 한다.**

**설계가 미리 값을 한 사례.** `KakaoOAuthProperties`의 `allowed-audiences`는 *"카카오는 로그인한
플랫폼에 따라 `aud`가 다르다"* 를 예상해 **처음부터 목록**으로 설계돼 있었다(2026-08-02 S-G).
덕분에 REST API 키를 **교체가 아니라 추가**로 넣어 끝났고, 나중 네이티브 키도 같은 방식이다.

**최종 성공은 1~4단계를 한 스크립트로 이어 붙여 15초에 끝냈다.** 수동 절차의 병목이 사람의
복사·붙여넣기 시간이라는 뜻이다.

**닫히지 않은 것** — 통과한 `aud`는 **REST API 키**다. RN에서 카카오 SDK로 로그인하면 `aud`가
**네이티브 앱 키로 바뀌어 같은 `INVALID_OAUTH_TOKEN`을 다시 만난다.** 한 줄 추가로 끝나지만
모르면 M2에서 오늘을 반복한다. 실기기 redirect URI·HTTPS는 배포 시점 항목이다.

**검증** — `SELECT ... FROM user WHERE provider='KAKAO'`로 `email` 채움과 `password_hash IS NULL`
(로컬/소셜 상호배타 CHECK) 확인. `nonce-ttl`은 검증용 연장분을 `PT5M`으로 원복.

### 다음 작업 후보 (갱신 19차)

- **M1(TMDB 연동) 완료.** 다음 큰 단위는 **M2 프론트엔드** — 인수인계는 기획노트 **4-M2절**
- **8월 말 선행 2건** — `security-spec.md` **L-10**(`jwt.secret` 환경변수) ·
  **L-11**(시간대 고정). 배포를 기다리지 말 것 (4-INF)
- ⚠️ **M2에서 카카오 붙일 때** — `allowed-audiences`에 **네이티브 앱 키 추가**(L-7).
  오늘 통과한 `aud`는 REST API 키다. 안 넣으면 `INVALID_OAUTH_TOKEN`이 그대로 재현된다
- **L-14** — `INVALID_NONCE`의 두 원인(미발급/소비 vs 값 불일치)을 **서버 로그에서 구분**.
  오늘 진단이 여기서 막혔다
- **M2 착수 시 먼저 정할 것** — **L-13** `user.profile_image` 저장 위치.
  업로드 기능을 붙이기 **전에** 정해야 비용이 0이다
- 이미지 베이스 URL 상수화 — 프론트에서 한 곳으로. TMDB 출처 표기 요건도 함께 확인
- 잔여 #26 — `movie-seed-runbook.md` 보강 (로그 덮어쓰기·검증 SQL·토큰 만료). 다음 대규모 시드 전
- 잔여 #28 — `character_name` v15 확장 여부. **다음 resync 때 함께** (단독으로는 30분 값이 안 됨)
- 잔여 #18 — 다중 인스턴스 안전성(시드 `AtomicBoolean` + `BoxOfficeScheduler` 크론).
  **구성 A 유지 중에는 착수 불필요**
- 미커밋 상태 정리 — 코드 5개 파일 + 문서 4개 + `v14-delta.sql`·`movie-seed-runbook.md` 신규

## 2026-09-06

### `GET /api/movies/random` 구현 완료 (5-2 신설분, 2026-09-02 설계)

프론트 홈 화면의 배경(포스터 그리드)을 채우기 위한 엔드포인트다. 확정된 설계 그대로
구현했으며 설계 변경은 없다.

- `MovieRepository.findRandomWithPoster(size)` — `poster_path IS NOT NULL` 필터 +
  `ORDER BY RAND() LIMIT :size` native query 신설
- `MovieQueryService.getRandomMovies(size)` — `size` null이면 기본값(20), 상한(50) 초과면
  clamp. `cinemory.movie.random.default-size`/`max-size`를 `application.yml`에 추가하고
  `TheaterQueryService.resolveRadius`/`resolveLimit`와 같은 `@Value` 주입 패턴을 따랐다.
  연관관계(genre/country) 조회 없이 1쿼리로 끝난다
- `MovieController.getRandomMovies` — `GET /api/movies/random`, 응답은 `List<MovieSummaryResponse>`
  (페이징 없음, `GET /api/theaters/nearby`와 같은 성격)
- 화이트리스트·`WhitelistRegressionTest` 변경 없음 — `/api/movies/**`가 이미
  `PUBLIC_GET_ENDPOINTS`에 있고 신규 매핑은 동적 수집된다(5-7 A)

**검증** — `./gradlew compileJava` 통과 확인
