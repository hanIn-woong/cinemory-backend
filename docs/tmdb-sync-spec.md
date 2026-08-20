# CineMory TMDB 연동 설계 스펙 (Step6) — **6-0 확정 / 6-1~6-6 초안**

`service-layer-spec.md` 4-2에서 `MovieSyncService`를 **시그니처만 확정하고 구현을 미뤄둔** 부분을
채우는 문서다. **실제 코드는 Claude Code가 이 문서를 보고 작성**하며, 여기서는 패턴/시그니처/
설계 결정만 명시한다.

> ## ✅ 6-0 미결 4건 전부 확정 (2026-08-13)
>
> D-1(`role_tier`) / D-2(적재 전략) / D-3(대표국) / D-4(overview) 모두 확정됐다.
> **코드 세션 착수 가능하되, 순서에 두 개의 하드 선행 조건이 있다.**
>
> 1. **v11 스키마 델타 적용** (`docs/schema/v11-delta.sql`) — 엔티티를 먼저 고치면
>    `ddl-auto=validate`에서 기동 실패한다.
> 2. **`MovieSearchCondition` 설계** — D-2 ③(검색 병합)이 이 설계를 선행 조건으로 만들었다.
>    시드·동기화만 먼저 하고 검색은 뒤로 미뤄도 되지만, **온디맨드 경로는 검색 없이 성립하지
>    않는다.**

---

## 배경 — 왜 지금 최우선인가

**`movie` 테이블을 채울 경로가 현재 하나도 없다.** 유일하게 TMDB를 호출하던 `TestController`는
S-9 A-7로 삭제됐고, `global/infra`에 `kakao`/`kofic`/`mail`은 있으나 `tmdb`가 없다.

| 영향 | 내용 |
|---|---|
| 사용자 기능 전부 | 시청기록·위시·컬렉션·리뷰가 모두 `movie` FK를 요구 |
| 박스오피스 | 4-7의 1순위 매칭(`koficMovieCd`) 대상이 없어 수집분이 전부 `movie_id = NULL`로 적재 중 |
| 추천 (M3) | 임베딩 입력이 `movie_genre`/`movie_country`/`movie_actor` 가중치다 |
| 프론트 (M2) | 실데이터 없이는 화면 검증 불가 |

---

## 진행 로드맵

| 순서 | 항목 | 상태 |
|---|---|---|
| 6-0 | **착수 전 확정 필요 (미결 4건)** | ✅ **전부 확정** (2026-08-13) |
| — | ↳ ~~v11 스키마 델타 적용~~ | ✅ 적용 완료 |
| — | ↳ **v12 스키마 델타 적용** (`display_order` 정오 + `overview` 롤백) | 🔲 **엔티티 반영 전 필수** |
| — | ↳ `MovieSearchCondition` 설계 | 🔲 D-2 ③의 선행 조건 |
| 6-1 | 참조 테이블 선행 적재 (`genre`, `country`) | ✅ **구현 완료** (진입점은 6-5) |
| 6-2 | `TmdbClient` 인프라 | ✅ **구현 완료** (호출 메서드는 점증) |
| 6-3 | 도메인 매핑 — TMDB 응답 ↔ 엔티티 | ✅ **구현 완료** (2026-08-20) |
| 6-4 | `MovieSyncService` 구현 | ✅ **구현 완료** (2026-08-20) |
| 6-5 | 적재 전략 (시드 / 온디맨드) | 🔶 D-2 확정 반영됨. `syncFromTmdb` 구현 완료, 관리자 엔드포인트·`MovieSeedService`는 미구현 |
| 6-6 | ErrorCode · 잔여 | 🔲 초안 |

---

## 6-0. 착수 전 확정 필요 (미결 4건 — ✅ 전부 확정)

### ✅ D-1. `role_tier` 경계값 — **확정 (2026-08-13)**

```
order 0 ~ 4    → LEAD       (0.5)
order 5 ~ 9    → SUPPORTING (0.4)
order 10 ~ 20  → MINOR      (0.1)
order 21 ~     → EXTRA      (0.0)   ← 신규 enum 값
```

**cast는 자르지 않고 전량 저장한다.** 대신 `movie_actor`에 `display_order`(TMDB `order` 원본)를
추가하고, 21번 이후에는 가중치 0인 `EXTRA`를 부여해 **추천 집계에서 구조적으로 배제**한다.

#### 왜 비율 방식(기획 원안)을 버렸는가

기획노트는 *"`order` / 전체 출연진 수 기준 상위 비율"* 로 적고 있으나 수치가 없었고,
이 값은 단순 분류가 아니라 **추천 가중치에 직접 곱해진다.** TMDB `cast[]`는 영화당
10명대부터 200명 이상까지 편차가 커서, 상위 10%를 LEAD로 잡으면 다음이 벌어진다.

```
출연진 200명 영화:  MINOR 180명 × 0.1 = 18.0
                    LEAD    20명 × 0.5 = 10.0     ← 주연보다 단역 총합이 크다
출연진  15명 영화:  MINOR   10명 × 0.1 =  1.0
                    LEAD     2명 × 0.5 =  1.0
```

같은 "이 영화를 좋아함"이 출연진 수에 따라 전혀 다른 점수를 만든다 — **선호 배우 집계가
출연진 규모에 오염된다.** 절대 순번은 TMDB `order`가 이미 빌링 순서(중요도 순)라는 점에서
의미도 맞고, 영화 간 비교 가능성을 확보한다.

#### 확정안의 기여도 상한 — 출연진 수와 무관하게 고정된다

| tier | 최대 인원 | 가중치 | 영화당 최대 기여 |
|---|---|---|---|
| LEAD | 5 | 0.5 | 2.5 |
| SUPPORTING | 5 | 0.4 | 2.0 |
| MINOR | 11 | 0.1 | 1.1 |
| EXTRA | 무제한 | **0.0** | **0.0** |
| | | | **합계 5.6 (상한 고정)** |

출연진이 15명이든 200명이든 한 영화가 만들어내는 배우 선호 총점은 5.6을 넘지 않는다.
위 오염 문제가 정의상 발생하지 않는다.

#### 왜 `EXTRA` enum인가 — nullable을 쓰지 않은 이유

"21번 이후는 role을 부여하지 않는다"를 표현하는 방법은 둘이었다.

| 방식 | 평가 |
|---|---|
| **채택: `EXTRA(0.0)` enum 값 추가** | `NOT NULL` 유지. 가중치가 0.0이라 **집계 쿼리가 필터를 잊어도 오염이 0**이다. 쓰기 시점에 한 번 결정하고 끝 |
| 기각: `role_tier` nullable화 | null이 소비 지점 전체로 전파된다. 결정적으로 `ORDER BY role_tier ASC`에서 **MySQL은 NULL을 맨 앞에 놓아 단역이 최상단에 오는** 버그가 즉시 발생한다 |

nullable안은 "이 tier는 집계에서 빼야 한다"는 규칙을 M3의 모든 신규 집계 쿼리가 기억해야
한다. `EXTRA(0.0)`는 그 규칙을 데이터에 박아 넣는다.

#### 왜 `display_order`가 함께 필요한가

tier만으로는 **그룹 간** 순서만 정해지고 **그룹 내부**는 순서가 없다. EXTRA 180명 사이,
MINOR 11명 사이의 표시 순서를 복원할 수단이 사라진다. 삽입 순(`id`)에 암묵 의존하는 형태가
되는데, 6-4의 재동기화가 **전량 삭제 후 재삽입**이라 매 동기화마다 흔들린다.
"cast를 자르지 않는다"는 결정의 목적(전체 출연진을 순서대로 보여준다)이 순서 컬럼 없이는
달성되지 않는다.

#### 스키마 델타 (v11) — `docs/schema/v11-delta.sql`

```sql
ALTER TABLE movie_actor
  ADD COLUMN display_order INT NOT NULL AFTER character_name,
  MODIFY COLUMN role_tier ENUM('LEAD','SUPPORTING','MINOR','EXTRA') NOT NULL;
```

⚠️ `EXTRA`는 반드시 **enum 목록 맨 끝**에 붙인다. 중간에 끼워 넣으면 MySQL ENUM이 값을
인덱스로 저장하는 특성상 기존 행의 의미가 통째로 재해석된다. 끝에 추가하는 경우에만
`ALGORITHM=INSTANT`가 적용돼 비용이 없다.

#### 파급 항목

