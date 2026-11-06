# CineMory — 알림 스펙 (M4, 백엔드)

> **이 문서는 새 세션이 컨텍스트 없이 착수할 수 있도록 쓴 인수인계 문서다.** 2026-11-06 알림 설계 세션에서 확정한
> 결정과 근거, API 계약, 서비스 변경 지점, 테스트를 담는다. 앱 화면은 `cinemory-app/docs/social-screen-spec.md` **8절(A-6)**.
>
> **계약은 구현 후 레이어 스펙으로 옮긴다** — 엔드포인트는 `controller-layer-spec.md`. 이 문서는 **결정 기록과 근거**로 남는다
> (`social-feed-spec.md`와 같은 위치).

| 단계 | 내용 | 머지 조건 |
|---|---|---|
| **N-1** | 백엔드 — Repository · Service · Controller, `FollowService`·`UserService.withdraw` 연결, 테스트 | `./gradlew test` 통과. **API 추가뿐이라 앱(N-2) 없이 배포해도 무해** |
| **N-2** | 앱 — 소셜 탭 종 아이콘 · 알림 화면 · 새 팔로워 칩 제거 (앱 `social-screen-spec.md` 8절) | N-1 운영 배포 후(`gen:api`) |

**일정** — 기능 동결 11/12(기획노트 4절 2026-10-15). **N-1이 11/10까지 `main`에 들어가지 못하면 M4를 접는다** — 기존 새 팔로워 칩(SF-16)만으로
맞팔로우 흐름은 성립한다. N-1만 머지되고 N-2를 못 하면 API만 남는다(무해, 칩 유지).

**착수 전에 읽을 것**

