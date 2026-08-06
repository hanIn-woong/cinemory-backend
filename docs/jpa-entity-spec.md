# CineMory JPA Entity 설계 스펙

이 문서는 `docs/schema/cinemory_backup_v10.sql`(**ERD v10, 22 테이블**)을 기준으로
JPA 엔티티를 어떻게 구현할지 정리한 스펙이다. 공통 규칙은 `CLAUDE.md`를 따르고,
이 문서는 **엔티티별 구체 스펙**만 담는다.

> v9 → v10 변경분은 `docs/schema/v10-delta.sql` 참고
> (`refresh_token.revoked_reason` 추가, `password_reset_token` 신설, `box_office_record.open_date` 추가).
>
> v8 → v9 변경분(`user.role` 추가, `refresh_token`/`notification` 신설)의 델타 파일은
> **커밋되지 않은 채 적용 후 삭제돼 남아 있지 않다.** 해당 변경 내역은 아래 Step4 절과
> `DevLog.md` 2026-07-29 기록으로만 확인할 수 있다.

Claude Code에 작업을 맡길 때는 이 문서의 특정 섹션만 지정해서 작은 단위로 진행할 것.
예: `"Step2 - 2) MovieCountry 스펙대로 구현해줘"`

---

## 진행 순서 (의존성 기준)

1. ✅ **Step1** — 공통 Base Entity + 독립 참조/마스터 엔티티 (완료)
2. ✅ **Step2** — 매핑/로그 엔티티 (`CollectionMovie` 제외 6개 먼저 가능, `CollectionMovie`는 Step3 이후)
3. ✅ **Step3** — 사용자 활동 엔티티 (`user`는 Step1에서 완료, `follow`/`collection`/`comment`/`review`/`watch_record`)
4. ✅ **Step4** — 인증/알림 엔티티 (v9 신규: `refresh_token`, `notification` + `user.role` 변경) (완료)
   — 스키마 v9는 **DB 적용 완료**. `ddl-auto=validate` 기동으로 세 엔티티 모두 검증됨
5. ✅ **v10 반영** — `RevokedReason` + `RefreshToken` 수정 / `PasswordResetToken` 신규 / `BoxOfficeRecord.openDate` (완료)
   — 스키마 v10 **DB 적용 완료**, 엔티티 3건 `validate` 기동 통과.
   `PasswordResetToken`의 Repository/서비스는 S-J, `openDate` 수집·재매칭 반영은 배치 작업 시점에 붙인다

---

## 공통 Base Entity

### BaseCreatedAtEntity
- 대상: `created_at`만 있는 불변(immutable) 로그성 엔티티
- 적용 테이블: `follow`, `movie_actor`, `movie_country`, `movie_director`, `movie_genre`, `box_office_record`,
  **`refresh_token`**, **`notification`** (v9 신규)
- 필드: `createdAt` (`@CreatedDate`, not null, not updatable)

### BaseTimeEntity (BaseCreatedAtEntity 상속)
- 대상: `created_at` + `updated_at`이 모두 있는 변경 가능(mutable) 엔티티
- 적용 테이블: `user`, `movie`, `genre`, `country`, `person`, `theater`, `ott_platform`, `collection`, `collection_movie`, `comment`, `review`, `wish_movie`, `watch_record`
- 추가 필드: `updatedAt` (`@LastModifiedDate`, not null)

패키지: `com.cinemory.domain.common.entity`

---

## Step1 — 독립 참조/마스터 엔티티 (✅ 완료)

