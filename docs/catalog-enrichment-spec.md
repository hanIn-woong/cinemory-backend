# CineMory — 카탈로그 보강 스펙 (박스오피스 자동 등록 · 다양성 박스오피스 · Wikidata 인물 한글명)

> **이 문서는 새 세션이 컨텍스트 없이 착수할 수 있도록 쓴 인수인계 문서다.** 2026-10-20 확정.
> 관리자 2단계(B-1~B-3, B-2.5)가 끝난 뒤, 운영 데이터를 실측하다 나온 세 가지를 묶었다.
>
> | Part | 내용 | 스키마 | 앱 |
> |---|---|---|---|
> | **C-1** | **박스오피스 자동 등록** — 매일 새 박스오피스 영화를 TMDB에서 찾아 등록 | 없음 | 없음 |
> | **C-2** | **다양성 영화 박스오피스 수집** — KOFIC `multiMovieYn=Y` TOP 10 | V31(확장) | 없음 — 화면은 별도 결정(6절) |
> | **C-3** | **Wikidata 인물 한글명 일괄 적재** — TMDB 인물 ID로 정확 매핑, 몇 번이고 다시 실행 가능 | 없음(V28 `name_ko` 재사용) | 없음 |
>
> **계약은 구현 후 레이어 스펙으로 옮긴다** — 엔드포인트 `controller-layer-spec.md`, 서비스 `service-layer-spec.md`·`tmdb-sync-spec.md`,
> 스키마 `docs/schema/v31-delta.sql`. 이 문서는 **결정 기록과 근거**로 남는다.
> **일정**: 최종 마감(11/18)과 무관한 보강이다. M3-b와 순서는 사용자가 정한다. Part마다 머지·배포 가능하게 닫는다.

**착수 전에 읽을 것**

| 문서 | 왜 |
|---|---|
| `tmdb-sync-spec.md` D-2 · 6-5 · 4-7 | 초기 적재 전략(박스오피스 역방향 시드 + discover + 온디맨드)과 재매칭 규칙 |
| `admin-spec.md` 10~12절(BD-1~BD-13) | 감사 로그 기록 규칙, `person.name_ko`·`movie.title_ko` 덮어쓰기 구조 |
| `CineMory_기획노트.md` 8절 "인물명 한글화" | Wikidata(ⓐ) → `also_known_as`(ⓑ) 순서 결정과 M3-b 임베딩 결합 경고 |
| `deploy-spec.md` D-3 | 확장/축소 — **C-2는 두 릴리스로 나눈다**(4-4) |

---

## 1. 실측 (2026-10-20, 운영)

| 지표 | 값 | 메모 |
|---|---|---|
| 인물 수 | 약 20만 | 90% 이상 한글명 없음 |
| ① 화면 노출 대상(출연 상위 20명 + 감독) 중 한글 없음 | **100,900** | 영화 1만여 편 × 최대 20명 |
| ② 기록된 영화의 출연 상위 20명 + 감독 중 한글 없음 | **12,133** | 기록 대부분이 10/12 개인 기록 1,016건이라 "한 사람이 본 영화"에 가깝다 |
| ③ 기록된 영화의 **주연(`display_order ≤ 4`) + 감독** 중 한글 없음 | **2,439** | **C-3의 1차 대상** — 사용자가 실제로 읽는 이름 |

수작업만으로는 ②도 약 67시간(1명 20초)이라 불가능하다 → **자동(Wikidata) → 노출 상위만 보완 → 관리자 수동** 순서(C-3).
**목표는 "전원 한글화"가 아니라 "사용자가 읽는 이름(주연·감독·리포트 TOP)의 한글화"** 다. 조연·단역의 원어 표기는 남아도 된다.

---

## 2. 확정 사항 (CE-1 ~ CE-10)

