# CineMory TMDB 연동 설계 스펙 (Step6) — **초안**

`service-layer-spec.md` 4-2에서 `MovieSyncService`를 **시그니처만 확정하고 구현을 미뤄둔** 부분을
채우는 문서다. **실제 코드는 Claude Code가 이 문서를 보고 작성**하며, 여기서는 패턴/시그니처/
설계 결정만 명시한다.

> ## ⚠️ 이 문서는 초안이다
>
> **6-0의 미결 4건이 확정되기 전에는 코드 세션을 열지 않는다.** 미정 상태로 넘기면
> Claude Code가 임의값을 박아 넣고, 그 값이 추천(M3) 결과 전체에 그대로 반영된다.

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
| 6-0 | **착수 전 확정 필요 (미결 4건)** | 🔲 **논의 필요** |
| 6-1 | 참조 테이블 선행 적재 (`genre`, `country`) | 🔲 초안 |
| 6-2 | `TmdbClient` 인프라 | 🔲 초안 |
| 6-3 | 도메인 매핑 — TMDB 응답 ↔ 엔티티 | 🔲 초안 |
| 6-4 | `MovieSyncService` 구현 | 🔲 초안 |
| 6-5 | 적재 전략 (시드 / 온디맨드) | 🔲 초안 |
| 6-6 | ErrorCode · 잔여 | 🔲 초안 |

---

## 6-0. 착수 전 확정 필요 (미결 4건)

### ⚠️ D-1. `role_tier` 경계값 — **가장 중요**

기획노트는 *"`order` / 전체 출연진 수 기준 상위 비율 → LEAD / SUPPORTING / MINOR"* 로 적고
있으나 **수치가 없다.** 그런데 이 값은 단순 분류가 아니라 **추천 가중치에 직접 곱해진다**
(`RoleTier`: LEAD 0.5 / SUPPORTING 0.4 / MINOR 0.1).

**⚠️ 비율 방식 그대로 가면 문제가 있다.**

TMDB `cast[]`는 영화당 10명대부터 200명 이상까지 편차가 크다. 상위 10%를 LEAD로 잡으면
출연진 200명짜리 영화의 LEAD가 **20명**이 된다. 더 큰 문제는 합계다.

```
출연진 200명 영화:  MINOR 180명 × 0.1 = 18.0
                    LEAD    20명 × 0.5 = 10.0     ← 주연보다 단역 총합이 크다
출연진  15명 영화:  MINOR   10명 × 0.1 =  1.0
                    LEAD     2명 × 0.5 =  1.0
```

같은 "이 영화를 좋아함"이 출연진 수에 따라 전혀 다른 점수를 만든다. **선호 배우 집계가
출연진 규모에 오염된다.**

| 안 | 방식 | 평가 |
|---|---|---|
| **A. 절대 순번** | `order` 0~2 = LEAD / 3~9 = SUPPORTING / 10~ = MINOR | 예측 가능하고 영화 간 비교 가능. TMDB `order`가 이미 중요도 순 정렬이라 의미도 맞음 |
| B. 비율 (기획 원안) | 상위 x% / y% / 나머지 | 출연진 수 편차에 취약 (위 문제) |
| C. 하이브리드 | 비율 + 절대 상한 | 정확하지만 경계 케이스 설명이 복잡해짐 |

> **함께 정해야 할 것 — 적재 대상 자체를 자를지.** TMDB `cast`를 전부 저장하면 `movie_actor`가
> 영화당 수백 행이 되고, 추천에는 기여하지 않으면서 목록 조회(4-2 상세 5쿼리)만 무겁게 한다.
> **상위 N명(예: 20명)만 저장**하면 위 합계 문제도 같이 완화된다.
> 잘라내면 "출연진 전체 보기" 화면은 불가능해지는데, 현재 기획에 그 화면은 없다.

**→ 권장: A안(절대 순번) + 상위 20명 컷.** 확정 필요.

### ⚠️ D-2. 초기 적재 전략

| 안 | 방식 | 트레이드오프 |
|---|---|---|
| A. 배치 시드 | `/movie/popular` 등으로 N편 선적재 | 콜드 스타트 해결. 시드에 없는 영화는 기록 불가 |
| B. 온디맨드 | 검색 결과에서 선택 시 그 영화만 상세 동기화 | 무한한 커버리지. **첫 사용자가 빈 화면을 본다** |
| **C. 하이브리드** | 시드로 초기 데이터 + 온디맨드로 보충 | 둘 다 필요한 이유가 명확 |