| 대상 | 변경 |
|---|---|
| `RoleTier` enum | `EXTRA(0.0)` 추가 (jpa-entity-spec 3절) |
| `MovieActor` | `displayOrder` 필드 추가, 필드 5개가 되어 `of()` → `@Builder` 전환 |
| `MovieActorRepository` | `findByMovieIdOrderByRoleTierAsc` → **`findByMovieIdOrderByDisplayOrderAsc`** |
| `MovieQueryService.getMovieDetail` | cast 전량(최대 수백 행)이 상세 응답에 실리는 문제 → 상세는 `displayOrder <= 20`만, 전체는 별도 엔드포인트 (잔여 #7) |

### ✅ D-2. 초기 적재 전략 — **확정 (2026-08-13)**

**C안(하이브리드) 채택.** 홈 화면(박스오피스·인기작)에는 시드가 필요하고, "내가 본 영화 기록"에는
온디맨드가 필요하다. 하나만으로는 어느 쪽도 성립하지 않는다.

| 안 | 방식 | 트레이드오프 |
|---|---|---|
| A. 배치 시드 | `/movie/popular` 등으로 N편 선적재 | 콜드 스타트 해결. 시드에 없는 영화는 기록 불가 |
| B. 온디맨드 | 검색 결과에서 선택 시 그 영화만 상세 동기화 | 무한한 커버리지. **첫 사용자가 빈 화면을 본다** |
| **C. 하이브리드 (채택)** | 시드로 초기 데이터 + 온디맨드로 보충 | 둘 다 필요한 이유가 명확 |

#### ① 시드 대상 — 박스오피스 역방향 + `/discover?region=KR` 병행

**`/movie/popular`(문서 원안)은 채택하지 않았다.** 전역 인기작이라 할리우드 위주로 채워지는데,
지금 `box_office_record`는 KOFIC에서 수집되어 **전량 `movie_id = NULL`로 쌓이는 중**이다.
시드를 할리우드로 채우면 콜드 스타트는 풀리지만 **4-7 재매칭은 여전히 아무것도 못 맞추고
홈 화면의 박스오피스는 그대로 비어 있다.** 두 문제 중 하나만 푸는 셈이다.

| 경로 | 대상 | 목적 |
|---|---|---|
| **주(主). 박스오피스 역방향** | `box_office_record`에서 `movie_id IS NULL`인 `movieTitleSnapshot` (+ `openDate`) | 콜드 스타트와 **4-7 재매칭을 동시에 해소.** "우리 사용자가 실제로 볼 영화"라 인기작 목록보다 정확 |
| 보(補). `/discover/movie` | `region=KR`, `sort_by=popularity.desc` | 박스오피스는 최근 흥행작으로 한정되므로 구작·비흥행작 커버리지를 보충 |

- 역방향 경로는 `/search/movie?query={제목}&year={개봉연도}`로 `tmdbId`를 먼저 찾고,
  그 결과로 `syncFromTmdb`를 태운다. **`openDate`(v10에서 이 목적으로 추가한 컬럼)를
  `year`로 넘겨 동명이인 오탐을 줄인다.**
- ⚠️ **제목 매칭은 실패할 수 있다.** KOFIC 한글 제목과 TMDB `ko-KR` 제목이 다른 경우
  (부제 유무, 띄어쓰기, 시리즈 표기)가 있다. **실패를 예외로 던지지 말고 건너뛰고 집계**한다 —
  한 편 때문에 시드 전체가 멈추면 안 된다. 결과에 `matched / skipped` 카운트를 반환한다.

#### ② 시드 성격 — 1회성 관리자 엔드포인트

4-7 `TheaterSeedService`와 동일한 패턴. **멱등 재실행 가능.** 엔드포인트는 2개로 나눈다
(`/api/admin/movies/seed/box-office`, `/seed/discover`) — **실패 양상과 결과 DTO가 다르다.**
역방향은 제목 매칭 실패가 정상 범주라 `skipped` 집계이고, discover는 페이지 순회 실패라
이어받기 지점이 다르다.

- 주기 배치로 두지 않는 이유 — 지속적 보충은 **온디맨드가 이미 맡는다.** 인기작 목록을
  주기 갱신해도 사용자가 실제로 기록하는 영화와는 무관하고, rate limit만 상시 소모한다.
- 멱등성은 4-7 박스오피스의 **"기존 키 집합 조회 → 차집합만 저장"** 패턴을 재사용한다.
  이미 적재된 `tmdbId`는 건너뛰므로 실패 지점부터 이어받기가 된다.

#### ③ 온디맨드 진입점 — DB + TMDB 병합, **선택 시** 동기화

검색 시점에 결과 20편을 미리 동기화하지 않는다. rate limit과 쓰레기 데이터 양쪽에서 손해다.

```
1. GET /api/movies/search?query=...
     → 우리 DB 검색 결과 + TMDB 검색 결과를 병합해 반환
2. 사용자가 미등록 항목을 선택
     → POST /api/movies/sync { tmdbId }  →  syncFromTmdb 후 movieId 반환
3. 프론트가 그 movieId로 시청기록·위시 등을 생성
```

⚠️ **응답 DTO 계약이 바뀐다.** 병합 결과에는 아직 우리 DB에 없는 항목이 섞이므로
**`movieId`가 없는 행이 응답에 존재**한다.

- `movieId`를 **nullable**로 두고 `tmdbId`를 함께 내린다. `movieId == null`이 "미등록"의 신호다.
- 중복 제거 — 같은 영화가 양쪽에 있으면 `tmdbId` 기준으로 합치고 **DB 쪽을 남긴다**(`movieId` 보존).
- ⚠️ **페이징 의미가 깨진다.** 두 출처를 병합하면 전체 건수를 알 수 없어 `Page`의
  `totalElements`가 성립하지 않는다. **`Slice`(다음 페이지 존재 여부만) 또는 별도 응답 형태**가
  필요하다 — `MovieSearchCondition` 설계에서 함께 확정한다(잔여 #5).

> ⚠️ **`searchMovies` 설계가 함께 열린다.** `controller-layer-spec.md` 잔여 #3에서
> `MovieSearchCondition` 미설계를 이유로 `/api/movies/search`를 보류해뒀는데,
> D-2 확정으로 그 설계가 선행 조건이 됐다.

### ✅ D-3. `production_countries`의 "대표국" — **확정 (2026-08-13)**

**B안 채택: `origin_country`가 있으면 그것을, 없으면 배열 첫 번째를 대표로 본다.**

기획노트의 국가 가중치 공식은 1위(대표)와 나머지를 구분한다.

```
1위(대표)   = (N+1) / (N²+1)
나머지 각각 =  N    / (N²+1)
```

그런데 **TMDB `production_countries[]`는 "대표"를 명시하지 않는다.** 배열 순서가 있을 뿐이고,
그 순서가 제작 기여도 순이라는 보장이 문서에 없다.

| 안 | 방식 | 평가 |
|---|---|---|
| A | 배열 첫 번째를 대표로 간주 | 단순하나 근거가 약하다 |
| **B (채택)** | `origin_country` 사용, 없으면 A로 폴백 | 존재 시 더 정확하고, 폴백이 있어 항상 판정 가능 |
| C | 대표 구분 포기, `1/N` 균등 | 기획노트 공식을 버리게 됨 |

**구현 시 주의**

- `origin_country`는 **배열(`string[]`)** 이다. 단일 값이 아니므로 `origin_country[0]`을 쓴다.
  값이 빈 배열인 경우도 A로 폴백한다.
- ⚠️ **`origin_country`가 `production_countries`에 없을 수 있다.** 두 필드는 출처가 달라
  일치가 보장되지 않는다. 이 경우 `origin_country`를 무시하고 A로 폴백한다 —
  **`production_countries`에 없는 국가를 대표로 세우면 나머지 가중치의 분모 N과 어긋난다.**
- `N = 1`이면 공식이 `2/2 = 1.0`으로 수렴해 대표 판정 자체가 무의미하다. 단축 처리해도 된다.

### ✅ D-4. `overview` 길이 초과 — **확정 (2026-08-13) / 정정 (2026-08-19)**

**결론: `varchar(1000)` 유지 + Service 절단.** 문서 원안의 A안이 옳았다.

> #### ⚠️ 이 항목은 한 번 잘못 확정됐다가 되돌렸다
>
> 2026-08-13에 *"TMDB overview가 1000자를 넘을 수 있어 적재가 `DataIntegrityViolationException`으로
> 죽는다"* 를 전제로 **`varchar(4000)`으로 확장**했다(v11 델타). **그 전제가 사실이 아니다.**
>
> **TMDB는 overview를 1000자로 제한한다.** TMDB 스태프가 포럼에서 *"we limit movie overviews
> to 1000 characters"* 라고 명시했다.
> ([출처](https://www.themoviedb.org/talk/5b59e5a09251414d1b012d9b))
>
> 즉 `movie.overview varchar(1000)`은 임의로 정해진 값이 아니라 **TMDB의 입력 제한에 맞춰
> 설계된 값**이었다. 실데이터도 공식 문서도 확인하지 않고 "넘을 수 있다"고 단정한 것이 오류다.
> **v12 델타에서 `varchar(1000)`으로 되돌린다.**

#### 되돌리는 이유는 성능이 아니다

확장 상태를 유지해도 성능 문제는 없다. 오해를 남기지 않기 위해 검토 결과를 적어둔다.

| 층 | 4000이 문제인가 |
|---|---|
| 저장 | 아니다. `varchar`는 가변 길이라 같은 데이터면 바이트가 같다. TMDB가 1000자로 막으므로 **실제 저장 데이터가 변경 전후 동일**했다 |
| 오버플로 페이지 | 아니다. `DYNAMIC` 행 포맷은 **실제 행 크기**(≈8126바이트)로 판단하지 선언 길이로 하지 않는다. 한국어 1000자 ≈ 3KB |
| 임시 테이블 | 아니다. `MEMORY` 엔진이 VARCHAR를 선언 길이만큼 패딩하던 문제는 MySQL 8.0의 **TempTable 엔진**에서 가변 길이로 바뀌며 해소됐다 |
| filesort | 아니다. addon 필드를 **packed**로 저장해 실제 길이만 쓴다 |
| 인덱스 접두사 | 해당 없음. `overview`에 인덱스가 없다 |

**되돌리는 이유는 오직 "근거가 없었다"는 것이다.** 스키마가 외부 API의 계약을 반영하고 있었는데
그 의미를 지워버렸으므로 복원한다.

#### 절단은 남긴다 — 오히려 더 중요해졌다

상한(1000)과 TMDB 제한(1000)이 **같아서 여유가 0**이다. TMDB의 1000자는 *입력 폼* 제한이라
그 이전에 등록됐거나 외부에서 임포트된 항목이 넘을 여지가 이론상 남는다.

- 1000자 초과 시 `997자 + "..."`로 절단하고 **`WARN` 로그에 `tmdbId`와 원본 길이를 남긴다.**
- 발동하면 그 자체가 "TMDB 제한이 바뀌었거나 예외가 있다"는 신호다. 조용히 데이터를 잃거나
  배치가 죽는 쪽보다 낫다.

#### 검토했으나 채택하지 않은 것

| 안 | 기각 사유 |
|---|---|
| `TEXT`로 변경 | 현 스키마에 TEXT 컬럼이 하나도 없다(최장 `review.content varchar(2000)`). 또 `@Column(columnDefinition = "TEXT")`는 Hibernate가 매핑 타입(VARCHAR)과 실제 컬럼 타입(LONGVARCHAR)을 다르게 봐 **`ddl-auto=validate` 기동 실패**를 유발할 수 있다 |
| `varchar(4000)` 유지 | 무해하지만 근거가 없다. 스키마가 TMDB 계약을 표현하던 정보를 잃는다 |

---

## 6-1. 참조 테이블 선행 적재 (✅ 구현 완료 — 엔드포인트 제외)

**`genre`·`country`가 비어 있으면 `movie_genre`·`movie_country`를 만들 수 없다.**
영화 동기화보다 **먼저** 채워야 한다.

| 테이블 | 출처 | 키 | 구현 |
|---|---|---|---|
| `genre` | TMDB `/genre/movie/list?language=ko-KR` | `tmdbGenreId` (`uk_genre_tmdb_id`) | `domain.genre.service.GenreSeedService` |
| `country` | TMDB `/configuration/countries?language=ko-KR` | `code` (`uk_country_code`) | `domain.country.service.CountrySeedService` |

- 둘 다 **멱등 upsert** — 이미 있으면 `rename()`으로 이름만 갱신, 없으면 `of()` 후 저장.
  `rename()`은 값 비교 후에만 대입하므로 실제 변경이 없으면 dirty checking이 UPDATE를 내지 않는다.
- 기존 행은 **ID/코드 집합을 1쿼리로 읽어** 신규/기존을 가른다(`findByTmdbGenreIdIn` /
  `findByCodeIn`). 건별 조회는 장르 20여 회, 국가 250여 회의 왕복이 된다.
- 영화 동기화 중 미등록 장르/국가를 만나면 **그 자리에서 생성하지 않고 실패시킨다.**
  참조 테이블은 선행 적재로만 채워 출처를 하나로 유지한다(정체불명 행 방지).
  → `GENRE_NOT_FOUND` / `COUNTRY_NOT_FOUND`

> `person`은 다르다. 인물은 수가 무한하고 영화마다 새로 등장하므로 **동기화 중 upsert**가 맞다.

### 구현 중 확정한 것

**① `Country.rename()`을 신설했다.** 초안이 *"둘 다 멱등 upsert — 이미 있으면 `Genre.rename()`으로
이름만 갱신"* 이라 적었는데 **`Country`에는 그 메서드가 없었다.** `Genre`와 대칭으로 추가했다.
국가명은 거의 변하지 않지만 TMDB의 한국어 지역화가 시간이 지나며 채워지는 경우가 있어
재적재로 반영할 수단이 필요하다. `code`는 자연키라 변경 수단을 두지 않는다.

**② 외부 데이터를 저장 전에 거른다** (4-7 `hasRequiredFields`와 같은 원칙).

| 검사 | 이유 |
|---|---|
| `iso_3166_1` 길이 2 | `country.code`가 `length 2`. 어긋나면 저장이 깨진다 |
| 이름 공백 여부 | `country.name` / `genre.name`이 not null |
| `tmdbId`·`code` 중복 제거 | 같은 키가 두 번 오면 `uk_*` 위반 |

셋 다 **트랜잭션 전체가 롤백되는** 유형이다. 한 건 때문에 정상인 250여 건이 통째로 날아간다.

**③ 국가명은 `native_name` → `english_name` 폴백이다.** `language=ko-KR`로 요청해도 TMDB의
국가명 지역화 범위가 완전하지 않아 `native_name`이 비거나 영문 그대로인 경우가 있다.
`country.name`이 not null이라 비우면 저장이 실패하므로 `TmdbCountryListItem.resolveName()`에서 폴백한다.

> **⚠️ 아직 호출 진입점이 없다.** 관리자 엔드포인트는 6-5에 시드 엔드포인트들과 함께 정의돼 있어
> 그쪽에서 한 번에 붙인다. 그전까지 이 두 서비스는 테스트에서만 호출 가능하다.

---

## 6-2. `TmdbClient` 인프라 (✅ 구현 완료 — 호출 메서드는 점증)

```
global/infra/tmdb
 ├─ TmdbClient.java          (RestClient 기반 — KoficClient와 동일 골격)
 ├─ TmdbProperties.java      (@ConfigurationProperties(prefix = "tmdb"), baseUrl + accessToken)
 ├─ TmdbConfig.java
 └─ dto/
     ├─ TmdbGenreListResponse.java   (래퍼 객체)
     └─ TmdbCountryListItem.java     (⚠️ 루트 배열의 항목)
```

**설정 키**

| 위치 | 키 | 값 |
|---|---|---|
| `application.yml` | `tmdb.base-url` | `https://api.themoviedb.org/3` |
| `application-secret.yml` | `tmdb.access-token` | v4 Read Access Token |

> 기존에 `tmdb.api.token`으로 들어 있던 키(삭제된 `TestController` 잔재)를
> **`tmdb.access-token`으로 정리**했다. `kofic.base-url` / `kofic.api-key`와 같은 2단 구조로 맞춘 것이다.

**호출 메서드는 필요 시점에 추가한다.** 현재는 6-1이 쓰는 `fetchMovieGenres()` /
`fetchCountries()` 둘뿐이다. 영화 상세·검색·discover는 응답 DTO 설계가 6-3의 범위라
그때 함께 붙인다. 쓰이지 않는 메서드를 미리 만들면 실호출로 검증되지 않은 채 남는다.

- **`KoficProperties`와 같은 형태를 따른다** — `isConfigured()`로 키 미설정 시 배치를 건너뛰고
  애플리케이션 기동은 막지 않는다.
- **인증은 Bearer 방식**(v4 API Read Access Token)을 쓴다. `api_key` 쿼리 파라미터 방식도
  동작하지만, 공식 문서가 신규 연동에 Bearer를 권장하고 URL·로그에 키가 남지 않는다.
  ```
  Authorization: Bearer <TMDB_ACCESS_TOKEN>
  ```
- 토큰은 `application-secret.yml`에 둔다(기존 secret 분리 구조 유지).
- **모든 요청에 `language=ko-KR`.** 한국어 제목·줄거리가 기본이며, 비어 오는 경우의 폴백은
  6-4 참고.
- 외부 호출 실패는 `EXTERNAL_API_ERROR`로 감싸고, **스케줄러/배치 진입점에서는 잡아서 로깅만
  하고 삼킨다**(4-7 KOFIC과 동일 원칙).

### 사용 엔드포인트

| 용도 | 엔드포인트 |
|---|---|
| 영화 상세 + 크레딧 | `/movie/{tmdbId}?append_to_response=credits&language=ko-KR` |
| 검색 (온디맨드 / 박스오피스 역방향) | `/search/movie?query=&year=&language=ko-KR` |
| 시드 보충 (D-2 ①) | `/discover/movie?region=KR&sort_by=popularity.desc&language=ko-KR` |
| 장르 목록 | `/genre/movie/list?language=ko-KR` |
| 국가 목록 | `/configuration/countries?language=ko-KR` |

- **`append_to_response=credits`로 상세와 출연진을 1회 호출에 묶는다.** 나눠 부르면 영화당
  왕복이 2배가 되고, 시드 적재에서 그대로 소요 시간이 된다.

---

## 6-3. 도메인 매핑 (✅ 확정 2026-08-19)

### `Movie`

| 엔티티 필드 | TMDB 응답 | 비고 |
|---|---|---|
| `tmdbId` | `id` | `uk_movie_tmdb_id` |
| `title` | `title` | `language=ko-KR` |
| `posterPath` | `poster_path` | 경로만 저장, 베이스 URL은 프론트가 조립 |
| `releaseDate` | `release_date` | 빈 문자열로 오는 경우가 있어 **null 처리 필요** |
| `overview` | `overview` | `length 1000` (= TMDB 제한과 동일, D-4). 초과 시 `997자 + "..."` 절단 + `WARN` |
| `runtime` | `runtime` | **`0`이면 `null`로 정규화** (아래 ② 참고) |
| `koficMovieCd` | **없음** | TMDB가 제공하지 않는다. 4-7 재매칭 배치가 `linkKoficCode()`로 역으로 채운다 |

- 재동기화 시 `Movie.updateMetadata(title, posterPath, overview, runtime, **releaseDate**)` 사용
  — **`releaseDate`를 시그니처에 추가한다**(아래 ③, 잔여 #2 종결).

### 6-3 확정 사항 (2026-08-19)

TMDB 공식 OpenAPI 정의와 대조해 초안의 누락 8건을 채웠다.

#### ① `adult` 영화는 `syncFromTmdb` 진입점에서 거부한다

초안이 `adult` 플래그를 아예 다루지 않았다. discover/search는 `include_adult=false`가 기본이라
시드 경로는 안전하지만, **`POST /api/movies/sync`는 사용자가 임의 `tmdbId`를 보내는 경로**라
필터가 없다. 성인 영화가 우리 DB에 들어올 통로가 열려 있다.

- `syncFromTmdb`에서 `adult == true`면 **저장하지 않고 예외**를 던진다 → `ADULT_CONTENT_NOT_ALLOWED`(6-6)
- 검사는 매핑보다 **먼저** 한다. `Person` upsert가 먼저 돌면 성인 영화 출연진이 `person`에 남는다

#### ② `runtime`이 `0`이면 `null`로 정규화한다

TMDB OpenAPI가 `runtime`에 `default: 0`을 명시한다. **미개봉작·데이터 미비 시 `null`이 아니라
`0`으로 온다.** 그대로 저장하면 상세 화면에 "0분"이 뜨고, "정보 없음"과 구분되지 않는다.
컬럼이 이미 nullable이라 스키마 변경은 없다.

#### ③ `releaseDate`를 `updateMetadata`에 포함한다 (잔여 #2 종결)

시드가 미개봉작을 담는 이상 **개봉일 확정·정정이 반드시 발생한다.** 갱신 수단이 없으면
최초 적재 시점의 값(또는 `null`)이 영구히 남는다. 별도 `changeReleaseDate()`로 분리하지 않은 이유는
호출부가 재동기화 한 곳뿐이라 메서드를 쪼개도 도메인 의미가 더 드러나지 않기 때문이다.

#### ④ 길이 초과는 **절단 + `WARN`** 으로 처리한다 (컬럼 확장 없음)

D-4와 같은 유형의 위험이 3곳 더 있다. nullable 여부와 무관하게 **초과하면 전부
`DataIntegrityViolationException`으로 배치가 죽는다** — nullable은 "비어도 된다"이지
"길어도 된다"가 아니다.

| 컬럼 | 상한 | 절단 규칙 | 초과 가능성 |
|---|---|---|---|
| `movie.title` | 255 | `252자 + "..."` | 매우 낮음 (KR 시드에 초장문 제목이 들어올 일이 없다) |
| `movie.overview` | 1000 | `997자 + "..."` | 낮음 (TMDB가 1000자로 제한) |
| `person.name` | 100 | `97자 + "..."` | **사실상 없음** (사람 이름이 100자에 닿지 않는다) |
| `movie_actor.character_name` | 100 | `97자 + "..."` | 셋 중 유일하게 근거가 있음 |

**컬럼을 넓히지 않는 이유** — 추정으로 스키마를 바꾸는 것이 D-4에서 이미 한 번 오류를 냈다.
`character_name`만 해도 TMDB 공식 가이드가 다역을 `Character 1 / Character 2 / Character 3`
슬래시 연결로 규정하므로 100자 초과가 *가능*하지만, **길이 상한은 문서화돼 있지 않고 실측값도 없다.**
`WARN` 로그가 첫 시드에서 실제 분포를 측정하게 하고, 울리면 그때 근거를 갖고 넓힌다
(인덱스 잔여 #8, rate limit 잔여 #6과 같은 "실측 후 판단" 패턴).

> ⚠️ **절단 헬퍼 구현 주의 — 서로게이트 페어.** `String.length()`는 UTF-16 코드 단위를 세고
> MySQL `varchar(N)`의 N은 **문자 수**다. 이모지 등 보조 평면 문자가 섞이면 Java 길이가 더 크게
> 나와 절단이 보수적으로 걸리는데(안전), **`substring`이 서로게이트 페어 중간을 자르면
> 깨진 문자가 저장된다.** 절단 위치는 `Character.isLowSurrogate` 확인 또는
> `offsetByCodePoints`로 보정한다.

#### ⑤ `cast[].id`와 `cast[].cast_id`는 다른 값이다

`id`가 **사람 ID**(`person.tmdb_person_id`)이고, `cast_id`는 **그 크레딧만의 ID**다.
`Person.tmdbPersonId`에 `cast_id`를 넣으면 `uk_person_tmdb_id`가 엉뚱한 값으로 잡히고
**예외 없이** 잘못된 인물 데이터가 쌓인다. 조용히 망가지는 유형이라 명시한다.

TMDB `cast[]` 실제 필드: `adult, gender, id, known_for_department, name, original_name,
popularity, profile_path, cast_id, character, credit_id, order`

#### ⑥ 감독도 중복 제거가 필요하다

초안은 cast의 1인 2역만 다뤘으나, crew에서 **같은 사람이 `job == "Director"`로 두 번**
나오는 경우(파트별 크레딧)가 있어 `uk_movie_director (movie_id, person_id)`를 똑같이 위반한다.
`personId` 기준으로 중복을 접는다.

#### ⑦ 한국어 폴백은 `title`·`overview`만이 아니다

`person.name`도 **not null**인데 6-4의 폴백 절이 다루지 않았다.

| 필드 | 폴백 | 비고 |
|---|---|---|
| `title` | `original_title` | `language` 없이 재요청하면 영화당 왕복이 2배가 된다. DTO에 `original_title`을 담아 해결 |
| `person.name` | `original_name` | `cast[]`/`crew[]`에 이미 들어 있다 |
| `overview` | 없음 | nullable이라 비어도 무방 |

#### ⑧ `weight` 계산 규칙을 못 박는다

- **`RoundingMode.HALF_UP`** — 초안이 "소수 셋째 자리 반올림"만 적어 구현이 갈릴 수 있었다.
  `divide(BigDecimal.valueOf(n), 3, RoundingMode.HALF_UP)`
- ⚠️ **장르가 빈 배열인 영화가 있다.** `1/N`을 루프 밖에서 미리 계산하면 `ArithmeticException`이다.
  빈 리스트는 매핑을 만들지 않고 바로 반환한다.
- 국가 공식은 분모가 `N²+1`이라 `N = 0`이어도 나눗셈이 죽지는 않지만, 같은 이유로 먼저 접는다.

#### ⑨ DTO 재사용 범위

`/movie/{id}`의 `genres[]` 항목은 `/genre/movie/list`의 항목과 구조가 같다(`{id, name}`).
**`TmdbGenreListResponse.Item`을 재사용**하고 별도 `TmdbGenreDto`를 만들지 않는다.
`production_countries[]`는 `{iso_3166_1, name}`으로 6-1의 `TmdbCountryListItem`
(`{iso_3166_1, english_name, native_name}`)과 **구조가 다르므로 별도 DTO**가 필요하다.

### `Person` / `MovieActor` / `MovieDirector`

- `Person.of(tmdbPersonId, name, profilePath)` — `uk_person_tmdb_id` 기준 upsert,
  존재하면 `updateProfile(name, profilePath)`
  - `name`이 비면 **`original_name` 폴백** (not null이라 비우면 저장 실패, ⑦ 참고)
- `MovieActor` ← `credits.cast[]` — `character` → `characterName`, `order` → **`displayOrder`(원본 보존)
  + `roleTier`(D-1 경계값으로 파생)**. cast는 자르지 않고 전량 저장한다.
  - ⚠️ `order`는 TMDB 응답에서 **연속 정수라는 보장이 없다.** 배열 인덱스가 아니라 `order`
    필드값을 그대로 저장하고, tier 판정도 그 값 기준이다.
  - ⚠️ `uk_movie_actor (movie_id, person_id)` — **같은 배우가 1인 2역으로 cast에 두 번
    등장하면 유니크 위반이다.** `personId` 기준으로 `order`가 가장 작은 항목만 남긴다.
  - ⚠️ **`cast[].id`(사람 ID)와 `cast[].cast_id`(크레딧 ID)를 혼동하지 말 것** — ⑤ 참고
- `MovieDirector` ← `credits.crew[]` 중 **`job == "Director"`** 만
  - ⚠️ `department == "Directing"`으로 거르면 조감독·스크립트 등이 함께 들어온다. `job` 기준으로 정확히 건다.
  - ⚠️ **감독도 `personId` 중복 제거가 필요하다** — 같은 사람이 `job == "Director"`로 두 번
    나오면 `uk_movie_director` 위반이다 (⑥ 참고).

### `MovieGenre` / `MovieCountry`

- `weight`는 **`BigDecimal(4,3)`** 이다. **`RoundingMode.HALF_UP`으로 소수 셋째 자리 반올림**하며,
  `1/3` 같은 값은 `0.333`으로 저장된다 — **가중치 합이 정확히 1이 되지 않는 경우가 정상**이다.
  집계 쿼리는 합이 1임을 전제하지 않는다.
- ⚠️ **`genres`/`production_countries`가 빈 배열인 영화가 있다.** `1/N`을 루프 밖에서 미리 계산하면
  `ArithmeticException`이므로 빈 리스트는 매핑을 만들지 않고 즉시 반환한다 (⑧ 참고).
- `genres[]`는 6-1의 `TmdbGenreListResponse.Item`을 재사용한다. `production_countries[]`는
  구조가 달라 별도 DTO가 필요하다 (⑨ 참고).

---

## 6-4. `MovieSyncService` 구현 (✅ 확정 2026-08-19)

> **4-2에서 확정한 시그니처를 폐기한다.** 4-2는 D-1·D-3 확정 이전에 작성돼
> **그대로는 구현이 불가능하다** — `syncCountries(Movie, List<TmdbCountryDto>)`에
> D-3이 요구하는 `origin_country`를 넘길 파라미터가 없다.
> `service-layer-spec.md` 4-2도 함께 갱신한다.

### 클래스 구성 — 빈 2개

```java
@Service
@RequiredArgsConstructor
public class MovieSyncService {                 // ⚠️ 트랜잭션 없음
    private final TmdbClient tmdbClient;
    private final MovieSyncPersister persister;

    public Movie syncFromTmdb(Long tmdbId) {
        var detail = tmdbClient.fetchMovieDetail(tmdbId);   // 네트워크 — 커넥션 미보유
        return persister.persist(detail);                   // 프록시 경유 → @Transactional 적용
    }
}
```

**공개 메서드는 `syncFromTmdb` 하나뿐이다.** 초안의 `syncGenres`/`syncCountries`/`syncCast`/
`syncCrew`는 전부 내부 단계이므로 `MovieSyncPersister`의 private으로 내린다.

#### 왜 public 4개를 없애는가 — 트랜잭션이 쪼개진다

public으로 남기면 각각에 `@Transactional`을 붙이게 되고, **한 영화의 동기화가 4개
트랜잭션으로 분해된다.** 재동기화가 "전량 삭제 후 재삽입"이라 이 조합이 특히 위험하다.

```
applyGenres    커밋 ✔
applyCountries 커밋 ✔
applyCast      실패 ✘   ← movie_actor는 삭제만 되고 재삽입이 안 된 상태로 커밋돼 있다
```

출연진이 통째로 사라진 영화가 남고 다음 재동기화 전까지 아무도 모른다.
private으로 두면 자기호출이라 `@Transactional`이 애초에 걸리지 않아, **트랜잭션 경계가
`persist()` 하나로 문법적으로 강제된다.**

#### 왜 빈을 2개로 나누는가 — 자기호출 함정

HTTP 호출을 트랜잭션 밖으로 빼야 한다. `syncFromTmdb` 전체에 `@Transactional`을 붙이면
**TMDB 왕복이 DB 트랜잭션 안에서 일어나** 커넥션을 쥔 채 네트워크를 기다린다. 시드로
수백 편을 도는 순간 커넥션 풀이 마른다.

그런데 같은 클래스 안에서 `fetch()` → `persist()`로 나누면 **자기호출이라 프록시를 타지
않아 `@Transactional`이 무시된다.** 예외도 경고도 없이 트랜잭션 0개가 되어, 위 시나리오보다
더 나빠진다. **빈을 분리해야 프록시를 경유한다.**

`TransactionTemplate`도 같은 문제를 풀지만, 이 프로젝트가 선언적 트랜잭션으로 일관돼 있어
여기만 예외를 두지 않는다.

### `MovieSyncPersister` — 5단계

```java
@Service
@RequiredArgsConstructor
public class MovieSyncPersister {

    @Transactional
    public Movie persist(TmdbMovieDetailResponse detail) { ... }

    private Map<Long, Person> upsertPersons(List<TmdbCast> cast, List<TmdbCrew> crew);
    private void applyGenres(Movie movie, List<TmdbGenreListResponse.Item> genres);
    private void applyCountries(Movie movie, List<TmdbProductionCountry> productionCountries,
                                List<String> originCountry);
    private void applyCast(Movie movie, List<TmdbCast> cast, Map<Long, Person> personIndex);
    private void applyDirectors(Movie movie, List<TmdbCrew> crew, Map<Long, Person> personIndex);
}
```

| 순서 | 단계 | 내용 |
|---|---|---|
| 1 | **`adult` 검사** | `adult == true`면 `ADULT_CONTENT_NOT_ALLOWED`. **매핑보다 먼저** — `Person` upsert가 먼저 돌면 거부된 영화의 출연진이 `person`에 남는다 |
| 2 | **`Movie` upsert** | `findByTmdbId` → 있으면 `updateMetadata(title, posterPath, overview, runtime, releaseDate)`, 없으면 `@Builder`로 생성·저장 |
| 3 | **`Person` 통합 upsert** | **cast ∪ directors 합집합**을 `findByTmdbPersonIdIn`으로 1쿼리 조회 → 없는 것만 생성. `Map<tmdbPersonId, Person>` 반환 |
| 4 | **매핑 4종 삭제** | `deleteByMovieId` 벌크 DML ×4 |
| 5 | **매핑 4종 재삽입** | `applyGenres` / `applyCountries` / `applyCast` / `applyDirectors` |

`persist()` **전체가 한 트랜잭션**이다. 3단계의 `Person` 조회가 트랜잭션 밖이면 detached라
`updateProfile()`의 dirty checking이 반영되지 않는다 — 조용히 안 되는 유형이다.

#### ⚠️ 3단계를 분리한 이유 — 배우 겸 감독

초안처럼 `syncCast`와 `syncCrew`가 각각 `Person`을 upsert하면 **배우이면서 감독인 사람**에서
`uk_person_tmdb_id`를 위반한다.

```
applyCast      : Person(X) 조회 → 없음 → 신규 저장 (아직 flush 전)
applyDirectors : Person(X) 조회 → 없음 → 또 신규 저장 → 유니크 위반
```

같은 트랜잭션에서 flush 전이라 두 번째 조회가 첫 번째 저장을 보지 못한다. 흔한 케이스라
시드를 조금만 돌려도 반드시 재현된다. **성능(N+1)이 아니라 정합성 때문에 통합해야 한다.**

부수 효과로 N+1도 사라진다 — cast 200명이면 건별 조회가 200회였다.
`PersonRepository.findByTmdbPersonIdIn`이 필요하다(현재 비어 있음).

### 재동기화 시 매핑 테이블 처리 — 벌크 DML

**"전량 삭제 후 재삽입".** `movie_genre` 등은 TMDB가 진실의 원천이고 우리가 보정하는 값이
없으므로 diff를 계산할 실익이 없다. `uk_movie_genre` 등 유니크 제약이 있어 삭제 없이
재삽입하면 위반이 난다.

```java
@Modifying(flushAutomatically = true)
@Query("delete from MovieActor ma where ma.movie.id = :movieId")
void deleteByMovieId(@Param("movieId") Long movieId);
```

**파생 쿼리(`void deleteByMovieId(Long)`)를 쓰지 않는 이유가 둘이다.**

1. Spring Data의 파생 delete는 **엔티티를 전부 select한 뒤 하나씩 `remove()`** 한다.
   출연진 200명이면 select 1 + delete 200회다.
2. Hibernate의 ActionQueue는 **INSERT를 DELETE보다 먼저** 내보낸다. 같은 flush에 재삽입이
   섞이면 유니크 위반이 난다.

> **벌크 DML의 통상적 위험(stale 관리 엔티티)이 이 프로젝트엔 없다.** `CLAUDE.md`가 양방향
> 컬렉션(`@OneToMany`)을 금지해서 `Movie`를 로딩해도 매핑 엔티티가 영속성 컨텍스트에
> 딸려 오지 않기 때문이다. 기존 아키텍처 결정이 여기서 값을 한다.
>
> ⚠️ **`clearAutomatically = true`를 쓰면 안 된다.** 컨텍스트를 통째로 비워
> **작업 중인 `Movie`가 detach되고** `updateMetadata()`의 dirty checking이 사라진다.

### 멱등 계약 — 주체별로 다르다

D-2가 *"이미 적재된 `tmdbId`는 건너뛴다"* 고 했는데 `syncFromTmdb`는 항상 재동기화한다.
충돌이 아니라 **책임이 다른 것**이므로 명시한다.

| 주체 | 계약 |
|---|---|
| `syncFromTmdb` | **항상** TMDB를 호출해 최신 상태로 덮어쓴다. 건너뛰지 않는다 |
| `MovieSeedService` | `existsByTmdbId`로 **사전 필터**해 이미 있으면 호출조차 하지 않는다 (rate limit 절약) |
| `POST /api/movies/sync` | 필터하지 않는다 — 사용자가 명시적으로 요청한 것이므로 최신화가 맞다 |

### 실패 격리 — 호출부 책임

`syncFromTmdb`는 실패하면 **그냥 던지고 자기 영화 하나만 롤백**한다. D-2의
"트랜잭션 경계 = 영화 1편"은 `MovieSeedService`가 편별로 `try-catch`해서 `skipped`로
집계한다는 뜻이다. 시드 전체를 한 트랜잭션으로 묶으면 중간 실패 시 수백 편이 롤백되어
멱등 이어받기 설계가 무의미해진다.

### 이미 저장된 영화가 `adult`로 바뀐 경우

**예외를 던지고 `WARN`을 남길 뿐, 삭제하지 않는다.** 선택의 여지가 없다 —
`watch_record` / `review` / `wish_movie` / `collection_movie`의 FK가 전부 **RESTRICT**라
사용자가 한 명이라도 담았으면 **삭제 자체가 불가능**하다. 기존 데이터는 두고 운영자가
개별 판단한다.

### 한국어 데이터 폴백 (6-3 ⑦ 확정 반영)

`language=ko-KR`로 요청해도 값이 빈 문자열로 오는 경우가 있다.
**`language` 없이 재요청하는 방식은 채택하지 않는다** — 영화당 왕복이 2배가 된다.
같은 응답 안의 원어 필드로 폴백한다.

| 필드 | not null | 폴백 |
|---|---|---|
| `title` | ✅ | `original_title` |
| `person.name` | ✅ | `original_name` (`cast[]`/`crew[]`에 이미 들어 있다) |
| `overview` | — | 없음. nullable이라 비어도 무방 |

### `Movie.updateMetadata` 파라미터 순서 주의

```java
public void updateMetadata(String title, String posterPath, String overview,
                           Integer runtime, LocalDate releaseDate)
```

⚠️ **`title`/`posterPath`/`overview`가 같은 타입으로 연속**이라 순서를 바꿔도 컴파일된다.
포스터 경로가 제목 자리에 들어가는 유형의 사고가 가능하다. 값 객체를 만들지 않는 이유는
레코드도 위치 기반이라 위험이 생성 지점으로 옮겨갈 뿐이기 때문이다.
호출부가 `MovieSyncPersister` 한 곳뿐이라 위험이 국소적이므로, **`@Builder` 필드 순서와
동일하게 유지**해 리뷰로 잡는다.

---

## 6-5. 적재 전략 (D-2 확정 반영)

| 경로 | 진입점 | 성격 |
|---|---|---|
| 참조 테이블 | `POST /api/admin/reference-data/seed` | `GenreSeedService` + `CountrySeedService`(6-1) 호출. **영화 시드보다 먼저 실행해야 한다** |
| 시드 (주) | `POST /api/admin/movies/seed/box-office` | 박스오피스 역방향. **1회성·멱등** |
| 시드 (보) | `POST /api/admin/movies/seed/discover` | `region=KR` 인기작 보충. **1회성·멱등** |
| 온디맨드 | `POST /api/movies/sync` | 검색 결과에서 미등록 영화 선택 시. `syncFromTmdb(tmdbId)` 후 `movieId` 반환 |

- 관리자 엔드포인트는 `domain/movie/controller`에 둔다
  (5-6-C ③에서 정한 **"패키지는 Service 소유, 경로는 별개"** 기준).
- 시드 2종을 하나의 엔드포인트로 합치지 않는 이유 — **실패 양상이 다르다.** 역방향은
  제목 매칭 실패가 정상 범주이고(건너뛰고 집계), discover는 페이지 순회 실패라
  이어받기 지점이 다르다. 결과 DTO도 달라진다.

### 박스오피스 역방향 시드

```
1. box_office_record 에서 movie_id IS NULL 인 (movieTitleSnapshot, openDate) DISTINCT 조회
2. 각 건에 대해 /search/movie?query={제목}&year={openDate의 연도}
3. 첫 결과의 tmdbId 로 syncFromTmdb  (이미 있으면 건너뜀)
4. 4-7 재매칭 배치가 이후 movie_id 를 채운다  ← 역방향 시드는 movie 를 만들 뿐,
                                                 직접 연결하지 않는다
```

- ⚠️ **3단계에서 `movie_id`를 직접 채우지 않는다.** 매칭 책임은 4-7 재매칭 배치 하나로
  유지한다. 두 곳에서 채우면 매칭 규칙이 이원화되고 어느 쪽이 채웠는지 추적할 수 없다.
- ⚠️ **제목 매칭 실패는 예외가 아니다.** KOFIC 한글 제목과 TMDB `ko-KR` 제목은 부제 유무·
  띄어쓰기·시리즈 표기에서 갈린다. 실패는 건너뛰고 `matched / skipped` 카운트로 반환한다.
  한 편 때문에 시드 전체가 멈추면 안 된다.
- `openDate`는 v10에서 4-7 2순위 매칭용으로 추가한 컬럼이다. `year` 파라미터로 넘겨
  동명 영화 오탐을 줄인다. **`openDate`가 NULL인 옛 레코드는 `year` 없이 조회**한다.

### Rate limit

시드로 수백 편을 연속 호출하면 스로틀링에 걸릴 수 있다.

- 요청 간 간격 또는 배치 크기 제한을 둔다.
- 실패 시 이어받기가 가능하도록 **이미 적재된 `tmdbId`를 건너뛰는 멱등 구조**로 만든다
  (4-7 박스오피스의 "기존 키 집합 조회 → 차집합만 저장" 패턴 재사용).
- `append_to_response=credits`(6-2)로 영화당 왕복이 1회다. 편당 2회로 부르면
  같은 시드가 두 배 걸린다.

### 온디맨드 — 검색 병합

```
1. GET  /api/movies/search?query=...
       DB 검색 + TMDB 검색을 병합. tmdbId 기준 중복 제거(DB 쪽 우선, movieId 보존)
2. POST /api/movies/sync { tmdbId }        ← 사용자가 미등록 항목을 선택했을 때만
       syncFromTmdb 후 movieId 반환
3. 프론트가 그 movieId 로 시청기록·위시 등을 생성
```

- **검색 시점에 결과 전체를 동기화하지 않는다.** 20편 중 1편만 선택되는 것이 보통이라
  rate limit과 쓰레기 데이터 양쪽에서 손해다.
- 응답에 `movieId`(nullable) + `tmdbId`를 함께 내린다. `movieId == null`이 "미등록" 신호다.
- `POST /api/movies/sync`는 **인증 필요**하다. 미인증 공개 경로로 두면 임의의 `tmdbId`로
  우리 DB를 채우는 통로가 된다.
- ⚠️ **페이징 의미가 깨진다** — 두 출처 병합이라 `totalElements`가 성립하지 않는다.
  `Slice` 또는 별도 응답 형태가 필요하다(잔여 #5, `MovieSearchCondition`과 함께 확정).

---

## 6-6. ErrorCode 추가분 (초안)

| 상수 | HTTP | 용도 |
|---|---|---|
| `TMDB_MOVIE_NOT_FOUND` | 404 | TMDB에 해당 `tmdbId`가 없음 — `POST /api/movies/sync`에서 사용자가 잘못된 `tmdbId`를 보낼 수 있으므로 4xx가 맞다 |
| `ADULT_CONTENT_NOT_ALLOWED` | 400 | `adult == true` 영화 동기화 거부 (6-3 ①). 사용자가 보낸 `tmdbId`에 대한 응답이므로 4xx |
| `GENRE_NOT_FOUND` | 500 | 참조 테이블 미적재 — 사용자 잘못이 아니므로 5xx |
| `COUNTRY_NOT_FOUND` | 500 | 동일 |

> `EXTERNAL_API_ERROR`(4-7)는 재사용한다.

---

## 잔여 확인 항목

| # | 항목 | 처리 시점 |
|---|---|---|
| 1 | ~~6-0 미결 4건 확정 (D-1 / D-2 / D-3 / D-4)~~ | ✅ **전부 확정** (2026-08-13) |
| 1-b | ~~v11 스키마 델타 적용~~ | ✅ **적용 완료** (단 `display_order`가 `smallint`로 적용됨 → v12에서 교정) |
| 1-c | **v12 스키마 델타 적용** — `display_order` `smallint`→`int` 정오 + `movie.overview` `4000`→`1000` 롤백 | **엔티티 반영 전 (필수, `ddl-auto=validate`)** |
| 2 | ~~`Movie.releaseDate` 수정 메서드 부재~~ | ✅ **종결** (6-3 ③ — `updateMetadata`에 포함) |
| 3 | ~~매핑 4종 Repository에 `deleteByMovieId` 추가~~ — 파생 쿼리가 아니라 `@Modifying(flushAutomatically = true) @Query` 벌크 DML로. `clearAutomatically`는 쓰지 않는다 (6-4) | ✅ **구현 완료** (2026-08-20) |
| 4 | 한국어 `title`/`overview` 빈 응답 폴백 방식 | 소규모 시드 후 실데이터 기준 판단 |
| 5 | **`searchMovies` / `MovieSearchCondition` 설계** — D-2 ③ 확정으로 **선행 조건이 됐다.** ① DB+TMDB 병합 결과의 응답 DTO(`movieId` nullable + `tmdbId`), ② 병합으로 `totalElements`가 성립하지 않는 문제(`Slice` 등), ③ `tmdbId` 기준 중복 제거 규칙 | **온디맨드 경로 착수 전 (필수)** |
| 6 | TMDB rate limit 실측 후 시드 배치 간격 조정 | 시드 최초 실행 시 |
| 7 | **영화 상세의 cast 응답 분리** — cast 전량 저장 확정으로 `getMovieDetail`이 최대 수백 행을 그대로 내려보낸다. 상세는 `displayOrder <= 20`으로 제한하고 전체 출연진은 `GET /api/movies/{id}/cast`(페이징)로 분리 | 6-4 구현 시 (controller 잔여 #10과 동일 건) |
| 8 | **`movie_actor` 행 수 증가에 따른 인덱스 검토** — 전량 저장으로 영화당 행 수가 20 → 최대 수백이 된다. `fk_movie_actor_person`은 있으나 `(movie_id, display_order)` 정렬 인덱스가 없어 상세 조회가 filesort로 갈 수 있다 | 시드 적재 후 실측 기준 판단 |
| 9 | **`POST /api/movies/sync` 시큐리티 화이트리스트 검토** — 인증 필요 경로다. 미인증 공개로 두면 임의 `tmdbId`로 우리 DB를 채우는 통로가 된다. 5-0 화이트리스트 회귀 테스트(5-7 A)에 함께 반영 | 온디맨드 경로 구현 시 |
| 10 | **박스오피스 역방향 시드의 제목 매칭 실패율 실측** — KOFIC 한글 제목과 TMDB `ko-KR` 제목이 부제·띄어쓰기·시리즈 표기에서 갈린다. `skipped` 비율이 높으면 정규화 규칙(공백 제거, 부제 분리)이 필요하다 | 시드 최초 실행 후 |
| 11 | **길이 초과 `WARN` 로그 실측 후 컬럼 확장 판단** (6-3 ④) — `title`(255) / `overview`(1000) / `person.name`(100) / `character_name`(100) 네 곳. 추정으로 넓히지 않고 첫 시드가 실제 분포를 측정하게 한다. `character_name`이 유일하게 근거 있는 후보다(TMDB가 다역을 슬래시로 연결) | 시드 최초 실행 후 |
| 12 | **`getMovieList` projection 전환 검토** — `movieRepository.findAll(pageable)`이 `Movie` 엔티티 전체를 로딩하는데 `MovieListItemResponse`는 `id`/`title`/`posterPath`/`releaseDate`만 쓴다. `overview`가 목록 조회에서 DB→앱까지 실려 왔다가 버려진다. 한 페이지 20건 × 최대 1000자면 60KB 안팎이라 **체감 지연 규모는 아니지만** 쓰지 않는 컬럼을 읽는 것은 맞다. projection 인터페이스 또는 DTO 직접 조회로 SELECT에서 제외 | 목록 화면 성능 이슈가 실제로 관측되면 (선제 최적화 금지) |
| 13 | ~~`PersonRepository.findByTmdbPersonIdIn` 추가~~ — 현재 리포지토리가 비어 있다. 6-4의 3단계(`Person` 통합 upsert)가 이것 없이는 N+1이다 | ✅ **구현 완료** (2026-08-20) |
| 14 | ~~`Person.updateProfile()`에 값 비교 추가~~ — `Genre.rename()`·`Country.rename()`과 달리 무조건 대입이라 재동기화마다 출연진 전원 UPDATE가 나간다. `profilePath`가 nullable이므로 `Objects.equals`를 쓸 것 | ✅ **구현 완료** (2026-08-20) |
| 15 | ~~`RoleTier.fromDisplayOrder(int)` 정적 팩토리 추가~~ — D-1 경계값 파생을 서비스 private이 아니라 enum에 둔다. `displayOrder`는 이미 `MovieActor`의 필드라 도메인 개념이고, 경계값과 가중치가 한 파일에 모이며 M3 추천에서 재사용된다 (`jpa-entity-spec.md`의 "판정 로직은 서비스가 갖는다" 기술을 뒤집는 것) | ✅ **구현 완료** (2026-08-20) |
| 16 | ~~`Movie.updateMetadata`에 `releaseDate` 파라미터 추가~~ — 6-3 ③ 확정. `@Builder` 필드 순서와 동일하게 유지할 것 | ✅ **구현 완료** (2026-08-20) |

---

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-08-20 | **6-3·6-4 코드 구현 완료.** 확정된 스펙 그대로 구현했으며 설계 변경은 없다. **신규** — `TmdbMovieDetailResponse`/`TmdbCredits`/`TmdbCast`/`TmdbCrew`/`TmdbProductionCountry`(6-3 DTO), `TmdbClient.fetchMovieDetail`(404 → `TMDB_MOVIE_NOT_FOUND` 구분 처리), `MovieSyncService`(무트랜잭션, `syncFromTmdb` 단일 공개 메서드), `MovieSyncPersister`(`@Transactional persist()` — adult 검사 → Movie upsert → Person 통합 upsert → 매핑 4종 벌크 삭제 → 매핑 4종 재삽입 5단계), `ErrorCode` 4종(`TMDB_MOVIE_NOT_FOUND`/`ADULT_CONTENT_NOT_ALLOWED`/`GENRE_NOT_FOUND`/`COUNTRY_NOT_FOUND`). **잔여 #3·#13·#14·#15·#16 종결** — `MovieActor`/`MovieGenre`/`MovieCountry`/`MovieDirector` 4종 Repository에 `@Modifying(flushAutomatically=true) @Query` 벌크 `deleteByMovieId` 추가, `PersonRepository.findByTmdbPersonIdIn` 추가, `Person.updateProfile()`에 `Objects.equals` 값 비교 추가, `RoleTier.fromDisplayOrder(int)` 정적 팩토리 추가, `Movie.updateMetadata`에 `releaseDate` 파라미터 추가. **범위에서 제외** — 6-5 관리자 엔드포인트(`POST /api/admin/movies/seed/*`, `POST /api/movies/sync`)와 `MovieSeedService`는 이번 세션에 포함되지 않았다. `getMovieDetail`의 cast 전체 응답 분리(잔여 #7)도 컨트롤러 계층 건이라 제외. 스키마는 이미 v12 기준으로 작성된 엔티티(`Movie.overview length=1000`, `MovieActor.displayOrder` `Integer`)를 그대로 사용했고, 이번 세션에서 DB에 v12 델타를 직접 적용하지는 않았다(잔여 1-c 그대로 유지) — 실행 전 `docs/schema/v12-delta.sql` 적용 필요 |
| 2026-08-19 | **6-4 확정 — 4-2 시그니처 폐기.** 초안이 유지하려던 4-2 시그니처는 **그대로는 구현이 불가능**했다. `syncCountries(Movie, List<TmdbCountryDto>)`에 D-3이 요구하는 **`origin_country`를 넘길 파라미터가 없다** — 4-2가 D-1·D-3 확정 이전에 작성된 탓이다. 이를 계기로 구조를 다시 봤다. ① **공개 메서드를 `syncFromTmdb` 하나로 축소**하고 나머지 4개는 private으로 내렸다. public으로 남기면 각각에 `@Transactional`을 붙이게 되어 **한 영화 동기화가 4개 트랜잭션으로 분해**되는데, 재동기화가 "전량 삭제 후 재삽입"이라 중간 실패 시 **출연진이 삭제만 되고 재삽입이 안 된 상태로 커밋**된다. private이면 자기호출이라 `@Transactional`이 걸리지 않아 경계가 하나로 문법적으로 강제된다. ② **빈을 `MovieSyncService`(트랜잭션 없음) + `MovieSyncPersister`(`@Transactional`)로 분리** — HTTP 왕복이 트랜잭션 안에 있으면 커넥션을 쥔 채 네트워크를 기다려 시드 수백 편에서 풀이 마른다. 같은 클래스 안에서 fetch/persist로 나누는 순진한 분리는 **자기호출이라 프록시를 안 타서 `@Transactional`이 조용히 무시**되고, 트랜잭션 0개가 되어 오히려 더 나빠진다. `TransactionTemplate`도 해법이지만 프로젝트가 선언적 트랜잭션으로 일관돼 있어 채택하지 않았다. ③ **`Person` 통합 upsert를 3단계로 신설** — 초안처럼 cast/crew가 각각 upsert하면 **배우 겸 감독**에서 `uk_person_tmdb_id`를 위반한다(같은 트랜잭션 flush 전이라 두 번째 조회가 첫 번째 저장을 못 본다). 흔한 케이스라 반드시 재현된다. **성능이 아니라 정합성 요구**이며, 부수로 N+1도 사라진다. ④ **매핑 삭제를 `@Modifying` 벌크 DML로** — 파생 delete는 select 후 건별 `remove()`라 출연진 200명이면 201쿼리이고, Hibernate ActionQueue가 **INSERT를 DELETE보다 먼저** 내보내 재삽입과 섞이면 유니크 위반이 난다. 통상 벌크 DML의 stale 엔티티 위험은 **CLAUDE.md가 양방향 컬렉션을 금지한 덕에 이 프로젝트엔 없다.** `clearAutomatically = true`는 금지 — 작업 중인 `Movie`가 detach돼 dirty checking이 사라진다. ⑤ **멱등 계약을 주체별로 분리 명시** — `syncFromTmdb`는 항상 최신화, 건너뛰기는 `MovieSeedService`의 `existsByTmdbId` 사전 필터 책임, `POST /api/movies/sync`는 필터하지 않는다. ⑥ **이미 저장된 영화가 `adult`로 바뀐 경우**는 예외+`WARN`만 — `watch_record`/`review`/`wish_movie`/`collection_movie` FK가 전부 **RESTRICT**라 삭제 자체가 불가능하다. ⑦ **한국어 폴백에서 "`language` 없이 재요청" 안을 기각** — 영화당 왕복이 2배가 된다. 같은 응답의 `original_title`/`original_name`으로 폴백한다. ⑧ **네이밍** — `Person` upsert가 빠져나가 나머지는 "동기화"가 아니라 매핑 행을 쓰는 일이므로 `applyGenres`/`applyCountries`/`applyCast`/`applyDirectors`로 바꿨다(`syncCrew` → `applyDirectors`). ⑨ **`updateMetadata` 5파라미터의 연속 `String` 3개 위험**을 명시 — 값 객체는 만들지 않는다(레코드도 위치 기반이라 위험이 옮겨갈 뿐). 호출부가 한 곳이므로 `@Builder` 순서 유지로 리뷰에서 잡는다. **잔여 #13~#16 신규 등록**(`PersonRepository`, `Person.updateProfile` 값 비교, `RoleTier.fromDisplayOrder`, `updateMetadata` 시그니처) |
| 2026-08-19 | **6-3 확정 + D-4 정정(롤백). v12 델타 신설.** TMDB 공식 OpenAPI 정의와 대조해 초안 누락 9건을 채웠다. **⚠️ D-4를 되돌렸다** — *"TMDB overview가 1000자를 넘을 수 있다"* 는 v11의 전제가 **사실이 아니다.** TMDB 스태프가 *"we limit movie overviews to 1000 characters"* 로 명시하고 있어, `varchar(1000)`은 임의값이 아니라 **TMDB 입력 제한에 맞춰 설계된 값**이었다. 실데이터도 공식 문서도 확인하지 않고 단정한 것이 오류다. v12에서 `varchar(1000)`으로 복원한다. **성능이 롤백 사유가 아님을 명시** — `varchar`는 가변 길이라 같은 데이터면 바이트가 같고, 선언 길이가 문제되던 경로(`MEMORY` 임시 테이블의 고정 폭 패딩, filesort 고정 폭 행)는 MySQL 8.0의 TempTable 엔진과 packed addon field로 해소됐으며 `overview`에는 인덱스도 없다. 되돌리는 이유는 **스키마가 표현하던 외부 API 계약 정보를 잃었다**는 것뿐이다. 같은 오류를 반복하지 않기 위해 **길이 초과 3곳(`title`/`person.name`/`character_name`)도 컬럼 확장 대신 절단+`WARN`으로 확정**했다(잔여 #11로 실측 후 재판단). 그 밖에 확정 — ① **`adult == true` 영화는 `syncFromTmdb` 진입점에서 거부**(`ADULT_CONTENT_NOT_ALLOWED`). discover/search는 `include_adult=false`가 기본이라 안전하지만 **`POST /api/movies/sync`는 사용자가 임의 `tmdbId`를 보내는 경로**라 필터가 없었다. 검사는 매핑보다 먼저 해야 한다 — `Person` upsert가 먼저 돌면 출연진이 `person`에 남는다. ② **`runtime == 0` → `null` 정규화** (OpenAPI에 `default: 0` 명시. 미개봉작이 `null`이 아니라 `0`으로 와서 "0분"이 표시된다). ③ **`releaseDate`를 `updateMetadata`에 포함**(잔여 #2 종결) — 시드가 미개봉작을 담는 이상 개봉일 확정·정정이 반드시 발생한다. ④ **`cast[].id`(사람 ID)와 `cast[].cast_id`(크레딧 ID) 혼동 경고** — 후자를 `Person.tmdbPersonId`에 넣으면 **예외 없이** 잘못된 인물이 쌓인다. ⑤ **감독 중복 제거** — 같은 사람이 `job == "Director"`로 두 번 나오면 `uk_movie_director` 위반인데 초안은 cast의 1인 2역만 다뤘다. ⑥ **`person.name`도 한국어 폴백 필요**(`original_name`) — 6-4 폴백 절이 `title`/`overview`만 다뤘으나 `person.name`도 not null이다. ⑦ **`RoundingMode.HALF_UP` 명시** 및 **장르 빈 배열 시 `1/N`의 `ArithmeticException` 가드**. ⑧ **DTO 재사용 범위** — `/movie/{id}`의 `genres[]`는 6-1의 `TmdbGenreListResponse.Item`과 구조가 같아 재사용, `production_countries[]`는 `{iso_3166_1, name}`으로 `TmdbCountryListItem`과 달라 별도 DTO. ⑨ **절단 헬퍼의 서로게이트 페어 주의** — `substring`이 페어 중간을 자르면 깨진 문자가 저장된다. **부수 등록** — 잔여 #12(`getMovieList`가 목록에 쓰지 않는 `overview`까지 엔티티 전체를 로딩. 60KB 안팎이라 체감 규모는 아니므로 **선제 최적화 금지**로 조건부 등록) |
| 2026-08-13 | **6-1·6-2 구현 완료 및 구현 중 조정 4건.** ① **`Country.rename()` 신설** — 6-1 초안이 *"둘 다 멱등 upsert — 이미 있으면 `Genre.rename()`으로 이름만 갱신"* 이라 적었으나 **`Country`에는 그 메서드가 없었다**(스펙 공백). `Genre`와 대칭으로 추가했다. 국가명은 거의 변하지 않지만 TMDB의 한국어 지역화가 시간이 지나며 채워지는 경우가 있어 재적재로 반영할 수단이 필요하다. ② **`/configuration/countries`는 루트 레벨 JSON 배열**이다(공식 OpenAPI 정의로 확인). `/genre/movie/list`가 `{genres:[...]}` 래퍼인 것과 달라 `ParameterizedTypeReference<List<...>>`로 받아야 한다. 또 필드가 snake_case(`iso_3166_1`)인데 이 프로젝트는 Jackson 네이밍 전략을 바꾸지 않았으므로(`KoficDailyBoxOfficeResponse`는 KOBIS가 camelCase라 우연히 문제가 없었다) **`@JsonProperty` 명시 매핑**이 필요하다. ③ **국가명 `native_name` → `english_name` 폴백** — `language=ko-KR`로 요청해도 TMDB 국가명 지역화 범위가 완전하지 않아 비거나 영문으로 온다. `country.name`이 not null이라 폴백 없이는 저장이 실패한다. ④ **설정 키 정리** — `application-secret.yml`에 삭제된 `TestController` 잔재로 `tmdb.api.token`이 남아 있어 `kofic`과 같은 2단 구조인 **`tmdb.access-token`** 으로 맞추고 `application.yml`에 `tmdb.base-url`을 추가했다. 부수로 **저장 전 필터링**(코드 길이 2, 이름 공백, 키 중복)을 넣었다 — 셋 다 `DataIntegrityViolationException`으로 **트랜잭션 전체가 롤백되는** 유형이라 한 건 때문에 정상 250여 건이 통째로 날아간다(4-7 `hasRequiredFields`와 같은 원칙). **호출 진입점(`POST /api/admin/reference-data/seed`)은 붙이지 않았다** — 관리자 엔드포인트는 6-5에 시드 3종으로 함께 정의돼 있어 그쪽에서 한 번에 처리한다 |
| 2026-08-13 | **D-2·D-3·D-4 확정 — 6-0 미결 4건 종결.** **D-2 = C안(하이브리드)**, 세부 3건 확정. ① **시드 대상에서 `/movie/popular`(문서 원안)을 기각** — 전역 인기작이라 할리우드 위주로 채워지는데, `box_office_record`가 전량 `movie_id = NULL`로 쌓이는 현 상황에서 시드가 할리우드면 **콜드 스타트만 풀리고 4-7 재매칭은 여전히 아무것도 못 맞춰 홈 화면 박스오피스가 그대로 빈다.** 두 문제 중 하나만 푸는 셈이라, 문서에 없던 **박스오피스 역방향 시드**(미매칭 `movieTitleSnapshot` + `openDate`로 `/search/movie` 역조회)를 주 경로로 신설하고 `/discover?region=KR`을 보조로 병행한다. 역방향 시드는 **`movie`를 만들 뿐 `movie_id`를 직접 채우지 않는다** — 매칭 책임을 4-7 배치 하나로 유지해야 규칙이 이원화되지 않는다. 제목 매칭 실패는 예외가 아니라 `skipped` 집계(한 편 때문에 시드 전체가 멈추면 안 된다). ② **1회성 관리자 엔드포인트**(4-7 `TheaterSeedService` 패턴, 멱등) — 주기 배치를 두지 않는 이유는 지속적 보충을 이미 온디맨드가 맡기 때문이고, 인기작 주기 갱신은 사용자가 실제로 기록하는 영화와 무관하게 rate limit만 상시 소모한다. 실패 양상이 달라(역방향=제목 매칭 실패 / discover=페이지 순회) 엔드포인트를 2개로 분리. ③ **검색은 DB+TMDB 병합, 선택 시에만 동기화** — 검색 20편을 미리 동기화하면 보통 1편만 선택되므로 rate limit·쓰레기 데이터 양쪽에서 손해다. **부수 발견: 응답 DTO 계약이 깨진다** — 미등록 항목이 섞여 `movieId`가 없는 행이 생기므로 `movieId`를 nullable로 두고 `tmdbId`를 병기하며, 두 출처 병합이라 **`totalElements`가 성립하지 않아 `Slice` 등 별도 형태가 필요**하다. 이로써 `MovieSearchCondition` 설계가 보류 항목에서 **선행 조건으로 승격**(잔여 #5). `POST /api/movies/sync`는 인증 필수(잔여 #9). **D-3 = B안** — `origin_country` 우선, 없으면 배열 첫 번째. 구현 주의 3건 추가: `origin_country`는 **단일 값이 아니라 배열**이라 `[0]`을 쓸 것, **`origin_country`가 `production_countries`에 없을 수 있고** 그 경우 대표를 세우면 나머지 가중치의 분모 N과 어긋나므로 폴백할 것, `N=1`이면 공식이 1.0으로 수렴해 판정이 무의미. **D-4 = 컬럼 확장(`varchar(1000)` → `varchar(4000)`)** — 원안 A(절단)의 근거가 *"스키마 변경은 별도 승인 필요"* 였고 승인을 받았다. **v11 델타가 미적용 상태라 묶는 비용이 0**인 시점. `TEXT` 대신 `varchar(4000)`을 택한 이유는 ① 현 스키마에 TEXT 컬럼이 하나도 없어 동질성이 깨지고 ② `@Column(columnDefinition = "TEXT")`가 매핑 타입(VARCHAR)과 실제 컬럼 타입(LONGVARCHAR) 불일치로 **`ddl-auto=validate` 기동 실패를 유발할 수 있어** `length = 65535` 우회가 필요한 반면, `varchar(4000)`은 `length = 4000` 한 줄로 끝나고 리스크가 없기 때문. 절단 로직은 제거하지 않고 `3997자 + "..."` + **`WARN` 로그**로 남긴다 — 발동 자체가 "상한 가정이 틀렸다"는 신호이고, 조용히 데이터를 잃거나 배치가 죽는 쪽이 더 나쁘다 |
| 2026-08-13 | **D-1 확정 — 절대 순번 `0~4 LEAD / 5~9 SUPPORTING / 10~20 MINOR / 21~ EXTRA`, cast 전량 저장.** 초안의 권장안은 "절대 순번 + 상위 20명 컷"이었으나, **컷은 사용자의 출연진 정보 확인을 제약**하므로 채택하지 않았다. 대신 **표시 범위와 가중치 범위를 분리** — 전량 저장하되 21번 이후에 가중치 0인 `EXTRA`를 부여해 추천 집계에서 구조적으로 배제한다. 이로써 영화당 배우 선호 기여 총점이 출연진 수와 무관하게 **5.6으로 고정**되어, D-1을 연 원인인 "MINOR 총합이 LEAD 총합을 넘는 오염"이 정의상 발생하지 않는다. **`EXTRA` enum을 택하고 `role_tier` nullable화를 기각한 이유** — null은 소비 지점 전체로 전파되고, 특히 `ORDER BY role_tier ASC`에서 MySQL이 NULL을 맨 앞에 놓아 단역이 최상단에 오는 버그가 즉시 발생한다. 가중치 0.0은 집계 필터를 잊어도 오염이 0이라 규칙을 데이터에 고정한다. **`display_order` 컬럼을 함께 추가** — tier만으로는 그룹 내부(EXTRA 180명 사이) 순서가 없고, 6-4의 재동기화가 "전량 삭제 후 재삽입"이라 `id` 삽입 순에 기대면 매 동기화마다 순서가 흔들린다. "자르지 않는다"는 결정의 목적이 순서 컬럼 없이는 달성되지 않는다. 부수 발견 — ① TMDB `order`는 연속 정수 보장이 없어 배열 인덱스가 아닌 필드값을 저장해야 함, ② 1인 2역 시 `uk_movie_actor` 위반 가능(최소 `order` 우선으로 중복 제거), ③ 전량 저장으로 `getMovieDetail` 응답 비대화(잔여 #7)와 `(movie_id, display_order)` 인덱스 부재(잔여 #8) 등록. **v11 델타 신설** |
| 2026-08-11 | **초안 작성.** 4-2에서 시그니처만 확정하고 미뤄둔 `MovieSyncService`의 구현 스펙. 착수 전 확정이 필요한 **미결 4건(6-0)** 을 분리해 앞에 배치했다. **D-1(`role_tier` 경계값)에서 기획 원안의 비율 방식에 결함을 발견** — 출연진 수 편차가 커서 상위 비율로 자르면 200명짜리 영화의 MINOR 총합(18.0)이 LEAD 총합(10.0)을 넘어 선호 배우 집계가 출연진 규모에 오염된다. 절대 순번 + 상위 N명 컷을 권장안으로 제시. **D-3** — TMDB `production_countries`가 "대표국"을 명시하지 않아 기획노트의 `(N+1)/(N²+1)` 공식을 그대로 적용할 수 없음을 확인, `origin_country` 폴백 방식 제안. **D-4** — `movie.overview`가 length 1000이라 TMDB 응답이 초과 시 적재 배치가 죽는다(실데이터 전까지 드러나지 않는 유형). 그 밖에 `genre`/`country` 참조 테이블 선행 적재가 영화 동기화의 하드 선행 조건임을 6-1로 분리했고, 매핑 테이블 재동기화를 "전량 삭제 후 재삽입"으로 제안하면서 `deleteByMovieId` 부재를 잔여로 등록 |