| # | 항목 | 결정 | 근거 / 기각한 안 |
|---|---|---|---|
| CE-1 | 새 영화 등록 경로 | **기존 박스오피스 역방향 시드(`seedFromBoxOffice`)를 스케줄에 얹는다** | 지금은 TMDB 신작이 들어오는 자동 경로가 없다 — 온디맨드 sync(사용자)·시드(관리자)뿐이고, 박스오피스 스케줄러는 **기록 저장·기존 영화 연결만** 한다. 새로 만들 것 없이 순서만 잇는다: **수집 → 등록 → 연결** |
| CE-2 | 자동 실행 시 매칭 | **엄격 모드** — 제목(정규화) 일치 + 개봉연도 ±1 + **후보가 정확히 하나일 때만** 등록 | 지금 시드는 TMDB 검색 **첫 결과**를 그대로 등록한다. 관리자가 지켜보는 수동 실행에선 괜찮지만, 무인 자동 실행은 동명 영화·재개봉작을 조용히 잘못 등록할 수 있다. 관리자 API의 기존 동작은 바꾸지 않는다(`strict` 기본 false) |
| CE-3 | `box_office_record.open_date` | **수집 시 KOFIC `openDt`로 채운다**(v10 TODO 해소) | CE-2의 연도 조건과 시드의 `year` 검색 파라미터가 이 값에 의존한다. 지금은 항상 NULL이라 연도 없이 검색하고 있다 |
| CE-4 | 박스오피스 20위 확장 | **하지 않는다** | KOFIC 일별 API `itemPerPage`는 기본·최대 10. 한국/외국(`repNationCd`) 두 번 받아 합치면 20편이 되지만 **뒤쪽 순위가 빠질 수 있고 순위 변동 값이 맞지 않는다**. 11~20위는 평일 관객이 수백 명이라 정보 가치도 작다 |
| CE-5 | 대신 | **다양성 영화(`multiMovieYn=Y`) TOP 10을 별도 목록으로 수집** | 정확한 순위이고 순위 변동·신규 진입 값이 그대로 맞다. 영화를 기록하는 사람에게 흥미로운 정보다 |
| CE-6 | 다양성 구분 저장 | **새 컬럼 `category ENUM('ALL','DIVERSITY') NOT NULL DEFAULT 'ALL'`** | `rank_type`은 **기간**(일별·주간·주말)이다. 다양성은 **집계 범위**라 축이 다르다 — `RankType.DAILY_DIVERSITY`로 섞으면 나중에 주간 다양성이 생길 때 값이 곱으로 늘어난다 |
| CE-7 | C-2 배포 | **두 릴리스** — ① 컬럼·UK 변경 + 모든 조회가 `ALL`만 읽게(수집 안 함) ② 다양성 수집 시작 + API 파라미터 | 한 번에 넣고 CI가 이전 jar로 자동 롤백되면, 이전 코드의 조회(`rank_type`만 거름)가 **다양성 행까지 섞어 1위가 두 개**인 목록을 낸다. ①이 먼저 배포돼 있으면 ②를 되돌려도 ①은 `ALL`만 읽는다(deploy-spec D-3 조건 6) |
| CE-8 | Wikidata 실행 위치 | **백엔드가 SPARQL을 직접 호출하는 관리자 API**(커서 분할 실행) | CSV 업로드는 업로드 인프라(Nginx `client_max_body_size 1m`)가 필요하고, 개발 PC 스크립트 + SQL은 운영 DB에 손으로 쓰는 경로가 생긴다. API면 **대상 선정·검증·조건부 저장·감사 로그**가 코드 한 곳에 있고 재실행이 버튼 하나다 |
| CE-9 | 저장 규칙 | **`name_ko IS NULL`인 행만, 한글이 들어간 값만, 후보가 하나일 때만** — 조건부 UPDATE | 관리자가 손으로 넣은 값은 **절대 덮어쓰지 않는다**(수동이 이긴다). 같은 TMDB ID에 Wikidata 항목이 둘 이상이고 레이블이 다르면 건너뛴다. 이 규칙 덕분에 **언제 몇 번 다시 돌려도 안전하다**(카탈로그가 늘면 다시 실행) |
| CE-10 | 출처 기록 | **`name_ko`의 출처(수동/Wikidata) 컬럼은 두지 않는다** | CE-9로 충돌이 없다. 대가는 "Wikidata가 나중에 표기를 고쳐도 반영되지 않음" — 필요해지면 그때 `name_ko_source`를 확장으로 추가 |

---

## 3. C-1 박스오피스 자동 등록 (스키마 변경 없음)

### 3-1. 수집 시 `open_date` 채우기 (CE-3)

- `BoxOfficeSyncService.toEntity`에서 `DailyBoxOffice.openDt`를 파싱해 `BoxOfficeRecord.openDate`에 넣는다.
  KOFIC 형식은 `yyyy-MM-dd`이고 **빈 문자열·공백이 올 수 있다**(재개봉·미정) → 파싱 실패는 NULL(예외로 수집을 멈추지 않는다).
