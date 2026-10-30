# CineMory — 소셜 피드 스펙 (백엔드)

> **이 문서는 새 세션이 컨텍스트 없이 착수할 수 있도록 쓴 인수인계 문서다.** 2026-10-29 소셜 설계 세션에서 확정한
> 결정과 근거, API 계약, 쿼리 설계, 단계별 실행 순서를 담는다. 앱 화면은 `cinemory-app/docs/social-screen-spec.md`.
>
> **계약은 구현 후 레이어 스펙으로 옮긴다** — 엔드포인트는 `controller-layer-spec.md`, 스키마는 `docs/schema/v{n}-delta.sql`.
> 이 문서는 **결정 기록과 근거**로 남는다(`account-integrity-spec.md`와 같은 위치).

| 단계 | 내용 | 머지 조건 |
|---|---|---|
| **F-0** | 댓글 API 끄기 (`controller-layer-spec.md` 잔여 #21) | 독립 — 언제든 |
| **F-1** | 인덱스(V35) + 활동 조회 공통부 + **개인 활동 피드** + **팔로잉 스트립** + 부하 측정 ① | 측정 ① 합격 |
| **F-2** | 앱 — 소셜 탭·티켓 피드·다른 사용자 페이지 (앱 스펙) | F-1 머지 후 |
| **F-3** | **전체 스트립**(공용 캐시) + 부하 측정 ② + 앱 범위 전환 | 측정 ② 합격 — **불합격이면 F-3 전체를 버린다** |
| **F-4** | **사용자 검색 · 공개 범위 기본값 FRIENDS(V36) · 팔로우 목록 정렬 · 새 팔로워 수** (10절) | 독립 — F-3과 무관, **F-3보다 먼저** |

각 단계는 끝날 때마다 `main`에 머지·배포 가능한 상태로 닫는다(기획노트 4절 2026-10-15 원칙). F-3를 버려도 F-1·F-2만으로
기능이 완결된다.

**착수 전에 읽을 것**

| 문서 | 왜 |
|---|---|
| `global/access/UserAccessPolicy` | 공개 범위 판정의 단일 출처 — 피드의 SQL 필터가 이것과 **같은 규칙**이어야 한다(SF-9) |
| `controller-layer-spec.md` **5-0-D** | 기존 페이징은 `PageResponse`(offset). 피드는 **커서형**이라 새 응답 타입을 쓴다(SF-7) |
| `deploy-spec.md` **D-3** | Flyway 규칙 — 실행 SQL은 동결, 설계 근거는 `docs/schema/` |
| `account-integrity-spec.md` **Part C** | 탈퇴는 즉시 완전 삭제 — 피드는 조회 시 합성이라 정리 코드가 필요 없다(SF-3) |
| `UserPreferenceQueryRepository` | 네이티브 쿼리 작성 관례(`EntityManager.createNativeQuery`) |

---

## 1. 범위 결정 (2026-10-29)

| # | 항목 | 결정 | 이유 |
|---|---|---|---|
| SF-1 | **댓글** | **캡스톤 범위 밖** — 테이블·엔티티·서비스는 유지, **API만 끈다**(F-0) | 신고·검수·개인정보 정책이 없다. "본인만의 기록" 컨셉에서 대화는 후순위. 앱에 댓글 화면이 없는데 API는 운영에서 열려 있어 **검수 없는 글이 쌓일 수 있는 상태**였다 |
| SF-2 | 형태 | **사람 단위 필름 스트립(목록) → 사람별 티켓 피드(상세)** | 한 사람이 활동을 몰아 해도 목록에서는 **한 줄**이다(도배 문제를 구조로 해결). 일반 시간순 카드 피드는 왓챠피디아와 같아 기각. 시청 기록 = 티켓(관람일·극장/OTT·별점), 리뷰 = 티켓 뒷면, 컬렉션 = 팸플릿 |
| SF-3 | **저장 방식** | **활동 테이블 없음 — 조회 시 원천 테이블에서 합성** | "수정은 조용히 반영, 삭제는 조용히 사라짐"이 **동기화 코드 없이** 성립한다. 활동 테이블을 두면 수정·삭제·탈퇴 경로마다 맞춰야 하고 다형 참조(댓글·알림과 같은 문제)가 다시 생긴다 |
| SF-4 | 정렬 | **`created_at` 기준** | 수정해도 위로 다시 올라오지 않는다(`updated_at`은 쓰지 않는다). 리뷰는 upsert지만 `update()`가 기존 행을 고치므로 `created_at`이 유지된다(확인함) |
| SF-5 | 범위 | **팔로잉 기본 + 전체 선택** | 사용자가 많아지면 전체가 난잡해진다. 선택값 기억은 앱 몫(서버 설정 컬럼 없음) |
| SF-6 | 기간 | 팔로잉 스트립 **30일** · 전체 스트립 **7일 + 상위 100명** · 내 스트립·개인 피드 **기간 없음** | 기간은 "목록에 오를 자격"을 거르는 조건이다. 팔로잉은 대상이 이미 좁아 30일도 싸다(4절). 전체는 대상이 플랫폼 전체라 기간·상한으로 비용을 고정한다 |
| SF-7 | 페이징 | **커서형 + `asOf`** | 최신순 목록을 offset으로 넘기면 그 사이 생긴 활동 때문에 **중복·누락**이 생긴다. 첫 페이지 시각(`asOf`) 이후 활동은 무시하고, 새로고침이 `asOf`를 갱신한다 |
| SF-8 | 비로그인 | **두 API 모두 인증 필수(401)** — 앱은 로그인 안내 | 팔로잉이 기본이라 비로그인에게 보여 줄 것이 거의 없다 |
| SF-9 | 공개 범위 | 사용자 단위 `privacy_setting` 그대로(기록별 공개 범위 없음) — 판정은 `UserAccessPolicy`와 동일 | 기존 정책 유지 |
| SF-10 | **`private_review`** | **피드 응답 DTO에 필드 자체를 두지 않는다** — 내 피드에서도 | 이름 그대로 나만 보는 메모. 내 피드/남의 피드 DTO를 하나로 유지하는 것이 노출 사고를 막는 가장 확실한 방법 |
| SF-11 | 다른 사용자 페이지 | **개인 기능(편집·설정) 외 전부 공개** — 기록·찜·컬렉션·리포트·캘린더·팔로우 목록 | 백엔드는 `/api/users/{userId}/…` + `validateCanView`로 **이미 완비**. 앱 작업만 남는다 |

## 2. 활동 원천 6종

| `ActivityType` | 원천 | `occurredAt` | 단위 | 규칙 |
|---|---|---|---|---|
| `WATCH_RECORD` | `watch_record` | `created_at` | 행 하나 | 재관람 기록도 각각 하나 — 단 **스트립 프레임에서는 영화당 최신 1건으로 합친다**(4-1 ③). 개인 티켓 피드는 건별 그대로 |
| `REVIEW` | `review` | `created_at` | 행 하나 | 본문 전체를 내린다(최대 2,000자, 앱이 줄 수 제한) |
| `WISH` | `wish_movie` | `created_at` | 행 하나 | |
| `COLLECTION_CREATED` | `collection` | `created_at` | 행 하나 | **지금 상태**(이름·편수·미리보기 포스터 4장)를 보여 준다 |
| `COLLECTION_MOVIES_ADDED` | `collection_movie` ⋈ `collection` | 그룹의 `MAX(cm.created_at)` | **(컬렉션, 날짜)당 하나** | ① 같은 컬렉션·같은 날 추가는 묶는다("3편 추가") ② **컬렉션을 만든 날 추가한 영화는 제외**한다 — `COLLECTION_CREATED` 프레임이 지금 포스터를 보여 주므로 거기에 흡수된다. 날짜는 `DATE(created_at)`(서버·DB 모두 Asia/Seoul) |
| `FOLLOW` | `follow` (`follower_id` = 활동 주체) | `created_at` | 행 하나 | ⚠️ **팔로우 대상도 보는 사람이 볼 수 있을 때만** 노출(`filterViewable`) — 그렇지 않으면 PRIVATE 사용자의 존재가 피드로 드러난다 |

- 수정 → 원천 행이 바뀌므로 그대로 반영. 삭제 → 원천 행이 없으므로 사라짐. 언팔로우·컬렉션에서 영화 빼기도 같다.
- **정렬 키 상수**(커서 동점 처리용, SQL에 리터럴로 쓴다 — enum `ordinal()`에 의존하지 않는다. 순서를 바꾸면 기존 커서가 깨지므로 **값은 고정**):
  `WATCH_RECORD=1 · REVIEW=2 · WISH=3 · COLLECTION_CREATED=4 · COLLECTION_MOVIES_ADDED=5 · FOLLOW=6`.
- `sourceId` — 각 원천의 PK. `COLLECTION_MOVIES_ADDED`만 예외로 `collection_id`이고 `groupDate`가 함께 키가 된다.
- `created_at`이 초 단위라 동점이 흔하다 — 정렬은 항상 `(occurredAt DESC, sortKey DESC, sourceId DESC)`.

## 3. API 계약

### 3-1. `GET /api/feed/strips` — 스트립 목록 (인증 필수)

| 파라미터 | 기본 | 설명 |
|---|---|---|
| `scope` | `FOLLOWING` | `FOLLOWING` \| `ALL`(F-3 전까지는 `ALL` → 400 `INVALID_INPUT_VALUE`) |
| `cursor` | 없음 | 없으면 첫 페이지 — `asOf`를 새로 정하고 `me`를 싣는다 |
| `size` | 10 | 최대 20 |
| `seenAfter` | 없음 | ISO-8601. 주면 스트립마다 그 이후 활동 수(`newCount`)를 계산 |

```json
{
  "asOf": "2026-10-29T21:40:12",
  "me": { "user": {…}, "lastActivityAt": "…", "newCount": 0, "frames": [ … ≤6 ] },
  "content": [ { "user": {…}, "lastActivityAt": "…", "newCount": 3, "frames": [ … ≤6 ] } ],
  "nextCursor": "eyJ0Ijo…", "hasNext": true
}
```

- `me` — **첫 페이지에만**, 활동이 없어도 `frames: []`로 항상 싣는다(앱이 "첫 기록 남기기"를 띄운다). 기간 제한 없음.
- `content` — 본인 제외. `lastActivityAt DESC, userId DESC`. 프레임은 사람마다 최근 **6개**(한 줄에 보이는 만큼 — 앱 스펙 성능 절).
- `user` — `FeedUserResponse(userId, nickname, profileImage)`.

### 3-2. `GET /api/users/{userId}/activities` — 개인 티켓 피드 (인증 필수)

| 파라미터 | 기본 | 설명 |
|---|---|---|
| `cursor` | 없음 | 없으면 첫 페이지(`asOf` 새로 정함) |
| `size` | 20 | 최대 50 |

- `userAccessPolicy.validateCanView(viewerId, userId)` → 없으면 404 `USER_NOT_FOUND`, 권한 없으면 403 `ACCESS_DENIED`.
- 응답 `ActivityPageResponse { asOf, content: ActivityResponse[], nextCursor, hasNext }`. 기간 제한 없음.
- ⚠️ `FOLLOW` 프레임을 `filterViewable`로 거르므로 한 페이지가 `size`보다 적을 수 있다 — `hasNext`는 거르기 **전** `size+1`로 판정한다. 앱은 무한 스크롤이라 문제없다(`getMovieReviews` 4-6-E와 같은 한계).
- `/api/users/**` GET이 `permitAll`이므로 인증 강제는 `@AuthUser(required = true)`로 한다(팔로우 API와 같은 방식).

### 3-3. `ActivityResponse` (스트립 프레임·티켓 피드 공용)

```java
public record ActivityResponse(
    ActivityType type, LocalDateTime occurredAt, Long sourceId,
    MovieSummaryResponse movie,            // WATCH_RECORD · REVIEW · WISH
    WatchDetail watch,                     // WATCH_RECORD — watchDate, watchType, placeDetail, ottPlatformName, rating
    String reviewContent,                  // REVIEW
    CollectionDetail collection,           // COLLECTION_* — collectionId, name, movieCount, previewPosters(≤4)
    AddedMovies added,                     // COLLECTION_MOVIES_ADDED — groupDate, count, posters(≤4)
    FeedUserResponse followee              // FOLLOW
) {}
```

- 타입별로 해당 필드만 채우고 나머지는 `null`(springdoc 타입 생성이 단순하다 — 다형 JSON은 쓰지 않는다).
- **`private_review` 필드 없음**(SF-10). `WatchDetail.rating`은 서버 단위(1~10) 그대로 — 표시 변환은 앱.
- `movie`는 기존 `MovieSummaryResponse`(`displayTitle()` 적용) 재사용.

### 3-4. 커서

- `FeedCursor` record → JSON → Base64URL. 디코드 실패·필드 누락은 400 `INVALID_INPUT_VALUE`.
- 스트립: `{ asOf, at(lastActivityAt), uid }` · 피드: `{ asOf, at(occurredAt), k(sortKey), id(sourceId), d(groupDate?) }`.
- **모든 쿼리에 `created_at <= :asOf`** — 첫 페이지 이후 생긴 활동은 다음 새로고침까지 보이지 않는다(SF-7).

## 4. 쿼리 설계

> 네이티브 쿼리는 `domain/feed/repository/FeedQueryRepository`(`EntityManager.createNativeQuery`) 한곳에 모은다.
> 결과는 `(userId, sortKey, sourceId, occurredAt, groupDate)` 행 — **조회와 조립을 분리**해 조립은 `ActivityAssembler`가 한다.

### 4-1. 원천 합성 — `activity_union`

여섯 갈래를 `UNION ALL`. 각 갈래는 `user_id`·기간 조건을 **갈래 안에** 넣는다(밖에서 걸면 인덱스를 못 탄다).

```sql
SELECT wr.user_id, 1 AS sort_key, wr.id AS source_id, wr.created_at AS occurred_at, NULL AS group_date
  FROM watch_record wr WHERE wr.user_id IN (:users) AND wr.created_at BETWEEN :since AND :asOf
UNION ALL … review(2) … wish_movie(3) … collection(4) …
UNION ALL
SELECT c.user_id, 5, c.id, MAX(cm.created_at), DATE(cm.created_at)
  FROM collection_movie cm JOIN collection c ON c.id = cm.collection_id
 WHERE c.user_id IN (:users) AND cm.created_at BETWEEN :since AND :asOf
   AND DATE(cm.created_at) <> DATE(c.created_at)                -- 생성일 추가분은 흡수
 GROUP BY c.user_id, c.id, DATE(cm.created_at)
UNION ALL
SELECT f.follower_id, 6, f.id, f.created_at, NULL
  FROM follow f WHERE f.follower_id IN (:users) AND f.created_at BETWEEN :since AND :asOf
```

- `:since`가 없는 경우(내 스트립·개인 피드)는 조건을 빼거나 `'1970-01-01'`.
- 같은 갈래 목록을 세 용도(스트립 집계·프레임·개인 피드)에 쓰므로 **SQL 조각 생성 메서드 하나**로 만든다 — 갈래마다 조건이 어긋나는 사고를 막는다.

### 4-2. 팔로잉 스트립 (F-1)

1. **대상** — 내가 팔로우한 사람 중 볼 수 있는 사람(SQL로 거른다 — 뒤에서 거르면 페이지가 비는 문제가 생긴다):
   ```sql
   SELECT f.following_id FROM follow f JOIN `user` u ON u.id = f.following_id
    WHERE f.follower_id = :viewer
      AND (u.privacy_setting = 'PUBLIC'
           OR (u.privacy_setting = 'FRIENDS'
               AND EXISTS (SELECT 1 FROM follow b WHERE b.follower_id = u.id AND b.following_id = :viewer)))
   ```
   ⚠️ 이 조건은 `UserAccessPolicy.canView`를 **SQL로 옮긴 것**이다 — 정책이 바뀌면 함께 바뀌어야 한다(테스트 T-3이 둘을 대조한다).
2. **사람별 마지막 활동 — 첫 페이지와 이후 페이지를 나눈다** (2026-10-29 측정 ① 불합격 후 개정 — 4-2-A)
   - 공통: 갈래마다 먼저 `GROUP BY user_id`로 `MAX(created_at)`(갈래 안 집계)를 구한 뒤 바깥에서 다시 `MAX`. 원시 행을 합친 뒤 바깥에서만
     묶으면 30일 치 행이 전부 실체화된다. 그래서 4-1의 조각 생성 메서드는 **원시 행 모드**(프레임·개인 피드)와 **사람별 최댓값 모드**(스트립)를 낸다.
   - **첫 페이지(커서 없음) — 갈래 안에 기간 조건을 걸지 않는다.** `SELECT user_id, MAX(created_at) … WHERE user_id IN (:users) GROUP BY user_id`
     (고전 loose index scan — 사람당 탐색 1회). 30일 조건은 **바깥 `HAVING MAX(last_at) >= :since`**. `asOf` 조건도 갈래 안에 걸지 않는다 —
     첫 페이지의 `asOf`는 방금 정한 현재 시각이라 그 이후 활동이 사실상 없다(밀리초 경합으로 끼어든 행은 순서에만 영향, 프레임은 `asOf`로 잘린다).
     결과는 **보는 사람의 대상 전원 순서**(LIMIT 없음)이고, 이것을 4-2-A 캐시에 넣은 뒤 앞 `size+1`명을 자른다.
   - **이후 페이지(커서 있음)** — 캐시 적중이면 캐시된 순서를 키셋으로 자른다(DB 집계 없음). **캐시 미스**(만료·재시작·다른 인스턴스)면
     기존 **기간 경계 쿼리**(갈래 안 `created_at BETWEEN :since AND :asOf`, 커서 `last < :at OR (last = :at AND user_id < :uid)`,
     `LIMIT :size + 1`)로 대체한다 — 느리지만 커서의 `asOf` 기준으로 정확하다.
   - `COLLECTION_MOVIES_ADDED` 갈래의 상관 서브쿼리 대체는 측정에서 **오히려 느려 기각**(v35-delta.sql).
3. **프레임** — 이번 페이지 사람들(≤ size)에 대해 `ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY occurred_at DESC, sort_key DESC, source_id DESC) <= 6`.
   `seenAfter`가 있으면 같은 창에서 `COUNT(CASE WHEN occurred_at > :seenAfter THEN 1 END) OVER (PARTITION BY user_id)`로 `newCount`.
   ⚠️ 프레임 쿼리도 **since(30일)를 그대로** 건다 — 스트립 자격과 프레임 범위가 어긋나지 않게.
   **영화 단위 합치기(2026-10-30, 앱 요청)** — 같은 영화를 여러 번 본 사람의 띠가 같은 포스터로 채워지지 않게, 위 `<= 6`을 적용하기 **전에**
   `WATCH_RECORD` 갈래를 `ROW_NUMBER() OVER (PARTITION BY user_id, movie_id ORDER BY occurred_at DESC, source_id DESC) = 1`로 영화당 최신 1건만 남긴다.
   그 결과를 다른 갈래와 합친 뒤 사람별 최근 6개를 고르므로 합쳐진 만큼 뒤의 활동이 올라와 띠가 찬다. 합쳐진 프레임의 `occurredAt`·`watch`는 가장 최근 기록 기준이다.
   - **스트립 프레임에만** 적용한다. 개인 티켓 피드(3-2)는 2절대로 건별이다 — 같은 조각 생성 메서드를 쓰되 "영화별 최신 1건" 플래그로 가른다.
   - `REVIEW`·`WISH`·컬렉션·`FOLLOW`는 합치지 않는다. 같은 영화의 기록과 리뷰·찜도 서로 합치지 않는다(타입 표식이 다르다).
   - `newCount`(`seenAfter`)는 **합치기 전 원시 활동 수**로 센다 — "새 기록 N건"이 실제 행동 수와 맞고, `COUNT(...) OVER (PARTITION BY user_id)`를 합치기 앞 단계에서 그대로 쓴다.
   - `me`(4번)에도 같은 규칙을 적용한다.
   - **`WatchDetail.recordCount`(2026-10-30)** — 합쳐진 스트립 프레임이 대표하는 시청 기록 수(1 이상). 개인 티켓 피드에서는 `null`. 합치는 쿼리 안에서 `COUNT(*) OVER (PARTITION BY user_id, movie_id)`로 함께 구한다(추가 쿼리 없음). **30일 창 안의 기록만** 센다 — "이 기간에 N번"이라는 뜻이며 31일 전 기록은 제외한다(전체 횟수를 원하면 바꿔야 한다). `me`는 기간 제한이 없으므로 전체 기록을 센다.
4. **`me`** — 첫 페이지만. 내 id 하나로 3번(since 없음).
5. **조립** — 4-4.

**비용 근거 (1천 명을 팔로우한 사용자)** — 비용은 **내가 팔로우한 수**에 비례한다(팔로워 수와 무관 — 쓰기 시 전파가 없다).
⚠️ **초판 추정("6천 회 조회 → 수십 ms")은 약 3배 낙관적이었다** — 측정 ①(925명)에서 탐색 1회 약 20µs, 갈래 안 기간 조건이 있으면 MySQL이
사람마다 **두 번** 탐색해(범위 시작·끝) 집계가 142ms(p50), 스트립 p95 241.8ms로 불합격했다. 게다가 **페이지마다 전원 순서를 다시 계산**했다.
개정안은 ① 첫 페이지 탐색을 사람당 1회로(같은 데이터 mysql CLI 178 → 90ms) ② 이후 페이지는 캐시로 DB 집계 0회. **측정 ①-재**로 확인한다.

#### 4-2-A. 스트립 순서 캐시 — `StripOrderCache`

| 항목 | 값 | 이유 |
|---|---|---|
| 키 | `(viewerId, asOf)` | 같은 `asOf`의 커서만 같은 순서를 공유한다. 새로고침은 새 `asOf` → 새 항목 |
| 값 | 대상 전원의 `(userId, lastActivityAt)` 순서 목록 | 첫 페이지 집계 결과 그대로 |
| 만료 | `expireAfterWrite` **5분** | 한 번 훑어보는 시간. 지나면 미스 → 경계 쿼리로 대체(정확성 유지) |
| 크기 | **`maximumWeight` 200,000(목록 길이 합) + `weigher = 목록 크기`** | ⚠️ 항목 수 제한만 두면 1천 명 목록 × 수천 항목이 힙(`-Xmx640m`)을 위협한다. 행 하나 수십 바이트 → 상한 약 10MB대 |
| 위치 | Caffeine 로컬(단일 인스턴스 전제 — F-3 스냅샷과 같다) | |

- **캐시 적중 페이지는 페이지 인원에 `filterViewable`을 다시 적용**(최대 3쿼리) — 첫 페이지 이후 PRIVATE로 바꾼 사람·탈퇴한 사람을 거른다.
  걸러져 줄어든 페이지는 그대로 내보낸다(`hasNext`는 캐시 목록 기준).
- 무효화는 하지 않는다 — 내 팔로우 변경은 다음 새로고침(새 `asOf`)에 반영된다. 탈퇴 이벤트에서 지울 필요도 없다(키에 `asOf`가 있어 재사용되지 않고 5분 뒤 만료).

### 4-3. 개인 티켓 피드 (F-1)

`activity_union`(users = 대상 한 명, since 없음)에 키셋 조건:
`occurred_at < :at OR (occurred_at = :at AND (sort_key < :k OR (sort_key = :k AND source_id < :id)))` →
`ORDER BY occurred_at DESC, sort_key DESC, source_id DESC LIMIT :size + 1`.
(MySQL의 행 생성자 비교 `(a,b,c) < (x,y,z)`는 인덱스 범위로 풀리지 않는 경우가 있어 **풀어 쓴 OR**를 쓴다.)

### 4-4. 조립 — `ActivityAssembler`

행 목록을 타입별로 나눠 **IN절 벌크 조회 → `Map` → 원래 순서로 조립**(프로젝트 표준, backend-standards).

| 타입 | 벌크 조회 |
|---|---|
| `WATCH_RECORD` | 기록 + 영화 + OTT 플랫폼 이름 |
| `REVIEW` | 리뷰 + 영화 |
| `WISH` | 찜 + 영화 |
| `COLLECTION_*` | 컬렉션 + 편수(`CollectionMovieCountProjection`) + 미리보기 포스터(`CollectionPreviewPosterProjection`) — **기존 컬렉션 목록 쿼리 재사용** |
| `COLLECTION_MOVIES_ADDED` | (컬렉션, 날짜) 그룹의 포스터 4장 — `collection_id IN (…)`로 한 번에 읽고 날짜로 나눈다 |
| `FOLLOW` | 대상 사용자 → **`filterViewable(viewer, followeeIds)`로 거른 뒤** 조립 |

- 조회와 조립 사이에 행이 지워졌으면(조회 직후 삭제) **조용히 건너뛴다** — 예외를 던지지 않는다.
- 하나의 `@Transactional(readOnly = true)` 안에서 끝낸다.

### 4-5. 전체 스트립 (F-3)

전체를 매 요청 집계하면 비용이 **최근 7일 플랫폼 전체 활동량 × 요청 수**에 비례한다. 둘러보기 화면으로 정의를 좁혀 비용을 고정한다.

| 장치 | 내용 |
|---|---|
| 후보 스냅샷 | **PUBLIC 사용자** 중 최근 7일 활동이 있는 **상위 100명**의 `(userId, lastActivityAt)`. `activity_union`을 `user_id` 조건 없이 `created_at` 범위로(→ `(created_at)` 인덱스). Caffeine 1항목, **60초 만료**, 계산 시각이 `asOf` |
| 보는 사람별 추가 | FRIENDS이면서 나와 맞팔인 사람 중 최근 7일 활동자 — 4-2와 같은 싼 조회. 스냅샷과 합쳐 정렬 후 100명으로 자른다 |
| 서빙 시 재확인 | 페이지 인원에 `filterViewable`을 다시 적용(최대 3쿼리) — 60초 사이에 PRIVATE로 바꾼 사람·탈퇴한 사람을 거른다 |
| 본인 제외 | `me`는 3-1과 같이 따로 |

- 비용 = **분당 집계 1회 + 요청당 캐시 읽기 + 페이지 인원 프레임 쿼리** — 사용자 수와 무관하게 고정.
- 페이징은 현재 스냅샷 위의 키셋. 스냅샷이 갱신되는 경계에서 한 명이 빠질 수 있다 — **둘러보기라 허용**(문서화된 한계).
- 단일 인스턴스 전제(로컬 캐시). 서버를 늘리면 공용 캐시 저장소로 옮긴다 — 지금은 기록만.
- **규모 확장 경로(기록만)**: 사용자별 마지막 활동 시각 테이블 — 삭제 시 재계산이 필요해 SF-3과 충돌하므로 최후 수단.

## 5. 스키마 — V35 (확장, 인덱스만)

`V35__feed_indexes.sql` + `docs/schema/v35-delta.sql`(근거·`EXPLAIN` 전후·롤백).

| 테이블 | 인덱스 | 쓰는 곳 |
|---|---|---|
| `watch_record` · `review` · `wish_movie` · `collection` | `(user_id, created_at)` | 팔로잉 스트립·개인 피드·내 스트립 |
| `follow` | `(follower_id, created_at)` | 같은 용도의 `FOLLOW` 갈래 |
| `collection_movie` | `(collection_id, created_at)` | `COLLECTION_MOVIES_ADDED` 갈래 (user 조건은 `collection` 조인으로) |
| `watch_record` · `review` · `wish_movie` · `collection` · `collection_movie` · `follow` | `(created_at)` | **F-3 전체 스트립 전용** → **별도 마이그레이션으로 분리**(V36은 SF-12가 먼저 썼다 — F-3 착수 시 다음 번호)해 F-3와 함께 넣는다(F-3를 버리면 쓰지 않을 인덱스를 남기지 않는다) |

- 확인한 현황(v24 기준): 여섯 테이블 어디에도 `created_at` 인덱스가 없다. `review`·`wish_movie`는 `uk(user_id, movie_id)`뿐이다.
- `idx_collection_user_id (user_id)`는 새 `(user_id, created_at)`의 앞부분과 겹친다 — **이번에 지우지 않는다**(축소는 다음 릴리스, D-3 확장/축소 규칙). v35-delta에 후보로 기록만.
- 인덱스 이름: `idx_{table}_user_created` · `idx_follow_follower_created` · `idx_collection_movie_coll_created` · (F-3) `idx_{table}_created`.

## 6. 부하 측정 — 합격해야 머지

| | 측정 ① (F-1) | 측정 ② (F-3) |
|---|---|---|
| 데이터 | 사용자 1만 · 측정 대상 1명이 **1천 명 팔로우** · 30일 활동 30만 건(6종 분포: 기록 50% · 찜 20% · 리뷰 10% · 컬렉션 영화 추가 10% · 팔로우 7% · 컬렉션 3%) · 1년 치 과거 활동 100만 건(기간 밖 노이즈) | 같은 데이터 |
| 대상 | 팔로잉 스트립 첫 페이지·3페이지, 개인 피드(활동 5천 건 사용자) | 스냅샷 계산, 전체 스트립 첫 페이지(캐시 적중) |
| 합격 | 서버 p95 **≤ 200ms**(100회 반복) | 스냅샷 계산 **≤ 1s** · 캐시 적중 페이지 p95 ≤ 200ms |
| 기록 | `EXPLAIN ANALYZE` 전문 + 수치를 `v35-delta.sql`/DevLog에 | 같음 — **불합격이면 F-3를 버리고 결과만 기록** |

**측정 ①-재 (4-2 개정 후, 2026-10-29 추가)** — 같은 데이터·같은 기준(p95 ≤ 200ms). 항목: 첫 페이지 · 3페이지 **캐시 적중** · 3페이지 **캐시 미스**(참고값 —
대체 경로라 기준 적용 안 함, 현행과 비슷해야 정상) · 첫 페이지 집계 쿼리 단독 `EXPLAIN ANALYZE`(갈래마다 사람당 탐색 1회인지).
**불합격이면 머지하지 않고** 4-2의 다음 후보(사용자별 마지막 활동 컬럼 — SF-3과 충돌)를 다시 논의한다.

- **별도 DB `cinemory_perf`** 를 쓴다 — `cinemory_test`는 다른 테스트가 공유해 대량 시드가 섞이면 안 된다. Flyway로 최신까지 올린 뒤 시드.
- 시드는 `docs/schema/perf/social-feed-seed.sql`(재귀 CTE + `INSERT … SELECT`, 결정적 난수 `RAND(seed)`) — 재현 가능해야 한다.
- 측정은 `@Tag("perf")` JUnit(`SocialFeedPerfTest`) — **기본 `./gradlew test`에서 제외**(`test { useJUnitPlatform { excludeTags 'perf' } }` + 별도 `perfTest` 태스크).
- 실기기 첫 화면 포스터 2초는 **앱 스펙** 측정 항목이다.

## 7. 테스트 (`cinemory_test`, 실 DB)

| # | 시나리오 | 기대 |
|---|---|---|
| T-1 | 6종 활동 생성 후 개인 피드 | 6종 모두, `(occurredAt, sortKey, sourceId)` 내림차순 |
| T-2 | 기록 별점 수정 · 리뷰 재작성(upsert) | 피드 순서 그대로, 내용만 바뀜 |
| T-3 | **공개 범위 대조** — PUBLIC / FRIENDS(맞팔·단방향) / PRIVATE 대상을 팔로우 | 스트립 포함 여부가 `UserAccessPolicy.canView`와 **정확히 일치** |
| T-4 | FOLLOW 대상이 PRIVATE | 그 FOLLOW 프레임만 빠지고 나머지는 남음 |
| T-5 | 컬렉션 생성 당일 3편 추가 + 다음 날 2편 추가 | `COLLECTION_CREATED` 1 + `COLLECTION_MOVIES_ADDED`(count 2) 1 |
| T-6 | 컬렉션 삭제 · 언팔로우 · 찜 해제 · 회원 탈퇴 | 해당 프레임·스트립 사라짐(정리 코드 없이) |
| T-7 | 31일 전 활동만 있는 팔로잉 | 스트립에서 제외, 개인 피드에는 있음 |
| T-8 | **커서 안정성** — 1페이지 조회 후 새 활동 추가 → 2페이지 | 중복·누락 없음(`asOf` 이후 활동 무시) |
| T-9 | 동시각(같은 초) 활동 여러 개 | 페이지 경계에서 중복·누락 없음 |
| T-10 | 잘못된 커서 · `scope=ALL`(F-3 전) | 400 `INVALID_INPUT_VALUE` |
| T-11 | 비로그인 | 401 (두 API 모두) |
| T-12 | `WATCH_RECORD` 응답 JSON | **`privateReview` 키가 없다**(문자열 검색으로 확인) |
| T-13 | `me` | 첫 페이지에만, 활동 없으면 `frames: []` |
| T-14 | `seenAfter` | `newCount`가 그 이후 활동 수(30일 창 안) |
| T-15 | **캐시 경로와 경계 쿼리 경로의 동등성** — 같은 `asOf`로 전 페이지를 ① 캐시 적중 ② 캐시를 비운 뒤(미스 대체) 각각 넘김 | 두 결과의 사람 순서가 **완전히 같다** |
| T-16 | 첫 페이지 후 2페이지 대상이 PRIVATE로 변경 · 탈퇴 | 캐시 적중 2페이지에서 그 사람만 빠짐 |
| T-17 | 첫 페이지(기간 조건 없는 집계) — 마지막 활동이 31일 전인 대상 | `HAVING`으로 제외 |
| T-18 | **프레임 영화 합치기** — 같은 영화 3회 + 다른 영화 5편 | 스트립 `frames`가 6장이고 그 영화는 1장(가장 최근 기록의 `watch`). **같은 데이터의 개인 피드는 기록 3건이 모두** 나온다. `seenAfter`를 걸면 `newCount`는 합치기 전 건수 |
| T-19 | **`recordCount`** — 같은 영화 3회 중 1회가 31일 전이면 그 프레임의 `recordCount` 2, 단일 기록이면 1 | 개인 티켓 피드의 `watch.recordCount`는 `null` |

## 8. 패키지 · 클래스

```
domain/feed/
  controller/FeedController.java          GET /api/feed/strips
  controller/UserActivityController.java   GET /api/users/{userId}/activities  (또는 FeedController에 함께 — 구현 시 결정)
  dto/ FeedStripsResponse · FeedStripResponse · ActivityPageResponse · ActivityResponse · FeedUserResponse · WatchDetail · CollectionDetail · AddedMovies
  entity/ActivityType.java                 enum + sortKey(고정 정수)
  repository/FeedQueryRepository.java      4-1 ~ 4-3 네이티브 쿼리
  repository/ActivityRow.java              (userId, sortKey, sourceId, occurredAt, groupDate)
  service/FeedService.java                 스트립·개인 피드 흐름, asOf·커서
  service/ActivityAssembler.java           4-4
  service/FeedCursor.java                  인코드·디코드
  service/StripOrderCache.java            4-2-A 보는 사람별 스트립 순서 캐시
  service/AllScopeSnapshotCache.java       (F-3) 4-5
```

- 엔티티가 없는 조회 전용 도메인이다 — `ActivityType`은 DB에 저장되지 않으므로 `entity/` 대신 `domain/feed/` 바로 아래도 무방(구현 시 기존 `recommendation/service/…` 관례에 맞춘다).
- `ErrorCode` 신규 없음.

## 9. 회원 탈퇴와의 관계

조회 시 합성이라 **탈퇴 서비스에 추가할 것이 없다** — 원천 행이 CASCADE로 지워지면 피드에서도 사라진다.
F-3 스냅샷에 탈퇴자가 최대 60초 남을 수 있으나 서빙 시 `filterViewable`이 `findAllById`로 걸러낸다(4-5).

## 10. 팔로우 진입 — 사용자 검색 · 공개 범위 기본값 · 새 팔로워 (F-4, 2026-10-30)

> 소셜 탭 A-1을 구현하고 보니 **팔로우할 사람을 찾을 경로가 없었다**(Claude Code 제안 → 설계 세션). 추천 사용자는 F-3 전체 스트립과 겹쳐 따로 만들지 않는다.

| # | 항목 | 결정 | 이유 |
|---|---|---|---|
| SF-12 | **공개 범위 기본값** | **새 가입자 `FRIENDS`**(로컬·OAuth 모두) — 기존 사용자는 바꾸지 않는다 | 기본값 `PRIVATE`이면 설정을 안 바꾸는 대다수가 검색·피드에 나타나지 않아 **소셜 탭이 비어 보인다.** FRIENDS는 맞팔로우한 사람에게만 보여 "본인만의 기록"과 소셜 참여의 중간이다. 기존 사용자는 이미 PRIVATE에 동의한 상태라 일괄 변경하지 않는다. 대안 A(소셜 탭 안내 시트)·C(가입 시 선택)는 사용자 결정으로 기각 |
| SF-13 | **사용자 검색** | `GET /api/users/search?q=` — **닉네임만**, 부분 일치, 2자 이상, 인증 필수 | 이메일 검색은 개인정보 노출. 비로그인 허용 시 2자 조합으로 닉네임 전체를 수집할 수 있다 |
| SF-14 | 검색 대상 | **PUBLIC · FRIENDS만** — PRIVATE · **ADMIN** · 본인 제외 | PRIVATE는 팔로우해도 볼 것이 없다(존재 은닉 목적이 아니다 — 프로필 헤더·팔로우 목록은 이미 PRIVATE도 보여 준다). FRIENDS는 헤더·팔로우 버튼은 보이고 본문만 403(현행 규칙). 운영 관리자 계정(admin-spec AD-5)은 노출 금지 |
| SF-15 | **팔로우 목록 정렬** | 팔로워·팔로잉 목록을 **`follow.id DESC`(최근 팔로우 순)**로 고정 | 지금 `findByFollowingId/findByFollowerId(Pageable)`에 **정렬이 없다** — 잔여 #17과 같은 유형(무한스크롤 중복·누락). 새 팔로워가 맨 위에 와야 맞팔로우 흐름이 된다 |
| SF-16 | **새 팔로워 수** | 스트립 첫 페이지 응답에 `newFollowerCount` — 파라미터 `followersSeenAfter`가 있을 때만 | FRIENDS는 맞팔로우가 필요한데 알림(M4)이 없어 **누가 나를 팔로우했는지 알 신호가 없다.** 앱의 진입 칩이 이 수를 보여 준다. `social.lastSeenAt`(피드)과 **다른 기준 시각**을 쓴다 — 탭을 지나치기만 해도 0이 되면 안 된다. M4가 생기면 알림으로 옮긴다 |

### 10-1. 스키마 — V36 (확장)

`V36__user_privacy_default_friends.sql` + `docs/schema/v36-delta.sql`:
`ALTER TABLE user ALTER COLUMN privacy_setting SET DEFAULT 'FRIENDS'` — 메타데이터만 바뀐다(즉시). **기존 행은 그대로.**

- 엔티티 `User` 생성자의 `this.privacySetting = PrivacySetting.PRIVATE`도 `FRIENDS`로(주석 "DB 컬럼 default와 동일하게 명시" 유지). 실제 값은 엔티티가 명시해 넣으므로 이 줄이 결정의 본체다.
- 롤백 안전: 옛 jar는 PRIVATE를 명시해 넣을 뿐 새 기본값과 충돌하지 않는다(확장/축소 규칙 D-3 충족).
- ⚠️ 기본값에 기대던 테스트(가입 직후 PRIVATE를 전제)를 찾아 **명시적으로 설정**하도록 고친다 — 기본값 변경으로 초록불이 의미를 잃지 않게.
- 개인정보처리방침(M5)에 "새 계정의 기본 공개 범위는 맞팔로우 공개" 명시. 앱 로컬 가입 화면에 한 줄 안내(앱 스펙 A-5).

### 10-2. 검색 API

| 항목 | 값 |
|---|---|
| 경로 | `GET /api/users/search?q=&page=&size=` — **인증 필수**(`/api/users/**` GET이 `permitAll`이라 `@AuthUser(required = true)`) |
| `q` | 앞뒤 공백 제거 후 **2~20자**. 밖이면 400 `INVALID_INPUT_VALUE` |
| 일치 | `nickname LIKE CONCAT('%', :q, '%') ESCAPE '\\'` — **`%`·`_`·`\` 이스케이프 필수**(입력 `%`가 전체 일치가 되지 않게) |
| 필터 | `privacy_setting IN ('PUBLIC','FRIENDS') AND role = 'USER' AND id <> :viewer` |
| 정렬 | 완전 일치(0) → 앞부분 일치(1) → 포함(2), 같으면 `id ASC` — `ORDER BY CASE … END, id`. 고정 정렬이라 offset 페이징이 안정적이다 |
| 응답 | `PageResponse<FollowUserResponse>`(`following` 포함, 기존 `findFollowingIds` 벌크 재사용), `@PageableDefault(size = 20)` |
| 성능 | 닉네임 인덱스 없음 → 부분 일치는 `user` 전체 스캔. 수만 명까지 수 ms라 지금은 그대로. **확장 경로(기록만)**: MySQL ngram FULLTEXT — 기본 `ngram_token_size=2`가 "2자 이상" 규칙과 맞는다 |

- 닉네임은 유일하지 않다(제약 없음) — 동명이인은 프로필 사진으로만 구분된다. 지금 범위에서 허용.

### 10-3. 새 팔로워 수

- `GET /api/feed/strips`에 선택 파라미터 `followersSeenAfter`(ISO-8601). **첫 페이지에서만** `FeedStripsResponse.newFollowerCount`(없으면 `null`).
- `SELECT COUNT(*) FROM follow WHERE following_id = :me AND created_at > :followersSeenAfter AND created_at <= :asOf` — 기존 `fk_follow_following(following_id)` 인덱스로 내 팔로워 행만 읽는다(1천 명이어도 싸다). 새 인덱스 없음.
- 팔로워의 공개 범위는 따지지 않는다 — 나를 팔로우한 사실은 내 정보다(팔로워 목록도 거르지 않는다).

### 10-4. 테스트

| # | 시나리오 | 기대 |
|---|---|---|
| T-20 | 로컬·OAuth 신규 가입 | `privacySetting = FRIENDS` · 기존 PRIVATE 사용자는 그대로 |
| T-21 | 검색 — PUBLIC·FRIENDS·PRIVATE·ADMIN·본인이 모두 일치 | PUBLIC·FRIENDS만, `following` 정확 |
| T-22 | 검색 정렬 | 완전 일치 → 앞부분 → 포함 |
| T-23 | `q`에 `%` · `_` | 문자 그대로 일치(와일드카드로 해석 안 됨) |
| T-24 | `q` 1자 · 21자 · 공백만 · 비로그인 | 400 · 400 · 400 · 401 |
| T-25 | 팔로워 목록 3페이지 넘김(팔로우 45건) | 최근 팔로우 순, 중복·누락 없음 |
| T-26 | `followersSeenAfter` 이후 팔로우 2건 · 이전 1건 | `newFollowerCount = 2`, 2페이지 응답에는 `null` |

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-10-30 | **F-4 구현 완료 — 10절 그대로, 구현과 달라진 점.** V36(컬럼 DEFAULT만, 기존 행 불변)·`User` 생성자 `FRIENDS`·`GET /api/users/search`·팔로워/팔로잉 `follow.id DESC`·`followersSeenAfter` → 첫 페이지 `newFollowerCount`. 달라진 점: ① 검색 LIKE 이스케이프는 스펙의 `ESCAPE '\\'` 대신 **기존 `LikePattern`의 `!`**(`like … escape '!'`)을 재사용했다 — `%`·`_`·이스케이프 문자가 글자 그대로 일치한다는 계약은 같고(T-23이 `\`도 확인), JPQL/텍스트블록에서 백슬래시를 이스케이프 문자로 쓰는 실수를 피한다 ② 검색은 `FollowService.searchUsers`(팔로우 여부 벌크 조회를 재사용) + `UserController`, 요청의 `sort`는 무시(정렬은 쿼리가 고정) ③ 팔로워/팔로잉 목록도 요청 `sort`를 무시한다 ④ `FeedService.getStrips`에 6인자 오버로드를 추가하고 5인자는 위임으로 남겼다(성능 테스트 호환), `FeedService`가 `FollowRepository`를 주입받는다 ⑤ 기본값 PRIVATE에 기대던 테스트는 `AdminUserQueryServiceTest`("PRIVATE도 관리자에게 조회된다")뿐이었고 명시적으로 설정하게 고쳤다. T-20~T-26 → `FollowEntryApiTest`. 앱 `gen:api` 필요(`newFollowerCount`) |
| 2026-10-30 | **10절 신설(F-4, SF-12~SF-16) — 팔로우 진입 경로.** A-1 구현 후 팔로우할 사람을 찾을 경로가 없음을 발견. Claude Code의 닉네임 검색 제안을 채택하되 보강: 인증 필수(수집 방지)·ADMIN 제외·`LIKE` 이스케이프·정렬 고정. 검토 중 **새 사용자 기본값이 PRIVATE라 소셜 탭이 비어 보이는 문제**를 찾아 **새 가입자 기본값을 FRIENDS로**(SF-12, 사용자 결정 — 기존 사용자는 유지). FRIENDS는 맞팔로우가 필요한데 알림이 없어 **새 팔로워 수**(SF-16)를 스트립 응답에 추가. 팔로우 목록에 정렬이 없던 것도 함께 고정(SF-15). F-3 인덱스 마이그레이션 번호는 V36을 SF-12가 써서 착수 시 다음 번호로 |
| 2026-10-30 | **`WatchDetail.recordCount` 추가 구현 완료.** 합친 프레임이 대표하는 시청 기록 수 — 합치기 쿼리의 `COUNT(*) OVER (PARTITION BY user_id, movie_id)`(추가 쿼리 없음), 30일 창 안 기록만, 개인 피드는 `null`. `FrameRow.recordCount` → 서비스가 `ActivityResponse.withWatchRecordCount`로 싣는다. T-19 추가. 응답 DTO 필드가 늘어 **앱 `gen:api` 필요**(확장, 기존 필드 불변). **재측정 3회**(같은 시드, n=100): 프레임 쿼리 p50 5.6~6.9ms(합치기만 있을 때 5.3) · 첫 페이지 p95 346.1ms(불합격) / 193.9ms / 171.7ms. 순위 쿼리(94.9~99.4ms)·프레임은 안정적인데 첫 페이지 전체만 흔들리는 로컬 환경 잡음 패턴이지만, **여유가 200ms에 거의 없다(193.9)** — 운영 배포 뒤 실측 필요. 불합격이 나온 실행도 숨기지 않는다 |
| 2026-10-30 | **4-1 ③ 영화 단위 합치기 구현 완료.** `FeedQueryRepository.framesSql(bounded, withSeenAfter, dedupeWatch)` — 같은 조각 생성 메서드 `activityUnion(maxMode, range, withMovie)`에 원시 행 모드 전용 `movie_id` 열 플래그를 더해(WATCH_RECORD만 값, 나머지 NULL) 프레임이 `ROW_NUMBER() OVER (PARTITION BY user_id, movie_id ORDER BY occurred_at DESC, source_id DESC)`로 영화당 최신 1건만 남긴 뒤 사용자별 6개를 고른다. `newCount`는 합치기 앞 단계에서 세므로 원시 건수. 개인 티켓 피드(`activitiesSql`)는 손대지 않았다. `findFrames`는 항상 합친다(스트립·`me`). 테스트 T-18 + `me` 합치기 보강 1건 추가. **재측정(cinemory_perf, 같은 시드, n=100)**: 프레임 쿼리 p50 4.3 → 5.3ms · p95 7.4 → 9.7ms, 첫 페이지 p95 159.8ms · 3페이지 적중 47.4ms · 개인 피드 21.1ms로 합격. ⚠️ 첫 번째 실행에서 첫 페이지 p95 385.7ms(max 2,394ms)로 불합격이 한 번 나왔다 — 같은 실행의 프레임·순위 구간은 평소 수준(프레임 4.9ms, 순위 103.9ms)이라 일시적 스파이크(로컬 환경 잡음)로 보이며 같은 조건으로 다시 돌린 두 번째 실행은 합격했다. 두 실행 모두 기록한다. **스펙과 다른 점은 없다** |
| 2026-10-30 | **4-1 ③ 개정 — 스트립 프레임을 영화 단위로 합친다(앱 `social-screen-spec` A-1 요청).** `WATCH_RECORD`가 재관람마다 한 행이라 같은 영화를 여러 번 본 사람의 띠가 같은 포스터로 채워졌고, 앱이 받은 6장에서 임시로 합치면 합친 만큼 띠가 비었다(앱에는 채울 후보가 없다). 프레임 쿼리에서 `<= 6` 전에 `(user_id, movie_id)`별 최신 1건만 남긴다. 개인 티켓 피드는 건별 유지(2절), 다른 갈래는 합치지 않음. `newCount`는 합치기 전 건수. 응답 DTO·엔드포인트 변경 없음(앱 `gen:api` 불필요). 테스트 T-18 추가. 구현은 아래 2026-10-30 "구현 완료" 행 |
| 2026-10-29 | **F-1 구현 완료 — 측정 ①-재 합격.** 4-2 개정안(A+B) 그대로 구현: 첫 페이지 순위 쿼리(갈래 안 경계 제거 + 바깥 `HAVING`) + `StripOrderCache`(`maximumWeight` 200,000). 결과(cinemory_perf, 같은 시드, n=100): 스트립 첫 페이지 p95 **161.1ms**(이전 241.8) · 3페이지 캐시 적중 **44.1ms**(이전 205.6) · 개인 피드 31.1ms · (참고) 캐시 미스 198.7ms. 새 쿼리 EXPLAIN은 5갈래 모두 `Using index for group-by`에 읽은 행 = 사람 수(925)로 사람당 탐색 1회를 확인했고, 순위 쿼리 단독은 142 → 92ms. **스펙과 다른 점은 없다.** 구현 중 정한 것: 캐시에 빈 목록도 넣음 · 커서는 공개 범위 재확인으로 거르기 전 마지막 행 기준 · `size`는 400이 아니라 범위로 보정 · 프레임이 모두 걸러진 스트립은 목록에서 뺌 · `me.newCount`는 0 · `seenAfter`는 UTC(`Z`)도 허용 · `asOf`는 초 단위 올림(DATETIME(0) 반올림 때문). 정정: 3-2의 "`/api/users/**` GET이 `permitAll`"은 사실과 다르다 — `PUBLIC_GET_ENDPOINTS`는 경로별 목록이고 `activities`는 목록 밖이라 기본 `authenticated()`로 401이 나며 `@AuthUser(required = true)`를 한 번 더 둔다. **다음 병목 후보**: `COLLECTION_MOVIES_ADDED` 갈래 37.5ms(중첩 루프) — 합격이라 보류. 계약은 `controller-layer-spec.md` **5-16**으로 이동(단일 출처) |
| 2026-10-29 | **4-2 개정 — 측정 ① 불합격(스트립 p95 241.8ms / 3페이지 205.6ms) 대응.** 원인은 인덱스 미사용이 아니라 **탐색 횟수**: 925명 × 6갈래, 갈래 안 기간 조건 때문에 사람당 2회 탐색, 그리고 페이지마다 전원 순서 재계산. 초판 비용 추정이 약 3배 낙관적이었음을 기록. 선택지 A(기간 조건을 바깥 HAVING으로)·B(보는 사람별 순서 캐시)·C(마지막 활동 컬럼 — SF-3과 충돌)·D(기준 완화 — 기각) 중 **A+B 채택**: 첫 페이지는 `asOf`가 현재라 기간 조건을 빼도 정합성 문제가 없고, 이후 페이지는 첫 페이지 순서를 재사용하며, 캐시 미스는 기존 경계 쿼리로 대체해 정확성을 유지한다. 캐시는 `maximumWeight`로 힙 상한. T-15~T-17, 측정 ①-재 추가 |
| 2026-10-29 | **신설 — SF-1~SF-11 확정.** 소셜 설계 세션: 댓글은 신고·개인정보 정책 부재와 "본인만의 기록" 컨셉으로 캡스톤 밖(API만 끔). 피드 형식은 4안(전광판·영화 중심·티켓·필름 릴)을 목업으로 비교해 **필름 스트립 목록 → 티켓 피드 상세**로 확정 — 한 사람이 한 줄이라 도배 문제가 구조로 풀린다. "수정·삭제는 조용히"에서 **활동 테이블 없는 조회 시 합성**이 결정됐다. 범위는 팔로잉 기본(사용자 결정 — 전체는 사용자가 늘면 난잡). 기간은 팔로잉 30일/전체 7일 — 1천 명 팔로우 기준 비용을 따져 팔로잉은 그룹별 MAX라 기간 영향이 거의 없고, 전체는 플랫폼 활동량에 비례해 **상위 100명 + 60초 공용 캐시**로 비용을 고정. 성능 변수가 많아 **측정 합격을 머지 조건**으로 두고 전체 스트립(F-3)은 버릴 수 있게 분리 |
