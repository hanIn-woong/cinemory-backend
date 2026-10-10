# CineMory — 스키마 무결성 정리 · 프로필 사진 · 회원 탈퇴 스펙

> **이 문서는 새 세션이 컨텍스트 없이 착수할 수 있도록 쓴 인수인계 문서다.** 실서버 배포
> (`docs/deploy-spec.md`) 직전, *"추천·CineMap·소셜을 제외한 미구현 기능"* 을 점검하다 나온 세 덩어리를 묶었다.
>
> | Part | 내용 | 착수 조건 | 시점 |
> |---|---|---|---|
> | **A** | **스키마 무결성 정리 V18~V22** — FK 정책 3건 + CHECK 2건(+ V22: V19 CHECK 정정) | Flyway 도입(deploy-spec **1-4**) 완료 | **Phase 1 직후, Phase 3(데이터 이관) 전** — 운영 DB가 처음부터 V22로 시작한다 |
> | **B** | **프로필 사진** (S3 + CloudFront) — security-spec **L-13** 확정 | 운영 서버 + AWS 계정(Phase 2) | 배포 후 |
> | **C** | **회원 탈퇴** | **Part A의 V18 필수**, Part B(S3 삭제)와 연동 | 배포 후, M5(스토어 출시) 전 필수 |
> | **D** | **소셜 계정 연결 + 구글·네이버 로그인** — 정책 확정, 설계 세부는 착수 시 | Phase 1 머지 | **Phase 2 동안 병렬 개발, Phase 5 이후 머지** (10월 우선순위 2번) |
>
> **계약은 확정 후 레이어 스펙으로 옮긴다** — 엔드포인트는 `controller-layer-spec.md`, 서비스 로직은
> `service-layer-spec.md`, 스키마는 `docs/schema/v{n}-delta.sql`. 이 문서는 **결정 기록과 근거**로 남는다
> (`M3a-report-spec.md`와 같은 위치).

**착수 전에 읽을 것**

| 문서 | 왜 |
|---|---|
| `docs/deploy-spec.md` **D-3 · 1-4** | Flyway 규칙 — **실행 SQL은 적용 후 동결**, 설계 근거는 `docs/schema/` |
| `docs/service-layer-spec.md` **4-5** | `collection_movie` RESTRICT를 서비스에서 우회하기로 했던 원래 결정과 근거 |
| `docs/security-spec.md` **S-11** | L-3(Access Token 즉시 무효화 불가), L-13(프로필 사진 저장 위치) |
| `CineMory_기획노트.md` **2-6** | FK `ON DELETE` 정책표 — Part A로 갱신된다 |
| `CLAUDE.md` | *"cascade 옵션은 지정하지 않음. 삭제 정책은 DB의 FK 제약이 전담"* |

---

# Part A — 스키마 무결성 정리 (V18 ~ V22)

## A-0. 왜 지금인가

회원 탈퇴를 설계하다 **user 행 하나를 지우면 실제로 무엇이 일어나는지** 따라가 보니, `collection_movie`의
RESTRICT 때문에 **영화가 담긴 컬렉션이 있는 사용자는 삭제 자체가 실패**했다. 같은 유형(FK 정책이 데이터의
의미와 어긋남)을 스키마 전체에서 점검한 결과가 아래 4건이다(2026-10-01).

**지금이 가장 싸다** — 운영 데이터가 쌓이기 전이고, Flyway가 들어오면 변경 하나가 파일 하나다.

## A-1. 점검 결과

| # | FK / 컬럼 | 현재 | 문제 | 변경 |
|---|---|---|---|---|
| 1 | `collection_movie → collection` | RESTRICT | 영화가 담긴 컬렉션은 DB가 삭제를 막는다. 지금은 `deleteCollection`이 하위 행을 먼저 지워 우회 중 | **CASCADE** (V18) |
| 2 | `watch_record → ott_platform` | SET NULL | 플랫폼 행을 지우면 OTT 기록이 **"OTT인데 플랫폼 없음"** — 서비스가 400으로 막는 조합이 DB에서 만들어진다. 그 기록은 이후 **수정도 불가**(전체 치환 PATCH가 400) | **RESTRICT** + **CHECK** (V19) |
| 3 | `movie_genre → genre` · `movie_actor → person` · `movie_director → person` | CASCADE | 같은 참조 테이블인 `movie_country → country`는 RESTRICT — 일관성 없음. 장르 하나를 지우면 영화들에서 **조용히 빠지고 `movie_genre.weight`(1/N) 합이 1이 아니게 돼** 리포트·추천 선호 점수가 틀어진다. 배우도 D-1의 *"영화당 배우 기여 총점 5.6 고정"* 이 깨진다 | **RESTRICT** (V20) |
| 4 | `watch_record.rating` | 제약 없음 | `decimal(3,1)`은 형식만 정한다 — `7.5`·`0.0`·`99.9`가 모두 저장된다. 규칙(1~10 정수)은 엔티티 `validateRating`만 지킨다 | **CHECK** (V21) |

- 2·3은 **잠복 상태**다 — 코드에 장르·인물·OTT 플랫폼 삭제 경로가 없다(시드는 전부 upsert). **운영에서 관리자가
  SQL로 직접 지우는 순간** 드러나는 유형이다. OTT 플랫폼을 내리는 정상 경로는 `ott_platform.is_active`다.
- `movie_x → movie` CASCADE는 **영화에 딸린 데이터**라 맞는 설계다 — 그대로 둔다.
- `collection_movie → movie`·`watch_record/review/wish_movie → movie`의 RESTRICT도 그대로 — 영화 삭제로부터
  **사용자 콘텐츠를 보호**하는 방향이다.

### 원래 결정과의 관계 — A-1 #1

`service-layer-spec.md` 4-5(2026-07-23)가 이미 적어 두었다: *"`collection_movie`는 `movie_genre`처럼 부모 없이는
의미 없는 순수 소속 관계라 **원래는 CASCADE가 더 일관됐을 관계다.** 다만 스키마를 되돌리는 실익이 적어 Service가
순서를 보장하기로 확정."* **그 판단의 전제 둘이 바뀌었다** — ① 삭제 경로가 `deleteCollection` 하나에서 **회원 탈퇴까지
둘**이 됐고(앞으로 관리자 삭제·배치가 생기면 경로마다 순서를 기억해야 한다), ② Flyway로 스키마 변경 비용이 파일 하나가
됐다. 또 **서비스 순서 방식은 이미 사고를 한 번 냈다** — `@Modifying(clearAutomatically = true)`가 앞서 지운
`collection_movie`를 flush 전에 폐기해 **RESTRICT 위반이 실제로 발생**했다(DevLog 4-6). CLAUDE.md의 *"삭제 정책은 DB의
FK 제약이 전담"* 원칙으로 돌아간다.

### 점검했으나 바꾸지 않는 것

| 대상 | 이유 |
|---|---|
| `comment.target_type/target_id` | 다형 참조라 FK를 걸 수 없다. 서비스가 정리(`deleteByTarget`). **소셜 설계 때 구조 재검토**(예: `collection_id`·`review_id` 중 하나만 채우는 배타 FK) |
| `notification.target_type/target_id` | 같은 구조. M4 착수 시 위와 함께 |
| 대표 기록 단일성 | 잔여 #19 — 생성 컬럼 + UNIQUE가 Hibernate flush 순서와 충돌해 보류 중 |
| **리뷰 ↔ 시청 기록 독립** | **의도된 설계로 확인**(2026-10-01). 기록을 전부 지워도 리뷰는 남고, 리뷰에 보이는 별점(대표 기록 파생)만 사라진다. 본 적 없는 영화에도 리뷰를 쓸 수 있다 |

## A-2. 마이그레이션 파일

> deploy-spec D-3 조건 — **작게 쪼갠다**(MySQL DDL은 트랜잭션으로 되돌릴 수 없다). 파일마다
> `src/main/resources/db/migration/V{n}__*.sql`(실행, **적용 후 동결**) + `docs/schema/v{n}-delta.sql`(설계 근거·
> **사전 점검 쿼리**·**롤백**) 한 쌍.

### V18 — `V18__collection_movie_cascade.sql`

```sql
ALTER TABLE collection_movie DROP FOREIGN KEY fk_collection_movie_collection;
ALTER TABLE collection_movie
  ADD CONSTRAINT fk_collection_movie_collection
  FOREIGN KEY (collection_id) REFERENCES collection (id) ON DELETE CASCADE;
```

**코드 변경** — `CollectionService.deleteCollection`

- `collectionMovieRepository.deleteAllByCollectionId(collectionId)` 호출과 *"RESTRICT 대응"* 주석 **삭제**.
  DB가 정리한다. **댓글 정리(`deleteByTarget`)는 유지**(다형 참조라 DB가 못 한다).
- `CollectionMovieRepository.deleteAllByCollectionId`가 다른 곳에서 쓰이지 않으면 메서드도 삭제.
- `service-layer-spec.md` 4-5의 "스키마 이슈" 절에 *"V18로 해소"* 를 추가하고 `deleteCollection` 행을 갱신.

### V19 — `V19__watch_record_ott_restrict_check.sql`

> ⚠️ **V19의 CHECK에는 NULL 구멍이 남아 있다 — V22에서 정정** (2026-10-02, Phase 1 구현 중 `SchemaConstraintTest`가 발견).
> 아래 SQL은 **이미 로컬 두 DB에 적용돼 동결된 원본**이라 그대로 둔다. 정정 내용과 근거는 **V22 절**.

**사전 점검** (0이어야 한다 — 지금은 OTT 저장이 막혀 있어 0이 정상) — ⚠️ 이 쿼리도 같은 구멍이 있어 `watch_type=NULL` +
플랫폼 행을 세지 못한다. **V22 절의 사전 점검 쿼리를 쓸 것.**

```sql
SELECT COUNT(*) FROM watch_record
WHERE NOT ((watch_type = 'OTT' AND ott_platform_id IS NOT NULL)
        OR ((watch_type IS NULL OR watch_type <> 'OTT') AND ott_platform_id IS NULL));
```

```sql
ALTER TABLE watch_record DROP FOREIGN KEY fk_watch_record_ott;
ALTER TABLE watch_record
  ADD CONSTRAINT fk_watch_record_ott
  FOREIGN KEY (ott_platform_id) REFERENCES ott_platform (id) ON DELETE RESTRICT;
ALTER TABLE watch_record
  ADD CONSTRAINT chk_watch_record_ott
  CHECK ((watch_type = 'OTT' AND ott_platform_id IS NOT NULL)
      OR ((watch_type IS NULL OR watch_type <> 'OTT') AND ott_platform_id IS NULL));
```

- ~~⚠️ **조건을 풀어 쓴다.** `(watch_type = 'OTT') = (ott_platform_id IS NOT NULL)`로 줄이면 `watch_type`이 NULL일 때
  결과가 NULL이고, MySQL CHECK는 FALSE일 때만 거부하므로 그냥 통과한다.~~ → ❌ **틀린 판단이었다.** 풀어 쓴 조건도 첫 항의
  `watch_type = 'OTT'`가 NULL을 퍼뜨려 `NULL OR FALSE = NULL`로 **똑같이 통과**한다. 정정은 **V22**(NULL 안전 비교 `<=>`).
- ⚠️ **FK RESTRICT와 한 쌍이다.** MySQL은 CHECK에 쓰인 컬럼에 `SET NULL`·`CASCADE` 같은 FK 참조 동작을 허용하지 않는다
  — 순서를 바꿔 CHECK를 먼저 걸면 실패한다. **빈 DB Flyway 경로(deploy-spec 1-4 ④)에서 실제로 통과하는지 확인**한다.
- **서비스 검증(`validateWatchTypeConsistency`)은 유지** — 사용자에게 `INVALID_WATCH_TYPE_OTT_COMBINATION`이라는 정확한
  에러를 주는 쪽은 서비스다. DB는 **서비스를 거치지 않는 경로의 안전망**이다.
- 엔티티 변경 없음(FK 참조 동작은 JPA에 매핑하지 않는다 — CLAUDE.md).

### V20 — `V20__reference_fk_restrict.sql`

```sql
ALTER TABLE movie_genre    DROP FOREIGN KEY fk_movie_genre_genre;
ALTER TABLE movie_genre    ADD CONSTRAINT fk_movie_genre_genre
  FOREIGN KEY (genre_id)  REFERENCES genre (id)  ON DELETE RESTRICT;

ALTER TABLE movie_actor    DROP FOREIGN KEY fk_movie_actor_person;
ALTER TABLE movie_actor    ADD CONSTRAINT fk_movie_actor_person
  FOREIGN KEY (person_id) REFERENCES person (id) ON DELETE RESTRICT;

ALTER TABLE movie_director DROP FOREIGN KEY fk_movie_director_person;
ALTER TABLE movie_director ADD CONSTRAINT fk_movie_director_person
  FOREIGN KEY (person_id) REFERENCES person (id) ON DELETE RESTRICT;
```

- `MovieSyncPersister`는 `movie_actor`·`movie_director`를 **`movie_id` 기준으로** 지우고 다시 넣는다(자식 행 삭제) —
  `person` 행은 지우지 않으므로 RESTRICT의 영향이 없다. **resync 1건을 돌려 확인**한다.
- ⚠️ `movie_actor`는 18만 행 규모다. FK 재생성은 기존 행을 검증하므로 몇 초~수십 초 걸릴 수 있다 — 로컬에서 시간을 재고
  delta 문서에 기록한다. 운영은 Phase 3(이관) **전**이라 빈 테이블에 적용된다.

### V21 — `V21__watch_record_rating_check.sql`

**사전 점검** (0이어야 한다 — v16 정정 이후 저장값은 모두 정수)

```sql
SELECT COUNT(*) FROM watch_record
WHERE rating IS NOT NULL AND NOT (rating BETWEEN 1 AND 10 AND rating = FLOOR(rating));
```

```sql
ALTER TABLE watch_record
  ADD CONSTRAINT chk_watch_record_rating
  CHECK (rating IS NULL OR (rating BETWEEN 1 AND 10 AND rating = FLOOR(rating)));
```

- `rating IS NULL OR`를 명시한다 — NULL은 CHECK를 통과하지만, 읽는 사람이 "미평가 허용"을 의도로 읽게 한다.
- 엔티티 `validateRating` 유지(정확한 에러는 엔티티가 준다). 기존 CHECK 3건(`chk_user_auth_method` ·
  `chk_follow_not_self` · `chk_refresh_token_revocation`)과 같은 *"규칙은 DB도 지킨다"* 기준.

### V22 — `V22__fix_watch_record_ott_check.sql` (V19 정정)

**무엇이 틀렸나** — `watch_type = NULL`, `ott_platform_id = 5`를 V19 조건에 넣으면:

```
(watch_type = 'OTT' AND id IS NOT NULL)                          → (NULL AND TRUE)  = NULL
OR ((watch_type IS NULL OR watch_type <> 'OTT') AND id IS NULL)  → (TRUE AND FALSE) = FALSE
                                                                    NULL OR FALSE   = NULL → 통과
```

MySQL CHECK는 **FALSE일 때만** 거부한다. `watch_type = 'OTT'`가 NULL을 그대로 퍼뜨리는 것이 원인이다. 조건을 *풀어 쓰는 것*으로는
막히지 않고, **비교 자체가 NULL을 내지 않아야** 한다 → **NULL 안전 비교 `<=>`**(NULL이면 0/1을 낸다).