- 이미 쌓인 행의 백필은 하지 않는다 — KOFIC 일별 API를 날짜마다 다시 불러야 하고, 시드는 `openDate DESC NULLS LAST`라 최신 행부터 본다.
- `rematchUnlinked`의 TODO(연도로 후보 좁히기)는 **이번 범위가 아니다**(주석은 "수집은 채운다, 재매칭은 아직 안 씀"으로 고친다).

### 3-2. 엄격 모드 시드 (CE-2)

`MovieSeedService.seedFromBoxOffice(Integer limit, boolean strict)` — 관리자 API는 `?strict=` 선택 파라미터(기본 false, 지금 동작 그대로).

`strict = true`일 때 한 제목의 처리:

1. `openDate`가 NULL이면 **건너뛴다**(연도 없이는 판별 근거가 없다) — `skipped`.
2. TMDB 검색(기존과 같이 `year` 전달) 결과에서 **아래를 모두 만족하는 후보만 남긴다**
   - `normalize(item.title) == normalize(snapshot)` — 정규화: NFC → 공백·구두점(`:·-,.!?'"`) 제거 → 소문자
   - `item.releaseDate`의 연도가 `openDate` 연도 ±1
3. 남은 후보가 **정확히 1개**일 때만 기존 흐름(`existsByTmdbId` → `syncFromTmdb`). 0개·2개 이상이면 제목·연도·후보 수를 `warn` 로그로 남기고 `skipped`.

> ⚠️ 재개봉작(옛 영화의 재상영)은 KOFIC `openDt`가 **재개봉일**이라 연도 조건에서 떨어진다. 자동 등록에서 빠지는 것이 맞다 —
> 사용자가 검색하면 온디맨드로 들어오고, 관리자가 수동 시드(비엄격)로 넣을 수도 있다.

`movie_id`는 여전히 채우지 않는다(기존 설계 — 연결은 재매칭 하나가 책임진다).

### 3-3. 스케줄 (KST)

| 시각 | 작업 | 비고 |
|---|---|---|
| 05:00 | `syncDaily` — 전체 (C-2 이후 + 다양성) | 기존 `daily-sync-cron` |
| **05:15** | **`seedFromBoxOffice(limit = 20, strict = true)`** | 신규 `cinemory.boxoffice.auto-seed-cron` · `auto-seed-limit`. 하루 새 박스오피스 영화는 몇 편이라 TMDB 호출이 작다 |
| 05:30 | `rematchUnlinked` | 기존. 05:15에 등록된 영화가 여기서 연결된다 |

- 시드는 일괄 작업과 **`running` 플래그를 공유**한다 — 새벽이라 관리자 작업과 겹칠 일은 드물고, 겹치면 `SEED_ALREADY_RUNNING`을 `warn` 로그만 남기고 넘어간다(다음 날 다시 돈다).
- KOFIC 키가 없으면 수집처럼 건너뛴다(기존 `isConfigured()` 가드 재사용). TMDB 429면 시드가 이미 `stoppedByRateLimit`으로 정상 종료한다.
- **스케줄러 실행은 감사 로그에 남기지 않는다**(admin-spec 11-2 — 관리자 행위가 아니다). 결과는 `info` 로그 한 줄.
- 운영 런북(`operations-runbook.md`)의 감시 장치 표에 "자동 등록 결과 로그"를 한 줄 추가한다.

### 3-4. 테스트

- `openDt` 파싱(정상·빈 값·잘못된 형식 → NULL).
- 엄격 모드: 제목 정규화 일치 · 연도 경계(±1 통과, ±2 탈락) · 후보 0/1/2개 · `openDate` NULL 건너뜀 · 비엄격은 기존 첫 결과 동작 유지.
- 스케줄러: `SEED_ALREADY_RUNNING`을 삼키고 예외를 밖으로 던지지 않음.

---

## 4. C-2 다양성 영화 박스오피스

### 4-1. 스키마 — V31 (확장 2문장)

```sql
-- V31__box_office_record_category.sql
ALTER TABLE box_office_record
  ADD COLUMN category ENUM('ALL','DIVERSITY') NOT NULL DEFAULT 'ALL' AFTER rank_type;
ALTER TABLE box_office_record
  DROP INDEX uk_box_office_record,
  ADD UNIQUE KEY uk_box_office_record (target_date, rank_type, category, kofic_movie_cd);
```