**→ 권장: C안.** 홈 화면(박스오피스·인기작)에는 시드가 필요하고, "내가 본 영화 기록"에는
온디맨드가 필요하다. 하나만으로는 어느 쪽도 성립하지 않는다.

확정할 세부:
- 시드 규모(몇 편?)와 기준(`/movie/popular` vs `/discover/movie` 한국 개봉작)
- 시드를 **1회성**으로 볼지(4-7 `TheaterSeedService`처럼), 주기 배치로 둘지
- 온디맨드 진입점 — 검색 응답에 TMDB 결과를 섞을지, 우리 DB만 검색할지

> ⚠️ **온디맨드는 `searchMovies` 설계와 맞물린다.** `controller-layer-spec.md` 잔여 #3에서
> `MovieSearchCondition` 미설계를 이유로 `/api/movies/search`를 보류해뒀는데, D-2를 확정하면
> 그 설계도 함께 열어야 한다.

### ⚠️ D-3. `production_countries`에서 "대표국"을 어떻게 정하는가

기획노트의 국가 가중치 공식은 **1위(대표)와 나머지를 구분**한다.

```
1위(대표)   = (N+1) / (N²+1)
나머지 각각 =  N    / (N²+1)
```

그런데 **TMDB `production_countries[]`는 "대표"를 명시하지 않는다.** 배열 순서가 있을 뿐이고,
그 순서가 제작 기여도 순이라는 보장이 문서에 없다.

- **A. 배열 첫 번째를 대표로 간주** — 단순. 다만 근거가 약하다
- **B. `origin_country` 필드를 사용** — 존재 시 더 정확. 없는 경우 A로 폴백
- C. 대표 구분을 포기하고 `1/N` 균등(장르와 동일) — 공식을 버리게 됨

**→ 권장: B안(있으면 `origin_country`, 없으면 배열 첫 번째).** 확정 필요.

### ⚠️ D-4. `overview` 길이 초과 처리

**`movie.overview`는 `length 1000`인데 TMDB overview는 이를 넘을 수 있다.** 그대로 넣으면
적재 배치가 `DataIntegrityViolationException`으로 죽는다. 실데이터를 넣기 전까지는 드러나지
않는 종류의 실패다.

| 안 | 방식 |
|---|---|
| **A. Service에서 절단** | 997자 + `...`. 스키마 무변경 |
| B. 컬럼 확장 | `TEXT`로 변경 — 스키마 v11 델타 필요 |

**→ 권장: A안.** 상세 화면 표시용이라 절단이 실질 손실을 만들지 않고, 스키마 변경은
`CLAUDE.md`의 "임의 변경 금지" 원칙상 별도 승인이 필요하다.

---

## 6-1. 참조 테이블 선행 적재 (초안)

**`genre`·`country`가 비어 있으면 `movie_genre`·`movie_country`를 만들 수 없다.**
영화 동기화보다 **먼저** 채워야 한다.

| 테이블 | 출처 | 키 |
|---|---|---|
| `genre` | TMDB `/genre/movie/list?language=ko-KR` | `tmdbGenreId` (`uk_genre_tmdb_id`) |
| `country` | ISO 3166-1 alpha-2 목록 (TMDB `/configuration/countries`) | `code` (`uk_country_code`) |

- 둘 다 **멱등 upsert** — 이미 있으면 `Genre.rename()`으로 이름만 갱신, 없으면 `of()` 후 저장.
- 영화 동기화 중 미등록 장르/국가를 만나면 **그 자리에서 생성하지 않고 실패시킨다.**
  참조 테이블은 선행 적재로만 채워 출처를 하나로 유지한다(정체불명 행 방지).
  → `GENRE_NOT_FOUND` / `COUNTRY_NOT_FOUND`

> `person`은 다르다. 인물은 수가 무한하고 영화마다 새로 등장하므로 **동기화 중 upsert**가 맞다.

---

## 6-2. `TmdbClient` 인프라 (초안)

```
global/infra/tmdb
 ├─ TmdbClient.java          (RestClient 기반 — KoficClient와 동일 골격)
 ├─ TmdbProperties.java      (@ConfigurationProperties, baseUrl + accessToken)
 ├─ TmdbConfig.java
 └─ dto/…                    (TMDB 응답 전용 DTO — 도메인에 노출 금지)
```

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
| 검색 | `/search/movie?query=&language=ko-KR` |
| 인기작 (시드) | `/movie/popular` 또는 `/discover/movie` |
| 장르 목록 | `/genre/movie/list?language=ko-KR` |