**사전 점검** (0이어야 한다 — 그 구멍으로 이미 들어간 행이 있으면 V22 적용이 실패한다)

```sql
SELECT COUNT(*) FROM watch_record
WHERE NOT ((watch_type <=> 'OTT' AND ott_platform_id IS NOT NULL)
        OR (NOT (watch_type <=> 'OTT') AND ott_platform_id IS NULL));
```

```sql
ALTER TABLE watch_record DROP CHECK chk_watch_record_ott;
ALTER TABLE watch_record
  ADD CONSTRAINT chk_watch_record_ott
  CHECK ((watch_type <=> 'OTT' AND ott_platform_id IS NOT NULL)
      OR (NOT (watch_type <=> 'OTT') AND ott_platform_id IS NULL));
```

| watch_type | ott_platform_id | 결과 |
|---|---|---|
| NULL | 5 | FALSE → **거부** (V19에서 새던 경우) |
| NULL | NULL | TRUE → 통과 |
| OTT | 5 | TRUE → 통과 |
| OTT | NULL | FALSE → 거부 |
| THEATER | 5 | FALSE → 거부 |

- **왜 V19를 고치지 않고 V22를 여나** — D-3 조건 1 *"한 곳에라도 적용되면 동결"* 의 첫 실전 사례다. V19는 이미 로컬
  `cinemory`·`cinemory_test`에 적용됐다. V19를 고치면 체크섬 불일치로 두 DB가 기동을 거부하고, `flyway repair`는 **체크섬만
  갱신하고 SQL을 다시 실행하지 않는다** — 로컬에는 **옛 CHECK가 남고** 나중에 만들 운영 DB에만 새 CHECK가 들어가 **환경마다
  스키마가 갈라진다.** 맞추려면 DB마다 수동 DROP/ADD — Flyway로 없애려던 바로 그 작업이다. 운영이 아직 없다는 점도 이유가
  되지 않는다 — 로컬 `cinemory`에는 이관할 영화 데이터 12,750편이 있어 지우고 다시 만들 수 없다.
- **V21은 같은 문제가 없다** — `rating IS NULL OR …`로 시작해 NULL이면 TRUE가 먼저 확정된다.
- **교훈** — CHECK 조건은 **모든 피연산자가 NULL일 수 있는 경우를 표로 계산**해 본다. 이번엔 스펙 단계의 손 계산이 틀렸고
  `SchemaConstraintTest`의 NULL 케이스가 잡았다 — **테스트를 스펙에 미리 적어 둔 것이 값을 했다.**

## A-3. 적용·검증 순서

1. 각 V 파일의 **사전 점검 쿼리를 `cinemory`·`cinemory_test`에서 먼저** 돌려 0 확인.
2. 앱 기동(로컬) → Flyway가 V18~V22 적용 → `flyway_schema_history`에 18~22 성공 5행(V18~V21이 이미 적용된 로컬은 V22 1행 추가).
3. `./gradlew test` (`cinemory_test`에도 자동 적용).
4. **빈 스키마**로 기동 → V17 baseline + V18~V22 연속 적용 → `validate` 통과(운영 초기화 경로).
5. **제약 테스트 추가** — `SchemaConstraintTest`(실 DB `cinemory_test`, 네이티브 쿼리):

   | 테스트 | 기대 |
   |---|---|
   | 영화가 담긴 컬렉션 `DELETE` | `collection_movie` 함께 삭제 (V18) |
   | `ott_platform` 행 `DELETE` (참조 기록 있음) | FK 위반 예외 (V19) |
   | `watch_type='THEATER'` + `ott_platform_id` 지정 INSERT | CHECK 위반 (V19) |
   | `watch_type=NULL` + `ott_platform_id` 지정 INSERT | **CHECK 위반** — NULL 구멍이 막혔는지 (**V22**. V19만 적용된 상태에선 통과해 버린다 — 이 케이스가 V19의 결함을 잡았다) |
   | 참조 중인 `genre` 행 `DELETE` | FK 위반 예외 (V20) |
   | `rating = 7.5` / `0` / `11` INSERT | CHECK 위반 (V21) |

6. 재덤프 → `docs/schema/cinemory_backup_v22.sql`, CLAUDE.md "진실의 원천" 경로를 v22로 갱신.
7. `CineMory_기획노트.md` 2-6 정책표가 실제와 맞는지 확인(이 문서와 함께 갱신됨).

## A-4. 실행 결과 (2026-10-02, 브랜치 `feature/deploy-phase1`) — ✅ Part A 완료

| 단계 | 결과 |
|---|---|
| 1 사전 점검 | V19·V21·V22 쿼리 모두 `cinemory` 0 / `cinemory_test` 0 |
| 2 `cinemory` 적용 | V18 397ms · V19 619ms · **V20 26.2초** · V21 226ms → (정정 후) V22 467ms. history 17(baseline)~22 성공 |
| 3 `./gradlew test` | `cinemory_test`에 V18~V22 자동 적용, 전체 153건 통과 |
| 4 빈 스키마 | **prod jar + 환경변수**로 V17→V22 6개 연속 적용 → `validate` 통과 → health UP. 이후 `cinemory`와 덤프 비교 — **제약 정의 전부 일치**(차이는 `watch_record` 인덱스 나열 순서뿐: FK를 지웠다 다시 걸며 `cinemory`에서 위치가 바뀌었다) |
| 5 `SchemaConstraintTest` | 8건 — 표의 6행 + *OTT인데 플랫폼 없음* 거부 + *정수 별점·NULL 허용*(과잉 차단 방지). **NULL 케이스가 V19 결함을 잡았다 → V22** |
| 6 재덤프 | `cinemory_backup_v22.sql` — v17 대비 diff가 의도한 변경(FK 4건 · CHECK 2건)과 인덱스 순서뿐임을 확인. CLAUDE.md 경로 갱신 |
| 7 기획노트 2-6 | v22 덤프의 FK 26개와 대조해 일치. V22 주석 추가 |

- **V20 사전 추정 정정** — `movie_actor`는 18만이 아니라 **446,998행**. 운영은 빈 테이블이라 무관하나 `v20-delta.sql`에 기록.
- **resync 확인(V20)** — `POST /api/admin/movies/resync?fromId=141&limit=1` → `updated 1`(movie 142, `fromId`는 미포함), FK 오류 없음.
- **코드(V18)** — `CollectionService.deleteCollection`의 `deleteAllByCollectionId` 호출·메서드 삭제, `CommentRepository.deleteByTarget` 주석 갱신. `service-layer-spec.md` 4-5 갱신.
- ⚠️ **발견 — 개발 DB `cinemory`의 기본 콜레이션이 `utf8mb4_unicode_ci`다**(`cinemory_test`는 `0900_ai_ci`). 기존 22개 테이블은 `COLLATE`를 명시해
  영향이 없고, Flyway가 만든 `flyway_schema_history`만 `unicode_ci`가 됐다. **앞으로 V 파일의 `CREATE TABLE`이 `COLLATE`를 빠뜨리면 개발은
  `unicode_ci`, 운영(2-3에서 `0900_ai_ci`로 생성)은 `0900_ai_ci`로 갈라진다.** → V 파일은 테이블 옵션에 `COLLATE=utf8mb4_0900_ai_ci`를 항상 명시한다
  (CLAUDE.md 네이밍 규칙과 같다). 개발 DB 기본값 교정(`ALTER DATABASE`)은 사용자 판단으로 남긴다.

---

# Part B — 프로필 사진 (S3 + CloudFront)

> security-spec **L-13 확정**(2026-10-01). 원칙은 포스터를 경로만 저장한 결정(tmdb-sync 6-3)과 같다 —
> **서버 디스크에 두지 않고, DB에는 위치를 가리키는 값만 둔다.**

## B-1. 확정 결정

| # | 항목 | 결정 | 이유 |
|---|---|---|---|
| P-1 | 업로드 방식 | **S3 presigned URL — 앱이 S3에 직접 PUT** | 2GB 서버가 이미지 전송을 떠안지 않는다(deploy-spec D-1 메모리 예산) |
| P-2 | DB 저장값 | **S3 키**(`profile/{userId}/{uuid}.jpg`). 카카오 프로필 URL과는 **`http` 접두사로 구분** | 주소 체계가 바뀌어도(CloudFront 커스텀 도메인 등) **설정 한 줄**. 전체 URL을 저장하면 전 사용자 일괄 갱신 — L-13이 피하려던 비용 |
| P-3 | 응답 | **서버가 완성된 URL로 변환해 내린다** | 앱은 지금처럼 `profileImage`를 그대로 쓴다. 카카오 URL은 그대로 통과 |
| P-4 | 클라이언트 축소 | **512×512 정사각형 JPEG(품질 0.8)** — `expo-image-picker`(정사각 자르기) + `expo-image-manipulator` | 수 MB → 100~200KB. **EXIF(위치 정보) 제거** — 개인정보 측면의 설계 근거 |
| P-5 | 공개 방식 | **비공개 버킷 + CloudFront(OAC)** — 퍼블릭 액세스 차단 **켠 채로** | 업계 표준 구성. URL 고정 → 앱 이미지 캐시(expo-image) 유효. 커스텀 도메인(`cdn.<도메인>`)은 선택 |
| P-6 | 캐시 무효화 | **하지 않는다** | 교체마다 UUID 새 키 → 엣지에 남은 옛 객체는 아무도 참조하지 않다가 만료된다 |
| P-7 | 교체 시 이전 파일 | **DB 커밋 후** 삭제(`AFTER_COMMIT`), 실패는 로그만 | 커밋 전에 지우고 롤백되면 DB는 옛 키를 가리키는데 파일은 없다 — S-9 F-2·세션 폐기 순서와 같은 유형 |
| P-8 | 확정 안 된 업로드 | **감수** | 축소 후 장당 100KB대. 정확히 막으려면 임시 경로 + 복사 구조가 필요한데 이득 대비 복잡 |
| P-9 | 검증 | **확정 단계에서 S3 `HeadObject`로 크기(≤ 2MB)·Content-Type(`image/jpeg`)·본인 경로** 검사 | presigned PUT은 크기를 서명으로 강제하기 어렵고, 크기 조건을 거는 POST 정책은 AWS Java SDK v2에 기본 지원이 없다. 확정 단계 검사가 앱 조작(큰 파일·남의 경로)까지 막는다 |
| P-10 | 서버 권한 | **EC2 인스턴스 역할**(버킷 한정). 로컬은 `~/.aws` 프로필 | **서버·설정 파일에 액세스 키가 없다** — deploy-spec 2-1 *"액세스 키를 만들지 않는다"*. SDK 기본 자격 증명 체인이 양쪽을 자동 처리 |

## B-2. 인프라 (deploy-spec Phase 2 이후, 콘솔 작업)

| 단계 | 내용 |
|---|---|
| 1 | S3 버킷 `cinemory-media-<접미사>`(서울). **퍼블릭 액세스 차단 ON 유지** |
| 2 | CloudFront 배포 — 원본 = 버킷, **OAC 선택**(⚠️ 튜토리얼에 흔한 예전 방식 **OAI가 아니다**) |
| 3 | 버킷 정책 — CloudFront 콘솔이 생성해 주는 정책을 그대로 붙여 넣기 |
| 4 | EC2 인스턴스 역할에 정책 추가 — `s3:PutObject`·`s3:GetObject`(HeadObject 포함)·`s3:DeleteObject` on `arn:aws:s3:::<버킷>/profile/*`, `s3:ListBucket` on 버킷(조건 `s3:prefix = profile/*`, 탈퇴 시 경로 삭제용) |
| 5 | 확인 — 업로드한 객체가 `https://<배포>.cloudfront.net/profile/...`로 열리고, **S3 직접 URL로는 403** |
| (선택) | 커스텀 도메인 — ACM 인증서는 **버지니아 북부(us-east-1)** 에서 발급 |

- 버킷 CORS는 필요 없다 — **RN 네이티브 요청은 CORS와 무관**(프론트 B-3과 같은 이유).
- 비용 — CloudFront 상시 무료 한도 안으로 예상. 설정 시 콘솔에서 한도 재확인.

## B-3. 백엔드

**의존성** — AWS SDK for Java v2 `software.amazon.awssdk:s3`(BOM으로 버전 관리).

**설정** (`application.yml` 공통 + 프로파일별 값, 비밀 아님)

```yaml
cinemory:
  media:
    region: ap-northeast-2
    bucket: ${MEDIA_BUCKET:}            # 운영 cinemory.env, 로컬 config/application-secret.yml
    public-base-url: ${MEDIA_PUBLIC_BASE_URL:}   # https://<배포>.cloudfront.net (끝 슬래시 없음)
    upload-url-ttl: PT5M
    profile-max-bytes: 2097152          # 2MB
```

**구성 요소** (`global/infra/media`)

| 클래스 | 역할 |
|---|---|
| `MediaProperties` | 위 설정 record. 컴팩트 생성자에서 `public-base-url` 끝 슬래시 정규화 |
| `MediaStorage` (인터페이스) | `presignPut(key, contentType, ttl)` · `head(key)` → `Optional<ObjectMeta(size, contentType)>` · `delete(key)` · `deletePrefix(prefix)` |
| `S3MediaStorage` | 위 구현(`S3Client` + `S3Presigner`). **테스트는 인메모리 가짜 구현**을 쓴다 — S3에 의존하지 않는다 |
| `ProfileImageUrlResolver` | `resolve(stored)` — `null` → `null` / `http`로 시작 → 그대로(카카오) / 그 외 → `publicBaseUrl + "/" + key` |

**DTO 변경 — 프로필 이미지를 노출하는 5종**

`UserResponse` · `UserProfileResponse` · `FollowUserResponse` · `CommentAuthorResponse` · `ReviewAuthorResponse`

- 정적 팩토리가 **해석된 URL을 인자로 받는다** — 예: `UserResponse.from(User user, String profileImageUrl)`. 호출하는
  서비스가 `ProfileImageUrlResolver`로 변환해 넘긴다. 필드명 `profileImage`는 유지(앱 무변경).
- ⚠️ *"`from(Entity)` 단일 인자"* 규칙의 예외다(`FollowUserResponse`가 첫 예외). **외부 설정이 필요한 표현 변환**이라 DTO가
  스스로 할 수 없다. 대안으로 검토한 **정적 홀더(기동 시 base URL을 static 필드에 주입)는 기각** — 숨은 전역 상태라
  테스트 간 오염과 초기화 순서 문제가 생긴다.
- 변환 누락을 막는 장치 — **키가 그대로 응답에 새는지** 검사하는 테스트 1건(`profileImage`가 `profile/`로 시작하면 실패).

**엔드포인트** (모두 인증 필수)

| 메서드 · 경로 | 요청 | 응답 | 동작 |
|---|---|---|---|
| `POST /api/users/me/profile-image/upload-url` | 없음 | `{ uploadUrl, key, expiresAt }` | 키 `profile/{me}/{UUID}.jpg` 생성 → **`Content-Type: image/jpeg`를 서명에 포함**한 PUT URL 발급(TTL 5분) |
| `PUT /api/users/me/profile-image` | `{ key }` | `UserResponse` | ① 키가 `profile/{me}/`로 시작 ② `head(key)` 존재 ③ 크기 ≤ 2MB ④ Content-Type `image/jpeg` — 실패 시 `INVALID_PROFILE_IMAGE` → `User.changeProfileImage(key)` → 이전 값이 **우리 키면** `ProfileImageReplacedEvent(oldKey)` 발행 |
| `DELETE /api/users/me/profile-image` | 없음 | `UserResponse` | `profileImage = null`(기본 아바타) + 이전 키 삭제 이벤트. **화면 설계에서 "기본 아바타로 되돌리기"를 넣을 때만** 노출 |