- ① 기본값 있는 NOT NULL 컬럼 추가 — 기존 행은 전부 `ALL`, 옛 코드는 이 컬럼을 모른 채 INSERT해도 `ALL`이 들어간다 → **확장**.
- ② UK에 컬럼을 하나 더해 **제약을 완화**(같은 날 같은 영화가 전체·다양성 양쪽에 있을 수 있다) → **확장**. 한 `ALTER` 안에서 DROP·ADD라 중간에 UK가 비는 순간이 없다.
- 번호는 **머지 직전 `main` 기준으로 확정**한다(병행 브랜치와 충돌 방지 — 머지 전이면 고쳐 쓰고 `cinemory_test` 재생성).
- 엔티티: `BoxOfficeCategory { ALL, DIVERSITY }`, `BoxOfficeRecord.category`(`EnumType.STRING`, 생성 시 지정, 수정 메서드 없음).

### 4-2. 릴리스 ① — 읽기 쪽 준비 (수집 안 함)

- **`box_office_record`를 읽는 모든 쿼리에 `category = ALL` 조건**: `findLatestTargetDate`, `findByTargetDateAndRankType…`, `findKoficMovieCds`, `findUnmatchedTitles`, 재매칭 대상 조회.
  ⚠️ 구현 전에 `BoxOfficeRecordRepository` 전체와 `box_office_record` 네이티브 쿼리를 다시 검색해 빠짐없이 바꾼다.
- 테스트: `DIVERSITY` 행을 직접 넣어도 기존 API 응답·시드 대상·재매칭 대상에 섞이지 않음.

> 단, `findUnmatchedTitles`·재매칭은 ②에서 **다양성 행도 대상으로 넓힌다**(다양성 영화도 등록·연결돼야 상세로 이어진다).
> ①에서 `ALL`로 좁혀 두는 이유는 롤백 안전성뿐이다.

### 4-3. 릴리스 ② — 수집과 API

- `KoficClient.fetchDailyBoxOffice(LocalDate targetDate, BoxOfficeCategory category)` — `DIVERSITY`면 `multiMovieYn=Y`를 붙인다.
- `syncDaily`는 **두 범주를 각각** 수집(호출 2회/일). 이미 수집 여부 판정(`findKoficMovieCds`)도 범주별.
- `findUnmatchedTitles`·재매칭은 범주 조건을 풀어 두 범주 모두 대상(위 메모). C-1 자동 시드가 다양성 신작도 등록하게 된다.
- **API**: `GET /api/box-office?rankType=DAILY&category=DIVERSITY` — `category` 선택 파라미터(기본 `ALL`) → **확장**(기존 앱 호출 그대로 동작).
  응답 `BoxOfficeResponse`에 `category` 필드 추가(확장). 주간·주말은 지금 수집하지 않으므로 `DIVERSITY`+`WEEKLY` 조합은 빈 목록.
- 관리자 API `POST /api/admin/box-office/sync`에도 `category` 선택 파라미터(기본 둘 다) — 감사 로그 `detail`에 범주 포함.

### 4-4. 배포 순서 요약

`V31 + 읽기 ALL 고정(①)` → 운영 배포·확인 → `수집 + API(②)` → 운영 배포. ②를 되돌려도 ①은 `ALL`만 읽는다.

### 4-5. 테스트

- 범주별 수집 멱등(같은 날 두 번) · 같은 영화가 두 범주에 함께 저장됨(UK) · `category` 미지정 = `ALL` · 응답 필드.

---

## 5. C-3 Wikidata 인물 한글명 일괄 적재

### 5-1. API — `POST /api/admin/persons/name-ko/wikidata`

| 파라미터 | 설명 |
|---|---|
| `scope` | `RECORDED_LEADS`(기본) — **기록된 영화의 주연(`display_order ≤ 4`)·감독**(1절 ③, 2,439명) · `ALL` — 한글 없는 인물 전체 |
| `fromId` (선택) | 커서. 이 `person.id`보다 큰 대상부터 |
| `limit` (선택) | 이번 호출에서 볼 인물 수, 기본 2,000 · 최대 5,000 |

**대상 선정**(`id` 오름차순, `limit`개): `name_ko IS NULL AND name NOT REGEXP '[가-힣]' AND id > :fromId`
(+ `RECORDED_LEADS`면 1절 ③의 조건 — `movie_actor.display_order <= 4` 또는 `movie_director`, 각각 `watch_record`와 JOIN).