- **`append_to_response=credits`로 상세와 출연진을 1회 호출에 묶는다.** 나눠 부르면 영화당
  왕복이 2배가 되고, 시드 적재에서 그대로 소요 시간이 된다.

---

## 6-3. 도메인 매핑 (초안)

### `Movie`

| 엔티티 필드 | TMDB 응답 | 비고 |
|---|---|---|
| `tmdbId` | `id` | `uk_movie_tmdb_id` |
| `title` | `title` | `language=ko-KR` |
| `posterPath` | `poster_path` | 경로만 저장, 베이스 URL은 프론트가 조립 |
| `releaseDate` | `release_date` | 빈 문자열로 오는 경우가 있어 **null 처리 필요** |
| `overview` | `overview` | **length 1000 — D-4 절단 규칙 적용** |
| `runtime` | `runtime` | nullable |
| `koficMovieCd` | **없음** | TMDB가 제공하지 않는다. 4-7 재매칭 배치가 `linkKoficCode()`로 역으로 채운다 |

- 재동기화 시 `Movie.updateMetadata(title, posterPath, overview, runtime)` 사용.
  `releaseDate`는 수정 메서드가 없다 — **변경이 필요하면 엔티티에 추가해야 하므로 확인 필요**(잔여).

### `Person` / `MovieActor` / `MovieDirector`

- `Person.of(tmdbPersonId, name, profilePath)` — `uk_person_tmdb_id` 기준 upsert,
  존재하면 `updateProfile(name, profilePath)`
- `MovieActor` ← `credits.cast[]` — `character` → `characterName`, `order` → `roleTier`(D-1)
- `MovieDirector` ← `credits.crew[]` 중 **`job == "Director"`** 만
  - ⚠️ `department == "Directing"`으로 거르면 조감독·스크립트 등이 함께 들어온다. `job` 기준으로 정확히 건다.

### `MovieGenre` / `MovieCountry`

- `weight`는 **`BigDecimal(4,3)`** 이다. 소수 셋째 자리에서 반올림해야 하며,
  `1/3` 같은 값은 `0.333`으로 저장된다 — **가중치 합이 정확히 1이 되지 않는 경우가 정상**이다.
  집계 쿼리는 합이 1임을 전제하지 않는다.

---

## 6-4. `MovieSyncService` 구현 (초안)

4-2에서 확정한 시그니처를 유지한다.

```java
public interface MovieSyncService {
    Movie syncFromTmdb(Long tmdbId);
    void syncGenres(Movie movie, List<TmdbGenreDto> genres);
    void syncCountries(Movie movie, List<TmdbCountryDto> countries);
    void syncCast(Movie movie, List<TmdbCastDto> cast);
    void syncCrew(Movie movie, List<TmdbCrewDto> crew);
}
```

| 메서드 | 로직 요약 |
|---|---|
| `syncFromTmdb(tmdbId)` | `findByTmdbId` → 있으면 `updateMetadata()`, 없으면 생성·저장 → 4개 매핑 동기화 순차 호출 |
| `syncGenres` | `tmdbGenreId`로 `Genre` 조회(없으면 `GENRE_NOT_FOUND`) → `weight = 1/N` |
| `syncCountries` | `iso_3166_1`로 `Country` 조회(없으면 `COUNTRY_NOT_FOUND`) → 대표 판정(D-3) 후 공식 적용 |
| `syncCast` | 상위 N명 컷(D-1) → `Person` upsert → `roleTier` 산출 → 저장 |
| `syncCrew` | `job == "Director"` 필터 → `Person` upsert → 저장 |

### 재동기화 시 매핑 테이블 처리

**"전량 삭제 후 재삽입"으로 확정 제안.** `movie_genre` 등은 TMDB가 진실의 원천이고 우리가
따로 보정하는 값이 없으므로, diff를 계산할 실익이 없다.

- 각 `syncXxx`는 시작 시 `deleteByMovieId(movieId)` 후 새로 `saveAll`
- ⚠️ 관련 Repository에 **`deleteByMovieId`가 현재 없다** — 추가 필요(잔여)
- `uk_movie_genre` 등 유니크 제약이 있어 삭제 없이 재삽입하면 위반이 난다

### 한국어 데이터 폴백

`language=ko-KR`로 요청해도 `title`/`overview`가 빈 문자열로 오는 경우가 있다.