| 문서 | 왜 |
|---|---|
| `domain/notification/entity/Notification` | 엔티티는 v9부터 있다. `read` 필드명 정정(잔여 #8)은 **이미 반영됨** — 파생 쿼리 `…AndReadFalse` 사용 가능 |
| `domain/follow/service/FollowService` | 알림 생성·삭제 지점. `follow()`는 멱등(이미 팔로우면 조기 반환) |
| `account-integrity-spec.md` **C-2** | 탈퇴 트랜잭션 순서 — 알림 정리를 **5단계(`userRepository.delete`) 전에** 넣는다 |
| `social-feed-spec.md` **SF-3 · SF-16** | 피드는 합성, 알림은 저장 — 구분 근거(NT-2). 새 팔로워 수를 알림이 대체(NT-9) |

---

## 1. 확정 결정 (2026-11-06)

| # | 항목 | 결정 | 이유 |
|---|---|---|---|
| NT-1 | **범위** | **팔로우 알림(`FOLLOW`)만.** 댓글 알림·푸시 알림 없음 | 댓글은 캡스톤 범위 밖(SF-1, 신고·제재·개인정보 방침 선행 필요). 푸시는 development build 전환·FCM·기기 토큰 저장이 필요해 마감 안에 위험(기획노트 9절 보류 ⓔ) |
| NT-2 | **저장 방식** | **`notification` 테이블에 이벤트로 저장** — 피드처럼 `follow`에서 합성하지 않는다 | 알림은 "그때 일어난 일"이고 읽음 상태(`is_read`)를 가진다 — 원천 테이블에 없는 상태라 저장이 맞다. 피드는 "지금의 상태"라 합성(SF-3). **두 방식을 성격에 따라 나눠 쓴다.** 합성안(follow 역순 + 마지막 확인 시각)도 검토했으나 v9부터 설계해 둔 테이블과 `idx_notification_user_read`가 쓰이지 않은 채 남고, 읽음 상태를 별도 컬럼으로 다시 만들어야 해 기각(사용자 결정) |
| NT-3 | **대상 컬럼** | `FOLLOW`는 **`target_type`·`target_id`를 NULL**로 둔다 | 이동 대상(나를 팔로우한 사람)은 이미 `actor_id`다 — FK·`SET NULL`이 있는 컬럼이다. 같은 값을 FK 없는 다형 컬럼에 복사하면 고아 위험만 늘어난다. 다형 대상은 댓글 알림(출시 후) 몫으로 남긴다. **v9 설계 메모("팔로우 대상 `USER`")를 이 결정으로 대체** — `jpa-entity-spec.md` Step4 2)에 반영 |
| NT-4 | **언팔로우** | 같은 `(user, actor, FOLLOW)` 알림을 **읽음 여부와 무관하게 삭제** | 팔로우가 없어졌는데 "팔로우했습니다"가 남으면 사실과 다르다(피드의 "삭제는 조용히 사라짐"과 같은 원칙). 다시 팔로우하면 새 알림 1건 — **한 쌍당 알림은 항상 최대 1건**이라 중복 처리가 필요 없다 |
| NT-5 | **탈퇴** | 받는 사람 탈퇴 → FK `CASCADE`. **보낸 사람 탈퇴 → 그 사람이 보낸 알림 삭제**(탈퇴 트랜잭션 안에서) | 탈퇴는 즉시 완전 삭제(Part C W-1) — 흔적을 남기지 않는 방향과 맞춘다. `actor_id`의 `SET NULL`은 안전망으로 두고, 조회는 `actor` 내부 조인이라 NULL 행이 생겨도 보이지 않는다. **사용자 결정** |
| NT-6 | **읽음** | 알림 화면을 열면 **받은 최상단 id까지 일괄 읽음**(`POST /api/notifications/read { upToId }`). 개별 읽음 없음 | `GET`에 부수효과를 두지 않는다. `upToId` 경계라 목록 조회와 읽음 처리 사이에 도착한 알림이 안 본 채 읽음이 되지 않는다. 팔로우 알림은 항목마다 열어 볼 내용이 없어 개별 읽음은 가치가 없다 |
| NT-7 | **진입점** | **소셜 탭 헤더의 종 아이콘**(검색 돋보기 옆) + 안 읽은 수 배지. 탭바 배지 없음 | 팔로우 관계가 사는 곳이 소셜 탭이다. 탭바 배지는 앱 전역 주기 조회가 필요해 범위 밖. **사용자 결정** |
| NT-8 | **목록 항목** | 행위자 + **맞팔로우 여부(`following`)** — 앱이 행에서 바로 "맞팔로우" | 새 가입자 기본값이 FRIENDS(SF-12)라 맞팔로우가 소셜의 관문이다. 알림의 실제 쓰임이 맞팔로우다. 판정은 기존 `findFollowingIdsIn` 벌크 1쿼리 재사용 |
| NT-9 | **새 팔로워 수(SF-16)** | 앱은 칩을 없애고 `followersSeenAfter`를 보내지 않는다. **서버 파라미터·필드는 유지** | 서버 제거는 축소 변경 — 옛 앱 빌드가 보내도 깨지지 않게 한 릴리스 둔다(deploy-spec D-3 조건 6). **다음 릴리스 할 일**: `followersSeenAfter`·`newFollowerCount` 제거(잔여로 기록) |
| NT-10 | **페이징** | **키셋 커서**(`cursor` = 마지막 알림 id), `id DESC` | 새 알림이 맨 위에 쌓이므로 offset이면 페이지가 밀려 중복이 생긴다(잔여 #17과 같은 유형). id는 단조 증가라 `asOf` 없이도 안정적 |
| NT-11 | **문구 · 보관** | 문구 컬럼 없음(조회 시 `actor` + `type`, 앱이 조합) · **보관 기한 없음** | 문구 미보관은 v9 결정 그대로(닉네임 변경이 즉시 반영). 팔로우 알림은 한 쌍당 1건이라 증가가 팔로우 수에 묶인다 — 정리 작업은 댓글 알림 도입 시 함께 |

### 한계 (기록만)

- **팔로우/언팔로우 반복 시 매번 새 알림** — 같은 상대에게 알림이 다시 뜬다. 한 쌍당 1건이라 쌓이지는 않는다. 쿨다운은 출시 후 검토.
- **실시간 아님** — 소셜 탭에 들어올 때·화면 포커스 때 안 읽은 수를 다시 조회한다. 소켓·푸시는 범위 밖.

---

## 2. 스키마 — 변경 없음

v9 `notification` 그대로 쓴다. **마이그레이션 없음**(확장/축소 분류 대상 없음).

```sql
-- 참고 (cinemory_backup_v24.sql)
KEY idx_notification_user_read (user_id, is_read, id)   -- 안 읽은 수 · 읽음 처리
KEY idx_notification_target   (target_type, target_id)  -- 댓글 알림용(이번엔 미사용)
KEY fk_notification_actor     (actor_id)                -- 언팔로우·탈퇴 삭제
```

| 쿼리 | 인덱스 |
|---|---|
| 안 읽은 수 `user_id = ? AND is_read = 0` | `idx_notification_user_read` 앞 두 컬럼 — 정확히 맞는다 |
| 읽음 처리 `user_id = ? AND is_read = 0 AND id <= ?` | 같은 인덱스 — 세 컬럼 모두 |
| 목록 `user_id = ? AND id < ? ORDER BY id DESC` | `user_id` 접두로 내 알림만 읽고 정렬은 filesort. **팔로우 알림은 사용자당 팔로워 수만큼이라 작다** — 새 인덱스 없음. 댓글 알림 도입 시 `(user_id, id)` 추가 검토 |
| 언팔로우 `user_id = ? AND actor_id = ? AND type = 'FOLLOW'` | `user_id` 접두 |
| 탈퇴 `actor_id = ?` | `fk_notification_actor` |

---

## 3. API 계약 — 전부 인증 필수

`/api/notifications/**`가 `SecurityConfig`에서 인증 필요인지 확인한다(기본 `anyRequest().authenticated()`라면 추가 설정 없음). 컨트롤러는 `@AuthUser(required = true)`.

### 3-1. `GET /api/notifications` — 목록

| 파라미터 | 기본 | 설명 |
|---|---|---|
| `cursor` | 없음 | 직전 페이지 `nextCursor`(알림 id). 없으면 첫 페이지 |
| `size` | 20 | 1~50, 밖이면 400 `INVALID_INPUT_VALUE` |

```json
{
  "content": [
    {
      "id": 812,
      "type": "FOLLOW",
      "actor": { "userId": 37, "nickname": "영화광", "profileImage": "https://…", "following": false },
      "read": false,
      "createdAt": "2026-11-06T17:02:11"
    }
  ],
  "nextCursor": 790,
  "hasNext": true
}
```

- `NotificationPageResponse(List<NotificationResponse> content, Long nextCursor, boolean hasNext)` — 피드(`ActivityPageResponse`)와 같은 `hasNext` 관례.
- `NotificationResponse(Long id, NotificationType type, FollowUserResponse actor, boolean read, LocalDateTime createdAt)` — **`actor`는 기존 `FollowUserResponse` 재사용**(`following` = 조회자가 행위자를 팔로우 중인지, NT-8). 프로필 이미지는 `ProfileImageUrlResolver`로 해석.
- `hasNext`는 `size + 1`건을 읽어 판정, `nextCursor`는 반환한 마지막 항목의 id(`hasNext=false`면 `null`).
- ⚠️ 엔티티 boolean 필드는 `read`지만 Lombok 게터는 `isRead()` — record 컴포넌트명은 `read`로 둬 JSON 키를 `read`로 고정한다.

### 3-2. `GET /api/notifications/unread-count`

```json
{ "count": 3 }
```

`UnreadCountResponse(long count)`. `countByUserIdAndReadFalse`.

### 3-3. `POST /api/notifications/read` — 일괄 읽음

- 요청 `{ "upToId": 812 }` — `@NotNull @Positive`. 응답 **204**.
- `UPDATE notification SET is_read = 1 WHERE user_id = :me AND is_read = 0 AND id <= :upToId` — 남의 알림 id를 보내도 `user_id` 조건으로 영향 없음(404를 따로 내지 않는다, 멱등).

---

## 4. 백엔드 구현

### 4-1. 패키지 · 클래스 (`domain.notification`)

| 클래스 | 내용 |
|---|---|
| `repository/NotificationRepository` | `findPage(userId, cursor, Pageable)` — JPQL `select n from Notification n join fetch n.actor where n.user.id = :userId and (:cursor is null or n.id < :cursor) order by n.id desc` (`PageRequest.of(0, size + 1)`) · `countByUserIdAndReadFalse` · `markReadUpTo(userId, upToId)` · `deleteFollow(userId, actorId)` · `deleteByActorId(actorId)` |
| `service/NotificationService` | 조회(`getNotifications` · `getUnreadCount` · `markRead`) + 팔로우 연결 2개(`notifyFollow` · `removeFollow`) |
| `controller/NotificationController` | 3절 3개 |
| `dto/` | `NotificationResponse` · `NotificationPageResponse` · `UnreadCountResponse` · `NotificationReadRequest` |

- **벌크 쓰기 3개(`markReadUpTo` · `deleteFollow` · `deleteByActorId`)는 `@Modifying @Query`, `clearAutomatically` 쓰지 않는다** — Part C·4-6에서 앞선 미flush 변경을 폐기해 FK 위반을 낸 옵션이다. 기존 `deleteByTarget`과 같은 설정.
- `join fetch n.actor` — 내부 조인이라 `actor_id`가 NULL인 행(NT-5 안전망)은 자연히 빠진다. 목록 쿼리 2회(목록 + `findFollowingIdsIn`) 고정, N+1 없음.

### 4-2. `NotificationService` 팔로우 연결

```java
/** FollowService.follow()가 새 팔로우를 저장한 직후, 같은 트랜잭션에서 호출 */
@Transactional
public void notifyFollow(User recipient, User actor) {
    notificationRepository.save(Notification.builder()
            .user(recipient).actor(actor).type(NotificationType.FOLLOW)
            .build());                          // targetType·targetId는 NULL (NT-3)
}

/** FollowService.unfollow()가 실제로 행을 지웠을 때만 호출 */
@Transactional
public void removeFollow(Long recipientId, Long actorId) {
    notificationRepository.deleteFollow(recipientId, actorId);
}
```

- `@Builder`가 `targetType`·`targetId`를 받지만 FOLLOW는 넘기지 않는다. 엔티티 변경 없음.
- 의존 방향: `follow → notification`(단방향). `NotificationService`는 `FollowRepository`를 읽기 전용으로만 쓴다(`findFollowingIdsIn`) — 서비스끼리 순환하지 않게 `FollowService`를 주입하지 않는다.

### 4-3. 기존 서비스 변경 — 2곳

**① `FollowService`**

| 메서드 | 변경 |
|---|---|
| `follow()` | `followRepository.save(...)` 다음 줄에 `notificationService.notifyFollow(following, follower)`. 이미 팔로우 중이면 기존대로 조기 반환 → **알림도 없음**(멱등 유지). follower는 지금처럼 `getReferenceById` 프록시로 충분 |
| `unfollow()` | `long deleted = followRepository.deleteByFollowerIdAndFollowingId(...)`; `if (deleted > 0) notificationService.removeFollow(followingId, followerId)` |

- 동시 팔로우 두 번 → 두 번째가 `uk_follow` 위반으로 롤백되며 그 트랜잭션의 알림도 함께 롤백된다 — 알림 중복 없음. 기존 동작(두 번째 요청의 오류 응답)은 바꾸지 않는다.

**② `UserService.withdraw()` (account-integrity-spec C-2)**

3단계(댓글 정리) 바로 뒤에 **`notificationRepository.deleteByActorId(userId)`** — 반드시 5단계 `userRepository.delete(user)` **전**. 뒤에 두면 FK `SET NULL`이 먼저 실행돼 지울 행을 찾지 못한다. 받는 알림은 5단계 CASCADE가 지운다.

- 탈퇴 외에 `user`를 지우는 경로는 없다(2026-11-06 확인 — `userRepository.delete` 호출은 `withdraw` 한 곳).

---

## 5. 테스트 (`cinemory_test`, 실 DB)

| # | 시나리오 | 기대 |
|---|---|---|
| N-T1 | A가 B를 팔로우 | B의 알림 1건(`FOLLOW`, actor=A, target NULL, read=false), 안 읽은 수 1 |
| N-T2 | A가 B를 같은 요청으로 두 번 팔로우(멱등) | 알림 여전히 1건 |
| N-T3 | 자기 자신 팔로우 | `CANNOT_FOLLOW_SELF`, 알림 0 |
| N-T4 | A가 B 언팔로우(읽은 알림 · 안 읽은 알림 각각) | B의 해당 알림 삭제, **C가 B를 팔로우한 알림은 보존** |
| N-T5 | 팔로우하지 않은 상태에서 언팔로우 | 정상 종료, 알림 변화 없음 |
| N-T6 | 언팔로우 후 다시 팔로우 | 알림 정확히 1건(새 id, read=false) |
| N-T7 | 알림 45건, `size=20`으로 3페이지 | `id DESC`, 중복·누락 없음, 마지막 `hasNext=false`·`nextCursor=null` |
| N-T8 | 1페이지 조회 후 새 팔로우 발생 → 2페이지 | 중복 없음(키셋 커서) |
| N-T9 | B가 A를 이미 팔로우 중인 상태의 알림 | `actor.following = true`, 아니면 `false` |
| N-T10 | 알림 3건 중 `upToId` = 가운데 id로 읽음 | 그 이하 2건 read, 최신 1건 unread, 안 읽은 수 1 |
| N-T11 | 다른 사용자의 알림 id를 `upToId`로 | 204, 다른 사용자 알림 변화 없음 |
| N-T12 | **행위자 A 탈퇴** | A가 보낸 알림 전부 삭제(NULL로 남지 않음), 다른 행위자의 알림 보존 — `UserWithdrawalTest`에 추가 |
| N-T13 | 받는 사람 B 탈퇴 | B의 알림 0(CASCADE) |
| N-T14 | 비로그인 3개 API · `size=0`/`51` · `upToId` 누락 | 401 · 400 · 400 |

---

## 6. 범위 밖 (출시 후)

- 댓글 알림(`COMMENT_ON_COLLECTION`·`COMMENT_ON_REVIEW`) — 댓글 재개 시. 다형 대상 정리는 `deleteCollection`/`deleteReview`/탈퇴 3단계의 댓글 정리와 **같은 자리**(jpa-entity-spec Step4 경고).
- 푸시 알림 — `expo-notifications` + development build + FCM + 기기 토큰 테이블. 생성 지점은 4-2 `notifyFollow`에 얹는다.
- 탭바 배지 · 알림 삭제/설정 · 보관 정리 작업 · 팔로우 반복 쿨다운.
- `followersSeenAfter`·`newFollowerCount` 제거(NT-9 축소, 다음 릴리스).

---

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-11-06 | **신설 — NT-1~NT-11 확정.** M4 알림 설계 세션. 범위는 팔로우 알림만(댓글 범위 밖·푸시는 development build 필요). 저장 방식은 **합성안(follow 역순 + 확인 시각)과 테이블 저장안을 비교해 테이블 저장으로 결정**(사용자) — 알림은 읽음 상태를 가진 이벤트라 저장이 맞고, 피드(SF-3 합성)와 성격에 따라 나눠 쓴다. v9부터 설계한 테이블·인덱스를 그대로 쓰므로 마이그레이션 없음. 엣지 케이스는 정책 두 줄로 닫음: 언팔로우 시 삭제(한 쌍당 최대 1건), 행위자 탈퇴 시 삭제(사용자 결정 — Part C의 완전 삭제와 일치). FOLLOW의 다형 대상 컬럼은 NULL(v9 메모 "대상 USER" 대체 — FK 있는 `actor_id`와 중복이라 고아 위험만 늘린다). 진입점은 소셜 탭 헤더 종 아이콘(사용자 결정). 새 팔로워 칩(SF-16)은 앱에서 대체하고 서버 파라미터는 한 릴리스 유지. 동결(11/12) 역산으로 **N-1이 11/10까지 머지되지 않으면 M4를 접는다** |
| 2026-11-06 | **N-1 구현 완료.** `NotificationRepository`·`NotificationService`·`NotificationController`·DTO 4종, `FollowService.follow/unfollow` 연결, `UserService.withdraw` 5단계 앞 `deleteByActorId`. 스펙대로 구현했고 달라진 점 없음. 테스트 `NotificationApiTest` 12 + `UserWithdrawalTest` 2, 전체 `./gradlew test` 통과. 계약은 `controller-layer-spec.md` 5-19로 옮김. 남은 일: N-2(앱), 다음 릴리스에 `followersSeenAfter`·`newFollowerCount` 제거(NT-9) |