- `User.changeProfileImage(String)` — 의미 있는 이름의 상태 변경 메서드(CLAUDE.md, setter 금지).
- `ProfileImageReplacedEvent` → `@TransactionalEventListener(phase = AFTER_COMMIT)`에서 `MediaStorage.delete`. 예외는 잡아
  `WARN` 로그만(P-7).
- **ErrorCode 추가** — `INVALID_PROFILE_IMAGE`(400, "프로필 이미지가 올바르지 않습니다."). 존재하지 않는 키도 이 코드로
  수렴한다 — 클라이언트 분기가 같다(다시 업로드).

## B-4. 앱 (요약 — 상세는 `cinemory-app/docs/`에 별도 문서)

- `EditProfile` placeholder 화면을 실제 화면으로 — 사진 변경(+ 닉네임 변경을 설정에서 옮길지는 화면 설계에서).
- 흐름: 이미지 선택(정사각 자르기) → 512 JPEG 축소 → `upload-url` → `fetch(uploadUrl, { method: 'PUT', headers:
  { 'Content-Type': 'image/jpeg' }, body: blob })` → `PUT /profile-image` → `['users','me']` 무효화.
- **네이티브 모듈 2종 추가 → 재빌드** — deploy-spec **Phase 5의 빌드에 포함**하면 추가 비용이 없다.

---

# Part C — 회원 탈퇴

## C-1. 확정 결정

| # | 항목 | 결정 | 이유 |
|---|---|---|---|
| W-1 | 방식 | **즉시 완전 삭제** | 현재 CASCADE 설계와 그대로 맞고 **개인정보가 남지 않는다.** 소프트 삭제·익명화는 **컨트롤러 12개의 모든 조회**(공개 프로필, 댓글 작성자, 팔로우 목록, 리포트)에서 탈퇴 사용자를 걸러야 하고, 유니크 제약(`uk_user_email`·`uk_user_provider`) 때문에 재가입이 막혀 이메일 익명화가 필요하며, 익명화는 `chk_user_auth_method`와도 충돌한다. "복구"는 확인 화면으로 대신한다 |
| W-2 | 내가 남긴 댓글 | **삭제**(현행 `fk_comment_user` CASCADE) | 스키마 변경 없음. 댓글 화면은 소셜(3군) 범위 — **"탈퇴한 사용자"로 남길지는 소셜 설계 때 재검토** |
| W-3 | 본인 확인 | **비밀번호가 있으면(`hasPassword()`) 비밀번호 재입력** / **없으면(소셜 전용) 확인 다이얼로그만** | 비밀번호 변경과 같은 패턴. 소셜 재로그인 강제는 과하다. ⚠️ 2026-10-10 갱신 — 초판의 *"로컬/카카오"* 구분은 계정 연결(Part D)로 성립하지 않는다(카카오를 연결한 로컬 가입자는 둘 다다). 판정 기준을 D-4 #1의 `hasPassword()`로 맞췄다 |
| W-4 | 남은 Access Token | **한계로 기록**(security-spec **L-15**) | 아래 C-3 |
| W-5 | 카카오 연결 끊기 | **서버 삭제 성공 → 앱이 SDK `unlink()`**(best-effort) | 서버에 권한이 강한 **카카오 Admin 키를 두지 않는다.** 순서를 뒤집으면 서버 삭제 실패 시 "계정은 남고 카카오 연결만 끊긴" 상태가 된다. 끊기 실패는 무해 — 다음 로그인 시 새 계정으로 가입될 뿐 |
| W-6 | 재가입 | **제한 없음** | 완전 삭제라 유니크 제약이 자동으로 풀린다. 악용할 동기(포인트·혜택)가 없는 앱 |
| W-7 | Google Play | 앱 내 탈퇴 + **웹 삭제 요청 페이지** | 계정 생성이 가능한 앱은 둘 다 요구된다. 운영 Nginx에 정적 페이지(요청 방법·문의 메일)를 **개인정보처리방침 페이지와 함께** — **M5**에서 |

## C-2. 백엔드

**엔드포인트** — `POST /api/users/me/withdrawal` (인증 필수) · 요청 `{ password }`(카카오 계정은 생략) · **204**

- `DELETE`가 아니라 `POST`인 이유 — 비밀번호를 본문에 실어야 하는데, `DELETE` 본문은 HTTP 의미상 정의되지 않아
  프록시·클라이언트 라이브러리마다 처리가 다르다.

**`UserService.withdraw(Long userId, String password)`** — 단일 `@Transactional`

| 순서 | 동작 | 근거 |
|---|---|---|
| 1 | `findUserOrThrow` → 로컬 계정이면 `passwordEncoder.matches` 실패 시 **`INVALID_CREDENTIALS`**(`changePassword`와 같은 코드 — 프론트 처리 재사용). 카카오 계정이면 `password` 무시 | W-3 |
| 2 | 내 컬렉션 id 목록·내 리뷰 id 목록 조회 (`CollectionRepository.findIdsByUserId` · `ReviewRepository.findIdsByUserId` — 신규, id만 프로젝션) | |
| 3 | `commentRepository.deleteByTargetIn(COLLECTION, collectionIds)` · `(REVIEW, reviewIds)` — **신규 벌크 메서드**, 목록이 비면 호출 생략 | **다른 사람이 내 글에 단 댓글** — 다형 참조라 CASCADE가 못 지운다. 방치하면 AUTO_INCREMENT 재사용 시 남의 컬렉션에 붙어 보인다(`deleteCollection` 주석과 같은 문제) |
| 4 | 이전 프로필 이미지가 우리 키인지 기억(이벤트용) | Part B |
| 5 | `userRepository.delete(user)` → **DB CASCADE**: 기록·리뷰·찜·컬렉션(→ V18로 `collection_movie`)·내 댓글·팔로우(양방향)·토큰 2종·알림 | **V18 필수** — 없으면 영화가 담긴 컬렉션이 있는 사용자에서 FK 위반 |
| 6 | `UserWithdrawnEvent(userId)` 발행 → `AFTER_COMMIT`에서 `MediaStorage.deletePrefix("profile/{userId}/")`, 실패는 `WARN` | P-7과 같은 이유 |

- ⚠️ 3의 벌크 삭제에 **`@Modifying(clearAutomatically = true)`를 쓰지 않는다** — 4-6에서 앞선 미flush 삭제를 폐기해
  FK 위반을 낸 바로 그 옵션이다. 기존 `deleteByTarget`과 같은 설정을 따른다.
- 이 흐름에서 자식 엔티티를 영속성 컨텍스트에 로드하지 않는다 — DB CASCADE가 지운 행이 컨텍스트에 남아 stale이 되는
  일이 없다. **로드가 필요한 단계를 추가하지 말 것.**
- `notification.actor_id`는 이미 `SET NULL` — M4에서 "탈퇴한 사용자" 표시가 자연스럽게 된다. 알림의 **다형 target**이
  탈퇴자의 컬렉션·리뷰를 가리키는 경우는 M4 착수 시 3번 단계에 함께 넣는다(기획노트 M4 경고와 같은 자리).

**테스트** (`UserWithdrawalTest`, 실 DB)

| 시나리오 | 기대 |
|---|---|
| 영화가 담긴 컬렉션·리뷰·기록·찜·팔로우가 있는 로컬 사용자 탈퇴 | 204, 관련 행 전부 0 |
| **다른 사용자가 탈퇴자의 컬렉션·리뷰에 단 댓글** | 삭제됨 |
| 다른 사용자가 **제3자의 글에 단 댓글**, 탈퇴자를 팔로우하던 다른 사용자의 나머지 팔로우 | 보존 |
| 잘못된 비밀번호 | `INVALID_CREDENTIALS`, 아무것도 지워지지 않음 |
| 카카오 사용자(비밀번호 없이) | 204 |
| 탈퇴 후 같은 이메일로 재가입 | 성공(W-6) |
| 프로필 이미지가 우리 키인 사용자 | 커밋 후 `deletePrefix` 호출(가짜 `MediaStorage`로 확인), **롤백 시 호출 안 됨** |

## C-3. 남은 Access Token — 한계 L-15

탈퇴해도 Access Token은 TTL(30분)까지 유효하다(L-3). 본인 `userId`는 신뢰해 `getReferenceById`로 쓰므로
(backend-standards), 그 토큰으로 쓰기 요청을 보내면 **FK 위반 → 500**이 난다. **데이터는 오염되지 않는다.**

- 앱은 탈퇴 성공 즉시 토큰을 지우므로, 남는 위험은 **토큰이 탈취된 경우**뿐이다.
- 막으려면 **요청마다 사용자 존재를 DB에서 확인**해야 한다 — 무상태 JWT의 이점을 버리는 것이라 L-3과 같은 이유로
  의도적으로 하지 않는다. **security-spec S-11에 L-15로 기록.**

## C-4. 앱 (요약 — 상세는 `cinemory-app/docs/`)

- 설정 화면 맨 아래 **"회원 탈퇴"** → 확인 화면: **무엇이 지워지는지**(기록·리뷰·컬렉션·찜·팔로우·댓글) 명시 +
  로컬은 비밀번호 입력, 카카오는 확인 버튼.
- 204 → (카카오면) `unlink()` best-effort → 토큰·쿼리 캐시 전체 삭제 → 게스트 상태로 홈.
- `INVALID_CREDENTIALS`는 **401 인터셉터의 재발급 대상이 아니다** — 비밀번호 변경 화면이 이미 이 분기를 갖고 있으니
  같은 처리를 쓴다(§6.3 단일 비행 인터셉터는 `TOKEN_EXPIRED`만 재발급).

---

# Part D — 소셜 계정 연결 + 구글·네이버 로그인

> **정책 확정(2026-10-01) → 연결 구조 설계 확정(S-5~S-8, 2026-10-10) → ✅ 1단위(연결 구조 리팩터링 + API 3종) 구현 완료(2026-10-10, D-4).**
> **구글 확정(2026-10-11, D-5) — ✅ 서버 ①~③ 완료(2026-10-11).** 남은 것: 앱 ④ 스파이크 · ⑤ 연동 → ⑥ 실기기 E2E → 네이버(Q-2). 10월 우선순위 2번 — 기획노트 4절.

## D-1. 왜 연결 구조가 먼저인가

지금 스키마는 **"user 한 명 = 인증 수단 하나"** 다 — `user.provider`·`provider_id` 컬럼이 하나씩이고 `email`이 전역 유니크다.
그래서 카카오로 가입한 사람이 **같은 이메일의 구글 계정으로 로그인하면 가입이 거부된다.** 게다가 지금 그 경우 나가는
`EMAIL_ALREADY_REGISTERED_LOCALLY`는 메시지가 *"일반 회원가입으로 등록"* 이라 **사실과도 다르다.** 두 번째 제공자를 붙이는 순간
드러나는 문제라, 제공자 추가와 **같은 작업 단위**로 연결 구조를 먼저 만든다.

## D-2. 확정

| # | 항목 | 결정 |
|---|---|---|
| S-1 | 정책 | **계정 연결 허용** — 한 사용자가 로컬·카카오·구글·네이버를 함께 가질 수 있다 |
| S-2 | 구조 | `user_social_account(id, user_id, provider, provider_id, created_at)` — `fk → user` **CASCADE**, `UNIQUE(provider, provider_id)`, `UNIQUE(user_id, provider)`(제공자당 하나). 기존 카카오 사용자는 `INSERT … SELECT`로 이전. ⚠️ **`user.provider`·`provider_id`·`uk_user_provider` 제거는 확장/축소에 따라 다음 릴리스의 축소 마이그레이션으로 미룬다**(2026-10-10 — D-4 "V24 재작성") |
| S-3 | 시점·방식 | **V23 이후**(Part A 다음 — V22는 V19 정정에 쓰였다) → **V24 확정**(V23은 2026-10-08 `ott_platform.sort_order`). Phase 2~3 동안 `feature/social-login`에서 개발 → **Phase 5 E2E(카카오만) 통과 후 머지** → CI 배포 → 제공자별 실기기 E2E. **PR 운영(2026-10-10)** — 1단위(연결 구조)와 **구글을 한 Draft PR에 쌓는다**(백엔드·앱 각각, S-7상 함께 머지) → Phase 5 통과 후 Ready → 머지. **네이버는 별도 PR**(Q-2 검증 방식이 불확실해 연결 구조·구글의 머지를 붙잡지 않게) |
| S-4 | 순서 | **계정 연결 리팩터링 → 구글 → 네이버.** 구글은 표준 OIDC라 위험이 낮아 연결 구조의 첫 검증 사례로 쓰고, 검증 방식이 불확실한 네이버를 마지막에 얹는다 |
| S-5 | **연결 방식** (구 Q-1) | **로그인한 상태에서 직접 연결만.** 같은 이메일 자동 연결 없음. 연결은 로그인과 **같은 검증기**(ID 토큰 + nonce)를 거친다. **이미 다른 사용자에게 연결된 소셜 계정은 409로 거부** — 계정 병합은 범위 밖. 연결하는 제공자의 이메일이 `user.email`과 **달라도 허용**(대표 이메일은 가입 시의 것 유지) |
| S-6 | **연결 해제 · 비밀번호 추가** (구 Q-6) | **인증 수단 = 비밀번호(로컬 가입자만) + 연결된 소셜들.** 합이 1이면 해제 거부. **소셜 전용 사용자의 비밀번호 추가는 허용하지 않는다** — `INVALID_AUTH_METHOD`(비밀번호 변경·재설정 불가) 로직 유지 |
| S-7 | **에러 코드** (구 Q-5) | `EMAIL_ALREADY_REGISTERED_LOCALLY` → **`EMAIL_ALREADY_REGISTERED`**(409). 메시지 *"이미 가입된 이메일입니다. 기존에 가입한 방법으로 로그인한 뒤 설정에서 계정을 연결해 주세요."* **가입 제공자는 노출하지 않는다.** 앱 참조 1곳(`cinemory-app/src/screens/auth/LoginScreen.tsx`)을 **같은 머지에서** 수정 |
| S-8 | **API 범위** | 연결 구조 리팩터링과 **같은 단위**에 연결·조회·해제 3종을 넣는다(아래 D-2-A) |

**왜 Part A(V18~V21)와 함께 하지 않았나** — 테이블 생성은 파일 하나지만, 실제 비용은 **인증 핵심(Step S) 전체**에 있다:
`chk_user_auth_method`(provider ⇔ 비밀번호 배타)가 성립하지 않게 되고, 대신 필요한 *"인증 수단이 최소 하나"* 는 테이블을
넘나들어 CHECK로 표현할 수 없어 서비스 불변식이 된다. `signUpOAuth`·`oauthLogin`·`isOAuthUser()`를 쓰는 비밀번호 변경·재설정,
인증 테스트 다수가 함께 바뀐다. **배포 직전에 가장 위험한 영역**(deploy-spec — *"배포에서 깨지는 건 대부분 인증 경로"*)이라
첫 배포와 분리했다. 데이터 이전은 `INSERT … SELECT` 한 문장이라 운영 데이터가 쌓인 뒤에도 비용이 거의 늘지 않는다 — D-3에서
Flyway를 들인 이유 그대로다.

### D-2-A. 확정 근거와 API 계약 (2026-10-10)

