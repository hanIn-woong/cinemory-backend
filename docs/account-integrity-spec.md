# CineMory — 스키마 무결성 정리 · 프로필 사진 · 회원 탈퇴 스펙

> **이 문서는 새 세션이 컨텍스트 없이 착수할 수 있도록 쓴 인수인계 문서다.** 실서버 배포
> (`docs/deploy-spec.md`) 직전, *"추천·CineMap·소셜을 제외한 미구현 기능"* 을 점검하다 나온 세 덩어리를 묶었다.
>
> | Part | 내용 | 착수 조건 | 시점 |
> |---|---|---|---|
> | **A** | **스키마 무결성 정리 V18~V21** — FK 정책 3건 + CHECK 2건 | Flyway 도입(deploy-spec **1-4**) 완료 | **Phase 1 직후, Phase 3(데이터 이관) 전** — 운영 DB가 처음부터 V21로 시작한다 |
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

# Part A — 스키마 무결성 정리 (V18 ~ V21)

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

**사전 점검** (0이어야 한다 — 지금은 OTT 저장이 막혀 있어 0이 정상)

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

- ⚠️ **조건을 풀어 쓴다.** `(watch_type = 'OTT') = (ott_platform_id IS NOT NULL)`로 줄이면 `watch_type`이 NULL일 때
  결과가 NULL이고, **MySQL CHECK는 FALSE일 때만 거부하므로 그냥 통과**한다(`watch_type` NULL + 플랫폼 있음이 새어 나간다).
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

## A-3. 적용·검증 순서

1. 각 V 파일의 **사전 점검 쿼리를 `cinemory`·`cinemory_test`에서 먼저** 돌려 0 확인.
2. 앱 기동(로컬) → Flyway가 V18~V21 적용 → `flyway_schema_history`에 18~21 성공 4행.
3. `./gradlew test` (`cinemory_test`에도 자동 적용).
4. **빈 스키마**로 기동 → V17 baseline + V18~V21 연속 적용 → `validate` 통과(운영 초기화 경로).
5. **제약 테스트 추가** — `SchemaConstraintTest`(실 DB `cinemory_test`, 네이티브 쿼리):

   | 테스트 | 기대 |
   |---|---|
   | 영화가 담긴 컬렉션 `DELETE` | `collection_movie` 함께 삭제 (V18) |
   | `ott_platform` 행 `DELETE` (참조 기록 있음) | FK 위반 예외 (V19) |
   | `watch_type='THEATER'` + `ott_platform_id` 지정 INSERT | CHECK 위반 (V19) |
   | `watch_type=NULL` + `ott_platform_id` 지정 INSERT | **CHECK 위반** — 풀어 쓴 조건이 NULL 구멍을 막는지 (V19) |
   | 참조 중인 `genre` 행 `DELETE` | FK 위반 예외 (V20) |
   | `rating = 7.5` / `0` / `11` INSERT | CHECK 위반 (V21) |

6. 재덤프 → `docs/schema/cinemory_backup_v21.sql`, CLAUDE.md "진실의 원천" 경로를 v21로 갱신.
7. `CineMory_기획노트.md` 2-6 정책표가 실제와 맞는지 확인(이 문서와 함께 갱신됨).

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
| W-3 | 본인 확인 | **로컬: 비밀번호 재입력** / **카카오: 확인 다이얼로그만** | 로컬은 비밀번호 변경과 같은 패턴. 카카오 재로그인 강제는 과하다 |
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

> **정책만 확정(2026-10-01), 설계 세부는 착수 시 이 절을 채운다.** 10월 우선순위 2번 — 기획노트 4절.

## D-1. 왜 연결 구조가 먼저인가

지금 스키마는 **"user 한 명 = 인증 수단 하나"** 다 — `user.provider`·`provider_id` 컬럼이 하나씩이고 `email`이 전역 유니크다.
그래서 카카오로 가입한 사람이 **같은 이메일의 구글 계정으로 로그인하면 가입이 거부된다.** 게다가 지금 그 경우 나가는
`EMAIL_ALREADY_REGISTERED_LOCALLY`는 메시지가 *"일반 회원가입으로 등록"* 이라 **사실과도 다르다.** 두 번째 제공자를 붙이는 순간
드러나는 문제라, 제공자 추가와 **같은 작업 단위**로 연결 구조를 먼저 만든다.