| 테이블 | 엔티티 | Base | 패키지 | 생성 방식 | 비고 |
|---|---|---|---|---|---|
| `genre` | `Genre` | BaseTimeEntity | `domain.genre.entity` | `of()` | `uk_genre_tmdb_id`, `rename()` 메서드 |
| `country` | `Country` | BaseTimeEntity | `domain.country.entity` | `of()` | `uk_country_code` |
| `ott_platform` | `OttPlatform` | BaseTimeEntity | `domain.ott.entity` | `of()` | `active` 필드, `activate()`/`deactivate()` |
| `person` | `Person` | BaseTimeEntity | `domain.person.entity` | `of()` | `uk_person_tmdb_id`, `updateProfile()` |
| `theater` | `Theater` | BaseTimeEntity | `domain.theater.entity` | `@Builder` | 위경도 `BigDecimal(10,7)`, `uk_theater_source_code` |
| `user` | `User` | BaseTimeEntity | `domain.user.entity` | `createLocal()` / `createOAuth()` | 인증 방식 불변식 강제, `PrivacySetting` enum |
| `movie` | `Movie` | BaseTimeEntity | `domain.movie.entity` | `@Builder` | `uk_movie_tmdb_id`, `uk_movie_kofic_cd`, `linkKoficCode()` |

구현 코드는 이미 작성 완료 상태 (별도 세션에서 Claude Code로 반영).

---

## Step2 — 매핑/로그 엔티티

### 공통 원칙
- 연관관계: 전부 단방향 `@ManyToOne(fetch = FetchType.LAZY)`, 참조 대상 엔티티에 컬렉션 필드 추가 금지
- Cascade 옵션 지정 안 함 (DB FK 정책이 전담)
- `created_at`만 있으면 `BaseCreatedAtEntity`, `updated_at`도 있으면 `BaseTimeEntity`
- 필드 3개 이하 → `of()` 정적 팩토리 / 4개 이상 → `@Builder`

### 1) MovieGenre
- 테이블: `movie_genre` / Base: `BaseCreatedAtEntity`
- 패키지: `domain.movie.entity`
- 필드
  - `id` (PK, IDENTITY)
  - `movie` — `@ManyToOne`, FK `movie_id`, not null
  - `genre` — `@ManyToOne`, FK `genre_id`, not null
  - `weight` — `BigDecimal(4,3)`, not null, default `0.000`
- Unique: `uk_movie_genre (movie_id, genre_id)`
- 팩토리: `MovieGenre.of(Movie movie, Genre genre, BigDecimal weight)`
- 비고: 장르 가중치(1/N 균등 분배)의 **계산 결과만 저장**. 계산 로직은 도메인 서비스(`MovieMetadataService` 등)에 위치.

### 2) MovieCountry
- 테이블: `movie_country` / Base: `BaseCreatedAtEntity`
- 패키지: `domain.movie.entity`
- 필드
  - `id` (PK)
  - `movie` — `@ManyToOne`, FK `movie_id`, not null
  - `country` — `@ManyToOne`, FK `country_id`, not null
  - `weight` — `BigDecimal(4,3)`, not null, default `0.000`
- Unique: `uk_movie_country (movie_id, country_id)`
- 팩토리: `MovieCountry.of(Movie movie, Country country, BigDecimal weight)`
- 비고: 국가 가중치 공식(대표국 `(N+1)/(N²+1)`, 나머지 `N/(N²+1)`)도 서비스 계층 책임.

### 3) MovieActor
- 테이블: `movie_actor` / Base: `BaseCreatedAtEntity`
- 패키지: `domain.movie.entity`
- 필드
  - `id` (PK)
  - `movie` — `@ManyToOne`, FK `movie_id`, not null
  - `person` — `@ManyToOne`, FK `person_id`, not null
  - `characterName` — `String`, nullable, length 100
  - `roleTier` — `RoleTier` enum, not null, `EnumType.STRING`
- Unique: `uk_movie_actor (movie_id, person_id)`
- 팩토리: `MovieActor.of(Movie movie, Person person, String characterName, RoleTier roleTier)`
- **RoleTier enum** (`domain.movie.entity.RoleTier`) — 애플리케이션 레벨 상수:
  ```java
  public enum RoleTier {
      LEAD(0.5), SUPPORTING(0.4), MINOR(0.1);

      private final double weight;
      RoleTier(double weight) { this.weight = weight; }
      public double getWeight() { return weight; }
  }
  ```
  `getWeight()`는 추천 알고리즘(임베딩 벡터 구성) 쪽에서 호출.

