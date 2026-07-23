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

### 다음 작업 후보

- Step3에서 미룬 서비스 레이어 로직: `WatchRecordService`(대표 기록 단일성 보장), `MovieMetadataService`(장르/국가 weight 계산)
- Repository/Controller/DTO 계층은 아직 미착수 (엔티티만 완료된 상태)