**S-5 근거 — 업계 표준.** 대형 서비스·인증 플랫폼 대부분이 *로그인한 사용자가 설정에서 직접 연결*하는 방식을 쓴다. Firebase Auth도
기본 설정(이메일당 계정 하나)에서 같은 이메일로 다른 제공자 로그인 시 **자동 병합하지 않고** 오류를 낸 뒤 *"기존 방법으로 로그인 후
연결"* 로 안내한다. 이메일 기준 자동 연결은 **선점형 계정 탈취(pre-account hijacking)** — 공격자가 피해자 이메일로 먼저 계정을 만들어
두면 피해자의 소셜 로그인이 그 계정에 합쳐진다 — 경로이고, 이메일 검증이 불확실한 제공자(네이버, Q-2)가 섞이면 위험이 커진다.

**S-6 근거.** 업계는 갈린다 — 허용하는 서비스는 "비밀번호 변경"과 별개의 **"비밀번호 설정" 흐름 + 이메일 인증**을 둔다. 연결 허용으로
소셜 전용 사용자도 **두 번째 소셜을 연결해** 한 제공자에 묶이는 문제를 피할 수 있어 필요성이 약하고, 허용하면 비밀번호 변경·재설정
(`INVALID_AUTH_METHOD`)·재설정 요청의 계정 존재 은닉(security-spec S-9 D-2)·이메일 인증 설계가 함께 바뀌어 단위가 커진다. 연결 리팩터링으로
`chk_user_auth_method`가 사라지므로 **나중에 넣어도 스키마 변경이 없다.**

**S-7 근거.** 에러 코드는 API 계약이라 배포된 앱이 있으면 *새 코드 추가 + 옛 코드 유예 후 제거*가 표준이다. 지금은 설치 기반이
개발 빌드뿐(Phase 5 전)이고 참조가 1곳이라 **즉시 교체**한다. 백엔드가 CI로 먼저 배포돼도 옛 앱은 모르는 코드를 일반 오류로 처리할
뿐이다. 제공자를 알려 주지 않는 이유는 **이메일 열거 단서**가 되기 때문 — 비밀번호 재설정에서 계정 존재를 숨기기로 한 security-spec S-9 D-2와
같은 방침이다(Firebase도 2023년부터 이메일 열거 방지를 기본값으로 바꿨다).

**API 계약** (모두 인증 필수 — ✅ `controller-layer-spec.md` **5-1-A**로 옮김, 2026-10-10)

| 메서드 · 경로 | 요청 | 응답 | 규칙 |
|---|---|---|---|
| `GET /api/users/me/social-accounts` | — | `[{ provider, linkedAt }]` + `hasPassword` | 설정 화면용. `hasPassword`는 해제 버튼 비활성화 판단에 쓴다(서버가 최종 판정) |
| `POST /api/users/me/social-accounts/{provider}` | `{ idToken, nonce }` (로그인과 동일) | 204 | ① nonce 소비 → ID 토큰 검증(로그인과 같은 순서) ② `(provider, providerId)`가 **다른 사용자**에 연결돼 있으면 **409 `SOCIAL_ACCOUNT_ALREADY_LINKED`** ③ 이미 **내게** 같은 제공자가 연결돼 있으면 **409 `SOCIAL_PROVIDER_ALREADY_LINKED`**(`UNIQUE(user_id, provider)`) ④ 저장 |
| `DELETE /api/users/me/social-accounts/{provider}` | — | 204 | 연결 안 된 제공자면 404 `SOCIAL_ACCOUNT_NOT_FOUND`. **인증 수단이 그것 하나뿐이면 409 `LAST_AUTH_METHOD`** |

- **신규 `ErrorCode` 4종** — `SOCIAL_ACCOUNT_ALREADY_LINKED`(409) · `SOCIAL_PROVIDER_ALREADY_LINKED`(409) · `SOCIAL_ACCOUNT_NOT_FOUND`(404) ·
  `LAST_AUTH_METHOD`(409) · 그리고 S-7의 `EMAIL_ALREADY_REGISTERED`(409, 기존 코드 개명).