### 4) MovieDirector
- 테이블: `movie_director` / Base: `BaseCreatedAtEntity`
- 패키지: `domain.movie.entity`
- 필드
  - `id` (PK)
  - `movie` — `@ManyToOne`, FK `movie_id`, not null
  - `person` — `@ManyToOne`, FK `person_id`, not null
- Unique: `uk_movie_director (movie_id, person_id)`
- 팩토리: `MovieDirector.of(Movie movie, Person person)`
- 비고: 역할 티어 구분 없음 (배우와 달리 감독은 단일 역할)

### 5) WishMovie
- 테이블: `wish_movie` / Base: `BaseTimeEntity`
- 패키지: `domain.wish.entity` (또는 `domain.movie.entity` — 선택)
- 필드
  - `id` (PK)
  - `user` — `@ManyToOne`, FK `user_id`, not null
  - `movie` — `@ManyToOne`, FK `movie_id`, not null
- Unique: `uk_wish_movie (user_id, movie_id)`
- 팩토리: `WishMovie.of(User user, Movie movie)`

### 6) BoxOfficeRecord
- 테이블: `box_office_record` / Base: `BaseCreatedAtEntity`
- 패키지: `domain.boxoffice.entity`
- 필드
  - `id` (PK)
  - `targetDate` — `LocalDate`, not null
  - `rankType` — `RankType{DAILY,WEEKLY,WEEKEND}` enum, not null
  - `boxOfficeRank` — `Integer`, not null
  - `rankChange` — `Integer`, nullable
  - `isNew` — `boolean`, default false
  - `koficMovieCd` — `String`, not null, length 20
  - `movieTitleSnapshot` — `String`, not null — **당시 KOFIC 제목 스냅샷, 수정 메서드 만들지 말 것** (역사적 정확성 보존)
  - `movie` — `@ManyToOne`, FK `movie_id`, **nullable** (TMDB 미매칭 상태로 먼저 적재 가능, `ON DELETE SET NULL`)
  - `salesAmount` — `Long`, default 0
  - `salesShare` — `BigDecimal(5,2)`, nullable
  - `audienceCount` — `Integer`, default 0
  - `audienceAcc` — `Long`, default 0
  - `screenCount` / `showCount` — `Integer`, nullable
- Unique: `uk_box_office_record (target_date, rank_type, kofic_movie_cd)`
- 팩토리: `@Builder` (필드 다수)
- 비고: `movie` FK가 nullable인 유일한 매핑 케이스 — KOFIC 코드 선(先) 적재, TMDB 매칭 후(後) 연결하는 흐름 지원.

### 7) CollectionMovie (보류 — Step3 이후)
- 테이블: `collection_movie` / Base: `BaseTimeEntity` (다른 매핑 엔티티와 달리 `updated_at` 있음)
- 패키지: `domain.collection.entity`
- 필드
  - `id` (PK)
  - `collection` — `@ManyToOne`, FK `collection_id`, not null
  - `movie` — `@ManyToOne`, FK `movie_id`, not null
- Unique: `uk_collection_movie (collection_id, movie_id)`
- 팩토리: `CollectionMovie.of(Collection collection, Movie movie)`
- **선행 조건**: `Collection` 엔티티(Step3)가 먼저 구현되어야 함

---

## Step3 — 사용자 활동 엔티티 (✅ 설계 확정)

### 공통 원칙
- 연관관계: 전부 단방향 `@ManyToOne(fetch = FetchType.LAZY)`, 참조 대상 엔티티에 컬렉션 필드 추가 금지
- Cascade 옵션 지정 안 함 (DB FK 정책이 전담)
- 크로스 엔티티 조율이 필요한 비즈니스 규칙(대표 시청기록 단일성 등)은 엔티티가 아니라 **서비스 레이어 책임**으로 명확히 분리

### 1) Follow
- 테이블: `follow` / Base: `BaseCreatedAtEntity`
- 패키지: `domain.follow.entity`
- 필드
  - `id` (PK)
  - `follower` — `@ManyToOne`, FK `follower_id`, not null
  - `following` — `@ManyToOne`, FK `following_id`, not null
