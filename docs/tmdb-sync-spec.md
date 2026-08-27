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
| — | ↳ ~~v12 스키마 델타 적용~~ (`display_order` 정오 + `overview` 롤백) | ✅ 적용 완료, `cinemory_backup_v12.sql` 재덤프 |
| — | ↳ ~~`MovieSearchCondition` 설계~~ | ✅ **6-8에서 종결** — 만들지 않기로 확정 (잔여 #22로 유예) |
| 6-1 | 참조 테이블 선행 적재 (`genre`, `country`) | ✅ **구현 완료** (진입점은 6-5) |
| 6-2 | `TmdbClient` 인프라 | ✅ **구현 완료** (호출 메서드는 점증) |
| 6-3 | 도메인 매핑 — TMDB 응답 ↔ 엔티티 | ✅ **구현 완료** (2026-08-20) |
| 6-4 | `MovieSyncService` 구현 | ✅ **구현 완료** (2026-08-20) |
| 6-5 | 적재 전략 (시드 / 온디맨드) | ✅ **구현 완료** (2026-08-20). `MovieSeedService`·관리자 엔드포인트 4종·온디맨드 sync 완료. 온디맨드 **검색**(`GET /api/movies/search`)은 `MovieSearchCondition` 미설계로 별도(잔여 #5) → 6-8에서 종결. **discover 시드 구성 전략**(프로필 파라미터 pass-through)은 2026-08-23 확정 → **2026-08-24 코드 반영 완료** |
| 6-6 | ErrorCode · 잔여 | ✅ **구현 완료** (2026-08-20) |
| 6-7 | **최초 시드 실측 결과** (60편) — 잔여 #4·#8·#11 판정 근거 | ✅ (2026-08-20) |
| 6-7-b | **본 시드 실측 결과** (4,609편) — 잔여 #8·#10·#11 종결, #19 재판정 | ✅ (2026-08-24) |
| 6-8 | **영화 검색 설계** — D-2 ③의 마지막 미완 항목 (잔여 #5) | ✅ **구현 완료** (2026-08-23) |
| 6-9 | **`movie` 메타데이터 보강** (v13 — 원어 제목·배경·평점) | ✅ **확정** (2026-08-23), 엔티티 반영 완료 (2026-08-24) |
| — | ↳ ~~v13 스키마 델타 적용~~ | ✅ 적용 완료, `Movie`/`TmdbMovieDetailResponse`/`MovieSyncPersister` 반영 완료 (2026-08-24) |
| — | ↳ ~~`POST /api/admin/movies/resync` 신설~~ | ✅ **구현 완료** (2026-08-24) — 잔여 #23 종결 |
| — | ↳ **v14 스키마 델타 적용** (`character_name` 100 → 255) | ✅ DB 적용·`cinemory_backup_v14.sql` 재덤프·엔티티 `@Column(length)`·`MovieSyncPersister.CHARACTER_NAME_MAX_LENGTH` 반영 완료 (2026-08-27) — 잔여 #27 종결. ✅ **`resync` 전량 실행 완료** (2026-08-27, 4,587건 갱신 / 29분 30초) — 잘린 29건 복구 및 v13 신규 컬럼 NULL 보정을 한 번에 해소. ⚠️ **255로도 부족한 3건이 새로 드러남 → 잔여 #28** |

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

#### ① 시드 대상 — 박스오피스 역방향 + `/discover` 병행

**`/movie/popular`(문서 원안)은 채택하지 않았다.** 전역 인기작이라 할리우드 위주로 채워지는데,
지금 `box_office_record`는 KOFIC에서 수집되어 **전량 `movie_id = NULL`로 쌓이는 중**이다.
시드를 할리우드로 채우면 콜드 스타트는 풀리지만 **4-7 재매칭은 여전히 아무것도 못 맞추고
홈 화면의 박스오피스는 그대로 비어 있다.** 두 문제 중 하나만 푸는 셈이다.

| 경로 | 대상 | 목적 |
|---|---|---|
| **주(主). 박스오피스 역방향** | `box_office_record`에서 `movie_id IS NULL`인 `movieTitleSnapshot` (+ `openDate`) | 콜드 스타트와 **4-7 재매칭을 동시에 해소.** "우리 사용자가 실제로 볼 영화"라 인기작 목록보다 정확 |
| 보(補). `/discover/movie` | **프로필 4종** (6-5 "discover 시드 구성 전략") | 박스오피스는 최근 흥행작으로 한정되므로 커버리지를 보충 |

> 초판은 보조 경로를 `region=KR&sort_by=popularity.desc` 하나로 적었으나, **5,000편으로
> 규모를 키우면서 프로필 4종으로 재설계**했다(2026-08-23). `popularity`가 1페이지부터
> 무명작을 섞고 `region=KR`은 한국 **개봉작**(대부분 할리우드)이라 진짜 한국 영화가
> 안 들어온다. 상세는 6-5 참고.

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

> #### ⚠️ 아래 3건은 **6-8에서 전부 철회됐다** (2026-08-20)
>
> 6-8이 응답을 `{registered, suggestions}` **2섹션으로 분리**해 두 집합을 아예 섞지 않는다.
> 그 결과 등록 여부가 **필드가 아니라 구조로** 표현되어 아래 전제가 모두 무너졌다.
> **확정본은 6-8이다.**

- ~~`movieId`를 **nullable**로 두고 `tmdbId`를 함께 내린다~~ → **불필요.** 섹션으로 구분된다
- ~~중복 제거 시 `tmdbId` 기준으로 합치고 DB 쪽을 남긴다~~ → **합칠 집합이 없다.**
  TMDB 결과에서 이미 등록된 것을 빼기만 한다
- ~~페이징이 깨져 `Slice`가 필요하다~~ → **`registered`가 완전한 `PageResponse`다.**
  5-0 규약 예외가 생기지 않는다

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
| 시드 보충 (D-2 ①) | `/discover/movie?language=ko-KR` + **프로필 파라미터** (`with_original_language` · `vote_count.gte` · `sort_by` · `primary_release_year`) — 6-5 참고 |
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

## 6-5. 적재 전략 (✅ 확정 2026-08-20)

| 경로 | 진입점 | 패키지 | 성격 |
|---|---|---|---|
| 참조 (장르) | `POST /api/admin/genres/seed` | `domain/genre/controller` | `GenreSeedService`(6-1). **영화 시드보다 먼저** |
| 참조 (국가) | `POST /api/admin/countries/seed` | `domain/country/controller` | `CountrySeedService`(6-1). **영화 시드보다 먼저** |
| 시드 (주) | `POST /api/admin/movies/seed/box-office` | `domain/movie/controller` | 박스오피스 역방향. **1회성·멱등** |
| 시드 (보) | `POST /api/admin/movies/seed/discover?pages=&lang=&minVotes=&sortBy=&year=` | `domain/movie/controller` | **프로필 4종**을 파라미터로 받아 보충 (아래 전략 절). **1회성·멱등** |
| 온디맨드 | `POST /api/movies/sync` | `domain/movie/controller` | 검색 결과에서 미등록 영화 선택 시 |

**참조 시드를 하나로 합치지 않는 이유** — 5-6-C ③의 **"패키지는 Service 소유, 경로는 별개"**
기준을 지키려면 합칠 수가 없다. 하나로 묶으면 오케스트레이션 서비스가 필요한데
`GenreSeedService`(`domain.genre`)와 `CountrySeedService`(`domain.country`) 중 어느 쪽 소유도
아니어서 소속될 도메인이 없다. 실패 양상도 다르다 — 장르 20건 대 국가 250건이고,
국가에만 ISO 코드 길이 필터가 있다. 호출이 두 번이지만 1회성이고,
하나를 빠뜨려도 **아래 가드가 둘 다 검사**하므로 영화 시드 시점에 잡힌다.

**영화 시드 2종을 합치지 않는 이유** — 실패 양상과 이어받기 지점이 다르다. 역방향은
제목 매칭 실패가 정상 범주이고(건너뛰고 집계), discover는 페이지 순회 실패라 재개 지점이 다르다.

> 초안은 분리 근거로 *"결과 DTO도 달라진다"* 를 함께 들었으나 **철회한다.** 실제로는
> 필드 구조가 같고 `skipped`의 의미만 다르다(아래 `SeedResult` 참고).

### ⚠️ 참조 테이블 가드 — 순서를 틀리면 조용히 전편 실패한다

*"참조 테이블을 먼저 실행하라"* 는 문구만으로는 부족하다. 순서를 틀리면 `syncFromTmdb`가
`GENRE_NOT_FOUND`로 죽는데, 아래 "실패 격리"에 따라 시드가 그걸 `skipped`로 집계하고
**끝까지 정상 종료**한다.

```
TMDB 시드 완료. matched=0, skipped=500, stoppedByRateLimit=false
```

로그만 보면 "제목 매칭이 다 실패했나?"로 읽힌다. 실제 원인은 `genre` 테이블이 비어 있는
것인데 진단이 엉뚱한 곳으로 간다.

**두 시드 진입점 맨 앞에서, TMDB 호출 전에 검사하고 예외로 중단한다.**

```java
if (genreRepository.count() == 0 || countryRepository.count() == 0) {
    throw new BusinessException(ErrorCode.REFERENCE_DATA_NOT_SEEDED);
}
```

- **`SeedResult`를 반환하지 않고 예외를 던진다.** 정상 종료로 보이면 안 되는 것이
  이 가드의 존재 이유다.
- **`GENRE_NOT_FOUND`를 재사용하지 않는다.** 그건 "이 장르를 못 찾음"이고 여기는
  "참조 테이블 자체가 비었음"이다. 무엇보다 메시지에 **다음 행동**이 들어가야 값을 한다 —
  *"참조 테이블이 비어 있습니다. 장르·국가 시드를 먼저 실행하세요."*
- **`MovieSyncPersister`와 온디맨드 경로에는 넣지 않는다.** 가드의 목적은 "대량 작업이
  조용히 전부 실패하는 것" 방지인데, `POST /api/movies/sync`는 단건이라 `GENRE_NOT_FOUND`가
  그대로 응답·로그에 드러나 오독 여지가 없다. `persist()`에 넣으면 시드 500편마다
  count 쿼리가 1,000번 추가된다.
- **한계 — 부분 적재는 못 잡는다.** 참조 시드가 중간에 실패해 장르가 10개만 들어가면
  `count > 0`이라 통과하고 특정 영화에서만 `GENRE_NOT_FOUND`가 난다. 다만 전편 실패가
  아니라 개별 skip으로 드러나므로 오독 위험이 낮다. "최소 기대 개수" 검사(예: `>= 10`)는
  TMDB가 장르를 줄이면 오작동하므로 두지 않는다.

### 박스오피스 역방향 시드

```
1. box_office_record 에서 movie_id IS NULL 인 (movieTitleSnapshot, openDate) DISTINCT 조회
2. 각 건에 대해 /search/movie?query={제목}&year={openDate의 연도}
3. 첫 결과의 tmdbId 로 existsByTmdbId 검사 → 이미 있으면 alreadyExists 집계 후 건너뜀
4. 없으면 syncFromTmdb(tmdbId)
5. 4-7 재매칭 배치가 이후 movie_id 를 채운다  ← 역방향 시드는 movie 를 만들 뿐,
                                                 직접 연결하지 않는다
```

- ⚠️ **`movie_id`를 직접 채우지 않는다.** 매칭 책임은 4-7 재매칭 배치 하나로 유지한다.
  두 곳에서 채우면 매칭 규칙이 이원화되고 어느 쪽이 채웠는지 추적할 수 없다.
- ⚠️ **제목 매칭 실패는 예외가 아니다.** KOFIC 한글 제목과 TMDB `ko-KR` 제목은 부제 유무·
  띄어쓰기·시리즈 표기에서 갈린다. 실패는 건너뛰고 `skipped`로 집계한다.
- `openDate`는 v10에서 4-7 2순위 매칭용으로 추가한 컬럼이다. `year` 파라미터로 넘겨
  동명 영화 오탐을 줄인다. **`openDate`가 NULL인 옛 레코드는 `year` 없이 조회**한다.

#### ⚠️ DISTINCT가 없으면 같은 영화를 수십 번 검색한다

**박스오피스는 일별 수집이라 인기작 한 편이 `box_office_record`에 수십 행으로 쌓인다.**
그대로 순회하면 같은 제목을 수십 번 TMDB에 검색한다 — rate limit이 그만큼 배로 든다.
`BoxOfficeRecordRepository`에는 현재 `findByMovieIsNull(Pageable)`밖에 없다(잔여 #17).

```java
@Query("""
    SELECT DISTINCT new com.project.cinemory...UnmatchedBoxOfficeTitle(
        b.movieTitleSnapshot, b.openDate)
    FROM BoxOfficeRecord b
    WHERE b.movie IS NULL
    ORDER BY b.openDate DESC NULLS LAST
    """)
List<UnmatchedBoxOfficeTitle> findUnmatchedTitles(Pageable pageable);
```

- **`ORDER BY openDate DESC`가 rate limit 대응과 맞물린다.** 429로 중단됐을 때
  **가치가 높은 것부터 확보**되어 있어야 한다. 최근 개봉작이 사용자에게 더 중요하다.
- 남는 틈 — `openDate`가 NULL인 옛 레코드와 채워진 레코드가 같은 영화에 섞여 있으면
  2행으로 남아 두 번 검색한다. 무해해서 감수한다.

### discover 시드 구성 전략 (✅ 확정 2026-08-23, ✅ 구현 완료 2026-08-24)

목표 규모를 **5,000편**으로 정하면서 "페이지를 늘린다"는 단순 접근을 폐기했다.

#### ⚠️ `popularity.desc`는 1페이지부터 무명작이 섞인다

TMDB 공식 예시 응답(`/discover/movie` 기본 정렬, **1페이지**)이 문제를 그대로 보여준다.

| 영화 | popularity | vote_count |
|---|---|---|
| 아바타: 물의 길 | 3,471 | **7,519** |
| 장화 신은 고양이 | 1,407 | **5,326** |
| Adrenaline | 1,269 | **4** |
| Pirates Down the Street II | 1,145 | **21** |
| Gangs of Lagos | 1,133 | **20** |

`popularity`는 **"그날의 조회·투표 활동"** 이라(6-9 참고) 일시적으로 화제인 작품이 상위로
올라온다. **250페이지까지 갈 것도 없이 1페이지부터 섞인다.**

#### 인지도 축은 `vote_count`다

`/discover/movie`가 받는 축 중 인지도를 직접 거르는 것은 **`vote_count.gte`** 다.
4표짜리와 7,519표짜리가 이걸로 갈린다.

정렬도 **`sort_by=vote_count.desc`** 가 낫다 — "많이 본 영화" = 인지도 그 자체이고,
`popularity`처럼 매일 변하지도 않는다.

> ⚠️ **한국 영화는 표가 적다.** 같은 예시의 「길복순」이 184표다. 전역 기준(300)을 그대로
> 적용하면 **한국 영화가 통째로 날아간다.** 기준을 나눠야 한다.

#### 4개 프로필

| # | 목적 | 파라미터 | 페이지 | 편수 |
|---|---|---|---|---|
| 1 | **한국 영화** | `with_original_language=ko` · `vote_count.gte=30` · `sort_by=vote_count.desc` | 50 | 1,000 |
| 2 | **전역 인지도** | `vote_count.gte=300` · `sort_by=vote_count.desc` | 100 | 2,000 |
| 3 | **최근작** | `primary_release_year=2021~2025` 각각 · `vote_count.gte=100` · `sort_by=popularity.desc` | 20 × 5 | 2,000 |
| 4 | **박스오피스 역방향** | (위 절) | — | 실제 흥행작 |

- **1번이 없으면 한국 영화가 거의 안 들어온다.** `region=KR`은 "한국에서 **개봉한**" 영화라
  대부분 할리우드다. 한국 사용자 대상 앱에서 어색하다.
  `with_original_language=ko`가 진짜 한국 영화를 뽑는다.
- **2번만 하면 오래된 명작 편중**이 된다. `vote_count.desc`는 표가 누적된 구작에 유리하다.
- **3번이 최근작을 채운다.** 전역 `popularity.desc`는 최근 2~3년에 쏠리므로 연도로 나눈다.
- 겹치는 영화는 `alreadyExists`로 빠지므로 **합계가 5,000에 정확히 맞지 않는다.**
  실제 4,000~4,500 정도로 예상한다.
- 프로필당 100페이지 이하라 **TMDB 페이지 상한(통상 500)에도 걸리지 않는다.**
  한 번에 250페이지를 도는 것보다 안전하다.

#### `region=KR`을 철회한다

초안은 `region=KR&sort_by=popularity.desc`를 하드코딩했다. `region`은 개봉일 기준을 좁혀
결과를 줄이는데, **`vote_count` 하한이 이미 품질을 보장하므로 좁힐 이유가 없다.**

#### 프로필을 코드에 박지 않는다 — 파라미터 pass-through

```java
// TmdbClient — null이면 해당 파라미터를 붙이지 않는다 (queryParamIfPresent)
TmdbDiscoverResponse discoverMovies(int page, String originalLanguage,
                                    Integer voteCountGte, String sortBy, Integer year);
```

```
POST /api/admin/movies/seed/discover?pages=50&lang=ko&minVotes=30&sortBy=vote_count.desc
```

**전략이 바뀌어도 코드를 고치지 않는다.** 프로필을 상수로 박으면 임계값 하나 조정할 때마다
빌드·재기동이 필요하다. 임계값(30 / 300 / 100)은 **첫 프로필 결과를 보고 조정할 값**이다.

#### 실행 순서 — 박스오피스가 먼저다

```bash
# 0. 박스오피스 (2주치 이상)
POST /api/admin/box-office/sync
POST /api/admin/movies/seed/box-office     ← 잔여 #10 측정
POST /api/admin/box-office/rematch

# 1. 한국 영화
POST /api/admin/movies/seed/discover?pages=50&lang=ko&minVotes=30&sortBy=vote_count.desc
# 2. 전역 인지도
POST /api/admin/movies/seed/discover?pages=100&minVotes=300&sortBy=vote_count.desc
# 3. 최근작 (연도별 5회)
POST /api/admin/movies/seed/discover?pages=20&year=2021&minVotes=100
#    ... 2022 ~ 2025
```

⚠️ **박스오피스를 먼저 돌리는 이유** — 잔여 #10(제목 매칭 실패율)을 **다른 시드가 섞이기
전에** 측정해야 한다. discover가 먼저 돌면 역방향 시드 대상이 `alreadyExists`로 빠져
표본이 줄어든다.

각 호출이 5~10분이므로 **로그를 파일로 남길 것**(잔여 #11 재확인용).

```bash
./gradlew bootRun > seed.log 2>&1
grep -c "길이 초과로 절단" seed.log     # 60편에선 0건 — 5,000편에서 재확인
grep -c "제목 매칭 실패" seed.log
grep "rate limit" seed.log
```

### Rate limit — 선제 스로틀링 없이 429 반응형 백오프

**현재 TMDB 제한은 약 50 req/s, IP당 커넥션 20개다**(2019년 12월에 40req/10s 하드 리밋 폐지).
**API 키가 아니라 IP 기준**이라는 점이 중요하다.

#### 선제 `sleep`은 두지 않는다

초안의 *"요청 간 간격을 둔다"* 는 철회한다. 우리는 순차 호출이라 왕복 지연(100~300ms)만으로
초당 3~10회이고, 50 req/s에 자연히 못 미친다. 인위적 `sleep`은 시드만 느리게 할 뿐이다.

#### 왕복 계산 정정 — "영화당 1회"가 아니다

초안이 *"`append_to_response=credits`로 영화당 왕복이 1회"* 라고 적었으나 **상세 조회만 해당**한다.

| 경로 | 실제 왕복 |
|---|---|
| 역방향 시드 | `/search/movie` 1 + `/movie/{id}` 1 = **편당 2회** |
| discover 시드 | 페이지당 `/discover` 1 + 상세 20 = **페이지당 21회** |

⚠️ **`existsByTmdbId` 사전 필터는 `tmdbId`를 알아야 걸 수 있다.** 역방향 시드는 search를
해야 tmdbId가 나오므로 **이미 적재된 영화에도 search 왕복은 발생한다.**
"이미 적재된 건 건너뛴다"가 rate limit을 **절반만** 아껴준다.
discover는 응답에 tmdbId가 들어 있어 페이지 단위로 차집합을 걸 수 있다(상세 호출 절약).

#### 429 처리 — `TmdbClient` 내부 재시도

```
429 수신
 → Retry-After 헤더가 있으면 그 값만큼 대기 (없으면 지수 백오프 1s → 2s → 4s)
 → 최대 3회 재시도
 → 그래도 429면 TMDB_RATE_LIMITED 예외
```

- **`TmdbClient`가 책임진다.** 429는 인프라 관심사지 도메인 관심사가 아니고, 호출부가
  셋(참조 시드·영화 시드·온디맨드)이라 각자 구현하면 중복이다.
  현재 `fetchXxx` 메서드들이 try-catch를 중복하고 있으므로 `executeWithRetry` 헬퍼로 접는다.
- **6-4의 빈 분리 덕에 성립한다.** `MovieSyncService`가 non-transactional이라 최대 7초
  대기가 커넥션을 점유하지 않는다. 트랜잭션 안에서 재시도했다면 백오프가 곧 커넥션 홀드다.
- ⚠️ **`EXTERNAL_API_ERROR`로 뭉뚱그리면 안 된다.** 그러면 시드가 "이 영화만 skip"하고
  남은 수백 편을 계속 두드려 **IP 차단**으로 간다. 별도 코드로 구분해 루프를 멈춰야 한다.
- **재시도 대상은 429만.** 5xx까지 넓히면 TMDB 장애 시 시드가 하염없이 길어진다.
- `spring-retry` 의존성은 추가하지 않는다. 수동 루프로 충분한 분량이다.

### 실패 격리와 트랜잭션

`syncFromTmdb`는 실패하면 그냥 던지고 **자기 영화 하나만 롤백**한다. 시드는 편별로 잡는다.

```java
catch (BusinessException e) {
    if (e.getErrorCode() == ErrorCode.TMDB_RATE_LIMITED) {
        log.warn("rate limit 도달, 중단합니다. 진척 matched={}, skipped={}", matched, skipped);
        stoppedByRateLimit = true;
        break;                     // ← 결과를 살려서 반환
    }
    skipped++;                     // 그 외 실패는 건너뛰고 계속
}
```

> **429는 예외로 던지지 않고 `break` 후 정상 반환한다.** 예외를 던지면 거기까지의 진척이
> 사라져 관리자가 어디까지 됐는지 모른다. 멱등이라 다시 실행하면 이어받으므로,
> 어디서 멈췄는지 알려주는 쪽이 훨씬 유용하다.

#### ⚠️ 시드 서비스 메서드에 `@Transactional`을 붙이지 않는다

`TheaterSeedService.seedAll`이 `@Transactional`이라 그대로 따라 하기 쉽지만 **성격이 다르다.**
극장 시드는 외부 호출이 없는 단일 배치라 한 트랜잭션이 맞다. 영화 시드는 아니다.

- 붙이면 수백 편이 **한 트랜잭션**에 묶여 중간 실패 시 전부 롤백된다 — 멱등 이어받기가 무의미해진다.
- `MovieSyncPersister`가 이미 편별 트랜잭션을 갖고 있는데, 시드에 붙이면 그것이
  **외부 트랜잭션에 참여**해버려 6-4의 빈 분리가 통째로 무력해진다.
- 429 백오프의 대기 시간이 트랜잭션 안에 들어가 커넥션을 점유한다.

시드 메서드는 **트랜잭션 없이 루프만 돈다.**

### `SeedResult` — 두 시드 공통

```java
public record SeedResult(int matched, int skipped, int alreadyExists,
                         boolean stoppedByRateLimit) {}
```

| 필드 | 의미 |
|---|---|
| `matched` | 새로 적재 성공 |
| `skipped` | 실패해서 건너뜀 (역방향=제목 매칭 실패, discover=상세 조회 실패) |
| `alreadyExists` | `existsByTmdbId` 사전 필터로 건너뜀 — **실패가 아니다** |
| `stoppedByRateLimit` | 429로 중도 중단 |

- **`alreadyExists`를 `skipped`에 섞지 않는다.** 정상 동작인데 섞으면 "매칭 실패가 많다"로
  오독된다.
- **`stoppedByRateLimit`이 없으면 "다 끝남"과 "중간에 멈춤"이 구별되지 않는다.**
  `{matched: 120, skipped: 3}`만으로는 500건을 다 처리한 건지 123건에서 멈춘 건지 알 수 없고,
  후자면 다시 실행해야 한다.

### 중복 실행 방어 — `AtomicBoolean`

수백 편이라 응답이 오래 걸린다. 관리자가 한 번 더 누르면 시드가 **동시에 두 번** 돈다.

- 두 실행이 같은 목록을 읽어 같은 영화를 각각 요청 → **요청량 2배** → 429 유발 →
  위 규칙에 따라 **양쪽 다 중단**. 두 배로 일하고 절반도 못 끝낸다.
- 같은 `tmdbId`를 동시에 저장하려다 `uk_movie_tmdb_id` 위반이 나서
  **멀쩡한 영화가 `skipped`로 집계**되기도 한다.

데이터가 깨지지는 않는다(멱등). **낭비와 오해가 문제다.**

실행 중이면 두 번째 호출을 **409로 거절**한다. 서버 재시작 시 플래그가 풀리는데
중단된 시드는 다시 돌려야 하므로 **오히려 맞는 동작**이다.

> ⚠️ **한 JVM 안에서만 유효하다.** 다중 인스턴스로 늘리면 무력해진다(잔여 #18).
> 지금은 단일 인스턴스이고 목적이 "관리자 오조작 방지"라 충분하다. DB 락이나
> 실행 이력 테이블은 스키마가 늘어 과하다.

### 온디맨드 — 검색 병합

```
1. GET  /api/movies/search?query=...
       registered = DB 검색 결과(PageResponse) / suggestions = TMDB 미등록 후보
2. POST /api/movies/sync { tmdbId }        ← 사용자가 suggestions 항목을 선택했을 때만
       syncFromTmdb 후 movieId 반환
3. 프론트가 그 movieId 로 시청기록·위시 등을 생성
```

- **검색 시점에 결과 전체를 동기화하지 않는다.** 20편 중 1편만 선택되는 것이 보통이라
  rate limit과 쓰레기 데이터 양쪽에서 손해다.
- `POST /api/movies/sync`는 **인증 필요**하다. 미인증 공개 경로로 두면 임의의 `tmdbId`로
  우리 DB를 채우는 통로가 된다.

> **검색 응답 계약의 확정본은 6-8이다.** 초안은 두 출처를 병합해 `movieId`를 nullable로
> 두는 방식이었고 그 때문에 `totalElements`가 성립하지 않았으나, **6-8이 `{registered,
> suggestions}` 2섹션으로 분리**하면서 병합 자체가 사라졌다.

---

## 6-6. ErrorCode 추가분 (✅ 확정 2026-08-20)

| 상수 | HTTP | 용도 |
|---|---|---|
| `TMDB_MOVIE_NOT_FOUND` | 404 | TMDB에 해당 `tmdbId`가 없음 — `POST /api/movies/sync`에서 사용자가 잘못된 `tmdbId`를 보낼 수 있으므로 4xx가 맞다 |
| `ADULT_CONTENT_NOT_ALLOWED` | 400 | `adult == true` 영화 동기화 거부 (6-3 ①). 사용자가 보낸 `tmdbId`에 대한 응답이므로 4xx |
| `GENRE_NOT_FOUND` | 500 | 동기화 중 미등록 장르를 만남 — 사용자 잘못이 아니므로 5xx |
| `COUNTRY_NOT_FOUND` | 500 | 동일 |
| **`REFERENCE_DATA_NOT_SEEDED`** | 500 | 시드 진입점 가드 (6-5). **메시지에 다음 행동을 담는다** — *"참조 테이블이 비어 있습니다. 장르·국가 시드를 먼저 실행하세요."* |
| **`TMDB_RATE_LIMITED`** | 429 | 백오프 3회 후에도 429 (6-5). **`EXTERNAL_API_ERROR`와 반드시 구분** — 시드가 이걸 만나면 루프를 멈춰야 하는데, 뭉뚱그리면 개별 skip으로 처리돼 남은 수백 편을 계속 두드리고 **IP 차단**으로 간다 |
| **`SEED_ALREADY_RUNNING`** | 409 | 시드 중복 실행 거절 (6-5) |

> `EXTERNAL_API_ERROR`(4-7)는 재사용한다.
>
> **`GENRE_NOT_FOUND`와 `REFERENCE_DATA_NOT_SEEDED`를 합치지 않는 이유** — 전자는
> "이 장르 하나를 못 찾음"(부분 적재·TMDB 신규 장르 추가)이고 후자는 "테이블 자체가 비었음"
> (실행 순서 오류)이다. 원인도 대응도 다르며, 후자만 **운영자에게 줄 다음 행동**이 명확하다.

---

## 6-7. 최초 시드 실측 결과 (2026-08-20)

`/seed/discover?pages=3`으로 **60편**을 적재하고, "실측 후 판단"으로 미뤄둔 잔여 3건을
판정했다. 계측이 아니라 **판정 근거**를 남기는 것이 이 절의 목적이다.

### 적재 규모

| 테이블 | 건수 | 비고 |
|---|---|---|
| `genre` | 19 | TMDB 표준 목록과 일치 |
| `country` | 251 | 〃 |
| `movie` | 60 | `pages=3` × 20. **실패 0건** |
| `movie_actor` | 2,375 | 영화당 평균 **39.6**, 최대 **141** |
| `person` | 2,306 | |

**설계가 검증된 것 — 배우 겸 감독이 실제로 존재했다.** 6-4에서 `Person` 통합 upsert를
3단계로 분리한 근거가 정확히 이것이었고, 분리하지 않았다면 `uk_person_tmdb_id` 위반으로
터졌을 것이다. 60편이라는 작은 표본에서 나왔으므로 "흔한 케이스"라는 판단도 맞았다.

`person`(2,306)이 `movie_actor`(2,375)와 거의 같다 — **인기작 60편 사이에 겹치는 배우가
거의 없다**는 뜻이다. `Person.updateProfile()` 값 비교 최적화(잔여 #14)의 효과는 이 규모에선
미미하고, 시드를 키울수록 커진다.

### ⚠️ 표본 편향 — 이 결과를 일반화하면 안 된다

`region=KR` + `popularity.desc` **인기작 60편**이다. 인기작은 편집자 손을 많이 타서
**데이터가 가장 정돈된 부류**다. 아래 판정은 전부 이 전제 위에 있다.

특히 `character_name`이 길어지는 유형(애니메이션 성우 다역, 다큐 `Self - ...` 표기,
옴니버스)은 인기작 60편에 섞였을 확률이 낮다.

### 잔여 #11 — 길이 초과: 절단 0건, 조치 불필요

| 컬럼 | 상한 | 실측 최대 | 여유 |
|---|---|---|---|
| `movie.title` | 255 | **33** | 7.7배 |
| `movie.overview` | 1000 | **684** | 1.5배 |
| `movie_actor.character_name` | 100 | **30** | 3.3배 |

**`overview` 684는 D-4 롤백이 옳았다는 직접 증거다.** TMDB의 1000자 제한이 실제로
지켜지고 있고 실측 최대가 684다. `varchar(4000)`은 확실히 근거가 없었다.

> #### ⚠️ 검증 지표 정정 — `LIKE '%...'`는 절단 탐지에 쓸 수 없다
>
> 처음에 절단 여부를 `SUM(overview LIKE '%...')`로 셌고 **11건**이 나왔다. 오탐이다.
> 절단됐다면 그 행의 `CHAR_LENGTH`가 상한(1000) 근처여야 하는데 최대가 684였다.
> 11건은 **TMDB 원문이 원래 `...`로 끝나는 것**이다(줄거리가 문장 중간에서 끊기거나
> 편집자가 말줄임표를 넣은 경우로 흔하다).
>
> **절단은 길이로 판정한다** — `SUM(CHAR_LENGTH(col) >= 상한 - 1)`.
> 값 패턴으로 보면 자연 발생 말줄임표와 구분되지 않는다.

**조치하지 않는 이유는 여유가 있어서만이 아니다.** 절단이 실제로 일어나면
`MovieSyncPersister.truncate()`의 `WARN`이 뜬다 — **조용히 데이터를 잃는 경로가 없다.**
6-3 ④에서 *"추정으로 넓히지 말고 절단 + `WARN` 후 실측"* 으로 정한 설계가 여기서 값을 한다.

### 잔여 #8 — cast 인덱스: 추가 불필요

영화당 출연진 상위 5편은 **141 / 112 / 93 / 92 / 85**다. 평균(39.6)의 3.5배로 **꼬리가 두껍다.**

> 초판 해석 정정 — 평균만 보고 *"걱정보다 낫다"* 고 적었으나 성급했다. 최대 141은
> 원래 우려했던 범위에 근접한다.

그럼에도 인덱스를 추가하지 않는 이유는 **기존 인덱스가 이미 커버하기 때문**이다.

```
uk_movie_actor (movie_id, person_id)
                ^^^^^^^^ 선두 컬럼
```

유니크 제약이 만든 이 인덱스의 선두 컬럼이 `movie_id`라 `WHERE movie_id = ?`는
**이미 인덱스 레인지 스캔을 탄다**(테이블 풀스캔이 아니다). 커버되지 않는 건
`ORDER BY display_order` 정렬뿐이고, **최대 141행 정렬은 무시 가능**하다.

게다가 상세 조회는 `display_order <= 20`으로 걸러 **실제로는 21행만** `person`과 조인한다.
141행이 실리는 곳은 아직 만들지 않은 전체 출연진 엔드포인트(잔여 #7)뿐이고,
그것도 141행이면 페이징 없이 감당된다.

### 잔여 #4 — 문제 정의가 틀렸다 (60편 기준)

한글 인물명 비율이 **664 / 2,306 = 28.8%** 다. **71%가 한글이 아니다.**

그런데 **폴백 문제가 아니다.** 폴백이 71% 발동했다면 `WARN`이 수천 줄 쏟아졌을 것이다.
`name` 필드가 비어 오지 않았고 **영문이 그대로 채워져 온 것**이다.

| 대상 | 상태 |
|---|---|
| 폴백 로직(`name` 비면 `original_name`) | ✅ 정상. 애초에 발동할 일이 거의 없다 |
| TMDB의 인물명 한글화 커버리지 | ❌ **29%** — 우리 로직과 무관한 **데이터 한계** |

#4를 *"한국어 폴백 방식/빈도"* 로 정의한 것이 잘못이었다. 실제 사안은
**"TMDB가 배우 이름을 한글로 주지 않는다"** 이고 대응 방법이 전혀 다르다.
**수용하고 별도 항목(잔여 #19)으로 분리**한다.

---

## 6-7-b. 본 시드 실측 결과 — 4,609편 (2026-08-24)

`movie-seed-runbook.md`에 따라 프로필 4종을 실행했다. **6-7(60편)이 경고한 표본 편향이
실제로 결과를 바꾼 사례가 나왔다.**

### 적재 규모

| 테이블 | 60편 | **4,609편** | 비고 |
|---|---|---|---|
| `movie` | 60 | **4,609** | 목표 5,000. 중복 감안 4,000~4,500 예상했는데 상회 |
| `movie_actor` | 2,375 | **186,717** | 영화당 평균 **40.5** (60편의 39.6과 거의 동일) |
| `person` | 2,306 | **113,909** | 중복률 3% → **39%** |

`person` 중복률 변화는 예상과 맞았다 — 6-7에서 *"배우 풀이 유한하므로 규모가 커지면
중복률이 올라간다"* 며 10만~15만으로 예상했고 113,909가 나왔다.

### 국가 구성 — 프로필 ①이 작동했다

| 국가 | 편수 |
|---|---|
| 미국 | 3,084 |
| **대한민국** | **843** |
| 영국 | 617 |
| 프랑스 | 339 |
| 캐나다 | 212 |

한국이 2위(4,609편 대비 **18.3%**)다. `with_original_language=ko` 프로필이 없었다면
미국·영국·프랑스가 상위를 채웠을 것이다. 다만 목표 1,000편에는 못 미친다 —
`vote_count.gte=30`을 만족하는 한국 영화가 그만큼 없었거나 중복이 있었다.

> `movie_country`는 공동제작 시 여러 국가가 붙어 합계가 4,609를 넘는다.

### 잔여 #10 — D-2가 처음으로 검증됐다

```
box_office_record 전체 140건 / 미매칭 13건  →  127건 매칭 (90.7%)
```

D-2에서 **박스오피스 역방향을 주 경로로 고른 근거**가 실측으로 확인됐다. 지금까지는
설계상 추론이었다. 역방향 시드가 `movie`를 만들고 → 4-7 재매칭이 연결하는 **2단 구조**도
정상 동작했다. 매칭 책임을 재매칭 배치 하나로 유지한 판단이 옳았다.

> 140건은 레코드 수이고 **고유 제목은 훨씬 적다**(2주간 같은 영화가 반복 차트인).
> 표본이 작다는 한계는 남는다.

### 잔여 #8 — 20만 행에서도 판정 유효

```
type=ref  key=uk_movie_actor  key_len=8  rows=141  Using where; Using filesort
```

6-7의 예측 그대로다. `type=ref` + `key=uk_movie_actor`이므로 **인덱스를 타고 있고**(풀스캔
아님), `key_len=8`은 `movie_id`(bigint) 하나만 쓴 것이다. `Using filesort`는 정렬이 인덱스로
덮이지 않는다는 뜻이지만 **141행 정렬은 무시 가능**하다.

판정 근거가 **"테이블 크기가 아니라 매칭 행 수"** 였는데, 테이블이 2,375 → 186,717로
**79배**가 되는 동안 `rows`는 141 그대로였다. **추가 인덱스 불필요 확정.**

### ⚠️ 잔여 #11 — 절단이 실제로 발생했다 (v14로 대응)

| 컬럼 | 상한 | 60편 최대 | **4,609편 최대** | 절단 |
|---|---|---|---|---|
| `movie.title` | 255 | 33 | **66** | 0건 |
| `movie.overview` | 1000 | 684 | **978** | 0건 |
| `movie_actor.character_name` | 100 | 30 | **100** | **29건** |
| `person.name` | 100 | — | — | 0건 |

**`character_name`의 MAX가 정확히 100인 것이 절단의 증거다** — `truncate()`가
`97자 + "..."`로 100자를 만든다. 자연 발생한 배역명이 우연히 정확히 100자일 확률은 낮다.

**6-3 ④의 예측이 맞았다.** 네 컬럼 중 `character_name`만 근거가 있다고 했고(TMDB 공식
가이드가 다역을 슬래시로 연결), 6-7이 경고한 표본 편향도 그대로 확인됐다 —
*"인기작 60편은 편집자 손을 많이 타 데이터가 가장 정돈된 부류"* 였다.

**→ `varchar(255)`로 확장했다** (`v14-delta.sql`, 적용 완료).
29건은 이미 100자로 저장돼 있어 **ALTER로는 복구되지 않는다** — `resync`가 필요하다.

> #### ⚠️ `overview` 978자 — 6-7의 판정을 갱신한다
>
> 6-7은 `overview`를 *"여유 1.5배"* 로 적었으나 그건 **684자(60편 표본) 기준**이었다.
> 실제로는 **978자로 상한의 97.8%** 를 쓴다. 절단이 0건인 이유는 여유가 있어서가 아니라
> **TMDB가 overview를 1000자로 제한해서**(D-4) 구조적으로 넘을 수 없기 때문이다.
>
> D-4에서 `varchar(1000)`으로 롤백하고 절단 로직을 남긴 판단이 둘 다 옳았다.

### 잔여 #19 — 악화됐다

**13,589 / 113,909 = 11.9%.** 60편 표본에선 28.8%였다.

규모가 커지며 외화 비중이 올라간 결과다(미국 3,084 대 한국 843). **한글 인물명이 12%면
외화 상세 화면은 사실상 전부 영문**이다.

보강 비용도 커졌다 — `also_known_as`를 받으려면 **11만 명 × 왕복 1회**다. 2,306명이던
때와 규모가 다르다. "수용 후 프론트 확인" 판단은 유지하되 **비용이 재검토 대상**이다.

### 계측 실패 — `seed.log`가 유실됐다

`SeedResult` 8건과 절단 원본 길이를 확인하지 못했다. PowerShell의 `*>`가 **덮어쓰기**라
시드 이후 `bootRun`을 재시작하면서 날아간 것으로 보인다.

**#8·#10·#11은 DB만으로 판정됐지만** 아래는 확인 못 했다.

- 프로필별 `matched` / `skipped` / `alreadyExists` — 한국 영화가 843편에 그친 원인
- `stoppedByRateLimit` — 429나 토큰 만료로 조용히 실패한 호출이 있었는지
- 절단된 배역명의 **원본 길이** — 255가 충분한지의 직접 근거

런북에 반영할 것(잔여 #26).

---

## 6-8. 영화 검색 설계 (✅ 확정 2026-08-20)

D-2 ③이 남긴 마지막 미완 항목이자, 4-2부터 `/api/movies/search`를 막아온
**`MovieSearchCondition`** 의 결론이다.

### 응답 계약 — 두 집합을 섞지 않는다

```json
{
  "registered":  { "content": [...], "page": 0, "size": 20,
                   "totalElements": 4, "totalPages": 1, "first": true, "last": true },
  "suggestions": [ { "tmdbId": 999001, "title": "...", "posterPath": "...", "releaseDate": "..." } ]
}
```

| | `registered` | `suggestions` |
|---|---|---|
| 출처 | **우리 DB** | TMDB (미등록분만) |
| 페이징 | **완전한 `PageResponse`** | 없음. 상위 N개 |
| 필터·정렬 | 가능(향후) | 불가 |
| 식별자 | `movieId` | `tmdbId`만 |

**섞지 않는 것이 설계의 핵심이다.** 섞으면 `totalElements`를 계산할 수 없다 —
DB 4건과 TMDB 9건을 합쳐 몇 건인지 알려면 **겹치는 수를 알아야 하고, 그건 TMDB 전체를
받아야만** 나온다. 현재 페이지 20건만으로는 불가능하다. 결과가 수백 건이면 총계 하나
구하자고 TMDB를 수십 페이지 호출해야 한다.

섹션을 나누면 `registered`의 총계는 DB `count`로 온전히 성립하고, `suggestions`는
페이징이 없으니 총계를 셀 필요 자체가 없다.

> **`movieId` nullable 안을 폐기한다.** D-2 ③은 *"`movieId`를 nullable로 두고 `tmdbId`를
> 병기해 `movieId == null`이 미등록 신호"* 로 정했으나, 섹션 분리로 **등록 여부가 필드가
> 아니라 구조로 표현**되므로 불필요해졌다. `registered`는 기존 `MovieSummaryResponse`를
> 그대로 쓴다.

- **중복 제거** — TMDB 결과의 `tmdbId`를 `findByTmdbIdIn`으로 걸러 이미 등록된 건
  `suggestions`에서 뺀다. 페이징이 없어 개수가 흔들려도 무해하다.

### `suggestions`는 `page = 1`에서만 채운다

2페이지 이후는 DB만 본다. 사용자가 결과를 넘겨보는 동안 TMDB를 반복 호출할 이유가 없다 —
"우리에게 없는 영화"는 첫 화면에서 한 번 보여주면 충분하다.
**rate limit과 지연이 첫 페이지에만 국한된다.**

### TMDB 장애 — 구조가 폴백을 대신한다

TMDB가 죽어도 `registered`는 DB에서 나오므로 **검색 자체는 정상 동작**하고
`suggestions`만 빈 배열이 된다. 별도 폴백 경로를 만들 것이 없다.

- **실패 유형을 구분하지 않는다.** 429·5xx·타임아웃·4xx 전부 `suggestions`를 비운다.
  검색에서 4xx가 날 상황은 `query`가 빌 때 정도인데 `@NotBlank`로 막히므로 분기의 실익이 없다.
- ⚠️ **발동 시 `WARN`이 필수다.** 이 처리는 본질적으로 **실패를 감추는 장치**라,
  감춰진 실패를 볼 수단이 없으면 TMDB 토큰이 만료돼도 아무도 모른 채
  *"요즘 추천이 안 뜨네"* 로 끝난다(잔여 #4·#10이 로그가 없어 실측 불가였던 것과 같은 유형).
- ⚠️ **검색 경로에서는 429 백오프를 타지 않는다.** `executeWithRetry`는 최대 7초를 기다리는데
  시드(배치)에는 옳지만 **사용자 대면 경로에서는 독**이다. 사용자는 이미 떠났고 그동안
  톰캣 스레드가 잡혀 있으며, rate limit에 걸린 순간엔 여러 요청이 동시에 7초씩 점유해
  스레드 풀이 마른다. 즉시 포기하고 `suggestions`를 비운다.

### 전제조건 2건

**① 타임아웃** — `TmdbConfig`·`KoficConfig` 모두 타임아웃이 없어 **사실상 무한 대기**다.
응답 없이 매달리면 `suggestions`를 비우기까지 수십 초가 걸리고 그동안 스레드가 잡힌다.

```java
ClientHttpRequestFactorySettings.defaults()
        .withConnectTimeout(Duration.ofSeconds(2))
        .withReadTimeout(Duration.ofSeconds(3))
```

우선 짧은 값 하나로 시작하고, 시드에서 타임아웃이 잦으면 그때 `RestClient` 빈을 나눈다.

**② `MovieSearchService` 별도 빈** — `MovieQueryService`는 클래스 레벨
`@Transactional(readOnly = true)`다. 여기에 검색을 넣으면 **읽기 트랜잭션 안에서 TMDB HTTP
호출**을 하게 된다. 6-4에서 `MovieSyncService`를 non-transactional로 분리한 것과 같은 문제다.
쓰기가 없으므로 트랜잭션 없이 리포지토리를 직접 호출한다.

### DB 제목 검색은 `LIKE`로 시작한다

```java
Page<Movie> findByTitleContaining(String keyword, Pageable pageable);
```

`movie.title`에 인덱스가 없고, **선행 와일드카드는 B-tree 인덱스를 원리적으로 못 탄다** —
인덱스를 추가해도 해결되지 않는다. 다만 현재 60행, 향후 수천 행 규모에서 풀스캔은 무해하다.
collation이 `utf8mb4_0900_ai_ci`라 대소문자·악센트는 자동으로 무시된다.

**한글 부분일치를 인덱스로 처리하려면 `WITH PARSER ngram` FULLTEXT가 필요하다**(v13 델타).
MySQL 기본 FULLTEXT 파서는 공백 기준이라 "베테랑"으로 "베테랑2"를 못 찾는다.
실제로 느려지면 그때 판단한다(잔여 #20).

### 파라미터 — `MovieSearchCondition`을 만들지 않는다

`suggestions`가 TMDB에서 오므로 **TMDB `/search/movie`가 받는 것 이상은 지원할 수 없다.**

```
query(필수) · include_adult · language · primary_release_year · page · region · year
```

**장르 필터도 정렬 옵션도 없다.** 확인된 사실이다([TMDB Search Movie](https://developer.themoviedb.org/reference/search-movie)).

| 파라미터 | 결정 |
|---|---|
| `query` | 필수, `@NotBlank` |
| `year` | 선택. `primary_release_year`가 아니라 **`year`** — 재개봉까지 잡아 관대하고, 6-5 역방향 시드가 이미 `year`를 쓴다 |
| `page` | 1부터 |
| `region` | **붙이지 않는다.** 검색에서 `region`은 개봉일 기준을 좁혀 결과를 줄인다 |
| `include_adult=false` | **명시적으로 보낸다.** DTO에서 `adult` 필드를 뺐으므로 이제 유일한 방어선이다(잔여 #21) |

`registered`는 DB 기반이라 장르 필터·정렬이 **기술적으로 가능**해졌지만,
**프론트 화면이 없어 어떤 축이 필요한지 모른다.** 상상해서 만들면 쓰이지 않는 축이 남는다.
`query` 하나로 시작하고 요구가 실제로 나오면 그때 조건 객체로 승격한다(잔여 #22).

### `registered` 정렬 — `releaseDate DESC, id DESC` (확정 2026-08-23)

```java
Sort.by(Sort.Order.desc("releaseDate"), Sort.Order.desc("id"))
```

> **초판 누락.** C안을 논의할 때 *"폴백(DB)은 `releaseDate DESC NULLS LAST`"* 로 정했는데,
> B안으로 바꾸면서 이 항목을 옮겨 적지 않았다. C안에서 DB는 폴백이었지만
> **B안에서 `registered`는 상시 경로**라 오히려 더 중요해졌다.

**정렬을 명시하지 않으면 순서가 정의되지 않는다.** 현재는 `title` 인덱스가 없어 풀스캔 →
클러스터드 인덱스(PK) 순으로 읽히므로 사실상 적재 순인데, 지금 데이터가
`discover?sort_by=popularity.desc`로 들어와 **우연히 인기순처럼 보일 뿐**이다.
박스오피스 역방향 시드와 온디맨드 `sync`가 섞이면 그 의미가 사라진다.

| 적재 경로 | `id` 순서의 의미 |
|---|---|
| discover 시드 | 인기순 — 우연히 괜찮음 |
| 박스오피스 역방향 | 미매칭 제목 순 — 인기와 무관 |
| 온디맨드 `sync` | **사용자가 검색한 순서** — 무의미 |

**더 실질적인 이유는 페이징 일관성이다.** `ORDER BY` 없는 `LIMIT/OFFSET`은 페이지 간
순서를 보장하지 않아 **같은 행이 중복되거나 누락될 수 있다.** 60편일 땐 대부분 1페이지에
담겨 안 드러나고, 2,000편에서 재현이 어려운 버그로 나타난다.

- ⚠️ **`id`를 tie-breaker로 함께 건다.** 같은 날 개봉한 영화가 여럿이면 `releaseDate`만으로는
  순서가 다시 불안정해져 위 문제가 그대로 재현된다.
- ⚠️ **`NULLS LAST`를 명시하지 않는다.** MySQL은 NULL을 가장 작은 값으로 취급하므로 `DESC`면
  자동으로 마지막에 온다. 명시하면 Hibernate가 MySQL 미지원 문법을
  `CASE WHEN ... IS NULL`로 에뮬레이션해 쿼리만 지저분해지고 인덱스 활용을 막는다.
- `suggestions`는 TMDB 관련도순이고 **변경할 수 없다.** 두 섹션의 정렬 기준이 다르지만
  출처가 다르므로 감출 수 없는 차이다.

### 페이징 함정 3건

| # | 내용 |
|---|---|
| 1 | **TMDB는 1-based, Spring `Pageable`은 0-based.** 변환을 빠뜨리면 1페이지 요청에 2페이지가 나온다 |
| 2 | **TMDB 페이지 크기는 20 고정.** `suggestions`에는 페이징이 없으므로 상위 N개만 잘라 쓰면 되고, 이 함정은 `registered`와 무관하다 |
| 3 | **`PageImpl`이 `total`을 보정한다** — 마지막 페이지에서 `content.size() < pageSize`이면 생성자가 총계를 다시 계산한다. `registered`는 DB `count`라 정확하므로 문제없으나, 직접 조립할 때 알고 있어야 한다 |

### 기각 — 검토했으나 채택하지 않은 것

**C안(TMDB 단일 출처 + `movieId` 라벨링).** 결과 집합을 TMDB 하나로 두고 우리 DB는
`movieId`만 채우는 안이다. `totalElements` 불성립·중복 제거·DB 제목 인덱스 **세 쟁점을
한 번에 없애는 우아함**이 있어 한때 채택 직전까지 갔다.

**기각 사유** — 우리 DB가 우리 제품에서 구경꾼이 된다. 장르 가중치·출연진·국가 가중치를
쌓아놓고도 검색에서 아무것도 쓰지 못하고, 검색 품질을 **통제 불가능한 TMDB 관련도 순위에
통째로 위임**하게 된다. 매 검색이 외부 호출이라 지연과 rate limit도 상시 부담이 된다.
"쟁점이 사라진다"는 것이 곧 "설계가 옳다"는 뜻은 아니었다.

**`genre_ids`로 클라이언트 사이드 필터링.** TMDB 검색 결과에 `genre_ids`가 들어 있지만,
받아온 20건 안에서만 필터되어 **페이지마다 결과 수가 들쭉날쭉해지고 `total_results`가
의미를 잃는다.** 장르 기반 탐색이 필요하면 검색이 아니라 `getMovieList` 쪽에서 설계한다.

---

## 6-9. `movie` 메타데이터 보강 (v13, ✅ 확정 2026-08-23)

6-8 실호출 검증 중 드러난 문제에서 출발했다. TMDB 응답에 **이미 들어오는데 저장하지 않던
값 4개**를 추가한다.

### 발단 — 원어로는 내 기록을 못 찾는다

```
GET /api/movies/search?query=avatar   →  registered 0건
GET /api/movies/search?query=아바타   →  registered 1건   ← 같은 영화
```

`movie.title`은 `language=ko-KR`로 받은 한국어 제목이라 `"아바타"`로 저장돼 있고
`LIKE '%avatar%'`에 걸리지 않는다. 반면 TMDB `/search/movie`는 공식 설명상
**"original, translated and alternative titles"** 를 전부 검색한다.

**같은 검색어로 두 섹션의 매칭 기준이 어긋난다.**

| 섹션 | 검색 대상 |
|---|---|
| `registered` | `title` 하나 — ko-KR 제목만 |
| `suggestions` | 원어·번역·대체 제목 전부 |

사용자가 이미 기록해 둔 영화가 `registered`에 안 나오고 `suggestions`에만 뜬다.
데이터는 안전하지만(`POST /api/movies/sync`가 멱등) **"내 기록이 검색에 없다"** 는 체감이 나쁘다.

> ⚠️ **`original_title`은 이미 받고 있었다.** `TmdbMovieDetailResponse.originalTitle`이
> `title`이 빌 때의 폴백(6-3 ⑦)으로만 쓰이고 저장되지 않았다.

### 추가하는 컬럼 4개

```sql
ALTER TABLE `movie`
  ADD COLUMN `original_title` varchar(255) DEFAULT NULL AFTER `title`,
  ADD COLUMN `backdrop_path`  varchar(255) DEFAULT NULL AFTER `poster_path`,
  ADD COLUMN `vote_average`   decimal(3,1) DEFAULT NULL AFTER `runtime`,
  ADD COLUMN `vote_count`     int          DEFAULT NULL AFTER `vote_average`;
```

| 컬럼 | 용도 | 근거 |
|---|---|---|
| `original_title` | 검색 매칭 | **실측된 문제** (위) |
| `backdrop_path` | 상세 화면 가로 배경(16:9) | 용도 명확. `poster_path`와 대칭 |
| `vote_average` | 상세 화면 평점 | 아래 참고 |
| `vote_count` | 평점 신뢰도 + M3 콜드 스타트 | 아래 참고 |

- **검색은 `title OR original_title`로 넓힌다** — `findByTitleContainingOrOriginalTitleContaining`.
- `backdrop_path`는 **`null`이 흔하다.** 인지도 낮은 작품일수록 없으므로 **프론트에 폴백이
  필요하다**(포스터 블러 또는 단색). 경로만 저장하고 베이스 URL은 프론트가 조립한다.
- `vote_average`가 `decimal(3,1)`인 이유 — TMDB가 소수 첫째 자리까지 주고 최대 10.0이다.
  `double`이면 `8.433`이 그대로 들어와 표시할 때마다 반올림이 필요해진다.

#### 평점은 우리 것과 TMDB 것을 함께 보여준다

`MovieDetailResponse`에 **평점 필드가 아예 없었고**, `ReviewRepository`에 집계 쿼리도 없다.
상세 화면에 평점이라는 개념 자체가 없는 상태다.

*"여러 계정으로 평점을 만들어 평균을 낸다"* 는 원안은 데모에서 성립하지 않는다.

| | 우리 평점 | TMDB `vote_average` |
|---|---|---|
| 영화당 표본 | 계정 5개를 만들어도 **1~3개** | 아바타 **22,061명** |
| 2,000편 중 보유 | 직접 입력한 극소수 | 거의 전부 |

**다만 대체 관계가 아니다.** TMDB 평점은 *영화 자체의 정보*, 우리 평점은 *이 앱 사용자들의
평가*다. 평균을 섞는 건 통계적으로도 이상하고, 무엇보다 **기록 앱인데 우리 데이터가 없으면
존재 이유가 흐려진다.** 상세 화면에 둘 다 표시한다.

**우리 평점은 컬럼이 아니라 집계 쿼리다.** 2,000편 규모면 `AVG(rating)` 실시간 계산으로
충분하고, 느려지면 그때 캐시 컬럼을 본다. 프론트에서 상세 화면을 잡을 때 함께 구현한다(잔여 #24).

### ⚠️ `resync` 엔드포인트가 선택이 아니라 필수가 된다

**`vote_average`는 시간에 따라 변한다.** 표가 쌓이며 8.4 → 8.6으로 움직인다. 저장하는 순간
stale해지고, 갱신 수단이 없으면 굳는다.

게다가 **기존 적재분은 새 컬럼이 전부 `NULL`** 인데, 시드는 `existsByTmdbId`로 이미 있는
영화를 건너뛰므로 **다시 돌려도 채워지지 않는다.**

```
POST /api/admin/movies/resync    ← 전체 재동기화 (existsByTmdbId 필터 우회)
```

비용은 크지 않다 — **2,000편 × 1왕복 × 약 200ms ≈ 7분.** `syncFromTmdb`가 멱등이고
기존 영화도 `updateMetadata`로 갱신한다. 이 엔드포인트는 v13과 무관하게도 유용하다:
TMDB 데이터가 갱신됐을 때(한국어 제목 추가, 포스터 교체) 반영할 경로가 지금 아예 없다.

> **실시간 호출은 채택하지 않는다** — 상세를 볼 때마다 TMDB를 부르면 지연과 rate limit을
> 사용자 대면 경로에 상시로 얹게 된다. 6-8에서 C안을 기각한 것과 같은 이유다.

#### `MovieSeedService`에 둔다 — 규칙이 거의 전부 같다

resync는 이름만 시드가 아닐 뿐 **같은 종류의 배치 작업**이다. 별도 서비스로 빼면
아래 규칙을 전부 복제하게 된다.

| 규칙 | resync에도 필요 |
|---|---|
| `AtomicBoolean running` | ✅ **반드시 공유** |
| 참조 테이블 가드 (`count() == 0`) | ✅ `syncFromTmdb`를 부르므로 `GENRE_NOT_FOUND` 가능 |
| `@Transactional` 금지 | ✅ |
| 429 → `break` + 중단 플래그 | ✅ |
| 편별 `try-catch` → `skipped` | ✅ |

⚠️ **`running` 플래그 공유가 핵심이다.** 별도로 두면 resync와 시드가 **동시에** 돌 수 있고,
TMDB 요청이 두 배가 되어 429를 자초한다 — 6-5에서 `AtomicBoolean`으로 막으려던 상황이
그대로 재현된다.

#### 대상 선정 — `id` 커서

```java
ResyncResult resync(Long fromId, Integer limit);
// SELECT * FROM movie WHERE id > :fromId ORDER BY id ASC LIMIT :limit
```

**조건을 걸지 않는다.** `WHERE original_title IS NULL` 같은 v13 전용 조건을 쓰면
**다음에 컬럼을 추가할 때 또 조건이 바뀐다.** 무조건 전체를 도는 편이 재사용된다.

> **`updated_at` 기준을 검토했다가 기각했다.** "오래된 것부터"가 자연스러운 rotation처럼
> 보이지만 함정이 있다 — `updateMetadata`가 무조건 대입해도 **Hibernate의 dirty check는
> 값을 비교**하므로, 실제로 안 바뀌면 UPDATE가 나가지 않고 `updated_at`(`ON UPDATE
> CURRENT_TIMESTAMP`)도 그대로다. 그러면 **같은 영화를 계속 다시 잡는다.**

**배치 분할이 필요하다.** 2,000편을 한 요청에 처리하면 7분이라 HTTP 타임아웃(클라이언트·프록시)에
걸린다. 시드가 `pages`로 나눠 호출하게 돼 있는 것과 같은 이유다.

#### `ResyncResult` — `SeedResult`를 재사용하지 않는다

```java
public record ResyncResult(int updated, int skipped,
                           boolean stoppedByRateLimit, Long lastProcessedId) {}
```

의미가 어긋난다.

| 필드 | 시드 | resync |
|---|---|---|
| `matched` | 새로 적재 성공 | **갱신 성공** — "새로"가 아니다 |
| `alreadyExists` | 사전 필터로 건너뜀 | **해당 없음** — 필터를 우회하는 게 목적이다 |

6-5에서 시드 2종의 `SeedResult` 통합을 정당화한 근거가 *"필드 구조가 같고 의미만 다르다"*
였는데, 여기는 **필드 자체가 맞지 않는다.**

`lastProcessedId`가 이어받기의 열쇠다. 429로 중단됐을 때 어디까지 갔는지 모르면
**처음부터 다시 돌아야 한다.** 관리자가 이 값을 다음 호출의 `fromId`로 넣는다.

> ⚠️ **`lastProcessedId` 갱신 위치를 주의할 것.** 429로 `break`할 때는 **그 영화를 처리하지
> 못했으므로** 전진시키면 안 된다. 전진시키면 재개할 때 그 편을 건너뛴다.
> 성공·`skipped` 처리가 끝난 뒤에만 갱신한다.

#### 검증 — v13이 실제로 문제를 해결했는지

```sql
SELECT COUNT(*), SUM(original_title IS NOT NULL), SUM(vote_average IS NOT NULL) FROM movie;
```

```
GET /api/movies/search?query=avatar   →  registered 에 "아바타"가 나와야 한다
```

두 번째가 v13의 존재 이유다. **resync 전에는 여전히 0건**이다.

### 검토했으나 넣지 않은 것

| 필드 | 기각 사유 |
|---|---|
| `tagline` | 홍보 문구 한 줄. 상세 화면 완성도에 기여하나 **한국어 번역률을 실측하지 않았다.** D-4·잔여 #11에서 "추정으로 스키마를 넓히지 않는다"를 두 번 지켰고, `resync`가 생기므로 나중에 추가해도 7분이면 채워진다 |
| `status` | `Rumored`/`Planned`/`In Production`/`Post Production`/`Released`/`Canceled`. **"개봉 예정" 판정은 `release_date > today`로 대체**된다. 고유 가치는 "제작 취소"와 "개봉일 미정" 구분뿐인데 현재 화면 요구에 없다 |
| `popularity` | 공식 문서상 **"그날의 투표·조회·즐겨찾기·워치리스트 수 + 개봉일 + 총 투표 수 + 전날 점수"로 매일 재계산**된다. 값에 절대적 의미가 없고 계속 흐르므로 저장하면 그날의 스냅샷일 뿐이다. 콜드 스타트에는 `vote_average` + `vote_count` 하한이 더 안정적 |
| `original_language` | 현재 용도 없음. 필요해지면 그때 추가 |
| `original_title` 인덱스 | 검색이 `LIKE '%q%'`라 **선행 와일드카드**다. B-tree가 원리적으로 무의미하다(잔여 #20과 같은 이유) |

### 파급 항목

| 대상 | 변경 |
|---|---|
| `Movie` | ✅ 완료 (2026-08-24) — 필드 4개 추가. ⚠️ **`updateMetadata`가 6+개 파라미터가 되고 연속 `String`이 늘어난다** — 6-4에서 이미 경고한 위험이 커진다. 값 객체 도입을 재검토할 것(잔여 #25) |
| `MovieSyncPersister` | ✅ 완료 (2026-08-24) — `detail`에서 4개 값 저장. 이미 받고 있으므로 매핑만 추가 |
| `MovieRepository` | ✅ 완료 (2026-08-24) — `findByTitleContaining` → `findByTitleContainingOrOriginalTitleContaining` |
| `MovieDetailResponse` | `backdropPath`·`voteAverage`·`voteCount` + 우리 평점 집계(잔여 #24) |
| `MovieSummaryResponse` | 검색 결과에 `backdropPath`가 필요한지는 화면 확정 후 |

---

## 잔여 확인 항목

> ### 📋 6-5 구현 완료 (2026-08-20)
>
> **구현 완료** — #3·#7·#9·#13·#14·#15·#16·#17 (아래 표에 취소선으로 표시).
> 계측 로그(#4 title/person.name 폴백, #10 실패 제목)도 함께 들어갔다 — "나중에 실측한다"고만
> 적어두면 로그가 없어 실측 자체가 불가능했기 때문이다.
>
> **✅ 첫 시드(60편)로 판정 완료** — #4 · #8 · #11. 판정 근거는 **6-7** 참고.
>
> **⚠️ 아직 실측 못 함** — #10(제목 매칭 실패율). `box_office_record`가 **8건뿐**이라
> **역방향 시드를 검증하지 못했다.** D-2가 이를 **주 경로**로 골랐는데 아직 설계상 추론일 뿐이다.
> `POST /api/admin/box-office/sync`로 며칠치를 쌓은 뒤 재시도해야 한다.
>
> **조건부 유예** — #12(projection, 실제 지연 관측 시) · #19(인물명 한글화, 프론트 확인 후)
>
> **✅ 6-8 코드 구현 완료 (2026-08-23)** — `GET /api/movies/search` 신설. 상세는 아래
> 변경 이력 참고.
>
> **별도 세션** — #18(다중 인스턴스)

| # | 항목 | 처리 시점 |
|---|---|---|
| 1 | ~~6-0 미결 4건 확정 (D-1 / D-2 / D-3 / D-4)~~ | ✅ **전부 확정** (2026-08-13) |
| 1-b | ~~v11 스키마 델타 적용~~ | ✅ **적용 완료** (단 `display_order`가 `smallint`로 적용됨 → v12에서 교정) |
| 1-c | ~~v12 스키마 델타 적용~~ — `display_order` `smallint`→`int` 정오 + `movie.overview` `4000`→`1000` 롤백 | ✅ **적용 완료** (`cinemory_backup_v12.sql` 재덤프, `validate` 통과) |
| 2 | ~~`Movie.releaseDate` 수정 메서드 부재~~ | ✅ **종결** (6-3 ③ — `updateMetadata`에 포함) |
| 3 | ~~매핑 4종 Repository에 `deleteByMovieId` 추가~~ — 파생 쿼리가 아니라 `@Modifying(flushAutomatically = true) @Query` 벌크 DML로. `clearAutomatically`는 쓰지 않는다 (6-4) | ✅ **구현 완료** (2026-08-20) |
| 4 | ~~한국어 폴백 발동 빈도 실측~~ | ✅ **종결** (6-7 — 폴백 로직은 정상 동작. 실제 사안은 **TMDB의 인물명 한글화 커버리지 29%** 라는 데이터 한계였고 문제 정의 자체가 틀렸다. 수용하고 잔여 #19로 분리) |
| 5 | ~~`searchMovies` / `MovieSearchCondition` 설계~~ | ✅ **구현 완료** (2026-08-23 — `GET /api/movies/search` 신설. **`MovieSearchCondition`은 만들지 않는다**: `suggestions`가 TMDB에서 오는 이상 TMDB가 받는 파라미터 이상은 지원 불가하고, `registered` 쪽 필터는 프론트 화면이 없어 축을 모른다 → 잔여 #22) |
| 6 | ~~TMDB rate limit 실측 후 시드 배치 간격 조정~~ | ✅ **종결** (6-5 — 선제 스로틀링 없이 429 반응형 백오프로 확정. 순차 호출이라 50 req/s에 자연히 못 미친다) |
| 7 | ~~영화 상세의 cast 응답 분리~~ — 상세 제한(`displayOrder <= 20`)에 이어 전체 출연진용 `GET /api/movies/{id}/cast`(페이징, `MovieActorRepository.findByMovieIdOrderByDisplayOrderAsc(Long, Pageable)`) 추가 (controller 잔여 #10과 동일 건) | ✅ **구현 완료** (2026-08-20) |
| 8 | ~~`movie_actor` 인덱스 검토~~ | ✅ **종결 확정** (6-7-b — 테이블이 2,375 → **186,717행(79배)** 이 되는 동안 `EXPLAIN`의 `rows`는 **141 그대로**였다. `type=ref`/`key=uk_movie_actor`로 인덱스를 탄다. 판정 근거였던 "테이블 크기가 아니라 매칭 행 수"가 실측으로 확인됐다) |
| 9 | ~~`POST /api/movies/sync` 시큐리티 화이트리스트 검토~~ — 인증 필요 경로다. `SecurityConfig.PUBLIC_POST_ENDPOINTS`에 올리지 않아 기본값 `authenticated()`가 그대로 적용된다(별도 설정 불필요). `WhitelistRegressionTest`(5-7 A)가 미인증 200 불가를 자동 검증 | ✅ **구현 완료** (2026-08-20) |
| 10 | ~~박스오피스 역방향 시드의 제목 매칭 실패율 실측~~ | ✅ **종결** (6-7-b — 140건 중 127건 매칭 **90.7%**. **D-2가 역방향을 주 경로로 고른 근거가 처음으로 실측 검증**됐고, 역방향 시드→4-7 재매칭 2단 구조도 정상 동작. 단 140건은 레코드 수라 고유 제목은 더 적다는 한계는 남는다) |
| 11 | ~~길이 초과 후 컬럼 확장 판단~~ (6-3 ④) | ✅ **종결 — `character_name`만 확장** (6-7-b). 60편에선 절단 0건이었으나 **4,609편에서 29건 발생**(MAX가 정확히 100 = 절단의 증거). **6-3 ④의 예측대로 네 컬럼 중 `character_name`만 실제로 걸렸고**, 6-7이 경고한 표본 편향도 확인됐다 → **v14로 `varchar(255)` 확장**(적용 완료). ⚠️ 이미 잘린 29건은 ALTER로 복구 안 됨 — `resync` 필요. `title`(66/255)·`person.name`·`overview`(978/1000)는 절단 0건 |
| 12 | **`getMovieList` projection 전환 검토** — `movieRepository.findAll(pageable)`이 `Movie` 엔티티 전체를 로딩하는데 `MovieListItemResponse`는 `id`/`title`/`posterPath`/`releaseDate`만 쓴다. `overview`가 목록 조회에서 DB→앱까지 실려 왔다가 버려진다. 한 페이지 20건 × 최대 1000자면 60KB 안팎이라 **체감 지연 규모는 아니지만** 쓰지 않는 컬럼을 읽는 것은 맞다. projection 인터페이스 또는 DTO 직접 조회로 SELECT에서 제외 | 목록 화면 성능 이슈가 실제로 관측되면 (선제 최적화 금지) |
| 13 | ~~`PersonRepository.findByTmdbPersonIdIn` 추가~~ — 현재 리포지토리가 비어 있다. 6-4의 3단계(`Person` 통합 upsert)가 이것 없이는 N+1이다 | ✅ **구현 완료** (2026-08-20) |
| 14 | ~~`Person.updateProfile()`에 값 비교 추가~~ — `Genre.rename()`·`Country.rename()`과 달리 무조건 대입이라 재동기화마다 출연진 전원 UPDATE가 나간다. `profilePath`가 nullable이므로 `Objects.equals`를 쓸 것 | ✅ **구현 완료** (2026-08-20) |
| 15 | ~~`RoleTier.fromDisplayOrder(int)` 정적 팩토리 추가~~ — D-1 경계값 파생을 서비스 private이 아니라 enum에 둔다. `displayOrder`는 이미 `MovieActor`의 필드라 도메인 개념이고, 경계값과 가중치가 한 파일에 모이며 M3 추천에서 재사용된다 (`jpa-entity-spec.md`의 "판정 로직은 서비스가 갖는다" 기술을 뒤집는 것) | ✅ **구현 완료** (2026-08-20) |
| 16 | ~~`Movie.updateMetadata`에 `releaseDate` 파라미터 추가~~ — 6-3 ③ 확정. `@Builder` 필드 순서와 동일하게 유지할 것 | ✅ **구현 완료** (2026-08-20) |
| 17 | ~~`BoxOfficeRecordRepository.findUnmatchedTitles` 추가~~ — `(movieTitleSnapshot, openDate)` DISTINCT + `ORDER BY openDate DESC NULLS LAST` + `Pageable`. `UnmatchedBoxOfficeTitle` projection record는 `domain.boxoffice.repository`에 신설(API DTO가 아니라 쿼리 캐리어라 `dto` 패키지 대신) | ✅ **구현 완료** (2026-08-20) |
| 18 | **다중 인스턴스 안전성 — 시드 + 스케줄러** — `MovieSeedService`의 `AtomicBoolean`은 한 JVM 안에서만 유효하다. 서버를 늘리면 DB 락이나 실행 이력 테이블이 필요해진다. ⚠️ **2026-08-27 범위 확대** — 위험이 시드에만 있는 게 아니다. **`BoxOfficeScheduler`에 `@Scheduled` 크론이 2건** 있어 복제본을 늘리면 **인스턴스마다 발화해 박스오피스 수집이 중복 실행**된다. 이쪽이 오히려 자동 실행이라 더 조용히 터진다. **해법이 같으므로(DB 락 또는 실행 이력 테이블) 한 번에 처리한다.** 배포 구성 A(단일 인스턴스)에 머무는 동안은 **양쪽 다 무해**하며, 이 항목이 곧 A→B/C 전환 비용의 실체다 — 상세는 `CineMory_기획노트.md` **4-INF** | **다중화 시점** (= 배포 구성 B·C 전환 시). A 유지 중에는 착수 불필요 |
| 19 | **인물명 한글화 보강 검토** (6-7, #4에서 분리) — 4,609편 실측에서 **11.9%(13,589/113,909)로 악화**됐다(60편 표본 28.8%). 규모가 커지며 외화 비중이 올라간 결과이고, **외화 상세 화면은 사실상 전부 영문**이다. 폴백 문제가 아니라 **TMDB가 `name`에 영문을 채워 주는 데이터 한계**다. 보강 비용도 커졌다 — `also_known_as`는 credits에 없는 필드라 **11만 명 × 왕복 1회**(2,306명이던 때와 규모가 다르다) | 프론트 화면이 나온 뒤 체감 손실 확인 후 (선제 대응 금지) |
| 20 | **한글 검색 FULLTEXT 전환 검토** (6-8) — DB 제목 검색이 `LIKE '%q%'` 풀스캔이다. **선행 와일드카드는 B-tree 인덱스를 원리적으로 못 타므로** 일반 인덱스로는 해결되지 않고, `WITH PARSER ngram` FULLTEXT(v13 델타)가 필요하다. MySQL 기본 파서는 공백 기준이라 "베테랑"으로 "베테랑2"를 못 찾는다. 현재 60행·향후 수천 행 규모에선 풀스캔이 무해하다 | 실제 지연이 관측되면 (선제 최적화 금지) |
| 21 | **검색 결과 `adult` 필터링** (6-8) — `TmdbSearchResponse.Item`에서 `adult` 필드를 빼기로 해(사용자 결정) `include_adult=false` 기본값이 유일한 방어선이다. 만에 하나 성인 영화가 `suggestions`에 섞이면 **목록에는 보이는데 선택하면 `ADULT_CONTENT_NOT_ALLOWED`(400)** 가 나는 경로가 열린다. **트리거 — `POST /api/movies/sync`가 실제로 이 코드를 반환하면** 그게 신호다(계측 수단이 이미 있는 셈) | 해당 에러가 실제 발생하면 |
| 22 | **`MovieSearchCondition` 승격 검토** (6-8) — `registered`가 DB 기반이라 장르 필터·정렬이 **기술적으로 가능**해졌으나, 프론트 화면이 없어 어떤 축이 필요한지 모른다. 상상해서 만들면 쓰이지 않는 축이 남는다. `query` 하나로 시작한다 | **기본 프론트 구현 후** (사용자 확정) |
| 23 | ~~`POST /api/admin/movies/resync` 신설~~ (6-9) — 전체 재동기화. 시드가 `existsByTmdbId`로 건너뛰므로 **v13 신규 컬럼을 채울 수단이 지금 없다.** 게다가 `vote_average`가 시간에 따라 변해 **상시 필요**하다(v13과 무관하게도 TMDB 데이터 갱신 반영 경로가 아예 없다). **설계 확정 (6-9)** — `MovieSeedService`에 `resync(fromId, limit)` 추가(`running` 플래그 **공유 필수**), `id` 커서 방식, `ResyncResult(updated, skipped, stoppedByRateLimit, lastProcessedId)` 별도 record. ⚠️ 429 `break` 시 `lastProcessedId`를 전진시키지 말 것 | ✅ **구현 완료** (2026-08-24) — 설계 그대로 반영, 실행 자체(운영자가 실제로 호출해 기존 적재분을 채우는 것)는 별도 |
| 24 | **영화 상세의 평점 표시** (6-9) — `MovieDetailResponse`에 평점 필드가 없고 `ReviewRepository`에 집계 쿼리도 없다. **TMDB 평점(영화 정보)과 우리 평점(앱 사용자 평가)을 함께** 표시한다. 우리 평점은 컬럼이 아니라 `AVG(rating)` 집계 — 2,000편 규모면 실시간으로 충분 | 프론트 상세 화면 구현 시 |
| 25 | **`Movie.updateMetadata` 값 객체 도입 재검토** (6-9) — v13으로 파라미터가 9개가 됐다. 6-4에서 "호출부가 한 곳뿐이라 `@Builder` 순서 유지로 충분"이라 판단했는데 그 전제가 약해진다 | ⚠️ **v13 엔티티 반영 시 재검토했으나 값 객체는 도입하지 않았다** (2026-08-24) — 신규 4개를 기존 시그니처 뒤에 덧붙여 연속 `String` 구간이 2개(`{title,posterPath,overview}`, `{originalTitle,backdropPath}`)로 늘었을 뿐 호출부는 여전히 `MovieSyncPersister` 한 곳이다. 위험이 완전히 해소된 건 아니다 — 호출부가 2곳 이상이 되거나 파라미터가 더 늘면 재검토 |
| 26 | **`movie-seed-runbook.md` 보강** (6-7-b) — 이번 실행에서 **`seed.log`가 유실**돼 `SeedResult` 8건과 절단 원본 길이를 확인하지 못했다. PowerShell `*>`가 **덮어쓰기**라 시드 후 `bootRun` 재시작에 날아간다(`*>>`가 이어쓰기). 그 밖에 ① **검증 절이 로그 grep만** — SQL 4종(규모·길이 분포·`EXPLAIN`·국가 구성)이 빠졌고, `grep -c`만으로는 **6-7의 "절단은 값 패턴이 아니라 길이로 판정" 교훈이 반영 안 됨**, ② **박스오피스 2주는 #10 표본으로 부족**(고유 제목 20~30편), ③ **토큰 30분 만료가 40~50분 작업 중간에 반드시 발생**하는데 PowerShell `catch`가 401을 삼켜 **뒤쪽 호출이 조용히 실패**, ④ 실행 결과를 **6-7-b에 기록하라는 안내 부재**, ⑤ 로그 인코딩(PS 5.1 = UTF-16LE)이라 `grep`이 못 읽음 | 다음 대규모 시드 전 |
| 27 | ⚠️ **v14 코드 반영 — `character_name` 상한 100 → 255** (6-7-b). **DB만 넓혀서는 아무 효과가 없다.** 두 곳을 함께 고쳐야 한다: ① `MovieActor.characterName`의 `@Column(length = 100)` → `255`, ② `MovieSyncPersister.CHARACTER_NAME_MAX_LENGTH` 상수 `100` → `255`. **②를 놓치면 절단이 계속되고 아무 신호도 없다** — `ddl-auto=validate`는 **길이를 검증하지 않아**(v12 델타 [3]) 기동이 그대로 통과하고, `truncate()`는 WARN만 남기며 정상 종료한다. ①만 고쳐도 마찬가지다. 그 뒤 **이미 잘린 29건은 `POST /api/admin/movies/resync`로 재적재**해야 복구된다(ALTER로는 안 됨). v13 이전 60편의 신규 컬럼 NULL 보정과 **같은 resync 한 번으로 함께 해소**된다 | **즉시** (다음 시드/resync 전) |
| 28 | **`character_name` v15 확장 여부 — `varchar(255)`로도 부족한 케이스 3건** (2026-08-27 resync 실측). 확장 후 절단이 29건 → **3건**으로 줄었으나 0은 아니다. 이번엔 **자르지 않은 원본 길이**가 로그에 남았다 — `tmdbId=35`에서 **300자·270자**, `tmdbId=9473`에서 **348자**. ⚠️ **v14의 상한 근거가 약했던 것이 드러났다** — *"실측 원본이 100자를 갓 넘는 수준이라 2.5배면 충분"* 이라 적었는데, 그 "실측 원본"은 **이미 100자로 잘린 값이라 진짜 길이를 알 수 없는 상태**였다. **상한에 걸린 값으로 상한을 정한 셈**이다(6-7이 예상한 다역·성우 유형은 맞았고 꼬리가 예상보다 길었다). **지금은 확장하지 않는다** — 186,717행 중 **3건(0.0016%)** 이고, 절단이 WARN과 함께 graceful하며, **348자짜리 배역명은 애초에 UI에서 전부 표시할 값이 아니다**(DB 상한보다 표시 정책이 먼저 걸린다). 무엇보다 **복구에 resync 30분이 또 든다.** `varchar(255)→(500)`은 utf8mb4에서 길이 접두사가 그대로 2바이트라 **여전히 `ALGORITHM=INSTANT`** 이므로 ALTER 자체는 싸다 — 비싼 건 재적재다 | **다음에 resync를 돌릴 일이 생길 때 함께** (단독으로는 값이 안 됨) |

---

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-08-24 | **discover 시드 구성 전략 코드 반영 완료 (6-5, 2026-08-23 확정분).** 확정된 설계 그대로 구현했으며 설계 변경은 없다. **변경** — `TmdbClient.discoverMovies(int page)` → `discoverMovies(int page, String originalLanguage, Integer voteCountGte, String sortBy, Integer year)`로 확장하고 `region=KR`/`sort_by=popularity.desc` 하드코딩을 제거, 4개 전부 `queryParamIfPresent`로 null이면 파라미터를 붙이지 않는다(`sortBy`가 null이면 TMDB 기본 정렬인 `popularity.desc`가 그대로 적용되므로 "최근작" 프로필처럼 `sort_by` 생략이 곧 `popularity.desc`다). `MovieSeedService.seedFromDiscover`를 동일하게 5파라미터로 확장해 그대로 pass-through, `AdminController`의 `POST /api/admin/movies/seed/discover`에 `lang`/`minVotes`/`sortBy`/`year` 쿼리 파라미터 추가(전부 `required=false`, 기본값 판단 없이 `null` 그대로 Service에 전달 — 5-6-A와 동일 원칙). **의도적으로 하지 않은 것** — 프로필 4종(한국 영화/전역 인지도/최근작/박스오피스 역방향)을 코드 상수로 박지 않았다. 스펙이 "임계값은 실행 결과를 보고 조정할 값"이라 명시했고, 각 프로필은 운영자가 호출 시점에 쿼리 파라미터로 넘긴다. `cinemory.movie.seed.discover-default-pages` 설정은 `pages`가 비었을 때의 폴백으로만 유지(프로필별 페이지 수는 6-5 표 참고: 한국 50 / 전역 100 / 최근작 20×5). **검증** — `compileJava`·전체 테스트 스위트 통과. 실제 적재 실행(각 프로필 호출)은 별도 — 호출 1회가 5~10분이라 로그를 파일로 남기고 잔여 #11(길이 초과)·#10(제목 매칭 실패율)을 5,000편 규모에서 재확인해야 한다(스펙 본문 참고) |
| 2026-08-24 | **6-9 실 DB 검증 완료 — `MovieRepository` 검색 확장 + resync 실행으로 원어 검색 문제 해소 확인.** 로컬 실 DB(v13 적용)에 대해 `POST /api/admin/movies/resync`를 실제로 호출해 기존 적재분 60편을 전수 갱신했다(`updated:60, skipped:0, stoppedByRateLimit:false`). `SELECT COUNT(*), SUM(original_title IS NOT NULL), SUM(vote_average IS NOT NULL) FROM movie`로 확인한 결과 **60/60/60** — v13 신규 컬럼이 기존 적재분에도 전부 채워졌다. 이 과정에서 **`MovieRepository.findByTitleContaining` → `findByTitleContainingOrOriginalTitleContaining`로의 검색 확장이 아직 반영되지 않은 채 남아 있던 것을 발견** — resync 직후에도 `GET /api/movies/search?query=avatar`가 `registered: []`였다(6-9가 풀려는 문제의 재현). `MovieRepository`에 두 컬럼을 OR로 묶는 파생 쿼리를 추가하고 `MovieSearchService.search`의 호출부를 `(query, query, pageable)`로 수정한 뒤 재검증 — **`query=avatar`가 `registered`에 매칭 결과를 반환**하는 것을 확인했다(6-9의 존재 이유였던 문제가 실제로 해소됨). **검증 방식** — ADMIN 인가가 필요해 관리자 계정이 없었고, `user.role`을 올리는 API는 의도적으로 없다(security-spec.md 확정 사항)는 이유로 DB 직접 조작(SQL 실행)은 전부 사용자가 로컬 터미널에서 직접 수행했다(정상 회원가입 API로 계정을 만들고 그 계정 하나만 `role='ADMIN'`으로 UPDATE). **검증** — `compileJava`·전체 테스트 스위트 통과, 로컬 서버(`bootRun`, 포트 8080) 기동 후 실제 HTTP 호출로 확인 |
| 2026-08-24 | **6-9 `resync` 엔드포인트 구현 완료 — 잔여 #23 종결.** 확정된 설계(6-9, 2026-08-23) 그대로 구현했으며 설계 변경은 없다. **신규** — `MovieSeedService.resync(fromId, limit)`(시드 2종과 `running` 플래그 공유), `MovieRepository.findByIdGreaterThanOrderByIdAsc`(id 커서, 조건 없음), `ResyncResult(updated, skipped, stoppedByRateLimit, lastProcessedId)`, `MovieResyncResponse`(응답 DTO), `POST /api/admin/movies/resync?fromId=&limit=`(`AdminController`, `domain/movie/controller`). 429 도달 시 시드와 동일하게 `break` 후 정상 반환하되 **`lastProcessedId`는 그 영화가 처리되지 못했으므로 전진시키지 않는다**(성공·`skipped` 처리 이후에만 갱신 — 설계 문서의 경고 그대로 지켰다). **설정 추가** — `cinemory.movie.seed.resync-default-limit=200`(편당 1왕복만 필요해 `box-office-default-limit=100`보다 여유를 뒀다). **`WhitelistRegressionTest` 변경 불필요** — `RequestMappingHandlerMapping`에서 엔드포인트를 동적으로 수집해 `/api/admin/**` 스윕에 자동으로 걸린다. **이번 세션 범위 밖** — 실제로 운영자가 이 엔드포인트를 호출해 기존 적재분(v12 이전, 신규 컬럼 전부 `NULL`)을 채우는 실행 자체는 별도(배포 후 운영 작업). **검증** — `compileJava`·전체 테스트 스위트 통과 |
| 2026-08-24 | **6-9 엔티티 반영 완료 — v13 스키마 델타 적용 후 코드 반영.** 확정된 스펙(6-9) 그대로 구현했으며 설계 변경은 없다. **변경** — `Movie`에 필드 4개(`originalTitle`/`backdropPath`/`voteAverage`/`voteCount`) 추가, `updateMetadata`를 9파라미터로 확장(기존 5개 뒤에 신규 4개를 덧붙임 — 필드 선언 순서가 아니라 잔여 #16 때와 같은 관례), `TmdbMovieDetailResponse`에 `backdropPath`/`voteAverage`(`Double`)/`voteCount` 원본 필드와 `normalizedVoteAverage()`(`HALF_UP` 반올림으로 `decimal(3,1)` 스케일 매칭 — `movie_genre` 가중치와 같은 라운딩 모드) 추가, `MovieSyncPersister.upsertMovie`가 4개 값을 매핑(`original_title`도 `title`과 동일하게 255자 `truncate()` 적용 — 같은 컬럼 길이라 잘라야 안전). **잔여 #25 재검토 결과 — 값 객체 도입하지 않음**: 호출부가 여전히 `MovieSyncPersister` 한 곳뿐이라 즉시 필요하지 않다고 판단했으나, 연속 `String` 구간이 2개로 늘어 위험이 남아 있다(호출부 추가·파라미터 추가 시 재검토). **이번 세션 범위 밖** — `MovieRepository.findByTitleContainingOrOriginalTitleContaining`(검색 확장), `MovieDetailResponse`/`MovieSummaryResponse`(평점·배경 노출)는 6-9 파급 항목 표에 별도 항목으로 남아 있고 서비스/컨트롤러 계층 작업이라 이번엔 제외. `POST /api/admin/movies/resync`(잔여 #23)도 여전히 미구현 — 기존 적재분(v12 이전)이 신규 컬럼 전부 `NULL`인 채로 남아 있다. **검증** — `./gradlew compileJava`·전체 테스트 스위트 통과(`ddl-auto=validate` 포함, v13 적용된 실 DB 대상) |
| 2026-08-23 | **6-8 코드 구현 완료 — 잔여 #5 종결.** 확정된 스펙 그대로 구현했으며 설계 변경은 없다. **신규** — `GET /api/movies/search`(`MovieController`), `MovieSearchService`(트랜잭션 없음, `MovieQueryService`와 분리), `MovieSearchResponse`/`MovieSearchSuggestionResponse`(응답 DTO), `MovieRepository.findByTitleContaining`(registered, `LIKE` 풀스캔)·`findByTmdbIdIn`(suggestions 중복 제거), `TmdbClient.searchMovieForSuggestions`(429 백오프를 타지 않는 별도 메서드 — 기존 `searchMovie`는 시드 전용으로 그대로 두고 새 메서드를 분리했다. 같은 메서드에 분기를 넣으면 시드 경로까지 백오프를 잃을 위험이 있었다), `TmdbConfig`·`KoficConfig`에 connect 2s/read 3s 타임아웃(`SimpleClientHttpRequestFactory`) — 스펙이 예시로 든 `ClientHttpRequestFactorySettings`는 이 프로젝트의 Boot 4.0.5 의존성 트리에 실제로 존재하지 않아(jar 전수조사로 확인) `spring-web`이 항상 제공하는 `SimpleClientHttpRequestFactory.setConnectTimeout(Duration)`으로 대체했다 — 동일한 결과(2s/3s 타임아웃)를 얻는다. **정리** — `MovieQueryService.searchMovies(Pageable)`(구 `getMovieList`와 동작이 같던 미사용 placeholder) 제거. `query` 빈 문자열 검증은 컨트롤러 `@Validated` 대신 `TheaterQueryService`와 같은 원칙으로 서비스에서 `INVALID_INPUT_VALUE`를 던진다(이 프로젝트는 `@RequestParam` 레벨 bean validation을 쓰지 않고 `GlobalExceptionHandler`에도 `ConstraintViolationException` 핸들러가 없다). **SecurityConfig 변경 없음** — `/api/movies/**`가 이미 `PUBLIC_GET_ENDPOINTS`에 있어 `/search`가 자동으로 커버된다(`WhitelistRegressionTest`로 확인). **검증** — 전체 스위트 통과(`WhitelistRegressionTest` 포함), `ddl-auto=validate` 통과(스키마 변경 없음) |
| 2026-08-20 | **6-5·6-6 코드 구현 완료.** 확정된 스펙 그대로 구현했으며 설계 변경은 없다. **신규** — `MovieSeedService`(`seedFromBoxOffice`/`seedFromDiscover`, 무트랜잭션, `AtomicBoolean` 중복 실행 방어), `SeedResult`, `TmdbSearchResponse`/`TmdbDiscoverResponse`(6-5 DTO), `TmdbClient.searchMovie`/`discoverMovies` + `executeWithRetry`(429 반응형 백오프, 기존 `fetchXxx` 4종도 이 헬퍼로 통합), `BoxOfficeRecordRepository.findUnmatchedTitles` + `UnmatchedBoxOfficeTitle` projection, `MovieActorRepository.findByMovieIdOrderByDisplayOrderAsc(Long, Pageable)` + `MovieQueryService.getMovieCast` + `GET /api/movies/{id}/cast`(잔여 #7 종결), 관리자 엔드포인트 4종(`domain/genre`·`domain/country`·`domain/movie` 각 `AdminController` — 장르/국가/박스오피스 역방향/discover 시드) + `POST /api/movies/sync`(`MovieSyncController`, 잔여 #9 종결 — 화이트리스트 미등재만으로 기본 `authenticated()` 적용됨을 `WhitelistRegressionTest`로 확인), `ErrorCode` 3종(`REFERENCE_DATA_NOT_SEEDED`/`TMDB_RATE_LIMITED`/`SEED_ALREADY_RUNNING`). **잔여 #4·#10 계측 로그 추가** — `MovieSyncPersister`에 title/person.name 폴백 발동 `WARN`(편당 집계), `MovieSeedService`에 제목 매칭 실패 시 실패 제목 자체를 `WARN`. **잔여 #17 종결.** **설정 추가** — `cinemory.movie.seed.{box-office-default-limit=100, discover-default-pages=5}`. ⚠️ **구현 중 발견 — Spring 빈 이름 충돌.** 패키지가 달라도 클래스명이 같으면(`AdminController`) 빈 이름이 단순 클래스명으로 겹쳐 `ConflictingBeanDefinitionException`이 난다(`domain/boxoffice`에 이미 있던 것과 신규 3개가 충돌). `@RestController("movieAdminController")`처럼 새로 추가한 3개에만 명시 이름을 줘서 해소했다 — `controller-layer-spec.md` 5-6-C ③에 정정 기록. **검증** — `WhitelistRegressionTest`(admin 403, 미인증 sync 비-200, permitAll 401 아님) 및 전체 스위트(16 클래스, 121건) 통과, `ddl-auto=validate` 통과. **범위에서 제외** — `GET /api/movies/search`(DB+TMDB 병합 검색)는 `MovieSearchCondition` 미설계로 계속 보류(잔여 #5, 온디맨드 sync 트리거만 구현) |
| 2026-08-20 | **6-3·6-4 코드 구현 완료.** 확정된 스펙 그대로 구현했으며 설계 변경은 없다. **신규** — `TmdbMovieDetailResponse`/`TmdbCredits`/`TmdbCast`/`TmdbCrew`/`TmdbProductionCountry`(6-3 DTO), `TmdbClient.fetchMovieDetail`(404 → `TMDB_MOVIE_NOT_FOUND` 구분 처리), `MovieSyncService`(무트랜잭션, `syncFromTmdb` 단일 공개 메서드), `MovieSyncPersister`(`@Transactional persist()` — adult 검사 → Movie upsert → Person 통합 upsert → 매핑 4종 벌크 삭제 → 매핑 4종 재삽입 5단계), `ErrorCode` 4종(`TMDB_MOVIE_NOT_FOUND`/`ADULT_CONTENT_NOT_ALLOWED`/`GENRE_NOT_FOUND`/`COUNTRY_NOT_FOUND`). **잔여 #3·#13·#14·#15·#16 종결** — `MovieActor`/`MovieGenre`/`MovieCountry`/`MovieDirector` 4종 Repository에 `@Modifying(flushAutomatically=true) @Query` 벌크 `deleteByMovieId` 추가, `PersonRepository.findByTmdbPersonIdIn` 추가, `Person.updateProfile()`에 `Objects.equals` 값 비교 추가, `RoleTier.fromDisplayOrder(int)` 정적 팩토리 추가, `Movie.updateMetadata`에 `releaseDate` 파라미터 추가. **범위에서 제외** — 6-5 관리자 엔드포인트(`POST /api/admin/movies/seed/*`, `POST /api/movies/sync`)와 `MovieSeedService`는 이번 세션에 포함되지 않았다. `getMovieDetail`의 cast 전체 응답 분리(잔여 #7)도 컨트롤러 계층 건이라 제외. 스키마는 이미 v12 기준으로 작성된 엔티티(`Movie.overview length=1000`, `MovieActor.displayOrder` `Integer`)를 그대로 사용했고, 이번 세션에서 DB에 v12 델타를 직접 적용하지는 않았다(잔여 1-c 그대로 유지) — 실행 전 `docs/schema/v12-delta.sql` 적용 필요 |
| 2026-08-27 | **잔여 #27 종결(v14 코드 반영) · `resync` 전량 실행 · 잔여 #28 신설.** ① **#27 종결** — `MovieActor.characterName`의 `@Column(length)`와 `MovieSyncPersister.CHARACTER_NAME_MAX_LENGTH`를 함께 **100 → 255**로 올렸다. DB만 넓히면 효과가 없는 유형이라 별도 항목으로 세워 뒀던 것이다. ② **`resync` 전량 실행** — 25라운드(200건 커서), **29분 30초**, `updated=4,587` / `skipped=22`(합 4,609로 **적재 전량과 일치**), 429 0건. **마지막 라운드가 `updated=0` + 커서 미전진으로 끝나** 6-9가 설계한 종료 신호가 의도대로 동작했다. `skipped` 22건이 `id` 1970~2370 **한 구간에 몰렸고**(라운드 10에 21건) 그 라운드만 3분 6초로 두 배 느렸다 — 원인 미확인. **토큰 30분 만료가 시작 24분 시점에 실제로 발생**해 재발급했다(잔여 #26이 지적한 그대로이며, 이번엔 명시적으로 처리). 로그도 이번엔 살아남았다. ③ ⚠️ **잔여 #28 신설 — `varchar(255)`로도 부족한 3건.** 절단이 29건 → **3건**으로 줄었으나 0이 아니다. 이번 resync에서 **처음으로 자르지 않은 원본 길이**를 봤다 — 300·270·348자. **v14의 상한 근거가 약했던 것이 드러났다**: *"실측 원본이 100자를 갓 넘는 수준"* 이라 판단했지만 그 값은 **이미 100자로 잘린 뒤**라 진짜 길이를 알 수 없었다. **상한에 걸린 값으로 상한을 정한 셈**이다(6-7이 지목한 다역·성우 유형은 맞았고, 꼬리가 예상보다 길었다). **확장은 보류** — 186,717행 중 3건(0.0016%), 절단이 graceful, 348자 배역명은 UI 표시 대상이 아니며, 복구에 resync 30분이 또 든다. `v14-delta.sql`에 정오 주석을 남겼다. ④ **6-5 discover 프로필 pass-through 코드 반영** — `region=KR`·`sort_by=popularity.desc` 하드코딩 철회, `lang`/`minVotes`/`sortBy`/`year`를 `queryParamIfPresent`로(3계층). ⑤ **잔여 #18 범위 확대** — `BoxOfficeScheduler`의 `@Scheduled` 2건 포함(인프라 상의 결과, 기획노트 **4-INF**) |
| 2026-08-24 | **본 시드 4,609편 실행 — 6-7-b 신설, 잔여 #8·#10·#11 종결. v14 델타(`character_name` 확장).** **6-7(60편)이 경고한 표본 편향이 실제로 결과를 바꿨다.** ⚠️ **잔여 #11 — 절단이 발생했다.** 60편에선 `character_name` 최대 30자·절단 0건이었는데 **4,609편에서 최대 100자(= 상한)·29건**이 나왔다. MAX가 정확히 100인 것이 절단의 증거다(`truncate()`가 `97자 + "..."`로 100자를 만든다). **6-3 ④의 예측이 정확히 맞았다** — 네 컬럼 중 `character_name`만 근거가 있다고 했고(TMDB가 다역을 슬래시로 연결), 6-7이 *"인기작 60편은 데이터가 가장 정돈된 부류"* 라고 단 경고도 그대로 확인됐다. **→ v14로 `varchar(255)` 확장**(사용자 직접 적용). `varchar(100)→(255)`는 utf8mb4에서 길이 접두사가 둘 다 2바이트라 **`ALGORITHM=INSTANT`** 로 18만 행도 몇 초다. ⚠️ **이미 잘린 29건은 ALTER로 복구되지 않는다** — `resync`가 필요하며, v13의 기존 60편 NULL 보정과 **한 번에 해소 가능**하다. **잔여 #8 종결** — 테이블이 2,375 → 186,717행(**79배**)이 되는 동안 `EXPLAIN`의 `rows`가 **141 그대로**였다. 판정 근거였던 *"테이블 크기가 아니라 매칭 행 수"* 가 실측으로 확인됐다. **잔여 #10 종결** — 박스오피스 140건 중 127건 매칭(**90.7%**)으로 **D-2가 역방향을 주 경로로 고른 근거가 처음으로 실측 검증**됐다. **잔여 #19는 악화** — 한글 인물명이 28.8% → **11.9%**(외화 비중 증가). 보강 비용도 2,306명 → **11만 명 × 왕복**으로 커졌다. **6-7의 `overview` 판정도 갱신** — *"여유 1.5배"* 는 684자(60편) 기준이었고 실제는 **978자로 상한의 97.8%** 다. 절단 0건인 이유는 여유가 아니라 **TMDB가 1000자로 제한**해서다(D-4). ⚠️ **계측 실패 1건** — `seed.log`가 유실돼 `SeedResult` 8건과 절단 원본 길이를 못 봤다. PowerShell `*>`가 덮어쓰기라 `bootRun` 재시작에 날아갔다. 런북 보강을 잔여 #26으로 등록(검증 절이 로그 grep만이라 SQL 4종 누락, 박스오피스 2주는 #10 표본 부족, 토큰 30분 만료가 40~50분 작업 중간에 반드시 발생하는데 `catch`가 401을 삼켜 뒤쪽 호출이 조용히 실패, 결과 기록처 부재, 로그 인코딩) |
| 2026-08-23 | **6-5에 discover 시드 구성 전략 추가 — 목표 5,000편.** "페이지를 늘린다"는 단순 접근을 폐기했다. ⚠️ **`popularity.desc`가 1페이지부터 무명작을 섞는다** — TMDB 공식 예시 응답(기본 정렬 1페이지)에 `vote_count` **4·20·21**짜리가 7,519짜리(아바타: 물의 길)와 나란히 있다. `popularity`는 "그날의 조회·투표 활동"이라 일시적 화제작이 올라오기 때문이며, **250페이지까지 갈 것도 없다.** → **인지도 축을 `vote_count.gte`로 확정**하고 정렬도 `vote_count.desc`를 기본으로 삼는다("많이 본 영화" = 인지도 그 자체이고 매일 변하지도 않는다). ⚠️ **한국 영화는 표가 적다**(「길복순」 184표) — 전역 기준 300을 그대로 적용하면 **한국 영화가 통째로 날아간다.** 기준을 나눠 **프로필 4종**으로 확정: ① 한국 영화(`with_original_language=ko`, `vote_count.gte=30`) ② 전역 인지도(`vote_count.gte=300`, `vote_count.desc`) ③ 최근작(연도별 2021~2025, `vote_count.gte=100`) ④ 박스오피스 역방향. **`region=KR` 철회** — 한국 **개봉작**(대부분 할리우드)을 뽑을 뿐이라 진짜 한국 영화가 안 들어오고, `vote_count` 하한이 이미 품질을 보장하므로 결과를 좁힐 이유가 없다. **프로필을 코드에 박지 않고 파라미터 pass-through**로 한다 — 임계값은 실행 결과를 보고 조정할 값이라 상수면 조정마다 빌드·재기동이 필요하다. ⚠️ **실행 순서에서 박스오피스가 먼저다** — discover가 먼저 돌면 역방향 시드 대상이 `alreadyExists`로 빠져 **잔여 #10(제목 매칭 실패율)의 표본이 줄어든다.** 부수 — 프로필당 100페이지 이하라 TMDB 페이지 상한(통상 500)에도 안 걸리고, 겹침이 `alreadyExists`로 빠지므로 **실제 적재는 4,000~4,500 예상**이다 |
| 2026-08-23 | **6-9에 `resync` 설계 추가.** 잔여 #23을 "필요하다"에서 구현 가능한 스펙으로 채웠다. ① **`MovieSeedService`에 둔다** — 이름만 시드가 아닐 뿐 같은 종류의 배치라 규칙(`running` 공유 · 참조 가드 · `@Transactional` 금지 · 429 `break` · 편별 `try-catch`)이 거의 전부 같다. 별도 서비스로 빼면 전부 복제하게 된다. ⚠️ **`running` 플래그 공유가 핵심** — 별도로 두면 resync와 시드가 동시에 돌아 TMDB 요청이 두 배가 되고, 6-5에서 `AtomicBoolean`으로 막으려던 상황이 재현된다. ② **`id` 커서 방식, 조건 없음** — `WHERE original_title IS NULL` 같은 v13 전용 조건은 다음 컬럼 추가 때 또 바뀐다. **`updated_at` 기준은 기각** — "오래된 것부터"가 자연스러워 보이지만 `updateMetadata`가 무조건 대입해도 **Hibernate dirty check가 값을 비교**해 실제 변경이 없으면 UPDATE가 안 나가고 `updated_at`도 그대로라, **같은 영화를 계속 다시 잡는다.** ③ **`limit` 분할 필수** — 2,000편 한 요청은 약 7분이라 HTTP 타임아웃에 걸린다(시드가 `pages`로 나누는 것과 같은 이유). ④ **`SeedResult` 재사용 기각** — `matched`(새로 적재)·`alreadyExists`(사전 필터)가 resync에서 의미가 맞지 않는다. 6-5에서 시드 2종 통합을 정당화한 근거가 *"필드 구조가 같고 의미만 다르다"* 였는데 여기는 **필드 자체가 안 맞는다.** `ResyncResult(updated, skipped, stoppedByRateLimit, lastProcessedId)` 신설. ⑤ ⚠️ **429 `break` 시 `lastProcessedId`를 전진시키지 말 것** — 그 영화를 처리하지 못했으므로 전진시키면 재개할 때 건너뛴다 |
| 2026-08-23 | **6-9 신설 — `movie` 메타데이터 4컬럼 보강 (v13).** 6-8 실호출 검증 중 **원어 검색이 안 된다는 것을 실측**했다(`query=avatar` → `registered` 0건 / `query=아바타` → 1건). `movie.title`이 ko-KR 제목이라 `LIKE '%avatar%'`에 안 걸리는데, TMDB `/search/movie`는 "original, translated and alternative titles"를 전부 검색한다 — **같은 검색어로 두 섹션의 매칭 기준이 어긋나** 사용자가 이미 기록한 영화가 `registered`에 안 나온다. ⚠️ **`original_title`은 이미 받고 있었다** — 6-3 ⑦의 폴백용으로만 쓰고 저장하지 않았다. 함께 추가한 것 — **`backdrop_path`**(상세 화면 16:9 배경, `null`이 흔해 프론트 폴백 필요), **`vote_average`/`vote_count`**. 평점은 **`MovieDetailResponse`에 필드가 아예 없었다** — *"여러 계정으로 평점을 만들어 평균"* 이라는 원안은 영화당 표본 1~3개라 데모에서 성립하지 않는다(TMDB는 아바타 22,061명). 다만 **대체 관계가 아니라 병기**한다 — TMDB 평점은 영화 자체의 정보고 우리 평점은 이 앱 사용자들의 평가라, 섞으면 기록 앱의 존재 이유가 흐려진다. 우리 평점은 컬럼이 아니라 집계 쿼리(잔여 #24). `vote_count`를 세트로 넣은 것은 **"3명 10.0"과 "22,061명 8.4"를 구별**하기 위함이다. **기각 4건** — `tagline`(한국어 번역률 미실측. D-4·#11에서 "추정으로 스키마를 넓히지 않는다"를 지켰고 `resync`가 생기므로 나중에 7분), `status`(`release_date > today`로 대체 가능, 고유 가치인 "제작 취소"·"개봉일 미정"이 현재 화면 요구에 없음), `popularity`(공식 문서상 **매일 재계산**되며 "전날 점수"가 입력에 들어가 계속 흐른다 — 저장하면 그날 스냅샷일 뿐), `original_language`(용도 없음). ⚠️ **`resync` 엔드포인트가 선택이 아니라 필수가 됐다**(잔여 #23) — 기존 적재분이 전부 NULL인데 시드가 `existsByTmdbId`로 건너뛰어 채울 수단이 없고, `vote_average`가 시간에 따라 변해 상시 필요하다. 재적재 비용은 2,000편 ≈ 7분으로 크지 않다. 부수로 **`updateMetadata` 파라미터가 6개 이상**이 되어 6-4의 연속 `String` 경고가 커졌다(잔여 #25) |
| 2026-08-23 | **6-8 `registered` 정렬 확정 — `releaseDate DESC, id DESC`.** 6-8 초판에 **정렬 기준이 통째로 빠져 있었다.** C안 논의 때 *"폴백(DB)은 `releaseDate DESC NULLS LAST`"* 로 정해놓고 **B안으로 전환하면서 옮겨 적지 않은 것**이다. C안에서 DB는 폴백이었지만 B안에서 `registered`는 상시 경로라 오히려 더 중요해졌는데도 놓쳤다. 구현(`MovieSearchService`)도 `Sort` 없이 나갔다. **지금 테스트하면 문제가 안 보인다** — `title` 인덱스가 없어 풀스캔 → PK 순이고, 현재 데이터가 `discover?sort_by=popularity.desc`로 들어와 **우연히 인기순처럼 보인다.** 박스오피스 역방향·온디맨드 `sync`가 섞이면 의미를 잃는다. **더 실질적인 이유는 페이징 일관성** — `ORDER BY` 없는 `LIMIT/OFFSET`은 페이지 간 순서를 보장하지 않아 같은 행이 중복·누락될 수 있고, 2,000편 규모에서 재현이 어려운 버그가 된다. 구현 시 확정 2건 — **`id`를 tie-breaker로 병기**(같은 날 개봉작이 여럿이면 `releaseDate`만으로는 다시 불안정), **`NULLS LAST`는 명시하지 않음**(MySQL은 NULL을 최소값으로 봐 `DESC`면 자동으로 마지막. 명시하면 Hibernate가 MySQL 미지원 문법을 `CASE WHEN ... IS NULL`로 에뮬레이션해 인덱스 활용을 막는다) |
| 2026-08-20 | **6-8 검색 설계 확정 — 잔여 #5 종결, 6장 설계 완결.** 4-2부터 `/api/movies/search`를 막아온 `MovieSearchCondition`의 결론이다. **핵심은 두 집합을 섞지 않는 것** — `{registered: PageResponse, suggestions: [...]}` 2섹션 구조다. 섞으면 `totalElements`를 계산할 수 없다(DB 4건 + TMDB 9건이 몇 건인지 알려면 겹치는 수를 알아야 하고, 그건 TMDB 전체를 받아야만 나온다). 섹션 분리로 **등록 여부가 필드가 아니라 구조로 표현**되면서 D-2 ③의 **`movieId` nullable 안이 폐기**됐고, `registered`가 완전한 `PageResponse`라 **5-0 규약 예외도 안 생긴다.** TMDB 장애 시엔 `suggestions`만 비고 `registered`는 정상이라 **별도 폴백 경로가 불필요**하다. ⚠️ **C안(TMDB 단일 출처 + `movieId` 라벨링)을 채택 직전까지 갔다가 기각했다** — `totalElements` 불성립·중복 제거·DB 제목 인덱스 **세 쟁점을 한 번에 없애는 우아함**이 있었으나, 우리 DB가 우리 제품에서 구경꾼이 되고 장르 가중치·출연진을 쌓아놓고도 검색에 못 쓰며 검색 품질을 통제 불가능한 TMDB 관련도 순위에 통째로 위임하게 된다. **"쟁점이 사라진다"가 곧 "설계가 옳다"는 뜻은 아니었다.** **`MovieSearchCondition`은 만들지 않는다** — TMDB `/search/movie`가 `query`·`year`·`page` 외에 **장르 필터도 정렬 옵션도 받지 않음을 공식 문서로 확인**했고(`suggestions`가 TMDB에서 오는 이상 그 이상은 지원 불가), `registered` 쪽 필터는 프론트 화면이 없어 축을 모른다(잔여 #22). 부수 발견 — **`TmdbConfig`·`KoficConfig`에 타임아웃이 없어 사실상 무한 대기**였다(connect 2s / read 3s), **검색 경로가 429 백오프(최대 7초)를 타고 있어** 사용자 대면 경로에서 스레드를 오래 점유한다(시드엔 옳지만 검색엔 독 — 즉시 포기), **`MovieQueryService`가 클래스 레벨 `@Transactional(readOnly = true)`** 라 검색을 넣으면 읽기 트랜잭션 안에서 HTTP 호출을 하게 된다(`MovieSearchService` 분리). 잔여 #20(FULLTEXT+ngram)·#21(`adult` 필터)·#22(조건 객체) 신규 등록 |
| 2026-08-20 | **최초 시드 60편 실측 — 잔여 #4·#8·#11 판정 (6-7 신설).** 설계만 하고 실데이터를 넣어본 적이 없던 것들을 처음 실측했다. **설계 검증** — **배우 겸 감독이 실제로 존재**했다. 6-4에서 `Person` 통합 upsert를 3단계로 분리한 근거가 정확히 이것이었고, 60편이라는 작은 표본에서 나왔으니 "흔한 케이스"라는 판단도 맞았다. **#11 조치 불필요** — 절단 0건(`title` 33/255, `overview` 684/1000, `character_name` 30/100). **`overview` 684는 D-4 롤백이 옳았다는 직접 증거다.** ⚠️ **검증 지표를 한 번 잘못 잡았다** — 절단을 `LIKE '%...'`로 셌더니 11건이 나왔는데 **오탐**이었다. 최대 길이가 684라 1000자 절단은 불가능했고, 11건은 TMDB 원문이 원래 말줄임표로 끝나는 것이었다. **절단은 값 패턴이 아니라 길이로 판정해야 한다.** **#8 인덱스 불필요** — 영화당 cast 최대 141행(평균 39.6, 꼬리가 두껍다). **평균만 보고 "걱정보다 낫다"고 한 초판 해석은 성급했다.** 그럼에도 추가하지 않는 이유는 여유가 아니라 **`uk_movie_actor`의 선두 컬럼이 `movie_id`라 조회가 이미 인덱스를 타기 때문**이다. 남는 141행 filesort는 무시 가능하고, 상세는 `display_order <= 20`으로 걸러 실제 조인이 21행이다. **#4는 문제 정의 자체가 틀렸다** — 한글 인물명이 28.8%(664/2,306)인데 **폴백 문제가 아니다.** 폴백이 71% 발동했다면 `WARN`이 수천 줄 나왔을 것이고, 실제로는 `name`에 영문이 채워져 온다. 즉 **TMDB의 인물명 한글화 커버리지 한계**이며 대응 방법이 전혀 다르다. 보강하려면 `/person/{id}`의 `also_known_as`가 필요한데 **credits에 없는 필드라 인물당 왕복 1회**가 추가된다(60편에도 2,306회). **수용하고 잔여 #19로 분리.** ⚠️ **#10은 실측 못 했다** — `box_office_record`가 8건뿐이라 **역방향 시드가 미검증 상태로 남았다.** D-2가 이를 주 경로로 고른 근거가 아직 추론이다. 아울러 **표본 편향을 6-7에 명시** — `region=KR` 인기작 60편은 데이터가 가장 정돈된 부류이고, `character_name`이 길어지는 유형(애니 성우 다역, 다큐 `Self` 표기)은 섞였을 확률이 낮다. 대규모 시드 시 `truncate()` WARN 재확인이 필요하다 |
| 2026-08-19 | **잔여 18건 정리 — 6-5 구현 착수 전 점검.** 10건 종결 확인, 나머지를 처리 시점별로 묶고 **실측 항목의 계측 전제 2건을 발견해 보강**했다. ⚠️ **#10(제목 매칭 실패율)과 #4(한국어 폴백 빈도)는 로그가 없어 실측 자체가 불가능한 상태였다.** 전자는 `SeedResult.skipped` 카운트만 있어 *"500건 중 120건 실패"* 는 알아도 원인이 부제인지 띄어쓰기인지 시리즈 표기인지 몰라 정규화 규칙을 만들 수 없다 → **실패 제목 자체를 `WARN`으로** 남기도록 6-5 구현 스펙에 추가. 후자는 폴백이 이미 구현돼 있으나(`resolveTitle`/`resolveName`) **조용히 원어로 대체**해서 빈도 측정도 사후 추적("왜 이 영화만 영어 제목이지?")도 불가능했다 → `title`은 `WARN`, `person.name`은 cast 200명마다 호출되므로 **편당 카운트 집계**로 분리. **"나중에 실측한다"고 미뤄둔 항목이 실은 구현 시점에 준비가 필요했다**는 점이 요지다. 상태 정정 2건 — **#4는 폴백 *방식* 이 6-3 ⑦에서 이미 확정**됐으므로 "방식 확정 필요"에서 "빈도 실측"으로 성격 변경, **#7은 상세 제한이 구현 완료**라 `GET /api/movies/{id}/cast` 엔드포인트만 남은 부분 완료로 표시. #11은 `truncate()`에 `WARN`+서로게이트 페어 보정이 이미 들어가 있어 **계측 준비 완료**. 아울러 잔여 표 앞에 **처리 시점별 그룹 요약**을 붙여 구현 항목·계측 전제·첫 시드 후 판단·별도 세션을 구분했다 |
| 2026-08-19 | **6-5·6-6 확정 — 6장 설계 종료.** 초안이 **조용히 실패하는 경로 두 개**를 갖고 있었다. ① **참조 테이블 시드를 건너뛰면 전편이 정상 종료로 보인다** — 순서를 틀리면 `syncFromTmdb`가 `GENRE_NOT_FOUND`로 죽는데, "실패는 `skipped`로 집계"라는 규칙 때문에 시드가 `matched=0, skipped=500`으로 **끝까지 완주**한다. 로그만 보면 제목 매칭 문제로 오독된다. 시드 진입점에서 `count() == 0` 검사 후 **예외로 중단**(`SeedResult`를 반환하지 않는 것이 핵심)하도록 확정하고 `REFERENCE_DATA_NOT_SEEDED`를 신설했다 — `GENRE_NOT_FOUND` 재사용을 기각한 이유는 원인·대응이 다르고 **후자만 운영자에게 줄 다음 행동이 명확**해서다. 온디맨드·`persist()`에는 넣지 않는다(단건은 오독 여지가 없고, 편당 count 쿼리 2개가 추가된다). ② **429가 나도 남은 수백 편을 계속 두드린다** — 모든 `RestClientException`을 `EXTERNAL_API_ERROR`로 감싸 개별 skip으로 처리하던 탓이다. TMDB의 rate limit이 **API 키가 아니라 IP 기준**이라 최악의 경우 차단으로 간다. `TmdbClient` 내부 백오프(`Retry-After` 우선, 없으면 1s→2s→4s, 3회) 후 **`TMDB_RATE_LIMITED`로 구분**하고 시드는 **`break` 후 정상 반환**하도록 확정 — 예외로 던지면 거기까지의 진척이 사라져 이어받기 판단이 불가능하다. **6-4의 빈 분리(non-transactional) 덕에 백오프 대기가 커넥션을 점유하지 않는다.** ③ **왕복 계산 정정** — *"`append_to_response`로 영화당 1회"* 는 상세 조회만 해당한다. 역방향 시드는 `/search` + `/movie` = **편당 2회**, discover는 **페이지당 21회**다. 또 `existsByTmdbId` 사전 필터는 tmdbId를 알아야 걸 수 있어 **역방향은 이미 적재된 영화에도 search 왕복이 발생**한다(절반만 절약). ④ **선제 `sleep` 철회** — 순차 호출이라 초당 3~10회로 TMDB 한도(약 50 req/s)에 자연히 못 미친다. 수치 없는 *"요청 간 간격을 둔다"* 를 반응형 백오프로 대체했다. ⑤ **참조 시드 엔드포인트를 장르/국가 2개로 분리** — 초안의 `/api/admin/reference-data/seed`는 **자기 규칙("패키지는 Service 소유")을 위반**했다. 두 서비스가 각각 다른 도메인 소유라 합칠 패키지가 없다. ⑥ **DISTINCT 조회 신설**(잔여 #17) — 박스오피스는 일별 수집이라 인기작이 수십 행으로 쌓여 같은 제목을 수십 번 검색한다. **`ORDER BY openDate DESC`가 429 중단과 맞물린다** — 멈췄을 때 가치 높은 것부터 확보돼 있어야 한다. ⑦ **시드 메서드 `@Transactional` 금지 명시** — `TheaterSeedService`가 붙이고 있어 따라 하기 쉬우나 성격이 다르다(외부 호출 없는 단일 배치). 붙이면 수백 편이 한 트랜잭션에 묶이고, `MovieSyncPersister`가 **외부 트랜잭션에 참여**해 6-4의 빈 분리가 통째로 무력해지며, 백오프 대기가 커넥션을 점유한다. ⑧ **`SeedResult` 4필드로 통합** — 429 중단이 생기면서 `{matched, skipped}`만으로는 "다 끝남"과 "중간에 멈춤"이 구별되지 않아 `stoppedByRateLimit`이 필요해졌다. `alreadyExists`를 `skipped`와 분리한 것은 전자가 정상 동작이라 섞으면 "매칭 실패가 많다"로 오독되기 때문. **초안의 "시드 2종은 결과 DTO가 다르다"는 분리 근거를 철회**했다 — 실제로는 필드가 같고 `skipped`의 의미만 다르다(분리 자체는 실패 양상·이어받기 지점 근거로 유지). ⑨ **중복 실행은 `AtomicBoolean` + 409**(`SEED_ALREADY_RUNNING`) — ②를 넣은 뒤로 동시 실행이 더 나빠졌다(요청 2배 → 429 자초 → 양쪽 다 중단). 한 JVM 한정이라 다중화 시 재검토(잔여 #18) |
| 2026-08-19 | **6-4 확정 — 4-2 시그니처 폐기.** 초안이 유지하려던 4-2 시그니처는 **그대로는 구현이 불가능**했다. `syncCountries(Movie, List<TmdbCountryDto>)`에 D-3이 요구하는 **`origin_country`를 넘길 파라미터가 없다** — 4-2가 D-1·D-3 확정 이전에 작성된 탓이다. 이를 계기로 구조를 다시 봤다. ① **공개 메서드를 `syncFromTmdb` 하나로 축소**하고 나머지 4개는 private으로 내렸다. public으로 남기면 각각에 `@Transactional`을 붙이게 되어 **한 영화 동기화가 4개 트랜잭션으로 분해**되는데, 재동기화가 "전량 삭제 후 재삽입"이라 중간 실패 시 **출연진이 삭제만 되고 재삽입이 안 된 상태로 커밋**된다. private이면 자기호출이라 `@Transactional`이 걸리지 않아 경계가 하나로 문법적으로 강제된다. ② **빈을 `MovieSyncService`(트랜잭션 없음) + `MovieSyncPersister`(`@Transactional`)로 분리** — HTTP 왕복이 트랜잭션 안에 있으면 커넥션을 쥔 채 네트워크를 기다려 시드 수백 편에서 풀이 마른다. 같은 클래스 안에서 fetch/persist로 나누는 순진한 분리는 **자기호출이라 프록시를 안 타서 `@Transactional`이 조용히 무시**되고, 트랜잭션 0개가 되어 오히려 더 나빠진다. `TransactionTemplate`도 해법이지만 프로젝트가 선언적 트랜잭션으로 일관돼 있어 채택하지 않았다. ③ **`Person` 통합 upsert를 3단계로 신설** — 초안처럼 cast/crew가 각각 upsert하면 **배우 겸 감독**에서 `uk_person_tmdb_id`를 위반한다(같은 트랜잭션 flush 전이라 두 번째 조회가 첫 번째 저장을 못 본다). 흔한 케이스라 반드시 재현된다. **성능이 아니라 정합성 요구**이며, 부수로 N+1도 사라진다. ④ **매핑 삭제를 `@Modifying` 벌크 DML로** — 파생 delete는 select 후 건별 `remove()`라 출연진 200명이면 201쿼리이고, Hibernate ActionQueue가 **INSERT를 DELETE보다 먼저** 내보내 재삽입과 섞이면 유니크 위반이 난다. 통상 벌크 DML의 stale 엔티티 위험은 **CLAUDE.md가 양방향 컬렉션을 금지한 덕에 이 프로젝트엔 없다.** `clearAutomatically = true`는 금지 — 작업 중인 `Movie`가 detach돼 dirty checking이 사라진다. ⑤ **멱등 계약을 주체별로 분리 명시** — `syncFromTmdb`는 항상 최신화, 건너뛰기는 `MovieSeedService`의 `existsByTmdbId` 사전 필터 책임, `POST /api/movies/sync`는 필터하지 않는다. ⑥ **이미 저장된 영화가 `adult`로 바뀐 경우**는 예외+`WARN`만 — `watch_record`/`review`/`wish_movie`/`collection_movie` FK가 전부 **RESTRICT**라 삭제 자체가 불가능하다. ⑦ **한국어 폴백에서 "`language` 없이 재요청" 안을 기각** — 영화당 왕복이 2배가 된다. 같은 응답의 `original_title`/`original_name`으로 폴백한다. ⑧ **네이밍** — `Person` upsert가 빠져나가 나머지는 "동기화"가 아니라 매핑 행을 쓰는 일이므로 `applyGenres`/`applyCountries`/`applyCast`/`applyDirectors`로 바꿨다(`syncCrew` → `applyDirectors`). ⑨ **`updateMetadata` 5파라미터의 연속 `String` 3개 위험**을 명시 — 값 객체는 만들지 않는다(레코드도 위치 기반이라 위험이 옮겨갈 뿐). 호출부가 한 곳이므로 `@Builder` 순서 유지로 리뷰에서 잡는다. **잔여 #13~#16 신규 등록**(`PersonRepository`, `Person.updateProfile` 값 비교, `RoleTier.fromDisplayOrder`, `updateMetadata` 시그니처) |
| 2026-08-19 | **6-3 확정 + D-4 정정(롤백). v12 델타 신설.** TMDB 공식 OpenAPI 정의와 대조해 초안 누락 9건을 채웠다. **⚠️ D-4를 되돌렸다** — *"TMDB overview가 1000자를 넘을 수 있다"* 는 v11의 전제가 **사실이 아니다.** TMDB 스태프가 *"we limit movie overviews to 1000 characters"* 로 명시하고 있어, `varchar(1000)`은 임의값이 아니라 **TMDB 입력 제한에 맞춰 설계된 값**이었다. 실데이터도 공식 문서도 확인하지 않고 단정한 것이 오류다. v12에서 `varchar(1000)`으로 복원한다. **성능이 롤백 사유가 아님을 명시** — `varchar`는 가변 길이라 같은 데이터면 바이트가 같고, 선언 길이가 문제되던 경로(`MEMORY` 임시 테이블의 고정 폭 패딩, filesort 고정 폭 행)는 MySQL 8.0의 TempTable 엔진과 packed addon field로 해소됐으며 `overview`에는 인덱스도 없다. 되돌리는 이유는 **스키마가 표현하던 외부 API 계약 정보를 잃었다**는 것뿐이다. 같은 오류를 반복하지 않기 위해 **길이 초과 3곳(`title`/`person.name`/`character_name`)도 컬럼 확장 대신 절단+`WARN`으로 확정**했다(잔여 #11로 실측 후 재판단). 그 밖에 확정 — ① **`adult == true` 영화는 `syncFromTmdb` 진입점에서 거부**(`ADULT_CONTENT_NOT_ALLOWED`). discover/search는 `include_adult=false`가 기본이라 안전하지만 **`POST /api/movies/sync`는 사용자가 임의 `tmdbId`를 보내는 경로**라 필터가 없었다. 검사는 매핑보다 먼저 해야 한다 — `Person` upsert가 먼저 돌면 출연진이 `person`에 남는다. ② **`runtime == 0` → `null` 정규화** (OpenAPI에 `default: 0` 명시. 미개봉작이 `null`이 아니라 `0`으로 와서 "0분"이 표시된다). ③ **`releaseDate`를 `updateMetadata`에 포함**(잔여 #2 종결) — 시드가 미개봉작을 담는 이상 개봉일 확정·정정이 반드시 발생한다. ④ **`cast[].id`(사람 ID)와 `cast[].cast_id`(크레딧 ID) 혼동 경고** — 후자를 `Person.tmdbPersonId`에 넣으면 **예외 없이** 잘못된 인물이 쌓인다. ⑤ **감독 중복 제거** — 같은 사람이 `job == "Director"`로 두 번 나오면 `uk_movie_director` 위반인데 초안은 cast의 1인 2역만 다뤘다. ⑥ **`person.name`도 한국어 폴백 필요**(`original_name`) — 6-4 폴백 절이 `title`/`overview`만 다뤘으나 `person.name`도 not null이다. ⑦ **`RoundingMode.HALF_UP` 명시** 및 **장르 빈 배열 시 `1/N`의 `ArithmeticException` 가드**. ⑧ **DTO 재사용 범위** — `/movie/{id}`의 `genres[]`는 6-1의 `TmdbGenreListResponse.Item`과 구조가 같아 재사용, `production_countries[]`는 `{iso_3166_1, name}`으로 `TmdbCountryListItem`과 달라 별도 DTO. ⑨ **절단 헬퍼의 서로게이트 페어 주의** — `substring`이 페어 중간을 자르면 깨진 문자가 저장된다. **부수 등록** — 잔여 #12(`getMovieList`가 목록에 쓰지 않는 `overview`까지 엔티티 전체를 로딩. 60KB 안팎이라 체감 규모는 아니므로 **선제 최적화 금지**로 조건부 등록) |
| 2026-08-13 | **6-1·6-2 구현 완료 및 구현 중 조정 4건.** ① **`Country.rename()` 신설** — 6-1 초안이 *"둘 다 멱등 upsert — 이미 있으면 `Genre.rename()`으로 이름만 갱신"* 이라 적었으나 **`Country`에는 그 메서드가 없었다**(스펙 공백). `Genre`와 대칭으로 추가했다. 국가명은 거의 변하지 않지만 TMDB의 한국어 지역화가 시간이 지나며 채워지는 경우가 있어 재적재로 반영할 수단이 필요하다. ② **`/configuration/countries`는 루트 레벨 JSON 배열**이다(공식 OpenAPI 정의로 확인). `/genre/movie/list`가 `{genres:[...]}` 래퍼인 것과 달라 `ParameterizedTypeReference<List<...>>`로 받아야 한다. 또 필드가 snake_case(`iso_3166_1`)인데 이 프로젝트는 Jackson 네이밍 전략을 바꾸지 않았으므로(`KoficDailyBoxOfficeResponse`는 KOBIS가 camelCase라 우연히 문제가 없었다) **`@JsonProperty` 명시 매핑**이 필요하다. ③ **국가명 `native_name` → `english_name` 폴백** — `language=ko-KR`로 요청해도 TMDB 국가명 지역화 범위가 완전하지 않아 비거나 영문으로 온다. `country.name`이 not null이라 폴백 없이는 저장이 실패한다. ④ **설정 키 정리** — `application-secret.yml`에 삭제된 `TestController` 잔재로 `tmdb.api.token`이 남아 있어 `kofic`과 같은 2단 구조인 **`tmdb.access-token`** 으로 맞추고 `application.yml`에 `tmdb.base-url`을 추가했다. 부수로 **저장 전 필터링**(코드 길이 2, 이름 공백, 키 중복)을 넣었다 — 셋 다 `DataIntegrityViolationException`으로 **트랜잭션 전체가 롤백되는** 유형이라 한 건 때문에 정상 250여 건이 통째로 날아간다(4-7 `hasRequiredFields`와 같은 원칙). **호출 진입점(`POST /api/admin/reference-data/seed`)은 붙이지 않았다** — 관리자 엔드포인트는 6-5에 시드 3종으로 함께 정의돼 있어 그쪽에서 한 번에 처리한다 |
| 2026-08-13 | **D-2·D-3·D-4 확정 — 6-0 미결 4건 종결.** **D-2 = C안(하이브리드)**, 세부 3건 확정. ① **시드 대상에서 `/movie/popular`(문서 원안)을 기각** — 전역 인기작이라 할리우드 위주로 채워지는데, `box_office_record`가 전량 `movie_id = NULL`로 쌓이는 현 상황에서 시드가 할리우드면 **콜드 스타트만 풀리고 4-7 재매칭은 여전히 아무것도 못 맞춰 홈 화면 박스오피스가 그대로 빈다.** 두 문제 중 하나만 푸는 셈이라, 문서에 없던 **박스오피스 역방향 시드**(미매칭 `movieTitleSnapshot` + `openDate`로 `/search/movie` 역조회)를 주 경로로 신설하고 `/discover?region=KR`을 보조로 병행한다. 역방향 시드는 **`movie`를 만들 뿐 `movie_id`를 직접 채우지 않는다** — 매칭 책임을 4-7 배치 하나로 유지해야 규칙이 이원화되지 않는다. 제목 매칭 실패는 예외가 아니라 `skipped` 집계(한 편 때문에 시드 전체가 멈추면 안 된다). ② **1회성 관리자 엔드포인트**(4-7 `TheaterSeedService` 패턴, 멱등) — 주기 배치를 두지 않는 이유는 지속적 보충을 이미 온디맨드가 맡기 때문이고, 인기작 주기 갱신은 사용자가 실제로 기록하는 영화와 무관하게 rate limit만 상시 소모한다. 실패 양상이 달라(역방향=제목 매칭 실패 / discover=페이지 순회) 엔드포인트를 2개로 분리. ③ **검색은 DB+TMDB 병합, 선택 시에만 동기화** — 검색 20편을 미리 동기화하면 보통 1편만 선택되므로 rate limit·쓰레기 데이터 양쪽에서 손해다. **부수 발견: 응답 DTO 계약이 깨진다** — 미등록 항목이 섞여 `movieId`가 없는 행이 생기므로 `movieId`를 nullable로 두고 `tmdbId`를 병기하며, 두 출처 병합이라 **`totalElements`가 성립하지 않아 `Slice` 등 별도 형태가 필요**하다. 이로써 `MovieSearchCondition` 설계가 보류 항목에서 **선행 조건으로 승격**(잔여 #5). `POST /api/movies/sync`는 인증 필수(잔여 #9). **D-3 = B안** — `origin_country` 우선, 없으면 배열 첫 번째. 구현 주의 3건 추가: `origin_country`는 **단일 값이 아니라 배열**이라 `[0]`을 쓸 것, **`origin_country`가 `production_countries`에 없을 수 있고** 그 경우 대표를 세우면 나머지 가중치의 분모 N과 어긋나므로 폴백할 것, `N=1`이면 공식이 1.0으로 수렴해 판정이 무의미. **D-4 = 컬럼 확장(`varchar(1000)` → `varchar(4000)`)** — 원안 A(절단)의 근거가 *"스키마 변경은 별도 승인 필요"* 였고 승인을 받았다. **v11 델타가 미적용 상태라 묶는 비용이 0**인 시점. `TEXT` 대신 `varchar(4000)`을 택한 이유는 ① 현 스키마에 TEXT 컬럼이 하나도 없어 동질성이 깨지고 ② `@Column(columnDefinition = "TEXT")`가 매핑 타입(VARCHAR)과 실제 컬럼 타입(LONGVARCHAR) 불일치로 **`ddl-auto=validate` 기동 실패를 유발할 수 있어** `length = 65535` 우회가 필요한 반면, `varchar(4000)`은 `length = 4000` 한 줄로 끝나고 리스크가 없기 때문. 절단 로직은 제거하지 않고 `3997자 + "..."` + **`WARN` 로그**로 남긴다 — 발동 자체가 "상한 가정이 틀렸다"는 신호이고, 조용히 데이터를 잃거나 배치가 죽는 쪽이 더 나쁘다 |
| 2026-08-13 | **D-1 확정 — 절대 순번 `0~4 LEAD / 5~9 SUPPORTING / 10~20 MINOR / 21~ EXTRA`, cast 전량 저장.** 초안의 권장안은 "절대 순번 + 상위 20명 컷"이었으나, **컷은 사용자의 출연진 정보 확인을 제약**하므로 채택하지 않았다. 대신 **표시 범위와 가중치 범위를 분리** — 전량 저장하되 21번 이후에 가중치 0인 `EXTRA`를 부여해 추천 집계에서 구조적으로 배제한다. 이로써 영화당 배우 선호 기여 총점이 출연진 수와 무관하게 **5.6으로 고정**되어, D-1을 연 원인인 "MINOR 총합이 LEAD 총합을 넘는 오염"이 정의상 발생하지 않는다. **`EXTRA` enum을 택하고 `role_tier` nullable화를 기각한 이유** — null은 소비 지점 전체로 전파되고, 특히 `ORDER BY role_tier ASC`에서 MySQL이 NULL을 맨 앞에 놓아 단역이 최상단에 오는 버그가 즉시 발생한다. 가중치 0.0은 집계 필터를 잊어도 오염이 0이라 규칙을 데이터에 고정한다. **`display_order` 컬럼을 함께 추가** — tier만으로는 그룹 내부(EXTRA 180명 사이) 순서가 없고, 6-4의 재동기화가 "전량 삭제 후 재삽입"이라 `id` 삽입 순에 기대면 매 동기화마다 순서가 흔들린다. "자르지 않는다"는 결정의 목적이 순서 컬럼 없이는 달성되지 않는다. 부수 발견 — ① TMDB `order`는 연속 정수 보장이 없어 배열 인덱스가 아닌 필드값을 저장해야 함, ② 1인 2역 시 `uk_movie_actor` 위반 가능(최소 `order` 우선으로 중복 제거), ③ 전량 저장으로 `getMovieDetail` 응답 비대화(잔여 #7)와 `(movie_id, display_order)` 인덱스 부재(잔여 #8) 등록. **v11 델타 신설** |
| 2026-08-11 | **초안 작성.** 4-2에서 시그니처만 확정하고 미뤄둔 `MovieSyncService`의 구현 스펙. 착수 전 확정이 필요한 **미결 4건(6-0)** 을 분리해 앞에 배치했다. **D-1(`role_tier` 경계값)에서 기획 원안의 비율 방식에 결함을 발견** — 출연진 수 편차가 커서 상위 비율로 자르면 200명짜리 영화의 MINOR 총합(18.0)이 LEAD 총합(10.0)을 넘어 선호 배우 집계가 출연진 규모에 오염된다. 절대 순번 + 상위 N명 컷을 권장안으로 제시. **D-3** — TMDB `production_countries`가 "대표국"을 명시하지 않아 기획노트의 `(N+1)/(N²+1)` 공식을 그대로 적용할 수 없음을 확인, `origin_country` 폴백 방식 제안. **D-4** — `movie.overview`가 length 1000이라 TMDB 응답이 초과 시 적재 배치가 죽는다(실데이터 전까지 드러나지 않는 유형). 그 밖에 `genre`/`country` 참조 테이블 선행 적재가 영화 동기화의 하드 선행 조건임을 6-1로 분리했고, 매핑 테이블 재동기화를 "전량 삭제 후 재삽입"으로 제안하면서 `deleteByMovieId` 부재를 잔여로 등록 |
