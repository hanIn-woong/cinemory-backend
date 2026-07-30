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

### 스키마 v9 적용 완료 (18 → 20 테이블)

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
- **잔여 2건** — ① HS256 고정을 검증하는 단정이 없다(라운드트립은 양쪽이 HS512여도 통과하므로
  정작 이 파일이 만들어진 계기가 고정되지 않았다). 헤더 `alg` 단정을 추가하면 현재 두 겹인 가드
  (`SecretKeySpec` JCA 이름 / `signWith` 명시 인자) 중 어느 쪽이 실효인지도 드러난다
  ② `Thread.sleep(50)` → `Clock` 주입. `RefreshToken.isExpired(now)`와 같은 이유로 만든 규칙인데
  `JwtTokenProvider`만 `Instant.now()`를 내부 호출하고 있어 컨벤션이 어긋난다

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

### 다음 작업 후보 (갱신 6차)

- **S-F — `AuthService` + `RefreshTokenRepository` + DTO 4종 + `AuthController`**
  (Step S에서 가장 큰 단위. 여기서 처음으로 S-D·S-E를 포함한 실제 HTTP 검증이 가능해진다)
  - 회전 + 재사용 감지 + **30초 유예**(A-4). 유예는 정석이 아니다 —
    v9 `refresh_token`에 "무엇으로 대체됐는가"를 가리키는 자기참조 컬럼이 없어
    직전 발급분을 되돌려주는 구현이 불가하다. 창 안에서는 탈취 토큰도 통과하므로 보고서에 한계로 명시
- **S-C 잔여 2건** — HS256 헤더 `alg` 단정 추가, `Clock` 주입
- S-G(카카오) → S-H(비밀번호 변경) → S-I(정리)
- **코드 외 선행 작업**
  - 카카오 콘솔 — 비즈앱 전환/본인인증 → `account_email` 활성화 → **필수 동의** 설정 (A-1)
  - 프론트 axios 인터셉터 2건 — 재발급 **mutex**(A-4), **401 전역 처리**(A-3에 따라
    공개 조회에서도 만료 시 401이 온다. `TOKEN_EXPIRED`는 재발급 후 재시도,
    `INVALID_TOKEN`은 토큰 삭제 후 로그인 화면)
- **문서 정리 과제** — `security-spec.md`가 633줄이 되면서 S-9와 각 절이 서로를 참조하는 구조가 됐다.
  Step S 완료 후 **S-9 내용을 각 절 본문에 흡수시키고 S-9는 결정 목록만 남기는** 정리를 한 번 할 것
- 알림 도메인 설계 (Step S 이후 별도 절, 고아 알림 정리 필수)
- Step5 Controller + `@Valid`