- Unique: `uk_follow (follower_id, following_id)`
- 팩토리: `Follow.of(User follower, User following)`
  - DB 체크 제약 `chk_follow_not_self`(자기 자신 팔로우 금지)를 정적 팩토리 내부에서 `IllegalArgumentException`으로 선반영 (DB 제약과 이중 방어)

```java
public static Follow of(User follower, User following) {
    if (follower.getId() != null && follower.getId().equals(following.getId())) {
        throw new IllegalArgumentException("자기 자신을 팔로우할 수 없습니다.");
    }
    return new Follow(follower, following);
}
```

### 2) Collection
- 테이블: `collection` / Base: `BaseTimeEntity`
- 패키지: `domain.collection.entity`
- 필드
  - `id` (PK)
  - `user` — `@ManyToOne`, FK `user_id`, not null
  - `name` — `String`, not null, length 100
  - `description` — `String`, nullable, length 500
- 팩토리: `Collection.of(User user, String name, String description)`
- 비즈니스 메서드: `update(String name, String description)`
- 비고: `CollectionMovie`(Step2 보류분)는 이 엔티티 구현 이후 바로 이어서 작업 가능

### 3) Comment (다형성 A안 확정 적용)
- 테이블: `comment` / Base: `BaseTimeEntity`
- 패키지: `domain.comment.entity`
- 필드
  - `id` (PK)
  - `user` — `@ManyToOne`, FK `user_id`, not null
  - `targetType` — `TargetType{COLLECTION, REVIEW}` enum, not null, `EnumType.STRING`
  - `targetId` — `Long`, not null — **연관관계 매핑하지 않음** (DB에도 FK 없음, 다형 참조이기 때문)
  - `content` — `String`, not null, length 500
- 팩토리: `Comment.of(User user, TargetType targetType, Long targetId, String content)`
- 비즈니스 메서드: `editContent(String content)`
- **서비스 레이어 책임**: `Comment` 생성 전 `targetId` 실존 여부 검증은 엔티티가 알 수 없는 영역 → `CommentService.create()`에서 `targetType`에 따라 `CollectionRepository.existsById()` / `ReviewRepository.existsById()`로 선검증 후 `Comment.of()` 호출

### 4) Review
- 테이블: `review` / Base: `BaseTimeEntity`
- 패키지: `domain.review.entity`
- 필드
  - `id` (PK)
  - `user` — `@ManyToOne`, FK `user_id`, not null
  - `movie` — `@ManyToOne`, FK `movie_id`, not null
  - `rating` — `Double`, not null (0.0 ~ 10.0 검증, TMDB 스케일 가정 — 실제 요구사항에 맞게 조정 가능)
  - `content` — `String`, not null, length 2000
- Unique: `uk_review (user_id, movie_id)` — 유저당 영화 1개 대표 공개 리뷰
- 팩토리: `Review.of(User user, Movie movie, Double rating, String content)` — 생성자 내부에서 `validateRating()` 호출
- 비즈니스 메서드: `update(Double rating, String content)` — 동일하게 검증 재수행

### 5) WatchRecord
- 테이블: `watch_record` / Base: `BaseTimeEntity`
- 패키지: `domain.watchrecord.entity`
- 필드
  - `id` (PK)
  - `user` — `@ManyToOne`, FK `user_id`, not null
  - `movie` — `@ManyToOne`, FK `movie_id`, not null
  - `watchDate` — `LocalDate`, nullable
  - `representative` — `boolean` (`is_representative` 컬럼), not null, default false — **생성자에서 항상 false로 초기화, 대표 지정은 반드시 서비스 조율을 거쳐 `markAsRepresentative()`로만 수행**
  - `watchType` — `WatchType{THEATER, OTT, ETC}` enum, nullable, `EnumType.STRING`
  - `placeDetail` — `String`, nullable, length 100 (`place_detail` 컬럼)
  - `ottPlatform` — `@ManyToOne`, FK `ott_platform_id`, nullable
  - `rating` — `Double`, nullable
  - `note` — `String`, nullable, length 1000 (`review` 컬럼에 매핑 — 공개 대표 리뷰인 `Review` 엔티티와 혼동 방지 위해 필드명은 `note`로 명명, `@Column(name = "review")`)
