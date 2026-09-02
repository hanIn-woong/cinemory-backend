# CineMory JPA Entity 설계 스펙

이 문서는 `docs/schema/cinemory_backup_v15.sql`(**ERD v15, 22 테이블**)을 기준으로
JPA 엔티티를 어떻게 구현할지 정리한 스펙이다. 공통 규칙은 `CLAUDE.md`를 따르고,
이 문서는 **엔티티별 구체 스펙**만 담는다.

> **v14 → v15 (적용 완료)** — `review.rating` 컬럼 제거. 별점은 `watch_record.rating`
> 단일 출처이며, 리뷰에 표시되는 별점은 대표 시청 기록 기준의 파생값으로 바뀐다.
> 테이블 수는 22로 변동 없다. 근거·폴백 규칙은 아래 "4) Review" 참고.

> v9 → v10 변경분은 `docs/schema/v10-delta.sql` 참고
> (`refresh_token.revoked_reason` 추가, `password_reset_token` 신설, `box_office_record.open_date` 추가).
>
> **v10 → v11 (`docs/schema/v11-delta.sql`, 적용 완료)** — `movie_actor.display_order` 추가 +
> `role_tier`에 `EXTRA` 확장(**D-1**) + `movie.overview` `varchar(1000)` → `varchar(4000)`(**D-4**).
> 테이블 수는 22로 변동 없다.
>
> **v12 → v13 (`docs/schema/v13-delta.sql`, 적용 완료)** — `movie`에 컬럼 4개 추가:
> `original_title`(원어 제목 — **원어 검색이 안 되는 문제를 실측**해서 나온 것),
> `backdrop_path`(상세 화면 16:9 배경), `vote_average`·`vote_count`(TMDB 평점).
> 전부 TMDB 응답에 **이미 들어오는데 저장하지 않던 값**이다. 근거는 `tmdb-sync-spec.md` 6-9.
> ⚠️ **적용만으로 끝나지 않는다** — 기존 적재분이 전부 NULL이라 `POST /api/admin/movies/resync`가
> 필요하다(잔여 #23).
>
> **v11 → v12 (`docs/schema/v12-delta.sql`, 적용 완료)** — v11의 판단 2건을 되돌린다.
> ① `display_order` `smallint` → **`int`**(v11 정오 — `Integer` 필드와 JDBC 타입 코드가 어긋나
> `validate`가 실패한다), ② `movie.overview` `varchar(4000)` → **`varchar(1000)`**(D-4 롤백 —
> TMDB가 overview를 1000자로 제한하므로 원래 값이 옳았다).
> **엔티티 반영 전에 DB 적용이 선행**되어야 한다(`ddl-auto=validate`).
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
6. ✅ **v11 반영** — `MovieActor.displayOrder` 추가 / `RoleTier.EXTRA` 추가 / `of()` → `@Builder`
7. ✅ **v12 반영** — **`Movie.overview` `length = 4000` → `1000` 롤백** (코드는 이미 되돌려 뒀다).
   `display_order`의 `smallint` → `int` 교정도 같은 델타에 있으나 엔티티 변경은 없다
   — ⚠️ **두 항목의 위험 성격이 다르다.** Hibernate의 스키마 검증은 컬럼 **타입은 보지만
   길이는 보지 않는다.** `display_order`는 타입 불일치(INTEGER vs SMALLINT)라 **`validate`가
   실제로 실패**하므로 델타 적용이 기동의 전제다. 반면 `overview`는 길이라서 엔티티(1000)와
   DB(4000)가 어긋나도 **`validate`는 조용히 통과**하고, 반대로 어긋난 상태(엔티티 4000 + DB 1000)에서는
   기동이 아니라 **적재 시점에 터진다.** 조용히 어긋나는 쪽이라 함께 맞춰 두는 것이 중요하다
   `MovieActorRepository.findByMovieIdOrderByRoleTierAsc` → `...OrderByDisplayOrderAsc` 동반 수정

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
| `country` | `Country` | BaseTimeEntity | `domain.country.entity` | `of()` | `uk_country_code`, **`rename()` 메서드 (6-1 구현 시 추가)** |
| `ott_platform` | `OttPlatform` | BaseTimeEntity | `domain.ott.entity` | `of()` | `active` 필드, `activate()`/`deactivate()` |
| `person` | `Person` | BaseTimeEntity | `domain.person.entity` | `of()` | `uk_person_tmdb_id`, `updateProfile()` — **값 비교 후에만 대입할 것** (아래 참고) |
| `theater` | `Theater` | BaseTimeEntity | `domain.theater.entity` | `@Builder` | 위경도 `BigDecimal(10,7)`, `uk_theater_source_code` |
| `user` | `User` | BaseTimeEntity | `domain.user.entity` | `createLocal()` / `createOAuth()` | 인증 방식 불변식 강제, `PrivacySetting` enum |
| `movie` | `Movie` | BaseTimeEntity | `domain.movie.entity` | `@Builder` | `uk_movie_tmdb_id`, `uk_movie_kofic_cd`, `linkKoficCode()`. **`overview` length 1000** (v11에서 4000으로 확장했다가 v12에서 롤백). **v13: `originalTitle`·`backdropPath`·`voteAverage`·`voteCount` 추가** |

구현 코드는 이미 작성 완료 상태 (별도 세션에서 Claude Code로 반영).

> **`Movie` v13 — 컬럼 4개 추가 (tmdb-sync 6-9)** — ✅ **구현 완료 (2026-08-24)**
>
> ```java
> @Column(name = "original_title") private String originalTitle;
> @Column(name = "backdrop_path")  private String backdropPath;
> @Column(name = "vote_average", precision = 3, scale = 1) private BigDecimal voteAverage;
> @Column(name = "vote_count") private Integer voteCount;
> ```
>
> `original_title`/`backdrop_path`는 `title`/`posterPath`와 마찬가지로 `length` 명시 없이
> Hibernate 기본값(255)에 맡긴다 — `varchar(255)`와 일치하고 기존 필드들과 스타일이 같다.
>
> **전부 nullable이다.** TMDB가 대개 주지만 보장이 없고, `backdrop_path`는 실제로 `null`이
> 흔하다(인지도 낮은 작품). not null로 묶으면 적재가 죽는다.
>
> `voteAverage`가 `BigDecimal(3,1)`인 이유 — TMDB가 소수 첫째 자리까지 주고 최대 10.0이다.
> `Double`이면 `8.433`이 그대로 들어와 표시할 때마다 반올림이 필요해진다.
> `movie_genre.weight`가 `BigDecimal`인 것과 같은 원칙이다.
>
> ⚠️ **`updateMetadata`가 9파라미터가 됐다.** 기존 5개(`title, posterPath, overview, runtime,
> releaseDate`) 뒤에 v13 4개(`originalTitle, backdropPath, voteAverage, voteCount`)를
> **덧붙였다** — 필드 선언 순서(`title` 다음 `originalTitle`)가 아니라 releaseDate가
> 추가됐을 때(잔여 #16)와 같은 방식으로, 시그니처 뒤에 이어 붙여 기존 호출부 이해를
> 방해하지 않는 쪽을 택했다. 그 결과 `{title, posterPath, overview}`와
> `{originalTitle, backdropPath}` 두 개의 연속 `String` 구간이 생긴다.
> 6-4에서 *"호출부가 `MovieSyncPersister` 한 곳뿐이라 `@Builder` 순서 유지로 충분"* 이라
> 판단했는데 **그 전제가 약해진다.** 값 객체 도입을 재검토할 것(tmdb-sync 잔여 #25 — 여전히 미해결).

> **`Person.updateProfile()` — 값 비교 추가 (tmdb-sync 6-4, 2026-08-19)**
>
> `Genre.rename()`·`Country.rename()`은 값을 비교한 뒤 대입하는데 이것만 무조건 대입한다.
> 인기 배우 한 명이 시드 500편 중 30편에 나오면 dirty checking으로 **30번 전부 UPDATE**가 나간다.
>
> ```java
> public void updateProfile(String name, String profilePath) {
>     if (!Objects.equals(this.name, name)) this.name = name;
>     if (!Objects.equals(this.profilePath, profilePath)) this.profilePath = profilePath;
> }
> ```
>
> ⚠️ `Genre`/`Country`와 달리 **`profilePath`가 nullable**이라 `this.name.equals(...)` 형태를
> 그대로 따라 쓰면 NPE다. `Objects.equals`를 쓴다.
>
> `name`은 not null이므로 호출 전에 `original_name` 폴백이 적용된 값이 넘어와야 한다(6-3 ⑦).

> **`Movie.updateMetadata()` — `releaseDate` 파라미터 추가 (tmdb-sync 6-3 ③, 2026-08-19)**
>
> ```java
> public void updateMetadata(String title, String posterPath, String overview,
>                            Integer runtime, LocalDate releaseDate)
> ```
>
> 시드가 미개봉작을 담는 이상 개봉일 확정·정정이 반드시 발생한다. 갱신 수단이 없으면
> 최초 적재 시점의 값(또는 `null`)이 영구히 남는다.
>
> ⚠️ **`title`/`posterPath`/`overview`가 같은 타입으로 연속**이라 순서를 바꿔도 컴파일된다.
> 값 객체를 만들지 않는 이유는 레코드도 위치 기반이라 위험이 생성 지점으로 옮겨갈 뿐이기
> 때문이다. 호출부가 `MovieSyncPersister` 한 곳뿐이므로 **`@Builder` 필드 순서와 동일하게**
> 유지해 리뷰에서 잡는다.

> **`Movie.overview` — v11에서 4000으로 확장했다가 v12에서 1000으로 롤백 (2026-08-19)**
>
> ```java
> @Column(name = "overview", length = 1000)   // TMDB 입력 제한과 같은 값
> private String overview;
> ```
>
> **확장 근거가 틀렸다.** v11(D-4)은 *"TMDB overview가 1000자를 넘을 수 있다"* 를 전제로
> 넓혔으나, **TMDB는 overview를 1000자로 제한한다**(스태프 명시). 즉 `varchar(1000)`은
> 임의값이 아니라 TMDB 계약을 반영한 값이었고, 넓히면서 그 의미를 지웠다. 자세한 경위는
> `tmdb-sync-spec.md` D-4 참고.
>
> ⚠️ **`@Lob`을 쓰지 않는다.** `@Lob` + `String`은 MySQL에서 `LONGTEXT`로 매핑돼
> `varchar`와 어긋난다. `length` 속성만 쓴다.
>
> **절단은 남긴다.** 상한(1000)과 TMDB 제한(1000)이 같아 여유가 0이고, TMDB의 1000자는
> *입력 폼* 제한이라 그 이전 등록분이 넘을 여지가 이론상 있다. `MovieSyncService`가
> `997자 + "..."`로 절단하고 `WARN`을 남긴다. 절단은 서비스 책임이며 **엔티티는 검증하지 않는다**
> — 상한 초과는 외부 API 계약 변화지 도메인 불변식이 아니다.

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
  - `characterName` — `String`, nullable, **length 255** (v14, 2026-08-24 — 100에서 확장)
    - 4,609편 실측에서 **`MAX(CHAR_LENGTH)`가 정확히 100, 절단 29건**이었다.
      MAX가 상한과 정확히 같은 것이 절단의 증거다(`truncate()`가 `97자 + "..."`로 100자를 만든다).
      60편 표본에선 최대 30자·절단 0건이었다 — 6-3 ④가 예측하고 6-7이 표본 편향을 경고한 그대로다.
    - ⚠️ **`ddl-auto=validate`는 길이를 검증하지 않는다.** 컬럼만 255로 넓히고
      엔티티 `@Column(length)`와 `MovieSyncPersister.CHARACTER_NAME_MAX_LENGTH`를
      그대로 두면 **기동은 통과하고 절단만 100자에서 계속된다.** 세 곳을 함께 바꿔야 한다.
    - ⚠️ 이미 잘린 29건은 ALTER로 복구되지 않는다 — `POST /api/admin/movies/resync` 필요.
  - `displayOrder` — `Integer`(컬럼 `int`), not null. **TMDB `order` 원본값.**
    표시 순서의 유일한 근거이며 수정 메서드를 두지 않는다(재동기화는 삭제 후 재삽입).
    - ⚠️ **컬럼을 `smallint`로 두면 `ddl-auto=validate`가 실패한다.** Hibernate는 SQL 타입명
      접두사 또는 JDBC 타입 코드가 일치할 때만 통과시키는데, `Integer`는 `integer`
      (`Types.INTEGER`)이고 `smallint`는 `Types.SMALLINT`라 양쪽 다 어긋난다. 맞추려면
      필드를 `Short`로 바꿔야 하고 그러면 tier 파생 산술에 캐스팅이 번진다.
      기존 스키마도 `box_office_rank`·`screen_count`·`movie.runtime` 전부 `int`다.
  - `roleTier` — `RoleTier` enum, not null, `EnumType.STRING`
- Unique: `uk_movie_actor (movie_id, person_id)`
- 생성: 필드 5개이므로 **`@Builder`** (CLAUDE.md의 "4개 이상 → Builder" 규칙).
  기존 `of()` 정적 팩토리는 폐기한다.
- **RoleTier enum** (`domain.movie.entity.RoleTier`) — 애플리케이션 레벨 상수:
  ```java
  public enum RoleTier {
      LEAD(0.5), SUPPORTING(0.4), MINOR(0.1), EXTRA(0.0);

      private final double weight;
      RoleTier(double weight) { this.weight = weight; }
      public double getWeight() { return weight; }
  }
  ```
  `getWeight()`는 추천 알고리즘(임베딩 벡터 구성) 쪽에서 호출.

  **`displayOrder` → `RoleTier` 파생 규칙 (tmdb-sync-spec D-1 확정, 2026-08-13)**

  | `displayOrder` | tier | 최대 인원 | 영화당 최대 기여 |
  |---|---|---|---|
  | 0 ~ 4 | `LEAD` | 5 | 2.5 |
  | 5 ~ 9 | `SUPPORTING` | 5 | 2.0 |
  | 10 ~ 20 | `MINOR` | 11 | 1.1 |
  | 21 ~ | `EXTRA` | 무제한 | **0.0** |

  - cast는 **자르지 않고 전량 저장**한다. 21번 이후는 가중치 0인 `EXTRA`라
    추천 집계에 필터를 걸지 않아도 기여가 0이다. 영화당 배우 선호 총점은
    출연진 수와 무관하게 **5.6 상한**으로 고정된다.
  - **판정 로직은 `RoleTier.fromDisplayOrder(int)` 정적 팩토리로 enum이 갖는다** (2026-08-19 변경).
    초판은 *"엔티티가 아니라 `MovieSyncService`가 갖는다"* 였으나 근거가 약했다 —
    `displayOrder`는 TMDB 개념이 아니라 **이미 `MovieActor`의 필드**, 즉 우리 도메인 개념이다.
    "우리 필드값 → 우리 enum"은 enum 자신의 규칙이고, 경계값과 가중치가 한 파일에 모이며
    M3 추천에서 같은 규칙을 재사용할 때 중복이 생기지 않는다. 서비스 private에 묻으면
    단위 테스트도 어렵다.
  - ⚠️ **`EXTRA`는 enum 선언 맨 끝에 둔다.** MySQL ENUM은 값을 인덱스로 저장하므로
    중간 삽입 시 기존 행의 의미가 재해석된다.

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
  - `content` — `String`, not null, length 2000
- Unique: `uk_review (user_id, movie_id)` — 유저당 영화 1개 대표 공개 리뷰
- 팩토리: `Review.of(User user, Movie movie, String content)`
- 비즈니스 메서드: `update(String content)`
- **`rating` 필드 없음 (v15에서 제거).** 별점은 `WatchRecord.rating` 단일 출처이고, `Review`
  엔티티는 순수 텍스트 리뷰만 갖는다. `ReviewResponse.rating`에 표시되는 값은 엔티티 컬럼이
  아니라 서비스 레벨 조회로 계산하는 **파생값**이며, 규칙은 다음 2단계 폴백이다:
  1. 대표 시청 기록(`watch_record.is_representative = true`)의 `rating`
  2. 그 값이 null이면, `rating IS NOT NULL`인 가장 최근(`id DESC`) 시청 기록의 `rating`
  3. 그것도 없으면 `null` (시청 기록 자체가 없는 리뷰 — 정상 상태)

  저장이 아니라 조회 시점 계산이므로 `Review`에는 이 로직이 들어가지 않는다
  (`ReviewRepository`의 조회 쿼리 소관 — `service-layer-spec.md` 4-4 참고).

### 5) WatchRecord
- 테이블: `watch_record` / Base: `BaseTimeEntity`
- 패키지: `domain.watchrecord.entity`
- 필드
  - `id` (PK)
  - `user` — `@ManyToOne`, FK `user_id`, not null
  - `movie` — `@ManyToOne`, FK `movie_id`, not null
  - `watchDate` — `LocalDate`, nullable
  - `representative` — `boolean` (`is_representative` 컬럼), not null, default false — **생성자에서 항상 false로 초기화, 대표 지정은 반드시 서비스 조율을 거쳐 `markAsRepresentative()`로만 수행**
    - ⚠️ **필드명에 `is` 접두사를 붙이지 않는다** (2026-08-07 확정 — 아래 "boolean 필드 명명 규칙" 참고).
      게터는 Lombok이 `isRepresentative()`로 생성하므로 호출부 가독성은 동일하다.
  - `watchType` — `WatchType{THEATER, OTT, ETC}` enum, nullable, `EnumType.STRING`
  - `placeDetail` — `String`, nullable, length 100 (`place_detail` 컬럼)
  - `ottPlatform` — `@ManyToOne`, FK `ott_platform_id`, nullable
  - `rating` — `Double`, nullable (0.0 ~ 10.0 검증). **별점의 유일한 저장 위치**(v15) — 리뷰
    화면에 표시되는 별점은 이 값을 대표 기록 기준으로 조회한 파생값이다("4) Review" 참고)
  - `note` — `String`, nullable, length 1000 (`review` 컬럼에 매핑 — 공개 대표 리뷰인 `Review` 엔티티와 혼동 방지 위해 필드명은 `note`로 명명, `@Column(name = "review")`)
- 팩토리: `@Builder` (필드 다수) — **`@Builder`가 붙은 생성자 내부에서 `validateRating()` 호출**
  - 별도 메서드로만 두면 빌더 경로가 검증을 타지 않아 아무도 부르지 않는 코드가 된다.
- 비즈니스 메서드: `markAsRepresentative()` / `unmarkAsRepresentative()` — 단순 상태 전환만 수행
- **`validateRating()`은 `rating != null`일 때만 범위를 검사한다** (2026-08-07 추가).
  `rating`이 nullable이므로 무조건 범위만 검사하면 "별점 없이 기록만 남기는" 정상 케이스가
  막힌다. (2026-08-07 도입 당시엔 `Review.rating`이 not null이라 대비되는 사례였으나,
  v15에서 `Review.rating` 자체가 없어져 지금은 `WatchRecord`만의 규칙이다.)

**핵심 설계 이슈 — 대표 기록(`is_representative`) 단일성**
같은 (user, movie) 조합에서 `is_representative = true`는 최대 1건이어야 하지만, 다건 로그가 정상 데이터이므로 DB 유니크 제약으로 강제할 수 없음 → **서비스 레이어 트랜잭션 로직**으로 강제.

- 필요한 Repository 메서드: `findByUserIdAndMovieIdAndRepresentativeTrue(Long userId, Long movieId)`
- `WatchRecordService.addWatchRecord()` 흐름:
  1. 기존 대표 기록 조회 → 존재하면 `unmarkAsRepresentative()`
  2. 신규 `WatchRecord` 생성 (빌더)
  3. 신규 기록에 `markAsRepresentative()` 호출 ("가장 최근 기록이 대표" 정책 반영)
  4. 저장
- **동시성 트레이드오프**: 동일 유저가 같은 영화를 거의 동시에 두 번 등록하는 경쟁 조건은 이론상 가능하나, 캡스톤 스코프에서 실사용 빈도가 극히 낮아 낙관적으로 수용. 필요시 `SELECT ... FOR UPDATE` 비관적 락 도입을 향후 개선 과제로 남김.

**boolean 필드 명명 규칙 (2026-08-07 확정)**

**엔티티의 boolean 필드명에 `is` 접두사를 붙이지 않는다.** 컬럼명(`is_representative`,
`is_read`)은 `@Column`으로 따로 지정한다.

필드를 `isRepresentative`로 두면 **두 네임스페이스가 갈라진다.**

| 네임스페이스 | 이름 | 결정 주체 |
|---|---|---|
| JPA 메타모델 속성 | `isRepresentative` | **FIELD 접근** → 필드명 그대로 |
| JavaBean 프로퍼티 | `representative` | 게터 `isRepresentative()`에서 `is` 제거 |

Spring Data 파생 쿼리는 **JavaBean 프로퍼티**로 경로를 해석하므로 `…AndRepresentativeTrue`를
정상 인식하고 그 이름으로 Criteria 경로를 만든다. 그런데 Hibernate 메타모델에는 그 속성이
없어 실행 시 `UnknownPathException`이 난다. 파싱 단계를 통과하기 때문에 **컴파일·기동에서
드러나지 않고 그 경로를 실제로 호출할 때까지 숨는다.**

영향 범위는 파생 쿼리 하나가 아니다. `Sort.by("representative")`, JPQL
`where w.representative = true`, `Specification`의 `root.get("representative")`,
`@EntityGraph(attributePaths = …)` 가 전부 같은 방식으로 실패한다.
따라서 리포지토리 메서드명을 필드에 맞추는 방향으로 고치면 **해당 호출부만 닫히고
나머지 함정은 그대로 남는다.** 필드명을 바꾸는 쪽이 근본 해결이며, 필드명은 DB에
아무것도 남기지 않으므로 마이그레이션 비용이 0이다.

> 이 규칙은 2026-08-07 `WatchRecord`에서 실제 버그로 드러나 확정됐다.
> 같은 엔티티에서 `note`↔`review` 필드명 드리프트에 이어 **두 번째 사례**다.

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
  - `read` — `boolean` (`is_read` 컬럼), not null, default false
    - **2026-08-07 정정.** 이전 판은 "`WatchRecord.isRepresentative`와 동일하게 `is` 접두사를
      유지한다"고 적고 있었으나, 이는 **드리프트된 구현을 기준으로 규칙을 승격시킨 것**이었다
      (2026-07-30 이력의 `read` → `isRead` 리네임이 그 지점). `WatchRecord`에서 실제 버그로
      드러났으므로(위 "boolean 필드 명명 규칙") 접두사를 제거해 되돌린다.
    - 게터는 Lombok이 `isRead()`로 생성하고 컬럼은 `is_read` 그대로라 **외부에 드러나는 변화가 없다.**
    - ⏰ **알림 도메인 착수 전에 반드시 처리한다.** 현재 엔티티만 구현돼 있고 리포지토리·쿼리가
      없어 비용이 0이지만, `findByUserIdAndReadFalse` 류를 작성한 뒤에는 `WatchRecord`와
      동일한 디버깅을 반복하게 된다. **2026-08-11 기준 코드는 아직 `isRead`다.**
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
| 2026-09-02 | **스키마 v15 반영 — `review.rating` 제거, 별점은 `watch_record.rating` 단일 출처.** `Review` 엔티티에서 `rating` 필드와 `validateRating()`을 제거, 팩토리 `Review.of(user, movie, content)`/`update(content)`로 시그니처 축소. 리뷰에 표시되는 별점은 저장값이 아니라 **조회 시점 파생값**으로 바뀌었다 — 대표 시청 기록(`is_representative=true`)의 rating → null이면 rating IS NOT NULL인 가장 최근(id DESC) 기록의 rating → 그것도 없으면 null. 이 2단계 폴백은 엔티티가 아니라 `ReviewRepository`의 조회 쿼리 책임(`service-layer-spec.md` 4-4 참고). 근거 — `CineMory_기획노트.md` 8절 R-1(선호도 산출 입력)이 "review만? watch_record도?"로 갈리던 것을 이 변경으로 대표 기록 기준으로 닫았다 |
| 2026-08-27 | **`MovieActor.characterName` v14 — 코드 반영 완료 (잔여 #27 종결).** 2026-08-24에 스펙만 갱신해뒀던 것을 코드로 반영했다. `MovieActor.characterName`의 `@Column(length)` 100 → 255, `MovieSyncPersister.CHARACTER_NAME_MAX_LENGTH` 100 → 255 두 곳을 함께 변경(하나만 바꾸면 컬럼만 넓어지고 절단은 그대로 남는다는 게 2026-08-24 스펙이 남긴 경고였다). tmdb-sync-spec.md 로드맵 표도 동기화. 이미 잘린 29건은 이 변경으로 복구되지 않으며 `POST /api/admin/movies/resync` 재적재가 별도로 필요하다 |
| 2026-08-24 | **`MovieActor.characterName` v14 — `length` 100 → 255 (스펙만 갱신, 코드 미반영).** tmdb-sync **6-7-b** 실측 반영. **2026-08-19에 "컬럼 확장 대신 절단+`WARN` 후 실측"으로 확정한 절차가 처음으로 결론을 냈다** — 4건 중 `character_name` 하나만 확장 대상이었다. 60편 표본에선 최대 30자·절단 0건이라 무해해 보였는데 **4,609편에서 `MAX(CHAR_LENGTH)`가 정확히 100, 절단 29건**이 나왔다. **MAX가 상한과 정확히 같은 것 자체가 절단의 증거다** — `truncate()`가 `97자 + "..."`로 항상 딱 100자를 만들기 때문이고, 자연 발생한 배역명이 우연히 100자일 확률은 낮다. tmdb-sync 6-3 ④가 *"넷 중 유일하게 근거가 있다"*(TMDB가 다역을 슬래시로 연결)고 지목한 컬럼이 그대로 걸렸고, 6-7이 단 경고(*"인기작 60편은 데이터가 가장 정돈된 부류"*)도 확인됐다. **`255`인 이유** — `title`/`original_title`/`backdrop_path`와 같은 값이라 스키마의 문자열 기본 폭이고, 원본이 100자를 갓 넘는 수준이라 2.5배면 충분하다. **`ALGORITHM=INSTANT`로 처리된다** — utf8mb4에서 길이 접두사는 최대 바이트가 255 이하일 때 1바이트인데 `varchar(100)`=400B, `varchar(255)`=1020B로 **둘 다 2바이트**라 접두사가 바뀌지 않는다(경계는 `varchar(63)`/`varchar(64)`). 18만 행이어도 테이블 재구축이 없다. ⚠️ **DB는 사용자가 직접 적용했고 코드는 아직 100이다** — `@Column(length)`와 `MovieSyncPersister.CHARACTER_NAME_MAX_LENGTH` 두 곳(tmdb-sync 잔여 #27). **`ddl-auto=validate`가 길이를 검증하지 않으므로 기동은 그대로 통과하고 절단만 조용히 계속된다** — v12에서 `display_order`(타입 불일치, 기동 실패)와 `overview`(길이 불일치, 무증상)를 가른 그 성질이다. ⚠️ **이미 잘린 29건은 ALTER로 복구되지 않는다** — `POST /api/admin/movies/resync`로 재적재해야 하며, v13 이전 60편의 신규 컬럼 NULL 보정과 한 번에 해소된다. **확장하지 않은 3건** — `person.name`(100) 절단 0건, `movie.title`(255) 최대 66자, `movie.overview`(1000) 최대 978자·절단 0건. ⚠️ 다만 **2026-08-20에 `overview`를 "여유 1.5배"로 판정한 것은 60편(684자) 기준의 착시**였다 — 실제 사용률은 97.8%이고, 절단이 없는 이유는 여유가 아니라 **TMDB가 1000자로 제한**(D-4)해서다. 절단 로직을 남긴 판단이 옳았다 |
| 2026-08-24 | **`Movie` v13 컬럼 4개 — 엔티티 반영 완료.** 2026-08-23에 확정만 해뒀던 스펙을 v13 델타 적용 후 코드로 반영했다. **`Movie`** — 필드 4개(`originalTitle`/`backdropPath`/`voteAverage`/`voteCount`) 추가, `@Builder` 생성자에 포함, `updateMetadata`를 9파라미터로 확장(기존 5개 뒤에 신규 4개를 덧붙이는 방식 — 잔여 #16 `releaseDate` 추가 때와 동일 관례). **함께 반영한 파급 항목 2건** — ① `TmdbMovieDetailResponse`에 `backdropPath`/`voteAverage`(`Double`)/`voteCount` 필드와 `normalizedVoteAverage()`(TMDB 응답을 `decimal(3,1)` 스케일로 `HALF_UP` 반올림, `movie_genre` 가중치 계산과 같은 라운딩 모드) 추가 — v13 이전엔 이 응답 DTO가 애초에 해당 필드를 갖고 있지 않아 매핑할 원본이 없었다. ② `MovieSyncPersister.upsertMovie`가 4개 값을 `updateMetadata`/`Movie.builder()` 양쪽에 전달하도록 수정, `original_title`도 `title`과 동일한 `truncate()`(255자)를 적용(같은 `varchar(255)` 컬럼이라 잘라야 안전). **이번 반영에서 제외한 것** — `MovieRepository.findByTitleContainingOrOriginalTitleContaining`(검색 쿼리 확장)과 `MovieDetailResponse`/`MovieSummaryResponse`(응답 DTO에 평점·배경 노출)는 tmdb-sync-spec 6-9 파급 항목 표에 별도 항목으로 분리돼 있고 각각 서비스/컨트롤러 계층 작업이라 이번엔 손대지 않았다 — `MovieDetailResponse`는 우리 평점 집계와 함께 프론트 상세 화면 구현 시로 스펙 자체가 명시적으로 미루고 있다(잔여 #24). **검증** — `./gradlew compileJava` 통과, 전체 테스트 스위트 통과(`ddl-auto=validate` 포함 — v13 적용된 실 DB 대상) |
| 2026-08-13 | **`Country.rename()` 추가 (6-1 구현 중 발견한 스펙 공백).** tmdb-sync 6-1이 *"둘 다 멱등 upsert — 이미 있으면 `Genre.rename()`으로 이름만 갱신"* 이라 적었으나 **`Country`에는 그 메서드가 없어 "둘 다"가 성립하지 않았다.** `Genre.rename()`과 동일한 형태(값 비교 후에만 대입 — 불필요한 dirty checking 방지)로 추가했다. 국가명은 거의 변하지 않지만 TMDB의 한국어 지역화가 시간이 지나며 채워지므로 재적재로 반영할 수단이 필요하다. `code`는 자연키라 변경 수단을 두지 않는다 |
| 2026-08-19 | **tmdb-sync 6-4 확정에 따른 엔티티 3건 소급 반영.** ① **`RoleTier.fromDisplayOrder(int)` 정적 팩토리 신설** — 2026-08-13에 *"판정 로직은 엔티티가 아니라 `MovieSyncService`가 갖는다"* 고 적었으나 **근거가 약해 뒤집는다.** `displayOrder`는 TMDB 개념이 아니라 이미 `MovieActor`의 필드, 즉 우리 도메인 개념이다. "우리 필드값 → 우리 enum"은 enum 자신의 규칙이고, D-1 경계값과 가중치가 한 파일에 모이며 M3 추천에서 재사용할 때 중복이 안 생긴다. 서비스 private에 묻으면 단위 테스트도 어렵다. ② **`Person.updateProfile()`에 값 비교 추가** — `Genre.rename()`·`Country.rename()`과 달리 무조건 대입이라, 인기 배우가 시드 500편 중 30편에 나오면 dirty checking으로 30번 전부 UPDATE가 나간다. **`profilePath`가 nullable이라 `Objects.equals`** 를 써야 한다(`Genre`/`Country` 형태를 그대로 따라 쓰면 NPE). ③ **`Movie.updateMetadata()`에 `releaseDate` 추가**(6-3 ③) — 시드가 미개봉작을 담는 이상 개봉일 확정·정정이 반드시 발생한다. 파라미터 5개 중 `title`/`posterPath`/`overview`가 **같은 타입으로 연속**이라 순서 실수가 컴파일을 통과하는 점을 경고로 남겼다. 값 객체는 만들지 않는다 — 레코드도 위치 기반이라 위험이 생성 지점으로 옮겨갈 뿐이고, 호출부가 한 곳이라 `@Builder` 순서 유지로 충분하다 |
| 2026-08-23 | **`Movie` v13 — 컬럼 4개 추가.** tmdb-sync **6-9** 확정 반영. `originalTitle`·`backdropPath`·`voteAverage`·`voteCount`. **전부 TMDB 응답에 이미 들어오는데 저장하지 않던 값**이다. 특히 `original_title`은 6-3 ⑦의 폴백용으로만 쓰고 버리고 있었는데, **원어 검색이 안 되는 문제가 실측**되면서(`query=avatar` → `registered` 0건, `query=아바타` → 1건) 저장 필요성이 드러났다. 전부 **nullable** — TMDB가 대개 주지만 보장이 없고 `backdrop_path`는 실제로 `null`이 흔하다. `voteAverage`를 `BigDecimal(3,1)`로 둔 것은 TMDB가 소수 첫째 자리까지 주고 최대 10.0이라 정확히 맞기 때문이며, `Double`이면 `8.433`이 들어와 표시마다 반올림이 필요하다(`movie_genre.weight`와 같은 원칙). ⚠️ **`updateMetadata` 파라미터가 6개 이상이 되고 연속 `String`이 늘어나** 6-4에서 *"호출부가 한 곳뿐이라 `@Builder` 순서 유지로 충분"* 이라 한 전제가 약해진다 — 값 객체 재검토(tmdb-sync 잔여 #25) |
| 2026-08-19 | **`Movie.overview` v12 롤백 — `length` 4000 → 1000.** 아래 2026-08-13 항목의 확장을 되돌린다. **확장 근거였던 "TMDB overview가 1000자를 넘을 수 있다"가 사실이 아니었다** — TMDB 스태프가 *"we limit movie overviews to 1000 characters"* 로 명시하고 있어, `varchar(1000)`은 임의값이 아니라 **TMDB 입력 제한을 반영한 값**이었다. 실데이터도 공식 문서도 확인하지 않고 단정한 것이 오류이며, 넓히면서 스키마가 담고 있던 외부 API 계약 정보를 지웠다. **성능은 롤백 사유가 아니다** — `varchar`는 가변 길이라 같은 데이터면 저장 바이트가 같고, 선언 길이가 문제되던 경로(`MEMORY` 임시 테이블의 고정 폭 패딩, filesort 고정 폭 행)는 MySQL 8.0의 TempTable 엔진·packed addon field로 해소됐으며 `overview`에는 인덱스도 없다. **절단은 유지**하되 `997자 + "..."`로 조정 — 상한과 TMDB 제한이 같아 여유가 0이고, TMDB의 1000자는 입력 폼 제한이라 그 이전 등록분이 넘을 여지가 이론상 남는다. 같은 판단 오류를 반복하지 않기 위해 `title`·`person.name`·`character_name`의 길이 초과도 **컬럼 확장 대신 절단+`WARN` 후 실측**으로 확정했다(tmdb-sync 6-3 ④, 잔여 #11) |
| 2026-08-13 | **`Movie.overview` v11 확장 — `length` 1000 → 4000.** tmdb-sync **D-4 확정**. `varchar(1000)`은 TMDB overview가 초과할 수 있어 적재가 `DataIntegrityViolationException`으로 죽는데, **실데이터를 넣기 전까지 드러나지 않는 유형**이다. `TEXT`를 택하지 않은 이유 — ① 현 스키마에 TEXT 컬럼이 하나도 없고 최장이 `review.content varchar(2000)`이라 동질성이 깨진다, ② `@Column(columnDefinition = "TEXT")`는 Hibernate가 매핑 타입(VARCHAR)과 실제 컬럼 타입(LONGVARCHAR)을 다르게 봐 **`ddl-auto=validate` 기동 실패를 유발할 수 있어** `length = 65535` 우회가 필요하다. `varchar(4000)`은 `length = 4000` 한 줄이고 리스크가 없으며, ko-KR overview가 최장 1,500자 안팎이라 헤드룸도 충분하다. **`@Lob` 금지** — `@Lob` + `String`은 MySQL에서 `LONGTEXT`로 매핑돼 컬럼과 어긋난다. 절단(`3997자 + "..."` + `WARN`)은 **서비스 책임이며 엔티티는 검증하지 않는다** — 상한 초과는 외부 API 계약 변화지 도메인 불변식이 아니다 |
| 2026-08-13 | **`MovieActor` v11 개정 — `displayOrder` 신규 + `RoleTier.EXTRA(0.0)` 추가.** tmdb-sync-spec **D-1 확정**의 반영분이다. cast를 상위 20명에서 자르는 초안 권장을 **채택하지 않고 전량 저장**으로 정했다(컷은 사용자의 출연진 정보 확인을 제약한다). 대신 **표시 범위와 가중치 범위를 분리** — `0~4 LEAD / 5~9 SUPPORTING / 10~20 MINOR / 21~ EXTRA(0.0)`로 파생해, 21번 이후가 추천 집계에 기여하지 않도록 **데이터 자체에 고정**한다. 집계 쿼리가 필터를 잊어도 오염이 0이라는 점이 nullable 대안(`role_tier IS NULL`)보다 안전하고, nullable은 `ORDER BY role_tier ASC`에서 MySQL이 NULL을 선두에 놓아 **단역이 최상단에 오는 버그**가 즉시 발생한다. 영화당 배우 선호 기여 총점은 출연진 수와 무관하게 **5.6 상한 고정**. **`displayOrder`가 함께 필요한 이유** — tier는 그룹 간 순서만 정하고 그룹 내부(EXTRA 180명)는 순서가 없는데, 재동기화가 "전량 삭제 후 재삽입"이라 `id` 삽입 순에 기대면 매번 흔들린다. 필드가 5개가 되어 **`of()` → `@Builder`** 전환(CLAUDE.md 4개 이상 규칙). **정오 1건** — 델타 초판이 `display_order`를 `smallint`로 적었으나 `Integer` 필드와 JDBC 타입 코드가 어긋나 `validate`가 실패한다. `int`로 교정(기존 스키마도 `box_office_rank`·`screen_count`·`movie.runtime` 전부 `int`이고 `smallint`는 하나도 없었다). `MovieActorRepository`의 정렬 메서드도 `OrderByRoleTierAsc` → `OrderByDisplayOrderAsc`로 바꾼다 — 기존 메서드는 MySQL ENUM 인덱스 정렬 덕에 우연히 맞고 있었고 `EXTRA` 추가로 더 취약해진다 |
| 2026-08-07 | **boolean 필드 명명 규칙 확정** — 엔티티 boolean 필드에 `is` 접두사를 붙이지 않는다(컬럼명은 `@Column`으로 분리). `WatchRecord.representative`가 구현에서 `isRepresentative`로 드리프트해 파생 쿼리가 `UnknownPathException`을 던진 것이 계기. FIELD 접근이라 **JPA 메타모델 속성(`isRepresentative`)과 JavaBean 프로퍼티(`representative`)가 갈리는데** Spring Data는 후자로 파싱해 통과시키고 Hibernate는 전자로 조회해 실패하므로, 기동이 아니라 **해당 경로를 실제 호출할 때까지 숨는다.** 문서는 원래 옳았고 구현이 어긋난 것이므로 필드명을 스펙대로 되돌렸다. 같은 규칙에 따라 **`Notification.isRead` → `read`로 정정** — 2026-07-30 이력에서 `read` → `isRead`로 바꾼 것이 드리프트된 구현을 근거로 규칙을 승격시킨 것이었고, 그대로 두면 알림 도메인 착수 시 동일 버그가 재현된다(**코드는 2026-08-11 기준 미반영**). 아울러 **`WatchRecord.rating`에 0.0~10.0 검증 추가**(기존에 전무, nullable이므로 null은 통과, `@Builder` 대상 생성자에서 호출) |
| 2026-07-23 | Step1 완료, Step2 스펙 확정 (comment 다형성 A안 채택)                                                                                                                                                                                                                                                                                                                                                                                          |
| 2026-07-23 | Step3 설계 확정 (Follow/Collection/Comment/Review/WatchRecord)                                                                                                                                                                                                                                                                                                                                                                         |
| 2026-07-30 | **스키마 v10 반영 (21 → 22 테이블).** ① `RefreshToken`에 `revokedReason`(`RevokedReason` enum 4종) 추가 — **유예 창 판정에 `ROTATED` 조건이 붙는다.** 없으면 로그아웃 직후 30초간 같은 토큰으로 세션을 되살릴 수 있다 ② `PasswordResetToken` 신규 — `RefreshToken`과 같은 골격(해시 저장 / 시각형 상태 / 시각 주입) 유지, SMTP 도입 확정에 따라 v10에 포함 ③ `BoxOfficeRecord.openDate` — 4-7 재매칭 2순위 전략 복원용. **기준 스키마를 v9 → v10으로 갱신** |
| 2026-07-30 | **테이블 수 표기 정정.** 문서 전반이 v8=18 / v9=20으로 적고 있었으나 실제 덤프는 **v9=21**이다(v6 16개 + `ott_platform`·`theater`·`box_office_record` = v8 19개, v9에서 2개 추가). `CineMory_기획노트.md`만 v8을 19개로 올바르게 적고 있었고, 오차는 2026-07-22 v7/v8 문서화 시점에 생겨 이후로 전파됐다. v9 델타 파일이 **커밋되지 않은 채 삭제**돼 참조가 끊긴 것도 함께 정리 |
| 2026-07-30 | **Step4 구현 완료** 및 구현 중 조정 4건. ① `RefreshToken` 팩토리명 `of()` → **`issue()`** (토큰 "발급"이라는 도메인 동작을 드러내기 위함). ② `revoke()` → **`revoke(LocalDateTime now)`** — `isExpired(now)`와 같은 이유(테스트 시간 고정)인데 한쪽만 인자를 받는 것이 일관되지 않았음. ③ `Notification`의 boolean 필드명 `read` → **`isRead`** (`WatchRecord.isRepresentative` 컨벤션과 통일). ④ S-9 A-4 결정에 따라 **`isWithinReuseGrace(now, grace)` 추가**. 엔티티 3건의 컬럼 구성을 v9 덤프와 대조해 차이 0 확인, `validate` 기동 통과 |
| 2026-08-02 | **v10 반영 구현 완료**(순서 5). `RevokedReason` 신규, `RefreshToken.revokedReason` 추가 + `revoke(now, reason)` / `isWithinReuseGrace`에 `ROTATED` 조건, `PasswordResetToken` 신규, `BoxOfficeRecord.openDate` 추가. 구현 중 조정 2건 — ① **`RefreshTokenRepository.revokeAllByUserId`에 `reason` 인자 추가**: 벌크 UPDATE는 엔티티 `revoke()`를 거치지 않아 `revokedAt`만 채우면 `chk_refresh_token_revocation`에 걸려 **재사용 감지 경로가 런타임에 실패**한다(호출부는 `REUSE_DETECTED`). ② `RefreshTokenHasher` → **`TokenHasher`** 리네임(스펙 예고분, 두 토큰이 공유). `openDate`는 컬럼·필드만 추가된 상태이고 **수집 시 채우지 않는다** — `BoxOfficeSyncService`에 TODO로 명시. `validate` 기동 통과 |
| 2026-07-29 | 기준 스키마를 v8(19 테이블) → **v9(21 테이블)** 로 갱신. Step4(인증/알림 엔티티) 스펙 신규 — `User.role` 추가(팩토리·비즈니스 메서드로는 변경 불가, DB UPDATE 전용), `RefreshToken`(해시만 보관, `revoke()` 멱등, `isExpired(now)` 주입식), `Notification`(문구 스냅샷 미보유, `NotificationTargetType`을 `Comment.TargetType`과 분리). 고아 알림이 4-6 고아 댓글과 동일 구조임을 명시                                                                                                                                   |