응답 `WikidataNameKoResult { scanned, matched, updated, ambiguous, notFound, invalid, stoppedByRateLimit, lastProcessedId, done }`
— `done = scanned < limit`(더 볼 대상이 없음). 관리자는 `done`이 될 때까지 `fromId = lastProcessedId`로 다시 부른다(기존 `resync` 커서와 같은 사용법).
`ALL`(약 18만 명)은 `limit` 2,000 기준 약 90회 — 한 호출이 Nginx `proxy_read_timeout`(120초) 안에 끝나게 `limit`을 둔 것이다.

### 5-2. Wikidata 호출

- 엔드포인트 `https://query.wikidata.org/sparql` — **POST**(form `query=`), `Accept: application/sparql-results+json`.
- **`User-Agent`에 서비스 이름과 연락처를 넣는다**(Wikimedia 정책 — 없으면 차단될 수 있다). 값은 설정(`cinemory.wikidata.user-agent`).
- 대상 인물의 TMDB ID를 **200개씩** 묶어 한 번에 묻는다(설정 `batch-size`). 묶음 사이 **1초 간격**(설정 `request-interval`).

```sparql
SELECT ?tmdb ?label WHERE {
  VALUES ?tmdb { "31" "500" "1245" }          # 이번 묶음의 tmdb_person_id (P4985 값은 문자열)
  ?item wdt:P4985 ?tmdb .
  ?item rdfs:label ?label .
  FILTER (LANG(?label) = "ko")
}
```

- **429·503**이면 `Retry-After`를 존중해 한 번 기다렸다 재시도, 그래도 실패하면 **그 묶음 앞에서 멈추고** `stoppedByRateLimit = true`로 정상 반환(진척 보존 — 시드와 같은 규칙). `lastProcessedId`는 **처리를 끝낸 마지막 인물**.
- 그 밖의 오류(타임아웃 60초 포함)는 `EXTERNAL_API_ERROR`로 중단. 클라이언트는 `global/infra/wikidata/WikidataClient`(`RestClient`, 기존 TMDB·KOFIC 클라이언트와 같은 구성).

### 5-3. 검증과 저장 (CE-9)

묶음 결과를 TMDB ID별로 모은 뒤 인물마다:

| 경우 | 처리 | 집계 |
|---|---|---|
| 결과 없음 | 건너뜀 | `notFound` |
| 서로 다른 레이블 2개 이상(항목 중복) | 건너뜀 | `ambiguous` |
| 레이블에 한글(`[가-힣]`)이 없음 · trim 후 빈 값 · 100자 초과 | 건너뜀 | `invalid` |
| 위를 통과한 레이블 1개 | **조건부 UPDATE** | `matched`, 실제 반영되면 `updated` |

```sql
UPDATE person SET name_ko = :label WHERE id = :id AND name_ko IS NULL
```

- **조건부라 그 사이 관리자가 손으로 넣은 값은 덮어쓰지 않는다**(`matched`인데 `updated`가 아닌 차이가 이것).
- 엔티티를 거치지 않는 벌크 UPDATE이므로 같은 트랜잭션에서 `Person`을 읽은 적이 없게 한다(영속성 컨텍스트 불일치 방지). 묶음마다 트랜잭션 하나.
- 표시 쪽은 B-2에서 이미 `name_ko`를 읽으므로 **저장 즉시 앱에 반영**된다(앱 배포 없음).

### 5-4. 동시 실행 · 감사 · 라이선스

- 전용 `AtomicBoolean`으로 중복 실행 409(`WIKIDATA_ALREADY_RUNNING` — `ErrorCode` 추가). TMDB 시드와는 **호출 예산이 달라 플래그를 공유하지 않는다**.
- 감사 로그: 호출마다 1행 `PERSON_NAME_KO_BULK`, `detail = {scope, fromId, limit, 결과 전부}` (인물별 행은 남기지 않는다 — 전부 NULL → 값이라 전후 비교가 무의미).
  `AdminAction`에 값 추가.
- Wikidata 데이터는 **CC0**라 표기 의무는 없다(앱 정보 화면에 "인물명 일부: Wikidata"를 넣는 것은 선택).

### 5-5. 관리자 웹

- 인물 화면 상단 **"Wikidata로 채우기"** — 범위 선택 → 실행 → 결과 표 → `done`이 아니면 **"이어서 실행"**(마지막 `lastProcessedId`를 자동으로 넘김).
- 대시보드의 `personsMissingKorean`과 1절 ③ 쿼리로 전후 비교.

### 5-6. 실행 후 할 일