- 팩토리: `@Builder` (필드 다수)
- 비즈니스 메서드: `markAsRepresentative()` / `unmarkAsRepresentative()` — 단순 상태 전환만 수행

**핵심 설계 이슈 — 대표 기록(`is_representative`) 단일성**
같은 (user, movie) 조합에서 `is_representative = true`는 최대 1건이어야 하지만, 다건 로그가 정상 데이터이므로 DB 유니크 제약으로 강제할 수 없음 → **서비스 레이어 트랜잭션 로직**으로 강제.

- 필요한 Repository 메서드: `findByUserIdAndMovieIdAndRepresentativeTrue(Long userId, Long movieId)`
- `WatchRecordService.addWatchRecord()` 흐름:
  1. 기존 대표 기록 조회 → 존재하면 `unmarkAsRepresentative()`
  2. 신규 `WatchRecord` 생성 (빌더)
  3. 신규 기록에 `markAsRepresentative()` 호출 ("가장 최근 기록이 대표" 정책 반영)
  4. 저장
- **동시성 트레이드오프**: 동일 유저가 같은 영화를 거의 동시에 두 번 등록하는 경쟁 조건은 이론상 가능하나, 캡스톤 스코프에서 실사용 빈도가 극히 낮아 낙관적으로 수용. 필요시 `SELECT ... FOR UPDATE` 비관적 락 도입을 향후 개선 과제로 남김.

---

## Step4 — 인증/알림 엔티티 (스키마 v9, ✅ 구현 완료)

### 공통 원칙
- Step2/3와 동일 (단방향 `@ManyToOne(LAZY)`, cascade 미지정, Setter 금지, id 기반 equals/hashCode)
- 두 엔티티 모두 `created_at`만 있으므로 **`BaseCreatedAtEntity`** 상속
  - `revoked_at`/`is_read` 갱신은 있지만 "행의 마지막 수정 시각"이 도메인적으로 의미가 없어
    `updated_at`을 두지 않았다. Base 선택은 컬럼 구성을 따른다.

### 0) User 변경분 (Step1 엔티티 수정)
- 추가 필드
  - `role` — `RoleType{USER, ADMIN}` enum, not null, `EnumType.STRING`, 기본값 `USER`
- `RoleType`은 `domain.user.entity` 패키지에 둔다 (`PrivacySetting`과 동일 위치)
- **팩토리는 권한을 인자로 받지 않는다.** `createLocal()`/`createOAuth()` 내부에서 `USER`로 고정한다.
  가입 경로로 권한을 지정할 수 있게 열어두면 권한 상승 경로가 생긴다.
- **권한 변경 비즈니스 메서드를 만들지 않는다.** 관리자 지정은 DB `UPDATE`로만 처리한다
  (`docs/security-spec.md` S-4 참고). 승격 API가 없으므로 엔티티에도 메서드가 불필요하다.

### 1) RefreshToken
- 테이블: `refresh_token` / Base: `BaseCreatedAtEntity`
- 패키지: `domain.auth.entity`
- 필드
  - `id` (PK)
  - `user` — `@ManyToOne`, FK `user_id`, not null
  - `tokenHash` — `String`, not null, length 64 (SHA-256 hex 고정 길이)
  - `expiresAt` — `LocalDateTime`, not null
  - `revokedAt` — `LocalDateTime`, nullable
  - **`revokedReason`** — `RevokedReason` enum, nullable, `EnumType.STRING` **(v10 신규)**