- ⚠️ **"인증 수단 최소 1개"는 서비스 불변식**이다(테이블을 넘나들어 CHECK 불가). 해제와 동시 요청 경합 — 두 기기에서 동시에 서로 다른
  제공자를 해제 — 을 막으려면 **`user` 행을 비관적 락(`SELECT … FOR UPDATE`)으로 잡고** 개수를 센 뒤 삭제한다.
  ⚠️ **개수를 세는 읽기도 잠금 읽기로 한다**(2026-10-10 보강 — D-4 #3). 잠금 읽기는 스냅샷이 아니라 **항상 최신 커밋 값**을 읽으므로
  *"락이 트랜잭션의 첫 DB 읽기여야 한다"* 는 순서 의존이 사라진다.
- **로그인 흐름(`oauthLogin`)의 분기** — `(provider, providerId)`로 연결을 찾으면 그 사용자로 로그인 / 없으면 **이메일이 이미 있는 경우
  `EMAIL_ALREADY_REGISTERED`**, 없으면 신규 가입(`user` + `user_social_account` 한 트랜잭션).
- **회원 탈퇴(Part C)** — `user_social_account`는 `user` FK **CASCADE**라 자동 삭제. 앱의 제공자별 unlink는 Q-7.

## D-3. 착수 시 정할 것

| # | 항목 | 현재 판단 |
|---|---|---|
| ~~Q-1~~ | 연결 방식 | ✅ **확정 → S-5** (2026-10-10) |
| Q-2 | **네이버 검증 방식** | OIDC 엔드포인트(JWKS)는 공개돼 있으나 **공식 지원 범위·nonce·이메일 제공이 불분명**(2026-08 `naver/naveridlogin-API` #92). RN 라이브러리는 보통 **액세스 토큰**을 준다. **착수 첫날 확인** — 안 되면 *"액세스 토큰으로 프로필 API를 서버가 조회"* 하는 두 번째 검증 방식을 `OAuthIdTokenVerifier` 옆에 추가(nonce 미적용) |
| ~~Q-3~~ | 구글 검증 | ✅ **확정 → D-5**(G-3·G-4, 2026-10-11) — `aud`는 웹 클라이언트 ID, `iss` 두 형식, `email_verified` 필수, `global/infra/oidc` 일반화 |
| Q-4 | 서명 키 등록 | 구글 ✅ **→ D-5-F**(2026-10-11 — Android 클라이언트 하나에 SHA-1 하나, 키마다 클라이언트). 네이버는 착수 시. 제공자마다 **개발·EAS 키(→ M5에서 Play 앱 서명 키)** — 카카오 L-7과 같은 함정 |
| ~~Q-5~~ | 에러 코드 정리 | ✅ **확정 → S-7** (2026-10-10) |
| ~~Q-6~~ | 연결 해제 · 비밀번호 추가 | ✅ **확정 → S-6** (2026-10-10) |
| Q-7 | 회원 탈퇴 연동 | Part C W-5가 *"앱이 카카오 SDK로 unlink"* 다 — **연결된 제공자마다** unlink. 네이버 토큰 폐기는 클라이언트 시크릿이 필요해 서버 처리가 될 수 있다 |
| ~~Q-8~~ | iOS (참고) | ✅ **범위 밖 확정 → D-5-H**(2026-10-11) — 진입 시 iOS 클라이언트 ID 추가, **Apple 로그인 의무**(지침 4.8), 탈퇴 시 Apple 토큰 revoke, 연 $99 |

## D-4. 1단위 실행 결과 (2026-10-10, 브랜치 `feature/social-login`) — ✅ 연결 구조 리팩터링 + API 3종

**스키마** — `V24__user_social_account.sql` + `docs/schema/v24-delta.sql`. `cinemory_test`에 적용(`./gradlew test`, 1.3초).
**`cinemory`·운영은 미적용** — `bootRun` 전에 v24-delta의 **사전 점검 쿼리**를 먼저 돌린다(DDL은 트랜잭션이 아니라
INSERT … SELECT 실패 시 테이블만 남는다). 적용 후 재덤프 `cinemory_backup_v24.sql`.

> ⚠️ **V24 재작성 — 확장 전용으로 (2026-10-10, deploy-spec D-3 조건 6·7).**
>
> **무엇이 문제였나** — 초판 V24는 새 테이블 생성(확장)과 `user.provider` 삭제(축소)를 **한 파일에** 넣었다. `cinemory_test`가 V24가
> 되자, 같은 DB를 쓰는 **main/develop의 `./gradlew test`가 `validate`의 *missing column [provider]* 로 실패**했다. 같은 원인이 운영에서는
> **CI 자동 롤백**을 깨뜨린다(Flyway가 V24 적용 → 새 jar 실패 → `app.jar.prev`가 provider 없는 DB에서 기동 실패).
>
> **재작성 내용** — 각 문장을 확장/축소로 분류해 **확장만 V24에 남긴다.**
>
> | V24 (확장, 지금) | 보류된 축소 (다음 릴리스) |
> |---|---|
> | `user_social_account` 생성 + FK·UNIQUE 2종 | `ALTER TABLE user DROP INDEX uk_user_provider` |
> | 기존 카카오 연결 `INSERT … SELECT` 복사 | `ALTER TABLE user DROP COLUMN provider, DROP COLUMN provider_id` |
> | `chk_user_auth_method` 삭제(제약 **완화** — 새 코드의 소셜 가입은 `provider`를 채우지 않아 이 CHECK를 위반한다) | ⚠️ **위 두 문장 앞에 마지막 보정 복사**(아래 "감수하는 것" ③) |
>
> - 새 코드(`User` 엔티티)는 `provider`·`provider_id`를 **매핑하지 않는다**(이미 제거됨) — 남은 컬럼은 `validate`가 무시한다.
> - 옛 코드는 컬럼이 그대로라 V24 DB에서 동작한다. ⚠️ `uk_user_provider`가 남아 있어도 새 코드는 두 컬럼을 NULL로 두므로 충돌하지 않는다
>   (MySQL UNIQUE는 NULL 중복을 허용한다).
> - **축소 시점** — 소셜 로그인이 머지·운영 배포·실기기 E2E(Phase 5 이후)를 통과한 **다음 배포**. 그때의 "이전 코드"는 소셜 버전이고, 그 코드는
>   두 컬럼을 쓰지 않으므로 안전하다. 번호는 그 시점의 다음 V.
> - **동결 예외 근거**(D-3 조건 7) — V24는 머지 전 브랜치에 있고 **버려도 되는 `cinemory_test`에만** 적용됐다. `cinemory`(개발)·운영은 미적용.
>
> **로컬 DB 정리 순서**
> 0. **main에 `spring.flyway.ignore-migration-patterns: "*:future"` 한 줄을 넣는 작은 PR을 먼저 머지한다**(2026-10-10 보완). 4단계에서
>    V24 DB 위에 뜨는 것은 **main 코드**다 — 소셜 브랜치에만 넣으면 4단계는 *명시된 설정*이 아니라 *Flyway 기본값*을 검증하게 된다.
>    지금은 결과가 같아도, 조건 6이 명시하라는 이유(*"버전마다 다를 수 있다"*)가 바로 이 경우다.
> 1. `cinemory_test`를 **비우고 다시 만든다**(`DROP DATABASE` → `CREATE DATABASE … utf8mb4_0900_ai_ci`). 테스트는 실행마다 자기 데이터를
>    넣으므로 버려도 된다.
> 2. **main에서 `./gradlew test`** → Flyway가 빈 DB 경로로 그 브랜치의 V까지 쌓는다 → 통과 확인(원래 상태 복구).
>    ⚠️ **develop이 아니라 main이다** — develop은 main보다 뒤처져 있어(2026-10-10 기준 33커밋, develop에만 있는 커밋 없음) V23 이전 코드를
>    검증하게 된다. "바로 이전 코드"는 운영에 배포되는 main이다.
> 3. 소셜 브랜치에서 V24(확장 전용)로 고쳐 쓰고 `./gradlew test` → V24 적용 → 통과.
> 4. **다시 main에서 `./gradlew test`** → V24가 적용된 DB에서 통과하는지 확인(`ignore-migration-patterns: "*:future"` — D-3 조건 6).
>    **이 4번이 확장/축소가 실제로 성립하는지에 대한 검증이다.**
>    ⚠️ **`./gradlew cleanTest test`로 강제 실행한다** — 코드가 2단계와 같으면 Gradle이 테스트를 UP-TO-DATE로 건너뛰고 **옛 결과를 그대로 보여 준다**
>    (DB 상태는 Gradle의 입력이 아니다). 2026-10-10 첫 시도가 4초 만에 "통과"했는데 로그 시각이 2단계의 것이었다.
> 5. 그 뒤 `cinemory`(개발)에 사전 점검 쿼리 → `bootRun`으로 V24 적용 → 재덤프.
>
> **진행 (2026-10-10)** — ✅ 0단계 PR #19(앱은 브랜치 규칙 PR #25 별도) · ✅ 1단계(사용자, DROP 24 · `utf8mb4_0900_ai_ci` 확인) ·
> ✅ 2단계(0단계 브랜치 = main + 설정 한 줄, 빈 DB 경로 V17~V23, 173건) · ✅ 3단계(V24 확장 전용 재작성, V23→V24 0.37초, **198건** —
> `SchemaConstraintTest`에 *"provider 컬럼·`uk_user_provider`가 남아 있다"* 추가) · ✅ **4단계**(PR #19 머지 커밋 `36f86b8`, `cleanTest test` — Flyway *"version (24) is newer than the latest available migration (23)"* 경고 후
> *up to date*, `validate` 통과, **173건** → **확장/축소 성립 확인**) · ✅ **5단계** — 사전 점검 0·0·복사 대상 1·V23 → 소셜 브랜치 `bootRun`으로
> `cinemory`에 V24 적용(0.726초), 기동·스모크 정상 → 복사 1행 값 일치 · `MAX(version)` 24 → 재덤프 `cinemory_backup_v24.sql`(v23 대비 diff:
> `chk_user_auth_method` 삭제 + `user_social_account` 추가 + 덤프 시각뿐 — `provider` 컬럼·`uk_user_provider`는 남음), CLAUDE.md 진실의 원천 v24.
> **로컬 DB 정리 0~5단계 완료.**
> ⚠️ 1단계부터 3단계 완료 전까지 소셜 브랜치에서 `./gradlew test`·`bootRun`을 하지 않았다 — 초판 V24가 다시 적용되는 것을 막기 위해서다.
>
> **감수하는 것과 보정하는 것 — 확장 기간의 데이터 비대칭** (2026-10-10 보완. 초판은 ①만 적고 *"운영에는 옛·새 코드가 동시에 쓰는 기간이 없다"*
> 고 했으나 **틀렸다** — CI 자동 롤백 기간이 바로 그 기간이다)
>
> 확장 기간에는 옛 코드는 `user.provider`에만, 새 코드는 `user_social_account`에만 쓴다. 한쪽이 쓴 연결을 다른 쪽이 보지 못한다.
>
> | 방향 | 언제 | 증상 | 처리 |
> |---|---|---|---|
> | ① 옛 코드로 가입 → 새 코드 | 로컬 브랜치 전환 | 새 테이블에 연결이 없어 카카오 로그인 409(`EMAIL_ALREADY_REGISTERED`) | **감수** — 개발 DB 테스트 계정 문제 |
> | ② 새 코드로 가입 → 옛 코드 | **운영 CI 자동 롤백 기간** | 새 코드 배포 후 카카오로 가입한 사용자는 `user.provider`가 NULL이라, 옛 코드가 `findByProviderAndProviderId`로 못 찾고 이메일 충돌 409 | **감수** — 기동 실패가 아니라 기능 일부 축소이고, 롤백 기간이 짧고 그 사이 신규 가입자도 적다. 막으려면 새 코드가 `user.provider`에도 쓰는 **이중 쓰기**가 필요한데, 엔티티에서 지운 컬럼을 다시 매핑해야 해 비용 대비 이득이 작다 |
> | ③ **롤백 기간에 옛 코드로 가입 → 재배포** | 롤백 후 **새 코드를 다시 배포한 뒤** | 그 사용자는 `user.provider`에만 있어 재배포 후에도 **계속** 카카오 로그인 409 — **롤백이 끝나도 남는다** | **보정** — ⓐ 재배포 직후 아래 보정 쿼리를 실행(런북, Phase 6) ⓑ **축소 마이그레이션 맨 앞에 같은 보정을 넣어**, 컬럼을 지우기 전 반드시 한 번 더 옮기게 한다 |
>
> ```sql
> -- 보정 복사 — 멱등. 이미 연결된 건은 건너뛴다. V24의 복사와 같은 매핑을 쓴다.
> -- created_at = u.created_at: 옛 코드가 user.provider를 채우는 건 소셜 가입 때뿐이라 가입 시각이 곧 연결 시각이다.
> -- (NOW()로 두면 연결 목록의 linkedAt이 재배포 시각으로 찍힌다 — 2026-10-10 정정)
> INSERT INTO user_social_account (user_id, provider, provider_id, created_at)
> SELECT u.id, u.provider, u.provider_id, u.created_at
> FROM user u
> WHERE u.provider IS NOT NULL
>   AND NOT EXISTS (SELECT 1 FROM user_social_account s
>                   WHERE s.provider = u.provider AND s.provider_id = u.provider_id);
> ```
>
> **보정이 처리하지 않는 경우 — 감수** (2026-10-10 추가). 새 코드에서 로컬 가입자 L이 카카오 K를 연결 → **롤백 기간**에 K로 로그인 → 옛 코드는
> L을 못 찾고(L의 `provider`는 NULL) K의 이메일이 L과 달라 **새 사용자 Y(`Y.provider = K`)를 만든다.** 보정은 `NOT EXISTS (provider, provider_id)`라
> **Y를 건너뛴다** — K는 이미 L에 연결돼 있으므로 `uk_user_social_account_provider` 위반을 피하는 **맞는 동작**이다. 재배포 후 K는 L로 로그인되고,
> **Y는 들어갈 방법이 없는 계정**으로 남는다(롤백 기간에 Y로 남긴 기록도 함께). 계정 병합은 범위 밖(S-5)이라 감수한다.
> `UNIQUE(user_id, provider)` 충돌은 생기지 않는다 — 옛 코드가 만든 Y에는 연결 행이 없다.
>
> **⚠️ 축소 전 점검 — Y 같은 계정은 축소 후 "인증 수단 0개"가 된다.** `user.provider`가 지워지면 Y는 비밀번호도 연결도 없어 **S-6 불변식을 어긴 행**이
> 된다. 그래서 축소 마이그레이션 **실행 전에** 런북 절차로 확인한다(보정 복사 다음):
>
> ```sql
> -- 보정 후 남는 "옛 연결만 있는 계정"(Y) 탐지 — 0건이어야 축소한다
> SELECT u.id, u.email, u.created_at FROM user u
> WHERE u.provider IS NOT NULL
>   AND NOT EXISTS (SELECT 1 FROM user_social_account s WHERE s.user_id = u.id);
> ```
>
> - **0건** → 축소 진행. 캡스톤 규모에서는 대부분 0건이다(롤백 + 그 사이 **다른 이메일**의 카카오 로그인이 겹쳐야 생긴다).
> - **0건이 아니면 축소를 멈추고 사람이 판단**한다(본인 확인 후 삭제 또는 보존). **마이그레이션이 자동으로 지우지 않는다** — Flyway 마이그레이션은 모든
>   환경에서 같은 결과를 내야 하는데, *"있으면 사람이 보고 결정"* 은 그렇게 만들 수 없고, 사용자 데이터를 마이그레이션이 판단해 지우는 것은 위험하다.
>
> ③이 복구 가능한 이유는 **축소 전까지 `user.provider`가 남아 있기 때문**이다 — 확장/축소를 나눈 덕분에 생긴 여유다. 그래서 축소 마이그레이션의
> 첫 문장이 이 보정이어야 하고, 축소는 **롤백 가능성이 없어진 뒤**에만 한다.

**코드**

| 영역 | 변경 |
|---|---|
| 엔티티 | `UserSocialAccount` 신설(`domain.user.entity`, `BaseCreatedAtEntity`, `link()` 팩토리, `provider`는 `OAuthProvider` STRING). `User`에서 `provider`·`providerId` 제거, `createOAuth(email, nickname, profileImage)`, **`isOAuthUser()` → `hasPassword()`** |
| 검증 관문 | **`OAuthVerificationService` 신설** — 검증기 조회 → nonce 소비 → ID 토큰 검증을 `AuthService`에서 꺼냈다. 로그인과 연결이 같은 관문을 지나는 것을 구조로 보장(S-5) |
| 로그인 | `UserService.signUpOAuth(…, OAuthProvider, providerId)` — 연결 조회 → 이메일 있으면 `EMAIL_ALREADY_REGISTERED` → 신규면 `user` + `user_social_account` 한 트랜잭션 |
| 연결 API | `SocialAccountService` + `UserController` 3종(D-2-A 계약 그대로). 연결·해제는 `UserRepository.findByIdForUpdate`(비관적 락) |
| 비밀번호 | `login`·`changePassword`·`PasswordResetService.requestReset`·`User.changePassword`의 판정을 `hasPassword()`로 — 소셜을 연결한 로컬 가입자도 비밀번호를 계속 쓴다 |
| `ErrorCode` | `EMAIL_ALREADY_REGISTERED_LOCALLY` → `EMAIL_ALREADY_REGISTERED`, 신규 4종 |

**구현 중 정한 것 3건**

1. **`isOAuthUser()`를 고치지 않고 없앴다.** 연결 허용 후 "소셜 가입자인가"는 판정 기준이 될 수 없다 — 로컬 가입자가 카카오를
   연결하면 `isOAuthUser()`가 참이 되어 **비밀번호 로그인·변경·재설정이 전부 막힌다.** S-6의 정의(인증 수단 = 비밀번호 + 소셜들)를
   그대로 옮긴 `hasPassword()`로 바꿨다. `UserServiceSocialAuthTest`가 이 경우를 고정한다.
2. **연결 요청 DTO는 `SocialLinkRequest`로 따로 뒀다**(필드는 `OAuthLoginRequest`와 같음). user 도메인이 auth 도메인의 요청 DTO에
   묶이지 않게 하려는 것이다.
3. **락이 트랜잭션의 첫 DB 읽기여야 한다.** InnoDB(REPEATABLE READ)는 첫 일반 SELECT에서 스냅샷을 만든다 — 락 앞에 일반 SELECT가
   있으면 이후의 개수 조회가 락 대기 전의 옛 스냅샷을 봐서 락이 무의미해진다. 연결은 검증(외부 JWKS)을 락보다 **먼저** 하는데,
   검증은 DB를 읽지 않으므로(nonce는 메모리 캐시) 이 조건이 지켜진다. `SocialAccountService` 주석에 남겼다.
   → **검토 의견 (2026-10-10): 판단은 정확하고 현재 코드도 올바르다**(락 앞의 DB 읽기 없음, `open-in-view: false`). 다만 정확성이
   **순서라는 암묵 조건**에 걸려 있다 — 나중에 누가 `unlink` 앞부분에 일반 조회 하나만 넣어도 **에러 없이** 보호가 사라지고, 주석은
   그걸 막지 못한다. **보강: 개수를 세는 읽기 자체를 잠금 읽기로 바꾼다.**
   - `UserSocialAccountRepository`에 `@Lock(PESSIMISTIC_WRITE)` 목록 조회(`findAllByUserIdForUpdate`) 추가 → `unlink`는 이 목록에서
     해당 제공자를 찾고(없으면 `SOCIAL_ACCOUNT_NOT_FOUND`) 크기로 센다. `findByUserIdAndProvider`·`countByUserId` 두 번의 일반 조회가
     잠금 조회 한 번으로 줄어든다(사용자당 최대 3행). JPA `COUNT`에는 락을 걸기 어려워 **목록을 잠그고 Java에서 센다.**
   - `hasPassword()`는 이미 잠근 `user` 행에서 읽으므로 최신이다.
   - 대안 — 메서드에 `@Transactional(isolation = READ_COMMITTED)`. 같은 효과지만 커넥션 단위 설정이라, **쿼리에서 바로 보이는**
     잠금 읽기를 택한다.
   - 이렇게 바꾸면 `SocialAccountService`의 *"첫 읽기여야 한다"* 주석은 **"개수는 잠금 읽기로 센다 — 스냅샷 순서와 무관"** 으로 바꾼다.
   - ✅ **반영 (2026-10-10)** — `findAllByUserIdForUpdate`(`@Query` + `@Lock`) 추가, `unlink`가 이 목록 하나로 대상 찾기·개수 세기.
     쓰는 곳이 없어진 `findByUserIdAndProvider`·`countByUserId`는 삭제(테스트는 `findAllByUserIdOrderByCreatedAtAscIdAsc`로 확인).
     관련 테스트 5개 클래스 통과. **순서 의존이 실제로 사라졌는지는** 위 동시 해제 테스트(구글 단위)가 고정한다.

**테스트** — 197건 통과(신규·재작성 31건): `SocialAccountServiceTest` 11 · `UserServiceSocialAuthTest` 8 ·
`OAuthVerificationServiceTest` 4(`AuthServiceOAuthLoginTest`의 순서 테스트를 옮겨 옴) · `AuthServiceOAuthLoginTest` 6 · `SchemaConstraintTest` V24 4.

**⚠️ 구글 단위로 넘긴 것** — 제공자가 `KAKAO` 하나라 `UNIQUE(user_id, provider)` 때문에 **소셜 2개를 가진 사용자를 만들 수 없다.**
"소셜 전용 사용자가 둘 중 하나를 해제"와 **동시 해제 경합(비관적 락) 테스트**는 구글 추가 시 함께 넣는다.
→ 동의(2026-10-10). 기대값: 비밀번호 없이 카카오·구글만 연결된 사용자에게 **두 스레드가 서로 다른 제공자를 동시에 해제** → **정확히
하나만 성공**, 다른 하나는 `LAST_AUTH_METHOD`(409), 연결 1개 남음. 위 #3 보강을 먼저 하면 이 테스트가 순서 의존까지 함께 고정한다.

**앱(S-7)** — ✅ `cinemory-app` `feature/social-login` 브랜치에서 `LoginScreen.tsx` 66행을 `EMAIL_ALREADY_REGISTERED`로 수정(2026-10-10, 미커밋).
문구는 *"이미 가입된 이메일이에요. 기존에 가입한 방법으로 로그인해 주세요"* — 서버 메시지의 *"설정에서 계정을 연결"* 은 **앱에 연결 화면이 없어**
뺐다(연결 UI 작업 때 추가). **백엔드와 앱 두 브랜치를 함께 머지한다.**

## D-5. 구글 로그인 — 확정 (2026-10-11, G-1~G-6)

> **Q-3·Q-4를 닫고 Q-8을 범위 밖으로 확정한다.** 구현 순서:
> **① OIDC 일반화 리팩터링(카카오만, 동작 불변) → ② 구글 검증기 → ③ 소셜 2개·동시 해제 테스트 → ④ 앱 스파이크 → ⑤ 앱 연동 → ⑥ 실기기 E2E.**
> ①은 **별도 커밋**으로 두고 기존 테스트 전부 통과를 확인한 뒤 ②로 넘어간다. 리팩터링과 기능 추가가 한 커밋에 섞이면 회귀가 났을 때 원인을 가를 수 없다.
> ④ 스파이크는 ①~③과 독립이라 병행해도 된다. PR은 S-3대로 1단위와 같은 Draft PR에 쌓는다.

### D-5-A. 확정

| # | 항목 | 결정 |
|---|---|---|
| G-1 | **앱 라이브러리** | **`react-native-nitro-google-signin` `2.3.0` — 정확히 고정(`^` 없음).** MIT, Android **Credential Manager**. **반나절 스파이크(D-5-E) 통과가 채택 조건이다.** 실패하면 **자체 Expo 로컬 모듈**(Credential Manager 직접 호출, `signIn(webClientId, nonce) → { idToken }`·`signOut()`만 노출)로 간다. 그래도 막히면 `@react-native-google-signin` 유료판(연 $79), 브라우저 방식(`expo-auth-session`)은 최후 수단이다. **라이브러리 코드를 리포에 복사(vendoring)하지 않는다** |
| G-2 | **nonce** | **서버가 발급한 원문을 그대로** 넘기고 서버도 원문으로 비교한다. 카카오와 같은 경로다. 라이브러리는 받은 값을 `GetGoogleIdOption.setNonce()`에 **가공 없이** 전달한다(2.3.0 소스 확인) |
| G-3 | **서버 검증** | 표준 OIDC 4종(서명·`iss`·`aud`·`nonce`) + `email_verified`. JWKS·토큰 검증 공통부를 **`global/infra/oidc`로 일반화**한다(D-5-B) |
| G-4 | **이메일 신뢰** | **`email_verified == true`만 요구한다.** `hd`·`@gmail.com` 조건은 두지 않는다. 남는 위험은 security-spec **L-16** |
| G-5 | **콘솔** | D-5-F |
| G-6 | **범위** | **Android만.** D-4에서 넘긴 **소셜 2개 사용자·동시 해제 경합 테스트를 이번 단위에 넣는다.** iOS는 범위 밖(Q-8) |

**G-1 근거.** 무료판 `@react-native-google-signin`은 구글이 폐기 중인 레거시 Google Sign-In SDK를 쓰고, nonce와 Credential Manager는 유료판에만 있다.
Expo의 `expo-auth-session` 구글 프로바이더는 deprecated이고 Expo 공식 문서도 네이티브 SDK를 권한다. nitro는 Expo 공식 구글 인증 문서에 소개된
Credential Manager 라이브러리지만 **신생**이다. 첫 배포가 2026-06-01이고, 4개월 동안 24개 버전(8월에 메이저 2.0)이 나왔으며, 메인테이너는 1인, star 43이다.
그래서 **버전 정확 고정 + 스파이크 + 업그레이드할 때 스파이크 체크리스트 재통과**로 위험을 관리한다.
**사용자가 보는 화면은 세 경로 모두 구글이 그리는 Credential Manager 바텀시트로 같다.** 라이브러리 선택은 UX가 아니라 유지보수 위험의 문제다.

**G-2 근거.** 해시 nonce는 nonce를 만든 쪽과 검증하는 쪽이 다를 때(예: Apple 로그인 + Firebase) 원문 노출을 막는 방식이다.
우리는 **서버가 발급하고, 1회 소비하고, 비교까지 모두** 하므로 해시를 끼워도 추가로 막아지는 공격이 없다. 성능 차이도 없다(SHA-256 한 번은 마이크로초 단위).
이 방식을 고른 이유는 **단순성과 카카오와의 일관성**이다. 비교 로직이 하나로 끝나고, 해시를 어디서 했는지 헷갈려서 생기는 버그가 없다.

**G-4 근거.** 구글 문서상 구글이 이메일의 권위를 갖는 건 `@gmail.com`이거나 `email_verified` + `hd`(Workspace)일 때뿐이다.
`email_verified`는 "그 시점에 확인됨"이지 "지금도 주인"이라는 보장이 아니다. 그래도 **자동 연결이 없어(S-5) 이메일로 남의 계정에 들어갈 경로가 없다.**
최악의 경우는 선점(진짜 주인이 `EMAIL_ALREADY_REGISTERED`를 보는 것)이다. 권위 조건으로 좁히면 회사·학교 메일로 만든 구글 계정 사용자를 전부 막게 되므로, 범위 대비 손해다.

### D-5-B. 서버 ① — OIDC 일반화 (카카오만, 동작 불변)

**왜 하나.** `CachingKakaoJwkSource`의 방어 4종은 카카오와 무관한 **OIDC 공통 로직**이다.
- 쿨다운
- `kid` 미스일 때만 재조회
- 조회 실패를 삼키고 캐시로 계속 동작
- `new BigInteger(1, …)` 부호 함정

구글에 복사하면 같은 방어를 두 곳에서 유지해야 하고, 한쪽만 고쳐지는 순간 갈라진다. 네이버(Q-2, OIDC로 갈 경우)와 Apple(Q-8)도 같은 구조를 쓴다.

| 이전 | 이후 |
|---|---|
| `global/infra/kakao/KakaoJwkSource` | **`global/infra/oidc/JwkSource`** — 메서드 동일(`RSAPublicKey findByKid(String kid)`). 인터페이스로 둔 이유(테스트에서 자체 RSA 키를 꽂는다)는 주석째 옮긴다 |
| `global/infra/kakao/CachingKakaoJwkSource` (`@Component`) | **`global/infra/oidc/CachingJwkSource` — `@Component`가 아니다.** 제공자별 `@Bean`으로 만든다. 생성자 `(String providerName, String jwksUri, Duration refreshCooldown, RestClient restClient, Clock clock)`. 로그 문구의 "카카오"를 `providerName`으로 바꾼다 |
| `global/infra/kakao/dto/JwkSetResponse` | **`global/infra/oidc/dto/JwkSetResponse`** |
| (신규) | **`global/infra/oidc/OidcIdTokenValidator`** — 서명·만료·`iss`·`aud`·`nonce`를 검증하고 `Claims`를 돌려준다 |
| `KakaoOAuthConfig` | `@Bean JwkSource kakaoJwkSource(RestClient oidcRestClient, KakaoOAuthProperties, Clock)` |
| (신규) `global/infra/oidc/OidcConfig` | **공용 `@Bean RestClient oidcRestClient` — 타임아웃 connect 2초 · read 3초.** `kakaoRestClient`를 대체한다 |

**`OidcIdTokenValidator`**
- **상태 없는 일반 클래스(빈 아님).** 제공자 검증기가 생성자에서 직접 만든다:
  `new OidcIdTokenValidator(jwkSource, Set.of(properties.issuer()), properties.allowedAudiences(), clock)`.
  이렇게 하면 기존 `KakaoIdTokenVerifierTest`처럼 **가짜 `JwkSource` 하나로 검증기 전체를 조립하는 테스트 구조가 그대로 유지된다.**
- `Claims validate(String idToken, String expectedNonce)` — `KakaoIdTokenVerifier`의 `parseAndVerifySignature`·`validateIssuer`·`validateAudience`·`validateNonce`와
  `CLOCK_SKEW_SECONDS`를 **주석째** 옮긴다. 검증 4종이 각각 무엇을 막는지에 대한 설명도 이 클래스로 간다.
- **`iss`는 `Set<String>`으로 받는다.** 카카오는 `Set.of(issuer)`로 넘긴다. **카카오 설정 키(`oauth.kakao.issuer`)는 바꾸지 않는다**(환경변수·secret 파일 변경 없음).
- **상속이 아니라 합성인 이유.** 제공자 간 차이는 *클레임 → `OAuthUserInfo` 매핑*뿐이고, 검증 순서는 바뀔 여지가 없어야 한다.
  템플릿 메서드 상속으로 만들면 하위 클래스가 검증 단계를 오버라이드해 빠뜨릴 수 있다. 제공자 검증기는 `validate()`를 호출한 뒤 **매핑만** 한다.
- **L-14(열려 있음) 동반 처리.** 옮기는 김에 nonce 불일치 지점에 `log.warn("{} ID 토큰 nonce 불일치", providerName)`을 남긴다(값은 남기지 않는다).
  클라이언트 응답(`INVALID_NONCE`)은 그대로 두고 **서버 로그에서만 두 원인을 가른다.** 구글 스파이크 디버깅이 카카오 때(2026-08-27)처럼 막히지 않게 하려는 것이다.
  생성자에 `providerName`을 추가한다.
- **확장 지점(기록만, G-6).** Apple은 토큰에 **nonce의 SHA-256 해시**를 넣는다. Apple을 추가할 때 생성자에 `UnaryOperator<String> nonceTransform`(기본값 identity)을 더하면 된다.
  **지금은 넣지 않는다** — 쓰는 곳 없는 확장이다.

**타임아웃을 넣는 이유.** 지금 `RestClient.create()`에는 타임아웃이 없다. JWKS 조회는 **로그인 요청 스레드 안에서**(kid 미스 시) 일어나므로,
제공자가 응답하지 않으면 요청이 무기한 붙잡힌다. 조회 실패는 이미 삼키도록 돼 있어서, 타임아웃이 나도 캐시된 키로 계속 동작한다.

**① 완료 기준** — 기존 테스트 전부 통과. 클래스 이름·생성 방식 변경 외에 **기대값 수정이 없어야 한다.**
`CachingKakaoJwkSourceTest`는 `CachingJwkSourceTest`로, `KakaoIdTokenVerifierTest`의 가짜 키 소스는 `JwkSource` 람다로 바꾼다.

**✅ ① 완료(2026-10-11)** — `cleanTest test` **198건 통과**(리팩터링 전과 같은 수). `CachingJwkSourceTest` 12 · `KakaoIdTokenVerifierTest` 14 · `KakaoOAuthPropertiesTest` 8.
테스트 diff는 패키지·클래스 이름, 생성 방식(`source()` 헬퍼, 가짜 키 소스 타입), 주석의 "카카오 → 제공자"뿐이고 **단언 변경은 0건**이다. 구현 메모:
- `KakaoIdTokenVerifier`는 `JwkSource`를 **`@Qualifier("kakaoJwkSource")`로** 받는다. 지금은 빈이 하나라 없어도 되지만, ②에서 `googleJwkSource`가 생기는 순간 주입이 모호해진다.
- `OidcIdTokenValidator` 생성자는 `(providerName, jwkSource, issuers, allowedAudiences, clock)` — 이름을 `CachingJwkSource`와 같이 맨 앞에 둔다. 입력 컬렉션은 `copyOf`로 불변 복사한다.
- nonce 로그(L-14)는 클레임 **누락과 불일치를 같은 줄**로 남긴다 — 둘 다 "캐시에 없음"(`consumeOrThrow`)과 갈리는 것이 목적이다.

### D-5-C. 서버 ② — 구글 검증기

| 파일 | 내용 |
|---|---|
| `domain/auth/entity/OAuthProvider` | **`GOOGLE` 추가** — 검증기와 **같은 커밋**에 넣는다(enum 주석의 원칙: 구현체 없는 값을 미리 넣지 않는다) |
| `global/infra/google/GoogleOAuthProperties` | `@ConfigurationProperties("oauth.google")` record — `List<String> issuers`, `jwksUri`, `List<String> allowedAudiences`, `Duration jwkRefreshCooldown`. **`KakaoOAuthProperties`와 같은 compact constructor 검증**(`issuers`·`allowedAudiences`가 비면 기동 실패 — 이유도 같다) |
| `global/infra/google/GoogleOAuthConfig` | `@EnableConfigurationProperties`, `@Bean JwkSource googleJwkSource(oidcRestClient, properties, clock)` |
| `domain/auth/service/oauth/GoogleIdTokenVerifier` | `OAuthIdTokenVerifier` 구현. `validate()` → 아래 매핑 |
| `global/exception/ErrorCode` | **`OAUTH_EMAIL_NOT_VERIFIED(400, "소셜 계정의 이메일이 인증되지 않았습니다. 이메일 인증 후 다시 시도해 주세요.")`** |

```yaml
# application.yml
oauth:
  google:
    # 구글은 두 형식을 모두 발급한다(공식 문서) — 하나만 넣으면 일부 토큰이 이유 없이 거부된다
    issuers:
      - https://accounts.google.com
      - accounts.google.com
    jwks-uri: https://www.googleapis.com/oauth2/v3/certs
    jwk-refresh-cooldown: PT1M
    # allowed-audiences는 config/application-secret.yml에 둔다 (웹 클라이언트 ID)

# application-prod.yml
oauth:
  google:
    allowed-audiences: ${GOOGLE_ALLOWED_AUDIENCES}
cinemory:
  startup-check:
    required-properties:
      # … 기존 목록 …
      - oauth.google.allowed-audiences     # ProdStartupGuard — 누락 시 기동 실패
```
`deploy/cinemory.env.example`에 `GOOGLE_ALLOWED_AUDIENCES=`를 추가하고, 운영 `/etc/cinemory/cinemory.env`에는 실제 값을 머지 **전에** 넣는다(deploy-spec 비밀 관리 절차).
운영 값은 **웹 클라이언트 ID 하나**다.

**클레임 매핑**

| 클레임 | 처리 |
|---|---|
| `sub` | `providerId`. 없으면 `INVALID_OAUTH_TOKEN`. ⚠️ 구글 문서상 **계정 식별자는 `sub`뿐이다** — 이메일은 바뀔 수 있으므로 키로 쓰지 않는다 |
| `email` | 필수. 없으면 `OAUTH_EMAIL_NOT_PROVIDED`(카카오와 같다) |
| `email_verified` | **`Boolean.TRUE` 또는 문자열 `"true"`만 통과**한다(일부 OIDC 제공자는 문자열로 보낸다. jjwt `claims.get(…, Object.class)`로 받아 판정). 그 외 값이거나 누락이면 **`OAUTH_EMAIL_NOT_VERIFIED`**. 판정 순서는 `email` 존재 → `email_verified` |
| `name` | 닉네임. 없으면 `"구글사용자" + sub 끝 6자리`(카카오 S-9 E-3과 같은 규칙) |
| `picture` | `profileImage`, 없으면 `null` |
| `azp` | **검증하지 않는다.** Android 토큰은 `azp` = Android 클라이언트 ID, `aud` = 웹 클라이언트 ID로 온다. 구글의 서버 측 ID 토큰 검증 기준은 `aud`다 |
| `hd` | 쓰지 않는다(G-4) |

**✅ ② 완료(2026-10-11)** — `cleanTest test` **229건 통과**(198 + 신규 31: `GoogleIdTokenVerifierTest` 20 · `GoogleOAuthPropertiesTest` 5 · `OAuthProviderTest` 5 · `OAuthVerificationServiceTest` +1). 구현 메모:
- `GoogleIdTokenVerifier`는 `@Qualifier("googleJwkSource")`로 받는다. `@SpringBootTest` 문맥 기동으로 `JwkSource` 빈 두 개의 주입이 갈리는 것을 확인했다.
- `email_verified`는 `Boolean.TRUE`·`"true"`만 통과 — 테스트에서 `"false"`·`"TRUE"`·`1`·`null`이 거부됨을 고정했다.
- ⚠️ **로컬 `config/application-secret.yml`에 `oauth.google.allowed-audiences`가 없으면 `test`·`bootRun` 모두 기동 실패한다**(비면 기동 실패가 설계). 자리표시자 `REPLACE-WITH-GOOGLE-WEB-CLIENT-ID`를 넣어 두었다 — 실제 웹 클라이언트 ID로 바꾸기 전까지 로컬 구글 로그인은 `aud` 불일치로 실패한다. 팀원 로컬도 같은 키가 필요하다.
- 운영 `GOOGLE_ALLOWED_AUDIENCES`는 deploy-spec 1-1·server-setup-runbook 환경변수 표에 추가했다.

**`OAUTH_EMAIL_NOT_VERIFIED`를 새로 만드는 이유.** `OAUTH_EMAIL_NOT_PROVIDED`를 재사용하면 메시지("제공받지 못했습니다")가 **사실과 다르다.**
S-7에서 사실과 다른 메시지를 고친 것과 같은 이유다. 앱은 모르는 코드를 일반 오류로 처리하므로 백엔드가 먼저 배포돼도 안전하다.
`controller-layer-spec.md` ErrorCode 표에도 추가한다.

**바뀌지 않는 것.** 로그인(`POST /api/auth/oauth/{provider}`), 연결(`POST /api/users/me/social-accounts/{provider}`), nonce 발급(`POST /api/auth/nonce`)은
**경로 변수로 제공자를 받으므로 컨트롤러 변경이 없다.** `OAuthVerificationService`는 `List<OAuthIdTokenVerifier>` 주입이라 자동 등록된다.
Nginx 요청 제한(login/oauth 10r/m)도 그대로 적용된다.

### D-5-D. 서버 ③ — 테스트

| 테스트 | 케이스 |
|---|---|
| `GoogleIdTokenVerifierTest` (자체 RSA 키) | 정상 · `iss` **두 형식 각각** 통과 · 다른 `iss` 거부 · `aud` 불일치 거부 · **`azp`가 달라도 통과** · nonce 불일치 → `INVALID_NONCE` · `email` 없음 → `OAUTH_EMAIL_NOT_PROVIDED` · `email_verified` `false`/누락 → `OAUTH_EMAIL_NOT_VERIFIED` · `email_verified` 문자열 `"true"` 통과 · `name` 없음 → 기본 닉네임 · 만료 |
| `GoogleOAuthPropertiesTest` | `issuers`·`allowed-audiences`가 비면 바인딩 실패 |
| `OAuthProviderTest` | `from("google")` → `GOOGLE` |
| `OAuthVerificationServiceTest` | `GOOGLE` 검증기가 등록되고 같은 순서(조회 → nonce 소비 → 검증)를 지난다 |
| `SocialAccountServiceTest` 추가 | 카카오 사용자가 구글 연결 → 성공 · 구글 계정이 **다른 사용자**에 연결돼 있음 → `SOCIAL_ACCOUNT_ALREADY_LINKED` · **소셜 전용(카카오 + 구글) 사용자가 하나 해제 → 성공, 남은 하나 해제 → `LAST_AUTH_METHOD`** |
| **`SocialAccountUnlinkConcurrencyTest`** (D-4 이월) | 아래 |

**동시 해제 경합 테스트** — D-4 #3 보강(잠금 읽기)이 순서 의존 없이 실제로 동작하는지를 고정한다.
- **실제 DB(`cinemory_test`), 테스트 메서드에 `@Transactional`을 붙이지 않는다.** 각 스레드가 자기 트랜잭션을 커밋해야 락 경합이 생긴다.
  롤백형 테스트로 쓰면 두 호출이 한 트랜잭션에 들어가 경합 자체가 사라져 **항상 통과**한다.
- 준비: 비밀번호 없는 사용자 + 카카오·구글 연결 2건(테스트가 직접 시드하고 `@AfterEach`에서 삭제).
- 실행: 스레드 2개를 `CountDownLatch`로 동시에 출발시켜 **서로 다른 제공자**를 `unlink`.
- 기대: **성공 정확히 1, `LAST_AUTH_METHOD` 정확히 1, 남은 연결 정확히 1.**
  락이 빠졌을 때의 실패 형태는 *둘 다 성공 → 연결 0*이다.
- 간헐성을 보려고 `@RepeatedTest(5)`. 실패 메시지에 성공 수와 남은 연결 수를 함께 찍는다.

**✅ ③ 완료(2026-10-11)** — `cleanTest test` **237건 통과**(229 + `SocialAccountServiceTest` +3 + `SocialAccountUnlinkConcurrencyTest` 5).
- **경합 테스트가 락을 실제로 잡아내는지 확인했다(뮤테이션).** `unlink`의 두 잠금 조회(`findByIdForUpdate`·`findAllByUserIdForUpdate`)를 일반 조회로 잠시 바꾸자
  **5회 전부 `결과=[SUCCESS, SUCCESS], 성공=2, 남은 연결=0`으로 실패**했다 — 위에 적은 실패 형태 그대로다. 되돌린 뒤 5회 전부 통과.
  이 확인이 없으면 "통과"가 락 덕분인지 경합이 안 생긴 덕분인지 가를 수 없다.
- 시드는 반복마다 UUID 접미사를 붙인 이메일·`providerId`로 만든다 — 앞 반복의 정리가 실패해도 UNIQUE 위반이 원인을 가리지 않게.
  정리는 사용자 삭제 하나(`user_social_account`는 FK `ON DELETE CASCADE`).
- `SocialAccountServiceTest` 클래스 주석의 "구글 단위에서 함께 넣는다"를 현재 상태로 고쳤다 — 경합은 롤백형인 이 클래스가 아니라 별도 클래스가 본다.

### D-5-E. 앱 ④ — 스파이크 (반나절, G-1 채택 조건)

**소스를 읽어 확인한 함정 4건** — 스파이크와 본 구현 모두에 적용한다.
1. **nonce가 `configure()`에 묶여 있다.** 로그인할 때마다 서버에서 새 nonce를 받아 **`configure({ webClientId, nonce })`를 매번 다시 호출**한다.
   그렇지 않으면 이전 nonce가 재사용되어 서버의 1회용 검증(`INVALID_NONCE`)에 걸린다.
2. **nonce를 생략하면 라이브러리가 조용히 자동 생성한다.** 그 값은 JS로 돌아오지 않아 서버가 검증할 수 없다. **앱은 nonce가 없으면 호출 자체를 하지 않는다.**
3. **Expo 플러그인이 Firebase 설정 파일 또는 `iosUrlScheme` 중 하나를 강제한다.** Firebase는 쓰지 않으므로 **iOS OAuth 클라이언트(무료, 번들 ID만)를 하나 만들어
   그 REVERSED_CLIENT_ID를 `iosUrlScheme`에 넣는다.** iOS 로그인에는 쓰지 않는다.
4. **`signIn()`은 이 앱을 이미 승인한 계정만 보여 준다**(`filterByAuthorizedAccounts=true`). 첫 사용자는 "저장된 자격 증명 없음"이 오므로 **`createAccount()`로 넘어간다.**

**체크리스트** — 결과는 이 절 아래 "스파이크 결과"에 기록한다.

| # | 확인 | 통과 기준 |
|---|---|---|
| 1 | `react-native-nitro-modules`(0.36.x~0.37.x — 라이브러리 `compatibility.json`) + 라이브러리 설치 → dev build | Expo SDK 57 / RN 0.86에서 빌드 성공 |
| 2 | 서버 nonce → `configure` → 로그인 → 토큰 디코딩 | **`nonce` 클레임이 보낸 원문과 바이트 단위로 같다** |
| 3 | 토큰 `aud` | **웹 클라이언트 ID**와 같다(Android 클라이언트 ID가 아니다) |
| 4 | 실제 서버 검증(①~③ 완료 후, 아니면 디코딩으로 대체) | `iss`·`email_verified` 통과 → 로그인 성공 |
| 5 | 첫 사용자 흐름 | `signIn` 실패 → `createAccount` 성공 |
| 6 | 취소 · 오류 | 취소는 조용히 무시된다. SHA-1 미등록 상태의 `DEVELOPER_ERROR`는 식별 가능한 코드로 받는다 |
| 7 | nonce 재사용 | 같은 nonce로 두 번째 시도 → 서버 `INVALID_NONCE` |

**판정** — **1·2·3 중 하나라도 막히면 C를 중단하고** 자체 Expo 로컬 모듈(G-1)로 간다. 4~7은 C 안에서 해결할 문제다.
**업그레이드 규칙** — 버전을 올릴 때는 1~3, 5, 7을 다시 통과해야 한다.

### D-5-F. 구글 콘솔 (G-5) — 발표 전에 꼭 챙길 함정

- **OAuth 클라이언트**
  - **웹** — 이 ID가 토큰의 `aud`다. 서버 `allowed-audiences`와 앱 `webClientId`(`EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID` — 공개 값이라 앱 번들에 들어가도 된다)에 쓴다.
  - **Android** — 패키지명 + **서명 키 SHA-1.** ⚠️ **Android 클라이언트 하나에 SHA-1 하나**라 키마다 클라이언트를 따로 만든다:
    개발 debug 키스토어 · EAS 빌드 키(`eas credentials`) · **M5에서 Play 앱 서명 키.** 카카오 L-7과 같은 함정으로, 빠뜨리면 **그 빌드에서만** `DEVELOPER_ERROR`가 난다.
  - **iOS** — D-5-E 함정 3의 플러그인 요구용으로만 만든다.
- **OAuth 동의 화면** — 범위는 `openid`·`email`·`profile`만 쓴다(비민감 범위라 구글 심사 대상이 아니다).
  ⚠️ **게시 상태가 "테스트"면 등록한 테스트 사용자만 로그인된다.** 개발 중에는 팀 계정을 테스트 사용자로 두고,
  **Phase 5 실기기 E2E와 발표 시연 전에 "프로덕션"으로 게시한다.** 시연 계정이 목록에 없으면 시연 당일에 실패한다.
  로고를 올리면 브랜드 확인 절차가 생길 수 있으므로 시연 전에는 로고 없이 게시한다.
- **ProdStartupGuard** — `oauth.google.allowed-audiences`를 추가했다(D-5-C).

### D-5-G. 앱 ⑤ — 연동 요약 (상세는 `cinemory-app/docs/`에 별도 문서)

1. `POST /api/auth/nonce` → `nonce`
2. `GoogleSignin.configure({ webClientId, nonce })` — **매번**(D-5-E 함정 1)
3. `signIn()` → "저장된 자격 증명 없음"이면 `createAccount()`. 취소는 조용히 종료
4. `POST /api/auth/oauth/google { idToken, nonce }` → 토큰 저장(카카오와 같은 경로)
5. 에러 처리:
   - `EMAIL_ALREADY_REGISTERED` → S-7 문구
   - `OAUTH_EMAIL_NOT_VERIFIED` → "구글 계정의 이메일 인증 후 다시 시도해 주세요"
   - `INVALID_NONCE` → 1단계부터 자동 재시도 1회

- **로그아웃** — 앱 로그아웃 시 `signOut()`으로 Credential Manager 상태를 비운다. 다음 로그인에서 계정 선택이 다시 나온다.
- **회원 탈퇴(Q-7)** — 서버 삭제가 성공한 **뒤에** 연결돼 있던 제공자마다 SDK 해제를 호출한다(구글은 `revokeAccess`). 연결 목록은 탈퇴 **전에**
  `GET /api/users/me/social-accounts`로 받아 둔다. 해제 실패는 무시한다(서버 데이터는 이미 삭제됐다). Part C W-5를 이 형태로 일반화한다.
- **버튼** — 구글 브랜딩 가이드라인을 따른다(라이브러리의 `GoogleSignInButton` 또는 가이드라인에 맞춘 자체 버튼).
- **설정 화면의 계정 연결 UI**는 이번 범위가 아니다(D-4 앱 노트). 이번에는 로그인 버튼만 넣는다. 연결 API는 서버 테스트로 검증한다.

### D-5-H. 한계와 범위 밖

- **L-16 신설**(security-spec S-11) — 구글 이메일 선점. G-4 근거 참고.
- **Q-8 iOS — 범위 밖 확정.** 진입할 때 필요한 것:
  ① `GOOGLE_ALLOWED_AUDIENCES`에 **iOS 클라이언트 ID 추가**(iOS 토큰의 `aud`) — 목록 설계라 코드 변경 없음
  ② **Apple 로그인 의무**(App Store 심사 지침 4.8) — `OidcIdTokenValidator` 재사용 + nonce 해시 확장 지점(D-5-B)
  ③ 탈퇴 시 **Apple 토큰 revoke**(지침 5.1.1(v)) — Part C에 단계 추가
  ④ **Apple Developer Program 연 $99**(개인은 면제 대상 아님). Apple 로그인 자체는 무료다.

### 스파이크 결과

(Claude Code가 D-5-E 체크리스트 결과를 여기에 기록한다 — 날짜, 라이브러리·nitro-modules 버전, 항목별 통과 여부, 디코딩한 `nonce`·`aud` 대조 결과(값 자체는 적지 않는다), 판정)

**2026-10-11 — 1번** · 앱 로컬 브랜치 `spike/google-signin`(`feature/social-login`에서 분기, 미push)
- 버전: `react-native-nitro-google-signin` **2.3.0** · `react-native-nitro-modules` **0.37.1** — 둘 다 `--save-exact`(`^` 없음). 0.37.1은 라이브러리 2.3.0의 개발 의존 버전이고 `compatibility.json`의 `2.0.x` 범위(0.36.x~0.37.x, RN ~0.87) 안이다
- 환경: Expo SDK 57(`expo ~57.0.24`) · RN 0.86.3 · JDK 21 · 기존 `android/`에 `./gradlew assembleDebug`(prebuild 없이 — 앱 DevLog의 기존 방식)
- **결과: ✅ 통과** — `BUILD SUCCESSFUL in 23m 44s`, `app-debug.apk` 생성. 두 모듈 모두 autolinking으로 잡혔다(`:react-native-nitro-google-signin:compileDebugKotlin`·`buildCMakeDebug[*]` 실행). 경고는 라이브러리의 `GoogleSignInButton`이 쓰는 Legacy Architecture 클래스(`LayoutShadowNode`) deprecated뿐 — 버튼 컴포넌트를 쓰지 않으면 무관하고, 써도 지금 RN에서는 동작한다. ABI 4종 C++ 컴파일이 시간 대부분이라, 반복 빌드는 `-PreactNativeArchitectures=arm64-v8a`로 줄일 수 있다
- **발견 — 함정 3은 Android 스파이크에는 해당하지 않는다.** 플러그인 소스(`plugin/withNitroGoogleSignIn.js`)를 읽어 보니 Firebase 없이 쓸 때 플러그인이 하는 일은 **iOS `Info.plist`에 URL 스킴 추가 + Podfile 수정뿐**이고 Android는 건드리지 않는다. 반면 `iosUrlScheme`이 없으면 **설정 평가 단계에서 예외**를 던진다. `npx expo install`이 `app.json`에 플러그인을 **자동으로 추가**하므로 이번에는 되돌렸다(`npx expo config` 정상 확인). 따라서 **iOS OAuth 클라이언트는 플러그인을 `app.json`에 넣을 때(=EAS 빌드 등 prebuild가 도는 경로)에만 필요하다.** 로컬 `android/` 빌드로 스파이크 2~7을 하는 동안은 없어도 된다 — D-5-F의 iOS 항목은 그 시점까지 미뤄도 된다
- 남은 것: 2·3번은 **콘솔(D-5-F)의 웹 클라이언트 ID와 debug 키 SHA-1 Android 클라이언트**가 있어야 한다. 그 뒤 실기기에서 2~7

---

## 진행 순서 요약

| 순서 | 작업 | 선행 |
|---|---|---|
| 1 | **Part A** V18~V22 + `SchemaConstraintTest` + 재덤프(v22) | deploy-spec 1-4 |
| 2 | deploy-spec Phase 2~3 (운영 DB는 V22로 시작) | 1 |
| 2-1 | **Part D** 소셜 계정 연결 → 구글 → 네이버 — Phase 2~3 동안 병렬 개발, **Phase 5 이후 머지** | Phase 1 머지 · Part A |
| 3 | **Part B** 인프라(B-2) → 백엔드(B-3) → 앱(B-4, 재빌드는 Phase 5 빌드에 포함) | Phase 2 |
| 4 | **Part C** 백엔드(C-2) → 앱(C-4) | 1, 3의 `MediaStorage` |
| 5 | W-7 웹 삭제 요청 페이지 | M5 |

---

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-10-11 | **D-5-E 스파이크 1번 통과 — 기록은 "스파이크 결과".** nitro-google-signin 2.3.0 + nitro-modules 0.37.1(둘 다 정확 고정)로 Expo SDK 57/RN 0.86.3 `assembleDebug` 성공. 플러그인은 Android에서 하는 일이 없고 `iosUrlScheme` 없이는 예외를 던져 `app.json`에서 뺐다 — iOS 클라이언트(함정 3)는 플러그인을 넣는 시점까지 미룰 수 있다 |
| 2026-10-11 | **D-5 ③ 테스트 완료 — 237건 통과, D-4 이월 2건 종결.** 소셜 2개 사용자 해제(하나 성공 → 남은 하나 `LAST_AUTH_METHOD`)와 커밋형 동시 해제 경합 테스트. 경합 테스트는 락을 빼면 5/5 "성공 2·남은 연결 0"으로 실패함을 확인해, 통과가 락 덕분임을 증명했다 |
| 2026-10-11 | **D-5 ② 구글 검증기 완료 — 229건 통과.** `OAuthProvider.GOOGLE`(검증기와 같은 커밋), `GoogleOAuthProperties`·`GoogleOAuthConfig`(`googleJwkSource`)·`GoogleIdTokenVerifier`, `OAUTH_EMAIL_NOT_VERIFIED`, `application.yml`·`application-prod.yml`·`ProdStartupGuard` 목록·`cinemory.env.example`. 테스트는 D-5-D의 ②해당분(검증기·속성·enum·검증 관문). 로컬 secret 파일에 구글 키가 없으면 기동이 실패하는 점을 D-5-C에 적었다 |
| 2026-10-11 | **D-5 ① OIDC 일반화 완료 — 동작 불변, 198건 통과.** `global/infra/oidc`에 `JwkSource`·`CachingJwkSource`(빈 아님, 제공자별 `@Bean`)·`OidcIdTokenValidator`(빈 아님, 합성)·`OidcConfig`(공용 `oidcRestClient`, connect 2초·read 3초 — `kakaoRestClient` 대체). 카카오 설정 키·환경변수는 그대로다. L-14는 서버 로그 구분으로 부분 처리했다. D-5-B에 구현 메모(검증기의 `@Qualifier` — ②에서 `JwkSource` 빈이 둘이 되기 때문)를 남겼다 |
| 2026-10-11 | **D-5 신설 — 구글 로그인 확정(G-1~G-6), Q-3·Q-4(구글) 종결, Q-8 범위 밖 확정.** **G-1** 앱 라이브러리는 `react-native-nitro-google-signin` 2.3.0 정확 고정 + 반나절 스파이크가 채택 조건이다. 무료판 `@react-native-google-signin`은 레거시 SDK이고 nonce·Credential Manager가 유료판에만 있다. nitro는 신생(4개월, 메인테이너 1인)이라 버전 고정과 업그레이드 시 체크리스트 재통과로 관리한다. 실패하면 **자체 Expo 로컬 모듈**로 가고, 라이브러리 코드 복사(vendoring)는 하지 않는다. npm 2.3.0 소스를 직접 읽어 **넘긴 nonce가 `setNonce()`에 가공 없이 들어감**을 확인했고, 함정 4건(nonce가 `configure()`에 묶임 · 생략 시 보이지 않는 자동 생성 · 플러그인의 Firebase/`iosUrlScheme` 강제 · `signIn()`의 승인 계정 필터)을 D-5-E에 적었다. **G-2** 원문 nonce 유지 — 성능 차이는 없고, 서버가 발급·소비·비교를 모두 하므로 해시가 막는 공격이 없다(해시는 Apple + Firebase처럼 발급자와 검증자가 다를 때의 방식). **G-3** `CachingKakaoJwkSource`의 방어 4종이 OIDC 공통이라 `global/infra/oidc`(`JwkSource`·`CachingJwkSource`·`OidcIdTokenValidator`)로 일반화한다. 검증은 상속이 아니라 **합성**(검증 순서를 하위 클래스가 바꿀 수 없게), `iss`는 Set, 공용 RestClient에 타임아웃을 추가하고 L-14 서버 로그 구분을 동반 처리한다. 리팩터링은 동작 불변의 별도 커밋이다. **G-4** `email_verified`만 요구하고 선점 위험은 security-spec **L-16**으로 기록했다. `email_verified` 실패는 신규 `OAUTH_EMAIL_NOT_VERIFIED`(기존 코드의 메시지가 사실과 달라서 — S-7과 같은 이유). **G-6** Android만, D-4에서 넘긴 동시 해제 경합 테스트를 이번에 넣는다(롤백형 테스트면 경합이 사라져 항상 통과하므로 커밋형으로). iOS는 서버 변경이 거의 없지만 **Apple 로그인 의무 + 연 $99**가 본체라 범위 밖으로 둔다 |
| 2026-10-10 | **S-3에 PR 운영 추가 — 1단위 + 구글 = 한 Draft PR, 네이버는 별도 PR.** 백엔드·앱 `feature/social-login`을 main 기준으로 정리(앱은 merge 커밋을 rebase로 걷어냄 — 내용 동일)하고 Draft PR을 열었다. Phase 5(카카오 E2E) 통과 후 Ready로 바꿔 함께 머지. 네이버를 떼는 이유는 Q-2(검증 방식 불확실)가 앞 두 단위의 머지를 붙잡지 않게 하려는 것 |
| 2026-10-10 | **✅ 5단계 — 로컬 DB 정리 완료.** `cinemory`에 V24 적용(복사 1행 대조 일치), 재덤프 v24의 v23 대비 차이가 확장 전용과 정확히 일치. 첫 재덤프는 상대 경로 `--result-file`이 실행 위치 기준이라 파일이 생기지 않아 **절대 경로**로 다시 떴다 |
| 2026-10-10 | **✅ 4단계 — 확장/축소 성립 확인.** PR #19 머지 후 main 코드로 V24 DB에서 173건 통과(Flyway는 미래 버전 경고만, `validate`는 남은 `provider` 컬럼으로 통과). 첫 시도는 Gradle UP-TO-DATE로 **옛 결과가 통과로 보였다** — 4단계에 `cleanTest` 필수를 적었다. DB 상태는 Gradle 입력이 아니라서, 같은 코드로 다른 DB를 검증할 때마다 생기는 함정이다 |
| 2026-10-10 | **로컬 DB 정리 0~3단계 완료.** V24를 확장 전용(생성·복사·CHECK 완화)으로 재작성, `v24-delta.sql`에 문장별 확장/축소 분류·보류된 축소(보정 복사 → 축소 전 점검 → 컬럼 삭제)·단순해진 롤백. 확장 전용임을 `SchemaConstraintTest`가 고정한다(축소 V를 추가할 때 함께 지울 것). 4단계는 PR #19 머지 후 |
| 2026-10-10 | **보정 쿼리 정정 + "보정이 처리하지 않는 경우"·축소 전 점검 추가.** Claude Code 점검 반영. ① 보정 쿼리가 주석(*"V24와 같은 매핑"*)과 달리 `NOW()`를 써 `linkedAt`이 재배포 시각으로 찍혔다 — **`u.created_at`** 으로 정정(옛 코드는 소셜 가입 때만 `provider`를 채우므로 가입 시각 = 연결 시각). ② 롤백 기간에 L에 연결된 K로 로그인하면 옛 코드가 새 사용자 Y를 만들고, 보정은 Y를 건너뛴다(맞는 동작, 계정 병합은 범위 밖이라 감수). 따라가 보니 **축소 후 Y는 인증 수단 0개**가 되어 S-6 불변식을 어긴다 — 축소 **전** 탐지 쿼리로 0건을 확인하고, 아니면 멈추고 사람이 판단한다(마이그레이션이 자동 삭제하지 않는다) |
| 2026-10-10 | **V24 재작성 보완 — 데이터 비대칭 3방향, 0단계, main 기준.** Claude Code 점검 반영. ① 초판의 *"운영에는 옛·새 코드가 동시에 쓰는 기간이 없다"* 는 **틀렸다** — CI 자동 롤백 기간에 새 코드로 가입한 사용자는 옛 코드에서 카카오 로그인이 안 된다(②, 감수). 따라가 보니 **③ 롤백 기간에 옛 코드로 가입한 사용자는 재배포 후에도 계속 로그인이 안 된다** — 롤백이 끝나도 남는 문제라 **멱등 보정 쿼리**(재배포 직후 + 축소 마이그레이션 맨 앞)로 처리한다. 축소 전까지 `user.provider`가 남아 있어 복구 가능하다. ② `ignore-migration-patterns` 명시는 4단계에서 뜨는 **main에 먼저** 넣어야 의미가 있다(0단계 PR). ③ 2·4단계 검증 대상은 **develop이 아니라 main**(develop은 33커밋 뒤처짐) |
| 2026-10-10 | **V24 재작성 — 확장 전용(deploy-spec D-3 조건 6·7).** 초판 V24가 `user.provider` 삭제까지 한 번에 해, `cinemory_test`를 공유하는 main/develop 테스트가 `validate`로 깨졌고, 같은 구조가 운영의 CI 자동 롤백도 깨뜨린다. 문장을 확장/축소로 분류해 **생성·복사·CHECK 완화만 V24에 남기고**, 컬럼·UNIQUE 삭제는 **보류된 축소**로 다음 릴리스에 둔다. V24는 버려도 되는 `cinemory_test`에만 적용돼 있어 동결 예외(조건 7)로 고쳐 쓰고, `cinemory_test`는 재생성. 로컬 정리 5단계 중 **4번(V24 DB 위에서 main 테스트 통과)이 확장/축소가 성립하는지의 실제 검증** |
| 2026-10-10 | **D-4 #3 보강 반영 + 앱 S-7 수정.** `unlink`의 개수 세기를 잠금 읽기(`findAllByUserIdForUpdate`)로 바꿔 스냅샷 순서 의존을 없앴다 — 일반 조회 2회가 잠금 조회 1회로 줄었다. 앱 `LoginScreen.tsx`는 같은 이름의 브랜치에서 코드 개명 + 문구 수정(가입 방법을 단정하지 않음). 서버 메시지의 "설정에서 연결" 안내는 앱에 연결 화면이 생길 때까지 보류 |
| 2026-10-10 | **D-4 구현 기록 검토 — #1·#2 승인, #3 보강, W-3 갱신.** #1 `hasPassword()`와 #2 `SocialLinkRequest` 분리는 그대로 승인. #3(*"락이 트랜잭션의 첫 DB 읽기여야 한다"*)은 InnoDB 스냅샷에 대한 판단이 정확하고 현재 코드도 올바르지만, 정확성이 **순서라는 암묵 조건**에 기대고 있어 이후 일반 조회 하나가 끼면 에러 없이 깨진다. **개수를 세는 읽기를 잠금 읽기로 바꿔 순서 의존을 없애도록** 보강했다(D-2-A·D-4). 동시 해제 테스트를 구글 단위로 넘긴 것은 동의(제공자 하나로는 소셜 2개 사용자를 만들 수 없다). 회원 탈퇴 W-3의 본인 확인 기준을 *"로컬/카카오"* 에서 **`hasPassword()`** 로 바꿨다 — 계정 연결 후에는 한 사용자가 둘 다일 수 있다 |
| 2026-10-10 | **✅ Part D 1단위 구현 — D-4에 실행 결과 기록.** V24(`user_social_account`, `cinemory_test`만 적용) + 엔티티·서비스·API 3종, 테스트 197건 통과. 구현 중 정한 것: ① **`isOAuthUser()` 폐기 → `hasPassword()`** — 그대로 두면 소셜을 연결한 로컬 가입자의 비밀번호 로그인·변경·재설정이 막힌다 ② 검증 관문을 **`OAuthVerificationService`로 분리**해 로그인과 연결이 같은 순서(nonce 소비 → 검증)를 지나는 것을 구조로 보장(S-5) ③ 비관적 락은 **트랜잭션의 첫 DB 읽기**여야 한다(InnoDB 스냅샷 시점). 소셜 2개 사용자·동시 해제 경합 테스트는 제공자가 하나라 **구글 단위로 이월** |
| 2026-10-10 | **Part D — Q-1·Q-5·Q-6 확정(S-5~S-8) + API 계약(D-2-A).** 연결 구조 리팩터링 착수 중 Claude Code가 세 항목의 결정을 요청했다. **S-5 직접 연결만**(업계 표준 — 이메일 자동 연결은 선점형 계정 탈취 경로), 이미 다른 사용자에 연결된 소셜은 409, 계정 병합은 범위 밖. **S-6 소셜 전용 사용자의 비밀번호 추가는 불허** — 연결 허용으로 필요성이 약하고 단위가 커진다, 나중에 넣어도 스키마 변경 없음. **S-7 `EMAIL_ALREADY_REGISTERED`로 즉시 개명**(설치 기반이 개발 빌드뿐, 앱 참조 1곳) — 가입 제공자는 노출하지 않는다(이메일 열거 방지, security-spec S-9 D-2와 같은 방침). **S-8 연결·조회·해제 API 3종을 리팩터링과 같은 단위로.** 해제의 "인증 수단 최소 1개" 불변식은 동시 해제 경합 때문에 `user` 행 비관적 락으로 보장 |
| 2026-10-02 | **✅ Part A 완료 — A-4에 실행 결과 기록.** V18~V22를 개발·테스트·빈 스키마 3경로에 적용, `SchemaConstraintTest` 8건, 재덤프 v22. 구현 중 확인한 것 3건: ① `movie_actor`가 스펙 추정(18만)의 2.5배인 446,998행이라 V20이 26.2초 — 운영은 빈 테이블이라 무관. ② 빈 스키마 경로와 개발 DB의 스키마를 덤프로 대조해 **제약이 완전히 같음**을 확인(인덱스 나열 순서만 다름). ③ **개발 DB 기본 콜레이션이 `unicode_ci`** — 향후 V 파일이 `COLLATE`를 빠뜨리면 환경별로 갈라질 수 있어 명시 규칙을 A-4에 적었다 |
| 2026-10-02 | **V22 추가 — V19 CHECK의 NULL 구멍 정정.** Phase 1 구현 중 `SchemaConstraintTest`의 *"`watch_type=NULL` + 플랫폼 지정 INSERT는 CHECK 위반"* 케이스가 **통과해 버려** 발견했다. 초판은 *"조건을 풀어 쓰면 구멍이 막힌다"* 고 적었으나 틀렸다 — 첫 항의 `watch_type = 'OTT'`가 NULL을 퍼뜨려 `NULL OR FALSE = NULL`이 되고, MySQL CHECK는 FALSE만 거부한다. **NULL 안전 비교 `<=>`** 로 정정. V19의 사전 점검 쿼리(`WHERE NOT (…)`)도 같은 이유로 그 행을 세지 못해 함께 고쳤다. **V19를 고치지 않고 V22를 연 것은 D-3 "적용 후 동결"의 첫 실전 적용**이다 — V19는 이미 로컬 두 DB에 적용됐고, 고치면 `repair`가 체크섬만 갱신해 로컬에는 옛 CHECK가 남아 환경마다 스키마가 갈라진다. 소셜 계정 연결(Part D)의 마이그레이션 번호는 V23 이후로 밀렸다 |
| 2026-10-01 | **Part D 추가 — 소셜 계정 연결(정책 확정, 세부는 착수 시).** 네이버·구글 로그인 추가를 검토하다 **현 스키마가 "user 하나 = 인증 수단 하나"** 라 같은 이메일의 두 번째 제공자 로그인이 거부되고, 그때의 에러 메시지(*"일반 회원가입으로 등록"*)가 사실과 다르다는 점을 확인했다. **계정 연결 허용**으로 정했다. 처음엔 V18~V21과 함께 넣는 안이 나왔으나, 비용이 테이블이 아니라 **인증 핵심 전체**(`chk_user_auth_method` 폐기·인증 흐름·테스트)에 있어 **첫 배포와 분리** — 대신 10월 우선순위 2번으로 올려 **Phase 2 동안 병렬 개발, Phase 5 이후 머지**로 확정 |
| 2026-10-01 | **신설 — Part A·B·C 확정.** 실서버 배포 직전 "추천·CineMap·소셜 외 미구현 기능" 점검에서 **프로필 수정이 placeholder**, **회원 탈퇴가 백엔드·앱 어디에도 없음**을 확인하고 설계를 닫았다. **탈퇴를 설계하다 `collection_movie → collection` RESTRICT 때문에 user 삭제가 실패하는 것을 발견** — 4-5(2026-07-23)가 *"원래는 CASCADE가 더 일관됐을 관계"* 라고 적고 서비스 순서로 우회했던 항목이다. 처음엔 탈퇴 서비스에서도 같은 순서 처리로 막으려 했으나 **"서비스로 해결하는 건 빈약하다"는 지적**을 받아 원래 전제를 다시 봤다 — 삭제 경로가 둘이 됐고, Flyway로 스키마 변경이 싸졌고, 서비스 순서 방식은 `clearAutomatically`로 이미 사고를 한 번 냈다. **같은 유형을 스키마 전체에서 점검해 2건을 추가로 찾았다**: `watch_record → ott_platform` SET NULL이 서비스가 금지하는 "OTT인데 플랫폼 없음"을 DB에서 만들어 내는 것, 참조 테이블 FK가 `country`만 RESTRICT이고 `genre`·`person`은 CASCADE라 장르 하나 삭제로 **가중치 합(1/N)이 깨지는** 것. 별점 CHECK(V21)는 *"`decimal`이면 충분하지 않나"* 를 검토 — `decimal(3,1)`은 형식만 정하고 7.5·0·99.9를 모두 받는다 — 해 비용이 한 줄이라 넣기로. **리뷰가 시청 기록과 독립인 것은 의도된 설계로 확인.** Part B는 security-spec L-13을 S3 + CloudFront(OAC) + 키 저장으로 닫았고, Part C는 즉시 완전 삭제 · 서버 삭제 후 앱 `unlink()` · 남은 Access Token은 L-15로 기록 |