1. 운영에서 `RECORDED_LEADS`를 `done`까지 실행 → 1절 ③을 다시 측정해 이 문서 1절에 **"적재 후"** 값을 기록.
2. 남은 인원 중 노출 상위는 **TMDB `also_known_as` 보완**(기획노트 8절 ⓑ — 별도 스펙, 한글 별칭이 여럿이면 자동 저장하지 않고 관리자 확인 목록으로).
3. 그래도 남는 사람은 관리자 화면에서 수동. 카탈로그가 커지면(시드·온디맨드) **C-3를 다시 실행**한다.

⚠️ **M3-b 임베딩 입력에 인물명을 넣을지**는 여전히 M3-b 설계 때 정한다 — 한글명이 채워질 때마다 벡터를 다시 만들어야 하는 결합(기획노트 8절).

### 5-7. 테스트

- 대상 선정(범위 2종 · 커서 · 한글 원본/한글명 보유 제외).
- 응답 파싱 · 묶음 분할(200 경계) · 4가지 판정 · **조건부 UPDATE가 수동 값을 보존**.
- 429 → 재시도 → 중단 시 `lastProcessedId`와 `stoppedByRateLimit` · 중복 실행 409 · 감사 로그 1행.
- Wikidata는 `MockRestServiceServer`로 대역(실호출 테스트 없음).

---

## 6. 범위 밖 / 별도 결정

- **앱의 박스오피스 화면** — 2026-10-20 확인 결과 **앱에는 박스오피스 화면이 아직 없다**(`endpoints.ts`에 경로만 있다). "전체 | 다양성" 탭은 그 화면을 만들 때 함께 한다(앱 스펙은 그때 `cinemory-app/docs/`에). 이 문서의 API는 그 화면이 바로 쓸 수 있게 준비만 한다.
- 주간·주말 박스오피스 수집, 박스오피스 20위 확장(CE-4), 재매칭에 개봉연도 사용(3-1), `name_ko` 출처 컬럼(CE-10), 영화 제목 Wikidata 매핑(P4947) — 필요해지면 확장으로.
- 카탈로그 선별 확장(TMDB 일일 ID 파일 기반 인기작 3~5만 편) — **M3-b 설계 때** 임베딩 크기와 함께 정한다.

---

## 7. 실행 순서

| 단계 | 내용 | 완료 판정 |
|---|---|---|
| **C-1** | `open_date` 수집 · 엄격 시드 · 05:15 스케줄 | 운영에서 다음 날 로그에 자동 등록 결과 · 신작이 재매칭으로 연결됨 |
| **C-2 ①** | V31 · 읽기 `ALL` 고정 | 운영 `flyway_schema_history`에 V31, 기존 박스오피스 API 응답 동일 |
| **C-2 ②** | 다양성 수집 · API 파라미터 | 다음 날 `category=DIVERSITY` 응답 10건 |
| **C-3** | Wikidata API · 관리자 버튼 | `RECORDED_LEADS` 완주 후 1절 ③ 재측정값 기록 |

C-1과 C-3은 서로 독립이라 순서를 바꿔도 된다. C-2 ②는 C-1이 먼저 있어야 다양성 신작이 자동 등록된다.

---

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-10-20 | **신설.** CE-1~CE-10. 근거 요약: 신작 자동 등록 경로가 없어 기존 역방향 시드를 수집과 재매칭 사이에 스케줄(CE-1), 무인 실행이라 첫 결과 대신 제목+연도+유일 후보(CE-2)와 그 전제인 `open_date` 수집(CE-3). 박스오피스 20위는 KOFIC `itemPerPage` 최대 10이라 합성 순위가 부정확해 기각하고 다양성 TOP 10으로 대체(CE-4·5), 저장은 기간 축(`rank_type`)과 분리한 `category` 컬럼(CE-6), 롤백 시 두 목록이 섞이지 않게 두 릴리스(CE-7). 인물 한글명은 실측(주연·감독 2,439명) 후 Wikidata를 백엔드 관리자 API로 커서 실행(CE-8), NULL만·한글만·유일 후보만 조건부 저장해 수동 값 보존과 재실행 안전성 확보(CE-9) |
| 2026-10-21 | **C-2 마이그레이션 번호 V30 → V31.** `tmdb-sync-spec` 6-11(임베딩 입력용 데이터 보강, PR #46)이 V30을 먼저 가져갔다. 이 문서는 미구현이라 번호만 바꾼다 — 설계 내용은 그대로이고, 구현 시점에 `migration` 폴더의 최신 번호를 다시 확인할 것 |