- Unique: `uk_refresh_token_hash (token_hash)` — user_id에는 걸지 않음(다중 기기 로그인 허용)
- CHECK: `chk_refresh_token_revocation` — `revokedAt`과 `revokedReason`은 **항상 함께** 채워진다 (v10)
- 팩토리: `RefreshToken.issue(User user, String tokenHash, LocalDateTime expiresAt)` (필드 3개)
- 비즈니스 메서드
  - **`revoke(LocalDateTime now, RevokedReason reason)`** — `revokedAt`/`revokedReason` 동시 설정.
    **이미 폐기된 경우 재호출해도 시각과 사유를 갱신하지 않는다**
    (최초 폐기 시점이 재사용 감지·유예 판정의 근거이므로 덮어쓰면 안 됨)
  - `isRevoked()` — `revokedAt != null`
  - `isExpired(LocalDateTime now)` — 만료 판정
  - `isWithinReuseGrace(LocalDateTime now, Duration grace)` — 회전 직후 유예 창 판정 (S-9 A-4).
    **v10부터 `revokedReason == ROTATED`를 추가 조건으로 요구한다**

**`RevokedReason` enum** (`domain.auth.entity`) — 값 4종

| 값 | 설정 지점 |
|---|---|
| `ROTATED` | `AuthService.reissue` 회전 — **유예 창의 대상이 되는 유일한 값** |
| `LOGOUT` | `AuthService.logout` |
| `REUSE_DETECTED` | 재사용 감지 시 전체 폐기 |
| `PASSWORD_CHANGED` | S-H 비밀번호 변경 / S-J 재설정 시 전체 폐기 |

> **이 enum이 없으면 로그아웃이 30초간 무효화된다.** 유예 판정이 `revokedAt`만 보면
> 회전으로 폐기된 것과 로그아웃으로 폐기된 것을 구분하지 못해, 로그아웃 직후 같은 토큰으로
> 재발급하면 세션이 되살아난다. v10을 연 직접적인 이유다.
- **현재 시각은 전부 인자로 주입받는다.** 엔티티가 `LocalDateTime.now()`를 직접 호출하면
  테스트에서 시간을 고정할 수 없다. `revoke()`도 같은 이유로 인자를 받는 형태로 확정했다.
- **원문 토큰은 저장하지 않는다.** 엔티티는 해시만 알며, 원문 ↔ 해시 변환은 서비스 레이어 책임이다
  (`RefreshTokenHasher` 전담 — `security-spec.md` S-9 참고).

### 2) Notification
- 테이블: `notification` / Base: `BaseCreatedAtEntity`
- 패키지: `domain.notification.entity`
- 필드
  - `id` (PK)
  - `user` — `@ManyToOne`, FK `user_id`, not null — **수신자**
  - `actor` — `@ManyToOne`, FK `actor_id`, **nullable** — **행위자**. 탈퇴 시 `SET NULL`
  - `type` — `NotificationType{FOLLOW, COMMENT_ON_COLLECTION, COMMENT_ON_REVIEW}`, not null, `EnumType.STRING`
  - `targetType` — `NotificationTargetType{USER, COLLECTION, REVIEW}`, nullable, `EnumType.STRING`
  - `targetId` — `Long`, nullable — **연관관계 매핑하지 않음** (DB에도 FK 없음, 다형 참조)
  - `isRead` — `boolean` (`is_read` 컬럼), not null, default false
    (필드명은 `WatchRecord.isRepresentative`와 동일하게 `is` 접두사를 유지한다)
- 팩토리: `@Builder` (필드 4개 이상)
- 비즈니스 메서드: `markAsRead()` — 단방향 전환만 제공(읽음 해제는 요구사항에 없음)
- **알림 문구 필드를 두지 않는다.** `actor` + `type`으로 조회 시점에 조합한다.
  `box_office_record.movie_title_snapshot`과 다른 선택인 이유: 박스오피스는 "그 시점의 사실"을
  보존해야 하지만, 알림은 닉네임이 바뀌면 최신 닉네임으로 보이는 편이 자연스럽다.
- **`Comment.TargetType`을 재사용하지 않는다.** 알림은 팔로우(대상 `USER`)를 포함해 값 집합이 다르다.
  같은 이름이라고 공유하면 한쪽 enum에 값을 추가할 때 다른 쪽 DB ENUM과 어긋난다.