## D-2. 확정

| # | 항목 | 결정 |
|---|---|---|
| S-1 | 정책 | **계정 연결 허용** — 한 사용자가 로컬·카카오·구글·네이버를 함께 가질 수 있다 |
| S-2 | 구조 | `user_social_account(id, user_id, provider, provider_id, created_at)` — `fk → user` **CASCADE**, `UNIQUE(provider, provider_id)`, `UNIQUE(user_id, provider)`(제공자당 하나). `user.provider`·`provider_id`·`uk_user_provider` 제거, 기존 카카오 사용자는 `INSERT … SELECT`로 이전 |
| S-3 | 시점·방식 | **V22 이후**(Part A 다음). Phase 2~3 동안 `feature/social-login`에서 개발 → **Phase 5 E2E(카카오만) 통과 후 머지** → CI 배포 → 제공자별 실기기 E2E |
| S-4 | 순서 | **계정 연결 리팩터링 → 구글 → 네이버.** 구글은 표준 OIDC라 위험이 낮아 연결 구조의 첫 검증 사례로 쓰고, 검증 방식이 불확실한 네이버를 마지막에 얹는다 |

**왜 Part A(V18~V21)와 함께 하지 않았나** — 테이블 생성은 파일 하나지만, 실제 비용은 **인증 핵심(Step S) 전체**에 있다:
`chk_user_auth_method`(provider ⇔ 비밀번호 배타)가 성립하지 않게 되고, 대신 필요한 *"인증 수단이 최소 하나"* 는 테이블을
넘나들어 CHECK로 표현할 수 없어 서비스 불변식이 된다. `signUpOAuth`·`oauthLogin`·`isOAuthUser()`를 쓰는 비밀번호 변경·재설정,
인증 테스트 다수가 함께 바뀐다. **배포 직전에 가장 위험한 영역**(deploy-spec — *"배포에서 깨지는 건 대부분 인증 경로"*)이라
첫 배포와 분리했다. 데이터 이전은 `INSERT … SELECT` 한 문장이라 운영 데이터가 쌓인 뒤에도 비용이 거의 늘지 않는다 — D-3에서
Flyway를 들인 이유 그대로다.

## D-3. 착수 시 정할 것