- `title`은 **not null** 이므로 비면 저장 자체가 실패한다 → 원어 제목 폴백이 필요하다
  (`original_title` 사용 또는 `language` 없이 재요청)
- `overview`는 nullable이라 비어도 무방하다

**→ 폴백 방식 확정 필요**(잔여). 실데이터 없이 판단하기 어려우므로, 시드 적재를 소규모로
먼저 돌려보고 실제 빈도를 확인한 뒤 정하는 것을 권한다.

---

## 6-5. 적재 전략 (D-2 확정 후 확정)

D-2에서 하이브리드(C안)를 택한다는 전제의 초안이다.

| 경로 | 진입점 | 성격 |
|---|---|---|
| 시드 | `/api/admin/movies/seed` (관리자) | 4-7 `TheaterSeedService`와 동일하게 **1회성**, 멱등 |
| 온디맨드 | 검색 결과에서 미등록 영화 선택 시 | `syncFromTmdb(tmdbId)` 호출 후 우리 `movieId` 반환 |

- 관리자 엔드포인트는 `domain/movie/controller`에 둔다
  (5-6-C ③에서 정한 **"패키지는 Service 소유, 경로는 별개"** 기준).
- **Rate limit 고려** — 시드로 수백 편을 연속 호출하면 스로틀링에 걸릴 수 있다.
  요청 간 간격 또는 배치 크기 제한을 두고, 실패 시 이어받기가 가능하도록
  **이미 적재된 `tmdbId`를 건너뛰는 멱등 구조**로 만든다(4-7 박스오피스의
  "기존 키 집합 조회 → 차집합만 저장" 패턴 재사용).

---

## 6-6. ErrorCode 추가분 (초안)

| 상수 | HTTP | 용도 |
|---|---|---|
| `TMDB_MOVIE_NOT_FOUND` | 404 | TMDB에 해당 `tmdbId`가 없음 |
| `GENRE_NOT_FOUND` | 500 | 참조 테이블 미적재 — 사용자 잘못이 아니므로 5xx |
| `COUNTRY_NOT_FOUND` | 500 | 동일 |

> `EXTERNAL_API_ERROR`(4-7)는 재사용한다.

---

## 잔여 확인 항목

| # | 항목 | 처리 시점 |
|---|---|---|
| 1 | **6-0 미결 4건 확정** (D-1 `role_tier` / D-2 적재 전략 / D-3 대표국 / D-4 overview) | **코드 세션 착수 전 (필수)** |
| 2 | `Movie.releaseDate` 수정 메서드 부재 — 재동기화 시 갱신 필요 여부 확인 | 6-3 확정 시 |
| 3 | `MovieGenre`/`MovieCountry`/`MovieActor`/`MovieDirector` Repository에 `deleteByMovieId` 추가 | 6-4 구현 시 |
| 4 | 한국어 `title`/`overview` 빈 응답 폴백 방식 | 소규모 시드 후 실데이터 기준 판단 |
| 5 | `searchMovies` / `MovieSearchCondition` 설계 — D-2 온디맨드와 맞물림 | D-2 확정과 동시 |
| 6 | TMDB rate limit 실측 후 시드 배치 간격 조정 | 시드 최초 실행 시 |

---

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-08-11 | **초안 작성.** 4-2에서 시그니처만 확정하고 미뤄둔 `MovieSyncService`의 구현 스펙. 착수 전 확정이 필요한 **미결 4건(6-0)** 을 분리해 앞에 배치했다. **D-1(`role_tier` 경계값)에서 기획 원안의 비율 방식에 결함을 발견** — 출연진 수 편차가 커서 상위 비율로 자르면 200명짜리 영화의 MINOR 총합(18.0)이 LEAD 총합(10.0)을 넘어 선호 배우 집계가 출연진 규모에 오염된다. 절대 순번 + 상위 N명 컷을 권장안으로 제시. **D-3** — TMDB `production_countries`가 "대표국"을 명시하지 않아 기획노트의 `(N+1)/(N²+1)` 공식을 그대로 적용할 수 없음을 확인, `origin_country` 폴백 방식 제안. **D-4** — `movie.overview`가 length 1000이라 TMDB 응답이 초과 시 적재 배치가 죽는다(실데이터 전까지 드러나지 않는 유형). 그 밖에 `genre`/`country` 참조 테이블 선행 적재가 영화 동기화의 하드 선행 조건임을 6-1로 분리했고, 매핑 테이블 재동기화를 "전량 삭제 후 재삽입"으로 제안하면서 `deleteByMovieId` 부재를 잔여로 등록 |