**⚠️ 서비스 레이어 책임 — 고아 알림**
`targetId`에 FK가 없어 `Collection`/`Review` 삭제 시 알림이 남는다. 재사용된 AUTO_INCREMENT id에
과거 알림이 붙는 오염이 생기므로, `CollectionService.deleteCollection()` /
`ReviewService.deleteReview()`에서 **댓글을 정리하는 바로 그 자리에** 알림 정리도 함께 호출해야 한다.
4-6 고아 댓글과 완전히 같은 구조의 문제다.

**알림 생성 지점** — 엔티티가 아닌 서비스 조율 대상
`FollowService.follow()`와 `CommentService.createComment()` 내부에서 생성한다. 즉 Step4 엔티티를
만든 뒤 **기존 도메인 서비스에 손이 닿는다.** 알림 도메인 설계는 Step S(Security) 구현 이후
별도 절로 진행한다.

### 3) PasswordResetToken (v10 신규)

- 테이블: `password_reset_token` / Base: `BaseCreatedAtEntity`
- 패키지: `domain.auth.entity`
- 필드
  - `id` (PK)
  - `user` — `@ManyToOne(LAZY)`, FK `user_id`, not null (FK 정책 `CASCADE`)
  - `tokenHash` — `String`, not null, length 64 (SHA-256 hex)
  - `expiresAt` — `LocalDateTime`, not null
  - `usedAt` — `LocalDateTime`, nullable — **NULL이면 미사용**
- Unique: `uk_password_reset_token_hash (token_hash)`
- 팩토리: `PasswordResetToken.issue(User user, String tokenHash, LocalDateTime expiresAt)` (필드 3개)
- 비즈니스 메서드
  - `markAsUsed(LocalDateTime now)` — 이미 사용된 경우 시각을 갱신하지 않는다(`revoke`와 동일한 멱등 규칙)
  - `isUsed()` — `usedAt != null`
  - `isExpired(LocalDateTime now)` — 만료 판정
  - `isUsable(LocalDateTime now)` — `!isUsed() && !isExpired(now)`

**`RefreshToken`과 같은 골격을 의도적으로 유지한다.** 해시만 저장하고, 상태를 boolean이 아닌
시각(`usedAt`/`revokedAt`)으로 남기며, 현재 시각은 인자로 주입받는다. 한 번 이해한 패턴을
다시 쓰게 하려는 의도다.

- **폐기 사유 컬럼은 두지 않는다.** 재설정 토큰은 소멸 경로가 "사용됨" 하나뿐이라 구분할 게 없다
- **TTL(기본 30분)은 엔티티가 아니라 설정값**이다. `expiresAt`을 만들어 넘기는 것은 서비스 책임
- 해시 변환은 `RefreshTokenHasher`를 **`TokenHasher`로 일반화**해 공유한다 (구현 시 리네임 필요)

> 이 엔티티는 **S-J(비밀번호 재설정)에서 실제로 쓰인다.** v10에 테이블을 넣은 이유는 SMTP 도입을
> 확정했기 때문이며, 스키마를 한 번 더 여는 비용을 피하려는 v9 `notification`과 같은 판단이다.

### 4) BoxOfficeRecord 변경분 (v10, Step2 엔티티 수정)

- 추가 필드: `openDate` — `LocalDate`, nullable, 컬럼 `open_date`
- KOFIC `openDt`를 담는다. 4-7에서 축소했던 재매칭 2순위 전략("한글 제목 + 개봉연도")을 복원하기 위함
- **기존 행은 NULL로 남는다.** 옛 데이터는 개봉연도 축소를 쓸 수 없고, 앞으로 수집되는 분부터 정확도가 올라간다
- 스냅샷 필드(`movieTitleSnapshot`)와 달리 수집 시점 값을 그대로 보존하는 성격이므로 수정 메서드를 만들지 않는다

---

## 변경 이력