| # | 항목 | 현재 판단 |
|---|---|---|
| Q-1 | **연결 방식** | **로그인한 상태에서 직접 연결만**(추천). *"같은 이메일이면 자동 연결"* 은 **계정 탈취 경로**다 — 이메일을 검증하지 않는 제공자 계정으로 남의 계정에 들어갈 수 있다 |
| Q-2 | **네이버 검증 방식** | OIDC 엔드포인트(JWKS)는 공개돼 있으나 **공식 지원 범위·nonce·이메일 제공이 불분명**(2026-08 `naver/naveridlogin-API` #92). RN 라이브러리는 보통 **액세스 토큰**을 준다. **착수 첫날 확인** — 안 되면 *"액세스 토큰으로 프로필 API를 서버가 조회"* 하는 두 번째 검증 방식을 `OAuthIdTokenVerifier` 옆에 추가(nonce 미적용) |
| Q-3 | 구글 검증 | 표준 OIDC. ⚠️ `aud`는 Android 클라이언트 ID가 아니라 **웹 클라이언트 ID** — `allowed-audiences`를 목록으로 설계해 둔 것이 그대로 쓰인다. **`email_verified` 필수 확인** |
| Q-4 | 서명 키 등록 | 제공자마다 **개발·EAS 키(→ M5에서 Play 앱 서명 키)** 를 등록. 카카오 L-7과 같은 함정 — 빠뜨리면 그 빌드에서만 로그인이 깨진다 |
| Q-5 | 에러 코드 정리 | `EMAIL_ALREADY_REGISTERED_LOCALLY` → 제공자를 가리지 않는 코드·메시지로(예: *"이미 다른 방법으로 가입된 이메일입니다. 로그인 후 계정 연결을 이용해 주세요"*) |
| Q-6 | 연결 해제 | **마지막 인증 수단은 해제 불가**(서비스 불변식). 소셜 전용 사용자가 비밀번호를 추가할 수 있게 할지도 함께 |
| Q-7 | 회원 탈퇴 연동 | Part C W-5가 *"앱이 카카오 SDK로 unlink"* 다 — **연결된 제공자마다** unlink. 네이버 토큰 폐기는 클라이언트 시크릿이 필요해 서버 처리가 될 수 있다 |
| Q-8 | iOS (참고) | iOS 출시 시 제3자 로그인을 제공하면 **Apple 정책상 "Apple로 로그인"도 필수**. 지금은 Android 우선이라 해당 없음 |

---

## 진행 순서 요약

| 순서 | 작업 | 선행 |
|---|---|---|
| 1 | **Part A** V18~V21 + `SchemaConstraintTest` + 재덤프(v21) | deploy-spec 1-4 |
| 2 | deploy-spec Phase 2~3 (운영 DB는 V21로 시작) | 1 |
| 2-1 | **Part D** 소셜 계정 연결 → 구글 → 네이버 — Phase 2~3 동안 병렬 개발, **Phase 5 이후 머지** | Phase 1 머지 · Part A |
| 3 | **Part B** 인프라(B-2) → 백엔드(B-3) → 앱(B-4, 재빌드는 Phase 5 빌드에 포함) | Phase 2 |
| 4 | **Part C** 백엔드(C-2) → 앱(C-4) | 1, 3의 `MediaStorage` |
| 5 | W-7 웹 삭제 요청 페이지 | M5 |

---

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-10-01 | **Part D 추가 — 소셜 계정 연결(정책 확정, 세부는 착수 시).** 네이버·구글 로그인 추가를 검토하다 **현 스키마가 "user 하나 = 인증 수단 하나"** 라 같은 이메일의 두 번째 제공자 로그인이 거부되고, 그때의 에러 메시지(*"일반 회원가입으로 등록"*)가 사실과 다르다는 점을 확인했다. **계정 연결 허용**으로 정했다. 처음엔 V18~V21과 함께 넣는 안이 나왔으나, 비용이 테이블이 아니라 **인증 핵심 전체**(`chk_user_auth_method` 폐기·인증 흐름·테스트)에 있어 **첫 배포와 분리** — 대신 10월 우선순위 2번으로 올려 **Phase 2 동안 병렬 개발, Phase 5 이후 머지**로 확정 |
| 2026-10-01 | **신설 — Part A·B·C 확정.** 실서버 배포 직전 "추천·CineMap·소셜 외 미구현 기능" 점검에서 **프로필 수정이 placeholder**, **회원 탈퇴가 백엔드·앱 어디에도 없음**을 확인하고 설계를 닫았다. **탈퇴를 설계하다 `collection_movie → collection` RESTRICT 때문에 user 삭제가 실패하는 것을 발견** — 4-5(2026-07-23)가 *"원래는 CASCADE가 더 일관됐을 관계"* 라고 적고 서비스 순서로 우회했던 항목이다. 처음엔 탈퇴 서비스에서도 같은 순서 처리로 막으려 했으나 **"서비스로 해결하는 건 빈약하다"는 지적**을 받아 원래 전제를 다시 봤다 — 삭제 경로가 둘이 됐고, Flyway로 스키마 변경이 싸졌고, 서비스 순서 방식은 `clearAutomatically`로 이미 사고를 한 번 냈다. **같은 유형을 스키마 전체에서 점검해 2건을 추가로 찾았다**: `watch_record → ott_platform` SET NULL이 서비스가 금지하는 "OTT인데 플랫폼 없음"을 DB에서 만들어 내는 것, 참조 테이블 FK가 `country`만 RESTRICT이고 `genre`·`person`은 CASCADE라 장르 하나 삭제로 **가중치 합(1/N)이 깨지는** 것. 별점 CHECK(V21)는 *"`decimal`이면 충분하지 않나"* 를 검토 — `decimal(3,1)`은 형식만 정하고 7.5·0·99.9를 모두 받는다 — 해 비용이 한 줄이라 넣기로. **리뷰가 시청 기록과 독립인 것은 의도된 설계로 확인.** Part B는 security-spec L-13을 S3 + CloudFront(OAC) + 키 저장으로 닫았고, Part C는 즉시 완전 삭제 · 서버 삭제 후 앱 `unlink()` · 남은 Access Token은 L-15로 기록 |