| 날짜 | 내용                                                                                                                                                                                                                                                                                                                                                                                                                                 |
|---|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 2026-07-23 | Step1 완료, Step2 스펙 확정 (comment 다형성 A안 채택)                                                                                                                                                                                                                                                                                                                                                                                          |
| 2026-07-23 | Step3 설계 확정 (Follow/Collection/Comment/Review/WatchRecord)                                                                                                                                                                                                                                                                                                                                                                         |
| 2026-07-30 | **스키마 v10 반영 (21 → 22 테이블).** ① `RefreshToken`에 `revokedReason`(`RevokedReason` enum 4종) 추가 — **유예 창 판정에 `ROTATED` 조건이 붙는다.** 없으면 로그아웃 직후 30초간 같은 토큰으로 세션을 되살릴 수 있다 ② `PasswordResetToken` 신규 — `RefreshToken`과 같은 골격(해시 저장 / 시각형 상태 / 시각 주입) 유지, SMTP 도입 확정에 따라 v10에 포함 ③ `BoxOfficeRecord.openDate` — 4-7 재매칭 2순위 전략 복원용. **기준 스키마를 v9 → v10으로 갱신** |
| 2026-07-30 | **테이블 수 표기 정정.** 문서 전반이 v8=18 / v9=20으로 적고 있었으나 실제 덤프는 **v9=21**이다(v6 16개 + `ott_platform`·`theater`·`box_office_record` = v8 19개, v9에서 2개 추가). `CineMory_기획노트.md`만 v8을 19개로 올바르게 적고 있었고, 오차는 2026-07-22 v7/v8 문서화 시점에 생겨 이후로 전파됐다. v9 델타 파일이 **커밋되지 않은 채 삭제**돼 참조가 끊긴 것도 함께 정리 |
| 2026-07-30 | **Step4 구현 완료** 및 구현 중 조정 4건. ① `RefreshToken` 팩토리명 `of()` → **`issue()`** (토큰 "발급"이라는 도메인 동작을 드러내기 위함). ② `revoke()` → **`revoke(LocalDateTime now)`** — `isExpired(now)`와 같은 이유(테스트 시간 고정)인데 한쪽만 인자를 받는 것이 일관되지 않았음. ③ `Notification`의 boolean 필드명 `read` → **`isRead`** (`WatchRecord.isRepresentative` 컨벤션과 통일). ④ S-9 A-4 결정에 따라 **`isWithinReuseGrace(now, grace)` 추가**. 엔티티 3건의 컬럼 구성을 v9 덤프와 대조해 차이 0 확인, `validate` 기동 통과 |
| 2026-08-02 | **v10 반영 구현 완료**(순서 5). `RevokedReason` 신규, `RefreshToken.revokedReason` 추가 + `revoke(now, reason)` / `isWithinReuseGrace`에 `ROTATED` 조건, `PasswordResetToken` 신규, `BoxOfficeRecord.openDate` 추가. 구현 중 조정 2건 — ① **`RefreshTokenRepository.revokeAllByUserId`에 `reason` 인자 추가**: 벌크 UPDATE는 엔티티 `revoke()`를 거치지 않아 `revokedAt`만 채우면 `chk_refresh_token_revocation`에 걸려 **재사용 감지 경로가 런타임에 실패**한다(호출부는 `REUSE_DETECTED`). ② `RefreshTokenHasher` → **`TokenHasher`** 리네임(스펙 예고분, 두 토큰이 공유). `openDate`는 컬럼·필드만 추가된 상태이고 **수집 시 채우지 않는다** — `BoxOfficeSyncService`에 TODO로 명시. `validate` 기동 통과 |
| 2026-07-29 | 기준 스키마를 v8(19 테이블) → **v9(21 테이블)** 로 갱신. Step4(인증/알림 엔티티) 스펙 신규 — `User.role` 추가(팩토리·비즈니스 메서드로는 변경 불가, DB UPDATE 전용), `RefreshToken`(해시만 보관, `revoke()` 멱등, `isExpired(now)` 주입식), `Notification`(문구 스냅샷 미보유, `NotificationTargetType`을 `Comment.TargetType`과 분리). 고아 알림이 4-6 고아 댓글과 동일 구조임을 명시                                                                                                                                   |
