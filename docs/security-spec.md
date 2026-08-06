# CineMory Spring Security 설계 스펙 (Step S)

Step4(Repository/Service) 완료 후 착수하는 인증·인가 설계. Step5(Controller)보다 먼저
진행하는 이유는, Controller 시그니처가 "인증된 호출자를 어떻게 받는가"에 직접 의존하기 때문이다.

**실제 코드는 Claude Code가 이 문서를 보고 작성**하며, 여기서는 패턴/시그니처/설계 결정만 명시한다.

---

## 진행 로드맵

| 순서 | 항목 | 설계 | 구현 |
|---|---|---|---|
| S-0 | 전제 및 파급 효과 점검 | ✅ | — |
| S-1 | 스키마 v9 델타 (`user.role`, `refresh_token`, `notification`) | ✅ | ✅ DB 적용 + 엔티티 3건 완료 |
| S-2 | 토큰 발급/검증 (`JwtTokenProvider`) | ✅ | ✅ 완료 (`JwtProperties`/`JwtTokenProvider`/`AuthUserPrincipal`/`ClockConfig`) |
| S-3 | 인증 흐름 (로컬 / 소셜 / 재발급 / 로그아웃) | ✅ | ✅ 완료 — 로컬·재발급·로그아웃은 HTTP 검증 통과, 소셜은 단위 검증 통과 (실토큰 E2E만 잔여) |
| S-4 | 필터체인 및 엔드포인트 접근 정책 | ✅ | ✅ 완료 (필터 배선 + `shouldNotFilter`) |
| S-5 | 인증 주체 주입 (`@AuthUser`) | ✅ | ✅ 완료 — S-F 검증에서 동작 확인(logout 204 / 미인증 401) |
| S-6 | 예외 응답 통일 | ✅ | ✅ 완료 (EntryPoint/AccessDeniedHandler/Writer) |
| S-7 | 기존 코드 영향 범위 | ✅ | ✅ 완료 (`TestController` 삭제 / `signUpOAuth` A-2 분기 + 반환 타입 `User`) |
| S-8 | 잔여 확인 항목 분류 | ✅ | — |
| S-9 | **착수 전 확정 결정** (A-1~A-7, B-1~B-5, C-1~C-3, D-1~D-2, E-1~E-3, **F-1~F-4**) | ✅ | ✅ **전 항목 반영 완료** — A-6은 Step5로 이관(철회), A-1·A-4 프론트/콘솔 몫만 코드 밖에 남는다 |
| S-G | 카카오 소셜 로그인 (+ **nonce**) | ✅ 확정 (S-9 **E-1~E-3**) | ✅ **완료** — S-G-1 nonce(12건) / S-G-2a JWKS(12+8건) / S-G-2b 검증기·엔드포인트(14+9건). **실토큰 E2E만 잔여** |
| S-10 | **비밀번호 재설정 구현 스펙** (S-J 상세) | ✅ 확정 (S-9 D-2 + **F-1~F-4**) | ✅ **완료** (S-J와 동일 범위) |
| S-J | 비밀번호 재설정 (SMTP + `password_reset_token`) | ✅ 확정 (S-10) | ✅ **완료** — 엔드포인트 3종 + `PasswordResetService` + 메일 인프라(19건). **실제 SMTP 발송 E2E만 잔여** |

> **구현 순서** — S-A(의존성 + `SecurityConfig` 골격) → S-B(엔티티 3건) → S-C(`JwtTokenProvider`)
> → S-D(필터 + 예외 핸들러) → S-E(`@AuthUser`) → S-F(`AuthService`) → S-G(카카오 로그인)
> → S-I(정리) → S-J(재설정 + SMTP). **`S-H`(비밀번호 변경)는 Step5로 이관됐다 — A-6 철회 참고.**
>
> **S-J까지 코드 완료** (2026-08-05). 테스트 **93건** 통과.
>
> | 단계 | 테스트 |
> |---|---|
> | S-C 토큰 발급/검증 | `JwtTokenProviderTest` 9 |
> | S-D 필터·에러 디스패치 | `SecurityErrorDispatchTest` 6 |
> | S-G-1 nonce | `OAuthNonceServiceTest` 12 |
> | S-G-2a JWKS | `CachingKakaoJwkSourceTest` 12 / `KakaoOAuthPropertiesTest` 8 |
> | S-G-2b 검증기·조율 | `KakaoIdTokenVerifierTest` 14 / `AuthServiceOAuthLoginTest` 9 |
> | **S-J 재설정** | **`PasswordResetServiceTest` 16 / `PasswordResetMailSenderTest` 3** |
>
> **남은 검증은 외부 연동 E2E 둘뿐이다.**
>
> - **카카오 실토큰** — 단위 테스트는 우리가 정한 값끼리 대조하므로
>   `application-secret.yml`의 **네이티브 앱 키가 실제 `aud`와 맞는지**는 실기기 로그인으로만 확인된다
> - **실제 SMTP 발송** — 같은 이유로 Gmail 앱 비밀번호와 STARTTLS 협상은 실제 발송으로만 확인된다.
>   `spring.mail.username`/`password`를 `application-secret.yml`에 넣어야 발송이 성립한다
>   (없어도 기동은 되고 **발송 시점에만 실패**한다)
>
> S-F까지의 HTTP 검증 결과는 아래 표 참고.
>
> | 검증 항목 | 결과 |
> |---|---|
> | 로그인 / 재발급 회전 | 200, 새 access·refresh 발급 및 RT 교체 확인 |
> | 회전 직후 유예 창 재요청(A-4) | 200 — 정상 사용자 강제 로그아웃 없음 |
> | **로그아웃 직후 재발급** | **401 `REFRESH_TOKEN_REUSED`** — v10의 핵심. `revokedReason != ROTATED`라 유예에서 제외된다 |
> | `@AuthUser` 로그아웃 | 본인 204 / 남의 refreshToken 403 `ACCESS_DENIED` / 미인증 401 `UNAUTHORIZED` |
> | `TOKEN_EXPIRED`(access-token-ttl PT5S 오버라이드) | 401 `TOKEN_EXPIRED` — `INVALID_TOKEN`과 분리 확인 |
> | 만료 토큰 헤더 + `reissue`(C-1) | 200 — 필터 제외라 재로그인/재발급이 막히지 않음 |
>
> DB `refresh_token.revoked_reason` 실측: `ROTATED` / `LOGOUT` / `REUSE_DETECTED`가 각각 제 자리에 기록됐고,
> 로그아웃된 행이 `LOGOUT`으로 남아 `revoke()` 멱등성(사유 미덮어쓰기)도 함께 확인됐다.
> TTL은 `--jwt.access-token-ttl=PT5S` 커맨드라인 오버라이드로 바꿨으므로 `application.yml`은 PT30M 그대로다.

---

## 이번 세션에서 결정한 사항

1. **토큰 = Access + Refresh, Refresh는 DB 저장** (회전 + 재사용 감지 포함)
2. **소셜 로그인 = 클라이언트 SDK가 받은 ID 토큰을 서버가 검증**하고 자체 JWT 발급
3. **관리자 권한 = `user.role` 컬럼 추가** (스키마 v9)
4. **팔로워/팔로잉 명단 = 현행 유지** (수는 공개, 명단은 공개범위 적용) — 4-6 잔여 항목 해소
5. **알림 기능 도입 = `notification` 테이블을 v9에 포함** — 4-6부터 이월된 미결 항목 해소

---

## S-0. 전제 및 파급 효과

### 의존성 추가

```gradle
implementation 'org.springframework.boot:spring-boot-starter-security'
implementation 'io.jsonwebtoken:jjwt-api:0.12.6'
runtimeOnly    'io.jsonwebtoken:jjwt-impl:0.12.6'
runtimeOnly    'io.jsonwebtoken:jjwt-jackson:0.12.6'
```

- 현재는 `spring-security-crypto`(BCrypt 전용)만 있다. 스타터를 추가하는 순간
  **전 엔드포인트가 기본 차단되고 폼 로그인/HTTP Basic이 자동 활성화**되므로,
  `SecurityConfig`에서 명시적으로 끄고 화이트리스트를 정의해야 한다.
- 자체 발급 JWT라 `oauth2-resource-server`(Nimbus) 대신 jjwt를 쓴다. 리소스 서버 스택은
  외부 발급 토큰을 검증하는 데 최적화돼 있어 자체 발급/회전 로직에는 오히려 우회가 많다.
- **`spring-boot-starter-oauth2-client`는 도입하지 않는다** (S-3 소셜 로그인 참고).

### ⚠️ `TestController` 처리 필요

`com.project.cinemory.TestController`가 `/test/movie`, `/test/movie/poster`를 인증 없이 열고 있다.
TMDB 토큰이 응답 본문에 노출되진 않지만, 인증 없이 호출 가능한 외부 API 프록시라
**TMDB 호출 쿼터를 임의로 소진시킬 수 있다.** 4-2 `MovieQueryService`로 역할이 대체됐으므로
**삭제를 권장**한다(별도 `chore` 커밋). 남긴다면 화이트리스트에서 제외해 인증 대상으로 둔다.

### 4-6에서 확정한 정책과의 관계 — 책임 분리

| 계층 | 책임 | 판정 대상 |
|---|---|---|
| Spring Security | **인증 여부** (당신이 누구인가) | 요청 자체 |
| `UserAccessPolicy` | **가시성** (그 사람의 데이터를 볼 자격이 있는가) | 조회 대상 사용자 |

`permitAll`은 "누구나 데이터를 본다"는 뜻이 **아니다.** 필터는 통과시키되 실제 가시성은
`UserAccessPolicy`가 판정한다. 이 분리를 지키면 Security 설정을 건드리지 않고도 공개범위
정책을 바꿀 수 있다.

---

## S-1. 스키마 v9 델타 (✅ 적용 완료)

**v8(19 테이블) → v9(21 테이블)**. v9 델타 파일은 커밋되지 않은 채 적용 후 삭제돼 남아 있지 않다 —
변경 내역은 아래 표와 `docs/schema/cinemory_backup_v10.sql`(현행 스냅샷)로 확인한다.

> 이후 **v10에서 `refresh_token.revoked_reason`이 추가**됐다. S-9 D-1 및 `docs/schema/v10-delta.sql` 참고.

| 변경 | 내용 | 근거 |
|---|---|---|
| `user.role` 추가 | `ENUM('USER','ADMIN') NOT NULL DEFAULT 'USER'` | 관리자 인가. 고정 폐쇄 집합이고 FK 참조가 없어 ENUM 컬럼으로 충분 |
| `refresh_token` 신설 | `token_hash`(SHA-256 hex, UNIQUE) / `expires_at` / `revoked_at` / `user_id` FK CASCADE | 토큰 회전·강제 로그아웃·재사용 감지 |
| `notification` 신설 | 수신자 `user_id`(CASCADE) + 행위자 `actor_id`(SET NULL) + 다형 target | 알림 기능. v9를 여는 김에 함께 반영 |

> `password_reset_token`은 SMTP 인프라 선행이 필요해 **v9에 넣지 않는다**. 도입 확정 시 v10 델타로 분리.

**핵심 설계 결정**

- **토큰 원문이 아니라 SHA-256 해시를 저장한다.** DB 유출 시 그대로 재사용 가능한 값이
  남지 않게 하기 위함. BCrypt를 쓰지 않는 이유는 리프레시 토큰이 이미 고엔트로피 랜덤값이라
  사전공격 대상이 아니고, **salt가 붙으면 인덱스 조회 자체가 불가능**해지기 때문이다.
- `revoked_at`으로 **soft revoke**. 하드 삭제하면 "이미 폐기된 토큰이 다시 왔다"는
  탈취 정황을 감지할 수 없다.
- UNIQUE는 `token_hash`에만 건다 → **다중 기기 동시 로그인 허용**.

> **2026-07-30 완료** — DB 적용 후 엔티티 3건(`User.role`/`RefreshToken`/`Notification`)을 구현하고
> `ddl-auto=validate` 기동으로 검증했다. 엔티티가 없는 테이블은 `validate` 대상이 아니므로,
> 세 엔티티를 만든 지금에야 v9 신규 테이블이 실제로 검증 범위에 들어왔다.
> 다만 `validate`는 **엔티티 → 스키마 단방향**이며 UNIQUE/FK/인덱스는 검증하지 않는다.

---

## S-2. 토큰 발급/검증

### 설정 (`application-secret.yml`)

```yaml
jwt:
  secret: <HS256용 256bit 이상 랜덤 문자열>
  access-token-ttl: PT30M    # 30분
  refresh-token-ttl: P14D    # 14일
```

- **HS256(대칭키)** 채택. 발급자와 검증자가 같은 단일 서버이므로 RS256의 키쌍 관리 비용이
  이득 없이 늘어난다. 인증 서버를 분리하게 되면 그때 RS256으로 전환한다.
- TTL은 `Duration`(ISO-8601)으로 받아 오타 시 기동 시점에 실패하게 한다.

### `JwtTokenProvider`

| 메서드 | 반환 | 비고 |
|---|---|---|
| `createAccessToken(Long userId, RoleType role)` | `String` | claims: `sub`=userId, `role`, `exp`, `iat` |
| `parseAccessToken(String token)` | `AuthUserPrincipal` | 서명/만료 검증 포함 |
| `createRefreshToken()` | `String` | **JWT가 아닌 고엔트로피 랜덤값**(256bit, Base64URL) |

**리프레시 토큰을 JWT로 만들지 않는 이유**: 어차피 DB를 조회해 유효성을 확인하므로 자체
검증 능력이 필요 없고, JWT로 만들면 페이로드에 불필요한 정보가 들어가 유출 시 노출면만 넓어진다.
불투명(opaque) 랜덤값이 더 안전하고 짧다.

**Access Token에 `role`을 넣는 이유**: 인가 판정마다 DB를 읽지 않기 위함. 대신 권한이 바뀌어도
Access TTL(30분)만큼 반영이 지연된다 — 관리자 승격이 실시간일 필요가 없어 수용 가능한 트레이드오프.

### `JwtTokenProviderTest`가 고정하는 불변식

HS256 알고리즘 고정 문제를 잡는 과정에서 만들어진 테스트다. **정리 대상으로 오해해 삭제하지 말 것** —
아래 결정들을 코드에서 고정하는 유일한 장치이고, 대부분 깨져도 컴파일은 통과한다.
Spring 컨텍스트와 DB를 쓰지 않는 순수 단위 테스트라 유지 비용도 없다.

| 테스트 | 고정하는 결정 | 깨졌을 때의 증상 |
|---|---|---|
| 발급 → 파싱 왕복 | claims 규약(`sub`=userId, `role`) | 클레임명이 바뀌면 즉시 실패 |
| **긴 secret에서도 헤더 `alg`가 HS256** | 키 길이와 무관한 알고리즘 고정 | 라운드트립만으로는 양쪽이 HS512여도 통과한다. 이 테스트만 잡는다 |
| 만료 직전 토큰은 유효 | TTL 경계 | 만료 판정이 한쪽으로 치우치면 실패 |
| Refresh Token에 `.`이 없다 | S-2의 "JWT가 아닌 불투명 값" | JWT로 바뀌면 실패 |
| 만료 → `TOKEN_EXPIRED` | **S-6의 `TOKEN_EXPIRED`/`INVALID_TOKEN` 분리** | `catch`를 한 줄로 합치면 컴파일은 통과하고, 증상은 **앱의 재발급 무한 루프**로만 나타난다 |
| 서명 불일치 → `INVALID_TOKEN` | **서명 검증이 실제로 수행됨** | 서명 미검증 파싱으로 바뀌어도 왕복 테스트는 통과한다. 이 테스트만 실패한다 |
| 비JWT 문자열 → `INVALID_TOKEN` | 라이브러리 예외가 500으로 누출되지 않음 | 잘못된 입력이 `BusinessException` 대신 500 |
| Refresh 43자 · 매번 다름 | S-2의 "256bit 불투명 랜덤값" | 인코딩·엔트로피 변경 시 실패 |
| 짧은 `secret` 거부 | **첫 로그인이 아니라 기동 시점에 실패** | 배포 후 첫 인증 요청에서야 발견 |

> 두 번째 항목이 가장 중요하다. S-9 프론트 계약(401 전역 처리)이 이 분리에 의존하므로,
> 서버에서 조용히 합쳐지면 앱 쪽에서 원인 불명의 루프로 드러난다.

**잔여 과제 2건 — ✅ 해소** (2026-07-30)

1. **HS256 고정 단정 추가.** 64바이트 secret(= `Keys.hmacShaKeyFor()`였다면 HS512가 선택됐을 길이이자
   운영 설정값의 길이)으로 발급한 토큰의 헤더 `alg`를 직접 확인한다.
2. **`Clock` 주입으로 `Thread.sleep` 제거.** `ClockConfig`가 `Clock` 빈을 노출하고
   `JwtTokenProvider`가 주입받는다. **jjwt 파서에도 `.clock(...)`을 지정**해야 만료 판정까지
   고정된 시간을 따른다 — 지정하지 않으면 파서가 시스템 시계를 쓴다.

> **남은 열린 질문** — 알고리즘 가드가 두 겹이다(`SecretKeySpec`의 JCA 이름 /
> `signWith`의 명시적 `Jwts.SIG.HS256` 인자). 위 테스트는 "고정됐다"만 확인할 뿐
> **어느 쪽이 실효인지는 구분하지 못한다.** `SecretKeySpec` 우회를 잠시 제거하고 테스트를 돌려보면
> 답이 나오며, 여전히 통과한다면 그 우회와 주석은 걷어낼 수 있다.

### `Clock`을 빈으로 두는 이유

도메인 규칙상 시각은 항상 인자로 주입받는다(`RefreshToken.isExpired(now)` / `revoke(now)`).
그런데 **그 인자를 만드는 쪽이 `LocalDateTime.now()`를 직접 호출하면 규칙이 무의미해진다.**
`Clock` 빈을 주입받아 시각을 얻도록 해 규칙을 실제로 성립시킨다. S-F의 `AuthService`도
만료 시각 계산과 유예 판정(A-4)에 같은 빈을 쓴다.

`Clock.systemDefaultZone()`을 쓰는 이유는 JPA Auditing(`@CreatedDate`)이 JVM 기본 시간대를
따르기 때문이다. 여기서만 시간대를 고정하면 같은 행의 `created_at`과 애플리케이션이 계산한
`expires_at`이 서로 다른 기준을 갖게 된다. 배포 환경 시간대는 `-Duser.timezone` 또는 `TZ`로
한 곳에서 맞춘다.

---

## S-3. 인증 흐름

### 로컬 로그인 — `POST /api/auth/login`

```
{email, password} → UserRepository.findByEmail
                  → passwordEncoder.matches
                  → Access + Refresh 발급 (refresh는 해시로 DB 저장)
```

- **OAuth 가입 유저는 `password_hash`가 null**이다. 이 경우에도 "소셜 계정입니다"가 아니라
  일반 로그인 실패와 **동일한 `INVALID_CREDENTIALS`(401)** 로 응답한다.
  응답을 구분하면 "이 이메일은 카카오로 가입돼 있다"는 정보가 유출된다.
- 이메일 미존재와 비밀번호 불일치도 같은 코드로 통일한다(계정 존재 여부 탐색 방지).

### 소셜 로그인 — `POST /api/auth/oauth/{provider}`

**2단계 흐름** (nonce 도입 확정 — S-9 E-1)

```
① POST /api/auth/nonce           → 서버가 nonce 발급·보관 후 반환
② 앱이 카카오 SDK 로그인 시 nonce 전달 → 카카오가 nonce를 담은 ID 토큰 발급
③ POST /api/auth/oauth/{provider} {idToken, nonce}
      → OAuthIdTokenVerifier: 서명(JWKS) / iss / aud / exp / nonce 검증
      → 대조 성공 시 nonce 즉시 소비(1회용)
      → UserService.signUpOAuth (기존 멱등 설계 그대로 재사용)
      → Access + Refresh 발급
```

**클라이언트 SDK 방식을 택한 이유**: React Native에서 서버 리다이렉트 기반 OAuth2는
브라우저 왕복과 딥링크 처리가 필요해 UX와 구현 모두 무겁다. 앱이 네이티브 SDK로 로그인하고
서버는 받은 ID 토큰만 검증하면, 4-1에서 이미 확정한 `signUpOAuth(멱등)` 설계와 그대로 맞물린다.

### nonce 처리 (S-9 E-1)

| 항목 | 확정 |
|---|---|
| 엔드포인트 | **`POST /api/auth/nonce`** — 아래 주의 참고 |
| 생성 | 256bit `SecureRandom` → Base64URL(패딩 없음). 리프레시 토큰과 같은 방식 |
| 저장 | **인메모리 Caffeine 캐시** (`expireAfterWrite`). 스키마 변경 없음 |
| TTL | 5분 (`auth.oauth.nonce-ttl`, `Duration`) |
| 소비 | 대조 성공 시 **즉시 제거**. 1회용이어야 재전송 방지가 성립한다 |
| 저장 형태 | 평문. 비밀이 아니라 일회성 대조값이고 인메모리에 짧게만 존재한다 |
| 실패 | `INVALID_NONCE`(401) — `INVALID_OAUTH_TOKEN`과 분리 |

**⚠️ 경로를 `/api/auth/oauth/nonce`로 두지 않는다.** 그러면 `POST /api/auth/oauth/{provider}`의
경로 변수와 겹쳐 `nonce`가 provider 이름처럼 보인다. Spring MVC는 정확 매칭을 우선하므로
동작 자체는 하지만 읽는 사람이 헷갈리고, provider가 늘어날 때 실수를 부른다.

**`INVALID_NONCE`를 분리하는 이유** — `TOKEN_EXPIRED`/`INVALID_TOKEN`을 나눈 것과 같은 논리다.
nonce 만료는 **"nonce를 다시 받아 로그인을 재시도할 상황"** 이고 ID 토큰 검증 실패는
**"로그인 자체가 실패한 상황"** 이라, 합치면 앱이 어느 쪽을 재시도해야 할지 알 수 없다.

**인메모리를 택한 이유** — 단일 인스턴스 전제이고 nonce는 5분짜리 일회성 값이라 영속화할 이유가 없다.
Caffeine은 `expireAfterWrite`로 스스로 만료시키므로 **정리 배치가 필요 없다** —
만료 리프레시 토큰 정리를 보류한 판단과 같은 맥락이다. 다중 인스턴스로 확장하면 Redis로 옮긴다.

> **C-1 연동** — `/api/auth/nonce`도 `PUBLIC_POST_ENDPOINTS`에 추가한다.
> 만료된 Access를 들고 재로그인하는 흐름이라 필터 제외 대상이며,
> 경로 상수를 공유하므로 `SecurityConfig`만 고치면 `shouldNotFilter`까지 함께 반영된다.

**검증 완료 (2026-08-02)** — `OAuthNonceServiceTest` 12건 통과 + 실제 HTTP 확인.

| 검증 항목 | 결과 |
|---|---|
| 인증 없이 `POST /api/auth/nonce` | 200, `{nonce, expiresIn:300}` — `PT5M` 설정 반영 확인 |
| nonce 형식 | 43자 Base64URL(`^[A-Za-z0-9_-]{43}$`), 패딩 없음. 1,000회 발급 충돌 0 |
| 1회용 소비 | 두 번째 소비는 `INVALID_NONCE` — **재전송 방지의 핵심** |
| 동시 소비 32스레드 | **정확히 1개만 성공** — `asMap().remove()` 원자성 확인 |
| TTL 만료 | 만료 후 `INVALID_NONCE` — Caffeine 자체 만료로 정리 배치 불필요함을 확인 |
| C-1 필터 제외 | 쓰레기·만료 Access 헤더가 있어도 200 |
| 잘못된 TTL(null/0/음수) | 기동 시점 `IllegalArgumentException` |

> **미검증** — nonce **소비 경로는 HTTP로 확인하지 못했다.** 소비 호출부인
> `POST /api/auth/oauth/{provider}`가 아직 없어 유닛 테스트로만 고정돼 있다.
> S-G 나머지를 구현할 때 발급 → 카카오 → 소비의 왕복을 실제로 한 번 태워볼 것.

**전략 인터페이스** — 4-6 `CommentTargetResolver`와 동일한 패턴을 재사용한다.

```java
public interface OAuthIdTokenVerifier {
    OAuthProvider supports();
    // 실패 시 BusinessException(INVALID_OAUTH_TOKEN / INVALID_NONCE / OAUTH_EMAIL_NOT_PROVIDED)
    OAuthUserInfo verify(String idToken, String expectedNonce);
}
```

- 구현체는 **`KakaoIdTokenVerifier` 하나만 만든다** (S-9 A-1). JWKS로 서명 검증
  - JWKS: `https://kauth.kakao.com/.well-known/jwks.json` — ID 토큰의 `kid`로 공개키를 찾아 검증하고,
    **공개키는 캐싱한다**(빈번한 요청은 차단될 수 있음).
    캐시 미스 시에만 재조회해 키 롤오버에 대응한다
  - 검증 항목 **4종**: `iss` == `https://kauth.kakao.com` / **`aud`** / `exp` / **`nonce`**
  - ⚠️ **`aud`는 로그인 플랫폼에 따라 값이 다르다.** 네이티브 앱 SDK로 로그인하면 **네이티브 앱 키**,
    웹에서 하면 REST API 키가 들어온다. 우리는 RN + 카카오 네이티브 SDK이므로
    **네이티브 앱 키로 대조해야 한다.** 설정은 허용 목록(`List<String>`)으로 두어
    나중에 웹 로그인을 붙일 때 키를 추가만 하면 되게 한다
  - 클레임 매핑: `sub` → `providerId`, `email`, `nickname`, `picture` → `profileImage`
    - `email` 없음 → `OAUTH_EMAIL_NOT_PROVIDED`(A-1 방어). **가입을 막는다**
    - `nickname` 없음 → **기본 닉네임 생성**(S-9 E-3). 가입을 막지 않는다
  - **`aud`는 배열로 와도 처리한다.** OIDC 표준상 문자열 또는 배열이며 카카오는 단일이지만,
    허용 목록과 교집합이 있으면 통과하도록 방어해 둔다
  - **clock skew 30초를 허용한다.** 카카오 서버와 우리 서버의 시계가 몇 초 어긋나면
    방금 발급된 토큰이 `exp`/`iat`에서 튕긴다. 시간 소스는 기존 `Clock` 빈을 그대로 물려
    테스트에서 고정 가능하게 한다
- `OAuthProvider` enum에는 **`KAKAO`만 정의한다.** 미구현 provider 값을 미리 넣어두면
  실패가 런타임까지 미뤄진다. 구글/애플은 구현체가 생기는 시점에 값을 함께 추가한다
- `List<OAuthIdTokenVerifier>`를 주입받아 **생성자에서** `EnumMap`으로 변환
  (Spring의 `Map` 자동 주입은 키가 빈 이름이라 enum 키로 쓸 수 없음 — 4-6과 동일한 이유)
- 미지원 provider는 `UNSUPPORTED_OAUTH_PROVIDER`(400)
- `OAuthUserInfo(providerId, email, nickname, profileImage)` — provider별 응답 차이를 여기서 흡수

### 소셜 로그인 처리 순서 (S-G-2)

```
POST /api/auth/oauth/{provider} {idToken, nonce}
  ① OAuthProvider.from(provider)        미지원 → UNSUPPORTED_OAUTH_PROVIDER(400)
  ② nonceService.consumeOrThrow(nonce)  실패 → INVALID_NONCE(401)
  ③ verifier.verify(idToken, nonce)     서명 / iss / aud / exp / nonce
  ④ userService.signUpOAuth(...)        기존 멱등 설계 + A-2 이메일 충돌 분기
  ⑤ issueTokens()                       TokenResponse
```

> **②를 ③보다 먼저 두는 것이 중요하다.** 순서를 바꾸면 ③이 실패할 때 nonce가 캐시에 남아
> **공격자가 같은 nonce로 토큰만 바꿔가며 반복 시도**할 수 있다. 소비를 먼저 하면
> 시도 1회당 nonce 1개가 강제된다. 검증 실패 시 사용자는 nonce를 새로 받아야 하는데,
> 실패한 시도의 nonce를 재사용하게 두면 안 되므로 그것이 의도된 동작이다.

### JWKS 처리 (S-9 E-2)

`global/infra/kakao` 패키지에 둔다 — `global/infra/kofic`과 같은 구조(`RestClient` 빈 +
`@ConfigurationProperties`)를 따른다.

| 항목 | 확정 |
|---|---|
| 조회 | `RestClient` (기존 `KoficClient` 패턴) |
| 캐시 | Caffeine, `kid` → `RSAPublicKey`. nonce와 같은 의존성 재사용 |
| 키 변환 | JWKS의 `n`/`e`(Base64URL) → `RSAPublicKeySpec` → `KeyFactory` — **JDK 표준 API만 사용** |
| 재조회 | **`kid` 캐시 미스 시에만.** 단 아래 쿨다운 적용 |
| 추상화 | **`KakaoJwkSource`를 인터페이스로 분리** — 테스트에서 자체 RSA 키쌍을 꽂는다 |

**⚠️ 재조회에 쿨다운(예: 1분)을 건다.** `kid` 미스 시 재조회하는 것은 키 롤오버 대응에 필수인데,
그것만 두면 **공격자가 아무 `kid`나 넣은 토큰을 반복 전송해 JWKS 조회를 무한 유발**할 수 있다.
카카오가 우리를 차단하면 소셜 로그인 전체가 죽는다. 최소 재조회 간격으로 막는다.

**Nimbus를 쓰지 않는 이유** — `nimbus-jose-jwt`에는 캐싱·재조회 제한이 내장된 JWK 소스가 있어
코드가 줄지만, S-0에서 배제한 `oauth2-resource-server`(필터 스택)와 달리 도입 자체가 결정 위반은
아니다. 그럼에도 직접 구현을 택한 이유는 **새 의존성이 0이고**(RestClient·Caffeine이 이미 있다)
**JDK 표준 API에만 의존해 버전 변동에 안전**하기 때문이다.

**S-G-2a 검증 완료 (2026-08-02)** — `CachingKakaoJwkSourceTest` 12건 + `KakaoOAuthPropertiesTest` 8건.

Mock이 아니라 **JDK 내장 `HttpServer`로 실제 HTTP를 태운다** — 카카오로 나간 요청 수를 정확히
세야 캐시·쿨다운이 실제로 동작함을 증명할 수 있기 때문이다. `Clock`은 주입식이라 쿨다운 경과를
`sleep` 없이 검증한다.

| 검증 항목 | 결과 |
|---|---|
| `kid` → 공개키 변환 | modulus·exponent가 원본 키와 일치 |
| **최상위 비트가 1인 modulus** | 양수로 복원 — `new BigInteger(1, …)`의 첫 인자를 고정 |
| 캐시 히트 | 3회 조회 → **HTTP 요청 1건** |
| 키 롤오버 | 새 `kid` 미스 → 재조회 후 연결 성공 |
| **재조회 쿨다운** | 임의 `kid` **50회** 전송 → **HTTP 요청 1건** |
| ↳ 대조군(쿨다운 0) | 같은 50회 → **50건** — 요청이 묶인 원인이 캐시가 아니라 쿨다운임을 증명 |
| 쿨다운 경과 후 | 다시 조회함 |
| 조회 실패(500) | 예외를 삼키고 **캐시된 키로 계속 동작** |
| 빈 응답 / RSA 아닌 `kty` / `kid` 누락 | `INVALID_OAUTH_TOKEN`, `kid` 누락 시 HTTP 요청 0건 |
| `allowedAudiences` 비어 있음 | 기동 시점 `IllegalArgumentException` — `aud` 검증 무력화 차단 |

> **쿨다운 대조군을 함께 둔 이유** — "요청 1건"만 단언하면 캐시 때문에 통과하는지 쿨다운 때문에
> 통과하는지 구분되지 않는다. 쿨다운을 0으로 둔 같은 시나리오가 50건을 내야 그 규칙이 실효임이 확정된다.
>
> **미검증** — 이 계층은 **공개키를 돌려주는 데까지**다. 서명 검증 / `iss` / `aud` / `exp` / `nonce`
> 대조는 S-G-2b(`KakaoIdTokenVerifier`)의 몫이며, 인터페이스를 분리해 둔 덕에 자체 RSA 키쌍으로
> 위조·불일치·만료 분기를 전부 태울 수 있다.

### 기본 닉네임 (S-9 E-3)

`user.nickname`은 `NOT NULL`이지만 **UNIQUE가 아니고 사용자가 나중에 변경할 수 있다**
(`changeNickname` 존재). 이메일과 성격이 다르므로 가입을 막지 않는다.

- 규칙: `"카카오사용자" + providerId 뒤 6자리` (50자 제한 안에 충분히 들어간다)
- **이메일은 막고 닉네임은 막지 않는 비대칭의 근거** — 이메일은 계정 식별자이자 `uk_user_email`의
  대상이라 대체값을 만들면 가짜 데이터가 UNIQUE 인덱스에 쌓인다(A-1에서 플레이스홀더를 배제한
  바로 그 이유). 닉네임은 표시용이고 대체·변경이 자유롭다

### 토큰 재발급 — `POST /api/auth/reissue`

```
{refreshToken} → SHA-256 해시로 조회
               → 없으면 REFRESH_TOKEN_NOT_FOUND
               → revoked_at != null 이면 REFRESH_TOKEN_REUSED (아래 참고)
               → expires_at 경과면 TOKEN_EXPIRED
               → 기존 토큰 revoke + 신규 Access/Refresh 발급 (회전)
```

**회전(rotation)**: 재발급 때마다 리프레시 토큰도 새로 발급하고 기존 것은 폐기한다.
탈취된 토큰의 유효 기간을 "다음 재발급 시점까지"로 좁힌다.

**재사용 감지**: 이미 폐기된 토큰이 다시 들어오면, 공격자와 정상 사용자 중 한쪽이 탈취된
토큰을 쓰고 있다는 뜻이다. 이때 **해당 유저의 모든 리프레시 토큰을 폐기**해 강제 재로그인시킨다.
정상 사용자가 불편을 겪을 수 있지만, 계정 탈취를 방치하는 것보다 낫다는 판단.

### 로그아웃 — `POST /api/auth/logout`

- 전달받은 리프레시 토큰을 revoke. Access Token은 **무효화하지 않는다.**
- 근거: Access를 즉시 무효화하려면 블랙리스트 저장소가 필요해 stateless 이점이 사라진다.
  TTL 30분이면 잔여 노출 시간이 짧아 캡스톤 범위에서는 수용 가능하다.

---

## S-4. 필터체인 및 엔드포인트 접근 정책

### `SecurityConfig`

```java
http
  .csrf(AbstractHttpConfigurer::disable)          // JWT + 무상태라 CSRF 토큰이 무의미
  .formLogin(AbstractHttpConfigurer::disable)     // 스타터 기본값 해제
  .httpBasic(AbstractHttpConfigurer::disable)
  .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
  .exceptionHandling(e -> e
      .authenticationEntryPoint(jwtAuthenticationEntryPoint)  // 401
      .accessDeniedHandler(jwtAccessDeniedHandler))           // 403
  .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
  .authorizeHttpRequests(...)
```

### 접근 정책 — 화이트리스트 방식

기본을 `authenticated()`로 두고 공개 경로만 열거한다. 반대(기본 공개 + 차단 열거)로 하면
엔드포인트를 추가할 때마다 보호를 빠뜨릴 위험이 생긴다.

아래는 **`requestMatchers`에 그대로 옮길 수 있는 최종 목록**이다(S-9 반영본).
중괄호 축약(`{records,collections}`)은 경로 매칭 문법이 아니라 경로 변수 캡처로 해석되므로
축약하지 말고 하나씩 열거해야 한다.

| 정책 | 메서드 | 경로 |
|---|---|---|
| `permitAll` | — | **`DispatcherType.ERROR`** — 아래 "에러 디스패치" 참고. 규칙 중 **맨 앞**에 둔다 |
| `permitAll` | POST | `/api/auth/signup`, `/api/auth/login`, **`/api/auth/nonce`**(S-9 E-1), `/api/auth/oauth/*`, `/api/auth/reissue` |
| **`authenticated`** | POST | **`/api/auth/logout`** — S-9 A-5로 화이트리스트에서 제외 |
| `permitAll` | GET | `/api/movies/**` (`/api/movies/{id}/reviews` 포함), `/api/theaters/**`, `/api/box-office/**` |
| `permitAll` | GET | `/api/comments` |
| `permitAll` | GET | `/api/users/*/profile` (프로필 헤더 — 비공개여도 노출) |
| `permitAll` | GET | `/api/users/*/records`, `/api/users/*/collections`, `/api/users/*/reviews`, `/api/users/*/wishes`, `/api/users/*/followers`, `/api/users/*/followings` |
| `hasRole('ADMIN')` | 전체 | `/api/admin/**` (박스오피스 수동 트리거, 극장 시드) |
| `authenticated()` | 전체 | **그 외 전부** |

> `permitAll`로 열린 조회 경로들은 **비로그인 = `viewerId == null`** 로 진입해
> `UserAccessPolicy`가 PUBLIC만 통과시킨다. 4-6에서 확정한 "null을 정상 입력으로 취급"이
> 여기서 실제로 동작한다.

### 에러 디스패치를 인가 대상에서 제외한다 (2026-08-02 추가)

**증상** — 유효한 Access Token을 들고 없는 경로를 호출해도 404가 아니라 **401**이 나왔다.
`GET /api/auth/nonce`(POST 전용)는 405가 아니라 401, `POST /api/auth/oauth/nonce`(핸들러 없음)도 401.
**API 전체에서 404 / 405 / 미처리 500이 전부 401로 덮이고 있었다.**

**원인** — 세 가지가 겹친다.

1. 핸들러가 없거나 메서드가 다르면 서블릿이 `/error`로 **ERROR 디스패치**를 한 번 더 건다
2. Spring Security 6+는 그 ERROR 디스패치에도 필터체인을 적용한다
3. 그런데 `JwtAuthenticationFilter`는 `OncePerRequestFilter`라
   **`shouldNotFilterErrorDispatch()`가 기본 `true`** — 에러 디스패치에서는 인증을 다시 채우지 않는다

결과적으로 `/error`가 빈 SecurityContext로 `anyRequest().authenticated()`에 걸려 401이 나가고,
원래의 404/405가 클라이언트에 도달하지 못한다. 클라이언트는 경로 오타를 **"세션 만료"로 오진해
A-3의 재발급/재로그인 분기를 잘못 탄다.**

**조치** — `authorizeHttpRequests`의 **첫 규칙**으로 다음을 둔다.

```java
.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
```

- **경로(`/error`)가 아니라 디스패치 타입으로 여는 이유** — 경로로 열면 외부에서 `GET /error`를
  직접 호출하는 것까지 함께 열린다. 그건 컨테이너가 만드는 내부 디스패치가 아니므로 계속 막는다
- **우회 경로가 생기지 않는 이유** — 인가 판정은 REQUEST 디스패치에서 이미 끝났고, 거부된 요청은
  `EntryPoint`가 직접 응답을 끝내 에러 디스패치까지 가지 않는다. 여기서는 에러 응답 렌더링만 한다
- **미인증 + 없는 경로는 여전히 401이다.** 인가가 핸들러 조회보다 먼저 돌기 때문이며,
  엔드포인트 존재 여부를 노출하지 않는다는 점에서 오히려 바람직하다

**회귀 테스트** — `SecurityErrorDispatchTest` 6건. MockMvc는 컨테이너의 ERROR 디스패치를
재현하지 않아 이 회귀를 잡지 못하므로 **`RANDOM_PORT`로 실제 톰캣을 띄워** 확인한다.

### `JwtAuthenticationFilter` 동작 규약 (S-D)

`OncePerRequestFilter`를 상속하고 `UsernamePasswordAuthenticationFilter` **앞에** 배치한다.
(폼 로그인을 껐어도 필터 순서 레지스트리의 위치 기준으로는 유효하다.)

**토큰 추출** — `Authorization: Bearer <token>`. 헤더가 없거나 `Bearer ` 접두사가 아니면
"토큰 없음"으로 취급한다(오류가 아니다).

**세 갈래 분기** — 이것이 필터의 전체 계약이다.

| 요청 | 동작 | 결과 |
|---|---|---|
| 토큰 없음 | SecurityContext를 비운 채 `doFilter` 계속 | `permitAll` 경로는 `viewerId == null`로 진입 / `authenticated` 경로는 뒤의 `AuthorizationFilter`가 401 |
| 토큰 유효 | `AuthUserPrincipal`로 인증 객체를 채우고 계속 | 정상 |
| **토큰 무효·만료** | **`EntryPoint.commence()`를 직접 호출하고 `doFilter`를 호출하지 않는다** | 401 (S-9 A-3) |

**⚠️ 세 번째 갈래에서 체인을 끊어야 하는 이유** — 오류만 기록하고 체인을 계속 태우면
`permitAll` 경로는 인가를 통과해 **200이 나가버려 A-3이 무력화된다.** 필터가 스스로 응답을
끝내야 한다. 이는 선택이 아니라 A-3의 귀결이다.

또한 필터가 던진 예외는 `ExceptionTranslationFilter`가 잡지 못한다 — 그 필터는 체인 **뒤쪽**에
있어 자기보다 하류에서 발생한 예외만 처리하기 때문이다. 그래서 "예외를 던진다"가 아니라
"`EntryPoint`를 직접 호출한다"로 규정한다. 응답을 쓰는 지점은 `EntryPoint` 한 곳으로 유지된다.

**`ErrorCode` 전달** — 필터는 판별한 `ErrorCode`(`TOKEN_EXPIRED` / `INVALID_TOKEN`)를 담은
전용 `AuthenticationException` 하위 타입을 만들어 `commence()`에 넘긴다.
`EntryPoint`는 그 타입이면 담긴 코드를, 아니면 `UNAUTHORIZED`를 쓴다.

**`shouldNotFilter` — auth 엔드포인트만 예외** (S-9 C-1)

```
/api/auth/signup, /api/auth/login, /api/auth/oauth/*, /api/auth/reissue
```

- `/api/auth/logout`은 **제외한다** (A-5로 인증이 필요하므로 필터를 타야 한다).
  `/api/auth/**` 통짜 패턴을 쓰면 안 되는 이유가 이것이다.
- 경로 목록은 `SecurityConfig`의 공개 POST 경로 **상수를 그대로 공유**한다.
  두 곳에 따로 적으면 엔드포인트 추가 시 목록이 갈라진다.
- 위 네 경로 외에는 **어떤 최적화 목적의 제외도 두지 않는다.** `permitAll` 조회 경로를
  건너뛰면 `viewerId`가 채워지지 않아 로그인 사용자에게 공개 데이터만 보이게 된다.

### 관리자 지정

`user.role`을 `ADMIN`으로 올리는 **API는 만들지 않는다.** 권한 승격 엔드포인트는 그 자체가
공격 표면이고, 캡스톤에서 관리자는 본인 1명이라 DB에서 직접 `UPDATE`하면 충분하다.

---

## S-5. 인증 주체 주입 — `@AuthUser`

```java
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthUser {
    boolean required() default false;   // S-9 C-2
}
```

- `@AuthUser Long viewerId` → 인증됐으면 userId, 익명이면 `null`
- `required = true`인데 익명이면 `UNAUTHORIZED`(401)

**기본값을 `false`로 두는 이유** — `authenticated()` 경로는 `AuthorizationFilter`가 이미
익명 요청을 막으므로 컨트롤러에 `null`이 도달할 수 없다. 반면 `permitAll` 조회 엔드포인트는
다수이고 전부 `null`을 정상 입력으로 받는다. 기본값이 `true`면 그 다수에 매번
`required = false`를 붙여야 해서, 흔한 쪽이 예외 표기를 지는 역전이 생긴다.

**리졸버 구현 요건**

- 지원 조건: **파라미터에 `@AuthUser`가 있으면 무조건 받는다.** 타입이 `Long`이 아니면
  `resolveArgument`에서 `IllegalStateException`으로 거부한다(구현 중 조정 — 아래 근거)
  - `supportsParameter`에서 타입까지 걸러 `false`를 반환하면, Spring이 그 파라미터를 요청
    파라미터 등으로 해석하려 들어 **엉뚱한 자리에서 실패**한다. 여기서 받아 명시적으로 거부하는
    편이 원인이 훨씬 빨리 드러난다
  - 특히 primitive `long`은 `null`을 담을 수 없어 "비로그인 = null" 계약과 애초에 양립하지 않는다
  - 클라이언트 잘못이 아니라 코딩 실수이므로 400이 아니라 500이 맞다
- 주입 타입은 `Long`만 지원한다. 권한이 필요한 경로는 `hasRole`로 이미 걸러지므로
  Controller가 `role`을 볼 일이 없다
- **`Authentication`이 `null`인지로 판정하면 안 된다.** `AnonymousAuthenticationFilter`가
  익명 요청에도 `AnonymousAuthenticationToken`(principal = `"anonymousUser"` **String**)을
  채우기 때문에 `authentication != null`은 항상 참이다.
  반드시 **`principal instanceof AuthUserPrincipal`** 로 판정한다
- 등록 위치: `global/config/WebConfig`(`WebMvcConfigurer`)에 `addArgumentResolvers`로 등록한다.
  인자 리졸버는 MVC 관심사라 `SecurityConfig`에 넣지 않는다.
  **등록을 빠뜨리면 예외 없이 조용히 `null`이 주입된다** — 이 단계의 첫 번째 실패 후보다

**`@AuthenticationPrincipal`을 쓰지 않는 이유**: 익명 요청의 principal은
`"anonymousUser"`라는 **String**이다. `@AuthenticationPrincipal CustomUserPrincipal p`로 받으면
타입 불일치라 null이 주입되긴 하지만, 이는 `errorOnInvalidType=false` 기본값에 기댄
"우연히 동작하는" 코드다. 4-6에서 **null을 정상 입력으로 설계**한 이상, null 가능성을
의도적으로 표현하는 전용 어노테이션이 낫다.

**Service 시그니처는 변하지 않는다.** Controller가 `@AuthUser`로 받아 기존
`(viewerId, targetUserId, …)` 파라미터에 그대로 넘기면 된다. 4-6-E에서 시그니처를 미리
정리해 둔 것이 여기서 회수된다.

---

## S-6. 예외 응답 통일

### ⚠️ 필터 단계 예외는 `@RestControllerAdvice`가 잡지 못한다

`GlobalExceptionHandler`는 DispatcherServlet **이후**에 동작한다. JWT 필터에서 던진 예외는
그 앞에서 발생하므로 핸들러에 도달하지 않고, 아무 처리를 안 하면 Spring Security 기본
HTML 에러 페이지가 나간다. 따라서 `AuthenticationEntryPoint` / `AccessDeniedHandler`에서
**직접 `ErrorResponse` JSON을 write**해 응답 포맷을 통일한다.

### 응답 작성 규약 (S-D)

| 항목 | 확정 |
|---|---|
| 직렬화 | **`tools.jackson.databind.json.JsonMapper` 주입** (S-9 C-3) |
| Content-Type | `application/json;charset=UTF-8` |
| 상태 코드 | `EntryPoint` → 401 / `AccessDeniedHandler` → 403 |
| 바디 | `ErrorResponse.from(errorCode)` — `GlobalExceptionHandler`와 동일 포맷 |

**⚠️ Boot 4의 Jackson** — Boot 4는 Jackson 3을 기본으로 쓰며 `JsonMapper`(`tools.jackson`)를
`@Primary`로 자동 구성한다. `com.fasterxml.jackson.databind.ObjectMapper`를 임포트해 주입하면
해당 타입의 빈이 없어 **기동이 실패**한다. `new ObjectMapper()`로 직접 만드는 것도 금지 —
설정이 자동 구성본과 갈라진다.

**charset을 명시하는 이유** — `ErrorCode`의 메시지가 한글이다. 생략하면 클라이언트에 따라
깨져서 도착한다.

**`AccessDeniedHandler`가 실제로 타는 경로** — 인증은 됐지만 권한이 모자란 경우, 즉
일반 유저의 `/api/admin/**` 호출이 사실상 유일하다. 익명 요청은 `AccessDeniedException`이 아니라
`EntryPoint`로 간다(Spring Security가 "로그인부터 하라"로 해석하기 때문).

### ErrorCode 추가분

| 상수 | HTTP | 용도 |
|---|---|---|
| `INVALID_CREDENTIALS` | 401 | 이메일 미존재 / 비밀번호 불일치 / OAuth 계정의 로컬 로그인 시도 (**전부 동일 응답**) |
| `TOKEN_EXPIRED` | 401 | Access 또는 Refresh 만료 |
| `INVALID_TOKEN` | 401 | 서명 불일치, 형식 오류 |
| `REFRESH_TOKEN_NOT_FOUND` | 401 | DB에 없는 리프레시 토큰 |
| `REFRESH_TOKEN_REUSED` | 401 | 폐기된 토큰 재사용 — 전체 세션 폐기 트리거 |
| `INVALID_OAUTH_TOKEN` | 401 | ID 토큰 검증 실패 |
| `UNSUPPORTED_OAUTH_PROVIDER` | 400 | 미지원 provider |
| `EMAIL_ALREADY_REGISTERED_LOCALLY` | 409 | **S-9 A-2** — 소셜 로그인 이메일이 로컬 가입 계정과 충돌 |
| `OAUTH_EMAIL_NOT_PROVIDED` | 400 | **S-9 A-1 방어** — 필수 동의 설정에도 ID 토큰에 `email` 클레임이 없는 경우 |
| `INVALID_NONCE` | 401 | **S-9 E-1** — nonce 만료·불일치·이미 소비됨. 클라이언트는 nonce를 다시 받아 재시도한다 |

> `UNAUTHORIZED`(4-6), `ACCESS_DENIED`(4-6), `INVALID_AUTH_METHOD`(4-1), `USER_NOT_FOUND`(4-1)은 기존 상수 재사용.

**`TOKEN_EXPIRED`를 `INVALID_TOKEN`과 분리하는 이유**: 클라이언트가 "재발급을 시도할 상황"과
"로그인 화면으로 보낼 상황"을 구분해야 한다. 하나로 합치면 앱이 매번 재발급을 시도하다
실패하는 루프에 빠진다.

---

## S-7. 기존 코드 영향 범위

| 대상 | 조치 |
|---|---|
| `User` 엔티티 | `RoleType role` 필드 추가(`@Enumerated(STRING)`). `createLocal`/`createOAuth`는 `USER` 고정 — 팩토리에서 권한을 받지 않는다 |
| `UserService` | `login(email, rawPassword)` / `changePassword(...)` 신규. **`signUpOAuth`는 변경 있음** — 이메일 충돌 사전 체크 추가(S-9 A-2). `signUpLocal`은 변경 없음 |
| `UserRepository` | 변경 없음 (`findByEmail` 이미 존재) |
| `PasswordEncoderConfig` | 유지. `SecurityConfig`로 옮기지 않고 그대로 둔다 — 이미 `UserService`가 참조 중이고 책임이 분리돼 있다 |
| Service 계층 전체 | **변경 없음.** 4-6-E에서 `(viewerId, targetUserId, …)` 시그니처를 이미 정리함 |
| `GlobalExceptionHandler` | 변경 없음 (필터 예외는 별도 핸들러가 담당) |
| `TestController` | ✅ 삭제 완료 (A-7) |

### 신규 패키지 구성

```
global/security
 ├─ SecurityConfig.java              ✅ 골격 완료 (필터 미배선)
 ├─ JwtProperties.java               ✅ 완료
 ├─ JwtTokenProvider.java            ✅ 완료
 ├─ JwtAuthenticationFilter.java
 ├─ AuthUserPrincipal.java           ✅ 완료
 ├─ RefreshTokenHasher.java          # SHA-256 hex 변환 전담
 ├─ handler/JwtAuthenticationEntryPoint.java
 ├─ handler/JwtAccessDeniedHandler.java
 └─ resolver/AuthUser.java, AuthUserArgumentResolver.java

domain/auth
 ├─ entity/RefreshToken.java         ✅ 완료
 ├─ repository/RefreshTokenRepository.java
 ├─ dto/  (LoginRequest, OAuthLoginRequest, TokenResponse, ReissueRequest)
 ├─ service/AuthService.java
 └─ service/oauth/OAuthIdTokenVerifier.java + KakaoIdTokenVerifier

domain/user/entity
 └─ RoleType.java                    ✅ 완료 (PrivacySetting과 동일 위치)

domain/notification/entity
 └─ Notification.java, NotificationType.java, NotificationTargetType.java   ✅ 완료 (엔티티만)
```

---

## S-8. 잔여 확인 항목

스키마 영향 여부를 기준으로 정리했다. 당시 DDL은 v9 델타에 반영됐으나 그 파일은 남아 있지 않고,
현행 스키마는 `docs/schema/cinemory_backup_v10.sql`이 기준이다.

| # | 항목 | 스키마 영향 | 상태 |
|---|---|---|---|
| 1 | 소셜 provider 우선순위 (카카오/구글/애플) | **없음** — `provider`/`provider_id`/`uk_user_provider`가 이미 있어 enum만 추가 | ✅ **카카오 확정** (S-9 A-1) |
| 2 | 비밀번호 **변경** (로그인 상태) | **없음** | ➡️ **Step5로 이관** — A-6 철회 (S-9 A-6 각주 참고) |
| 3 | 비밀번호 **재설정** (비로그인) | `password_reset_token` 테이블 | ✅ **v10 포함 확정** — SMTP 도입 결정. 구현은 S-J |
| 4 | 만료 리프레시 토큰 정리 | **없음** — `@Scheduled` 작업 | ⏸ **보류 확정** (S-9 A-4). 보고서에 한계로 명시 — Rate limiting과 동일 취급 |
| 5 | 댓글/팔로우 알림 | `notification` 테이블 | ✅ **v9 포함 확정** |
| 6 | Rate limiting | **없음** — 인메모리 처리 | 미도입 (보고서에 한계로 명시) |

### 판단 근거

- **알림(5)을 v9에 포함한 이유**: 기능 구현을 나중에 하더라도 어차피 v9를 여는 시점이다.
  나중에 추가하면 스키마를 한 번 더 열어야 한다.
  **단, 테이블만 만들어 둔 것이고 알림 도메인(Repository/Service)은 아직 설계하지 않았다.**
  Step S 구현이 끝난 뒤 별도 절로 진행한다 — 알림 생성 지점이 `FollowService.follow()`와
  `CommentService.createComment()` 안에 들어가야 해서, 기존 도메인 서비스에 손이 닿는다.
- **재설정(3)을 보류하는 이유**: 이메일 발송 인프라 없이는 테이블만 있고 동작하지 않는다.
  반면 **변경(2)**은 스키마 없이 지금 구현 가능하므로 먼저 넣는다.
- **정리 배치(4)를 MySQL EVENT로 하지 않는 이유**: `event_scheduler`가 기본 OFF라 서버 설정에
  의존하고, 정리 이력이 애플리케이션 로그에 남지 않아 추적이 어려우며, 비즈니스 로직이
  SoT 밖으로 새어 나간다. 4-7 스케줄러에 `@Scheduled` 작업으로 붙인다.
  단, `revoked` 이력을 재사용 감지에 쓰므로 만료 즉시가 아니라 **30일 유예 후** 삭제한다.
- **Rate limiting(6)을 테이블로 만들지 않는 이유**: 로그인 시도마다 쓰기가 발생해 DB에 부하를 준다.
  단일 인스턴스에서는 인메모리로 충분하고, 다중 인스턴스 확장 시 Redis로 옮기면 된다.

### ⚠️ 알림 도입 시 반복되는 문제 — 고아 알림

`notification`도 `comment`와 동일한 다형 참조(`target_type`/`target_id`, FK 없음) 구조다.
따라서 **4-6에서 겪은 고아 댓글 문제가 그대로 재현된다.** `Collection`/`Review` 삭제 경로에
댓글 정리와 **같은 자리에서** 알림 정리도 함께 호출해야 한다.

`comment`의 `TargetType`과 값이 다르다는 점도 주의 — 알림은 팔로우(대상=`USER`)를 포함하므로
enum을 재사용하지 말고 `NotificationTargetType`으로 분리한다.

---

## S-9. 착수 전 확정 결정 (✅ 확정)

S-0~S-8 설계를 구현 관점에서 재검토한 결과, "확정"으로 표시된 절에도 **케이스 자체가 비어 있어
구현을 시작하면 막히는 지점**이 7건 있었다. 결정 없이 넘기면 임의로 채워지고 되돌리기 비싼 것들이다.
아래 결정은 앞 절의 서술과 충돌할 경우 **S-9가 우선**한다.

이후 단계에서도 같은 방식으로 결정을 추가해 **A~F 24건**이 됐다.

### 결정 인덱스

**상세 서술은 각 절 본문에 있다.** 이 표는 "무엇을 언제 정했고 어디를 보면 되는지"의 진입점이다.

| # | 결정 | 상세 |
|---|---|---|
| A-1 | 카카오 이메일 **필수 동의** (비즈앱/본인인증 선행) | S-3 소셜 |
| A-2 | 로컬/소셜 이메일 충돌 → `EMAIL_ALREADY_REGISTERED_LOCALLY`(409) | S-3 · `service-layer-spec` 4-1 |
| A-3 | `permitAll`이어도 무효 토큰이면 **항상 401** | S-4 필터 규약 |
| A-4 | 회전 오탐 → 프론트 mutex + 서버 30초 유예 | S-3 재발급 |
| A-5 | `logout`만 `authenticated()` | S-4 화이트리스트 |
| A-6 | ~~비밀번호 변경을 Step S에~~ → **Step5로 이관** (철회) | S-9 A-6 각주 |
| A-7 | `TestController` 삭제 | S-0 |
| B-1 | authority에 **`ROLE_` 접두사 포함** | S-4 · `RoleType.authority()` |
| B-2 | CORS 설정 (`cinemory.cors.allowed-origins`) | S-4 |
| B-3 | `RoleType`은 `domain/user/entity` | S-7 |
| B-4 | `RefreshToken.user`는 **`@ManyToOne(LAZY)`** | `jpa-entity-spec` Step4 |
| B-5 | `jwt.secret`은 `application-secret.yml` | S-2 |
| C-1 | auth 4경로만 `shouldNotFilter` + **경로 상수 공유** | S-4 |
| C-2 | `@AuthUser.required` 기본값 **`false`** | S-5 |
| C-3 | 필터 단계 응답은 **`JsonMapper`**(Jackson 3) | S-6 |
| D-1 | `refresh_token.revoked_reason` — 유예는 `ROTATED`에만 | S-1 · S-3 |
| D-2 | SMTP 도입 + `password_reset_token` (v10) | S-10 |
| E-1 | 카카오 **nonce 검증 도입** | S-3 nonce 처리 |
| E-2 | JWKS **직접 구현** (Nimbus 미도입) | S-3 JWKS 처리 |
| E-3 | 카카오 `nickname` 부재 시 **기본값 생성** | S-3 기본 닉네임 |
| F-1 | **억제 판정을 미사용 토큰 삭제보다 먼저** | S-9 F-1 · S-10 ① |
| F-2 | 메일 발송을 트랜잭션 안에서, 실패 시 롤백 | S-9 F-2 · **S-11 한계 참고** |
| F-3 | 토큰 사전 검증 엔드포인트를 둔다 | S-10 ② |
| F-4 | 재설정 성공 후 자동 로그인하지 않는다 | S-10 ③ |

> **되돌린 결정 4건** — B-4(매핑 방식), A-6(단계 이관), 그리고 설계 중 철회한
> `replaced_by_id`·후속 토큰 추론. 앞의 셋은 **근거가 틀렸던** 경우, A-6은
> **근거가 충족돼 결정이 불필요해진** 경우다. 철회 사유의 성격이 다르므로 구분해 기록했다.

### 착수 전 결정 (A — 7건)

| # | 항목 | 결정 | 근거 |
|---|---|---|---|
| A-1 | 카카오 이메일 vs `email NOT NULL` | **비즈앱 전환(또는 개인 개발자 본인인증) 후 이메일 필수 동의** | 스키마·코드 변경이 0이다. 플레이스홀더 이메일은 `uk_user_email`에 가짜 데이터를 남겨 v10 비밀번호 재설정에서 터진다 |
| A-2 | 로컬 가입 이메일 == 소셜 이메일 | **`EMAIL_ALREADY_REGISTERED_LOCALLY`(409) 명시적 응답** | 로그인은 계정 존재 탐색을 막아야 하지만, 여기는 본인이 자기 계정으로 들어오려는 상황이라 알려줘야 한다 |
| A-3 | `permitAll` 경로의 무효 토큰 | **항상 401** (익명으로 강등하지 않음) | 조용한 강등은 "로그인했는데 안 보임"을 만들고 로그도 남지 않아 추적이 사실상 불가하다 |
| A-4 | 회전 + 동시 요청 오탐 | **클라이언트 mutex(프론트) + 서버 30초 유예(백엔드)** | 프론트 단독으로는 백엔드가 막을 수 없고, 서버 유예가 안전망이 된다 |
| A-5 | `/api/auth/**` 전체 `permitAll` | **`logout`만 `authenticated()`** 로 분리 | 로그아웃은 인증된 상태가 정상 흐름. 토큰 소유자 일치 검증도 가능해진다 |
| A-6 | 비밀번호 변경 | ~~Step S 범위에 포함~~ → **Step5로 이관** (2026-07-30 철회) | 아래 참고 |
| A-7 | `TestController` | **삭제** (별도 `chore` 커밋) | 인증 없는 TMDB 프록시라 호출 쿼터를 임의 소진시킬 수 있다. 4-2 `MovieQueryService`로 대체됨 |

#### A-6 철회 — 비밀번호 변경을 Step5로 이관 (2026-07-30)

A-6의 원래 근거는 **"변경 시 리프레시 토큰 전체 폐기가 필요한데 그 로직이 `AuthService`에
이미 생긴다"** 였다. v10 반영으로 `revokeAllByUserId(userId, now, reason)`와
`RevokedReason.PASSWORD_CHANGED`가 **이미 만들어졌으므로 그 근거는 충족됐고, 동시에
조기 구현의 이점도 사라졌다.** 남은 것은 호출뿐이다.

경로 의미로도 Step5가 맞다.

| | 상태 | 경로 | 소관 |
|---|---|---|---|
| 비밀번호 **변경** | 로그인 상태 | `/api/users/me/password` | `UserController` (**Step5**) |
| 비밀번호 **재설정** | 비로그인 | `/api/auth/password-reset/*` | `AuthController` (**S-J**) |

변경을 Step S에 두면 `/api/auth/password` 같은 어색한 경로가 생기거나 `UserController`를
조기에 만들어야 한다. 둘을 다른 단계에 두는 편이 도메인 경계와 일치한다.

> **S-J 구현 시 주의** — 재설정과 변경은 "비밀번호 갱신 + 세션 전체 폐기"라는 같은 로직을 공유한다.
> S-J가 먼저이므로 그때 `UserService`에 공통 내부 메서드를 두고,
> Step5의 변경은 거기에 "현재 비밀번호 검증"만 앞에 붙이는 형태로 간다.

#### A-1 보충 — 카카오 이메일의 제약

`account_email`은 **비즈니스 앱 전환 또는 개인 개발자 본인인증을 거쳐야 쓸 수 있는 동의항목**이고,
선택 동의로 두면 ID 토큰에 `email` 클레임이 아예 오지 않는다. 필수 동의로 설정하면 항상 들어오지만,
**사용자가 거부하면 로그인 자체가 불가**하다는 점을 UX로 수용한 결정이다.
또한 카카오 이메일은 **미인증(unverified) 상태일 수 있어** 로컬 가입 이메일과 신뢰 수준이 다르다 —
v10 비밀번호 재설정 도입 시 이 차이를 고려해야 한다.

#### A-3 보충 — optional 인증의 정확한 계약

`JwtAuthenticationFilter`는 세 경우를 구분한다.

| 요청 | 동작 |
|---|---|
| 토큰 없음 | SecurityContext를 비운 채 **통과** → `viewerId == null`로 진입 |
| 토큰 유효 | 인증 주체를 채우고 통과 |
| **토큰 있으나 무효/만료** | **401** — `permitAll` 경로여도 거부한다 |

세 번째가 이 결정의 핵심이다. 앱이 Access 만료 상태로 공개 목록을 조회하는 상황이 흔한데,
이때 조용히 익명으로 강등하면 사용자는 "로그인했는데 친구 공개 콘텐츠가 사라졌다"를 겪고
서버에는 아무 흔적도 남지 않는다. 401로 끊으면 클라이언트가 재발급 후 재시도하는 일관된 흐름이 된다.

#### A-4 보충 — 유예 판정 로직과 그 한계

```
해시로 조회 → 없으면 REFRESH_TOKEN_NOT_FOUND
expires_at 경과 → TOKEN_EXPIRED
revoked_at != null:
    ├─ revoked_reason == ROTATED 이고 now - revoked_at <= 30초
    │       → 유예. 재사용으로 보지 않고 새 토큰 쌍 발급
    └─ 그 외 → REFRESH_TOKEN_REUSED + 해당 유저 전체 폐기
정상 → 기존 revoke(ROTATED) + 새 토큰 쌍 발급 (회전)
```

> **`revoked_reason == ROTATED` 조건은 v10에서 추가됐다(S-9 D-1).** 이 조건이 없으면
> 유예 판정이 회전과 로그아웃을 구분하지 못해 **로그아웃 직후 30초간 같은 토큰으로
> 세션을 되살릴 수 있다.** v10을 연 직접적인 이유다.

**판정 순서** — 만료를 재사용보다 **먼저** 본다. 회전된 토큰은 시간이 지나면 "폐기됨 + 만료됨"이
되는데, 재사용을 먼저 판정하면 앱 저장소에 남은 오래된 토큰 하나 때문에 전체 세션이 끊긴다.
만료된 토큰은 어차피 쓸모가 없어 이 순서로 잃는 방어력이 없다.

**한계를 알고 쓸 것.** v9 `refresh_token`에는 "이 토큰이 무엇으로 대체됐는가"를 가리키는
자기참조 컬럼이 없어, 직전 발급분을 그대로 되돌려주는 정석 구현이 불가하다.
30초 창 안에서는 새 쌍을 한 번 더 발급하므로 **그 창 안의 탈취 토큰도 통과**한다.
근본 방어는 클라이언트의 재발급 직렬화이고 서버 측은 안전망이다. 보고서에 한계로 명시한다.
유예 시간은 `jwt.refresh-reuse-grace`(기본 `PT30S`)로 외부화한다.

### 명세 보강 (B — 5건)

| # | 항목 | 확정 내용 |
|---|---|---|
| B-1 | `role` → authorities | authority 문자열에 **`ROLE_` 접두사를 포함**한다(`ROLE_ADMIN`). `hasRole()`이 접두사를 자동 부착하므로 빠뜨리면 `/api/admin/**`이 전부 403이 되고 원인 추적이 까다롭다. `RoleType.authority()`가 전담 |
| B-2 | CORS | 스타터 추가 시 필터체인이 preflight까지 가로챈다. RN 네이티브는 무관하나 **Expo 웹/브라우저 테스트가 즉시 깨진다.** `SecurityConfig`에 `CorsConfigurationSource` 등록, 허용 오리진은 `cinemory.cors.allowed-origins`로 외부화 |
| B-3 | `RoleType` 위치 | **`domain/user/entity`** 로 확정 (기존 S-7의 병기 표기를 정리). `User`의 필드이므로 |
| B-4 | `RefreshToken` 연관 매핑 | **`@ManyToOne(LAZY) User`** 로 확정. 검토 과정에서 `Long userId`를 고려했으나, LAZY는 조인을 유발하지 않고 `getUser().getId()`도 프록시라 쿼리가 없다 — 이득 없이 CLAUDE.md의 "FK 보유 엔티티는 전부 단방향 `@ManyToOne(LAZY)`" 규칙만 깨는 선택이었다 |
| B-5 | `jwt.secret` 관리 | `application-secret.yml`(`.gitignore` 대상)에 둔다. 확인 완료. 배포 시에는 환경변수 주입으로 전환 |

### S-D·S-E 착수 전 확정 (C — 3건)

| # | 항목 | 결정 | 근거 |
|---|---|---|---|
| C-1 | A-3의 부작용 — 만료 토큰을 단 로그인/재발급이 401로 막힘 | **auth 4경로는 `shouldNotFilter`로 제외 + `SecurityConfig`의 경로 상수 공유** | 서버가 단독으로 보장하고 클라이언트 구현에 의존하지 않는다. 상수 공유로 목록 이중 관리를 원천 차단 |
| C-2 | `@AuthUser`의 `required` 기본값 | **`false`** (스펙 원문 `true`에서 변경) | `authenticated` 경로는 Security가 이미 막아 `null`이 도달할 수 없고, `permitAll` 조회가 다수다. 기본값이 `true`면 흔한 쪽이 예외 표기를 지게 된다 |
| C-3 | 필터 단계 응답의 JSON 직렬화 | **자동 구성된 `JsonMapper`(`tools.jackson`) 주입** | Boot 4는 Jackson 3이 기본이고 `JsonMapper`가 `@Primary`다. `com.fasterxml` `ObjectMapper`를 주입하면 빈이 없어 기동 실패 |

#### C-1 보충 — 이 예외가 안전한 이유

필터를 건너뛰면 SecurityContext가 빈 상태로 남지만, **인가는 이 필터가 아니라 체인 뒤쪽의
`AuthorizationFilter`가 판정**한다. 따라서 오류 방향이 fail-closed다.

- 제외 경로가 `permitAll`이면 → 원래도 통과였다. 새로 열리는 것이 없다
- 실수로 `authenticated` 경로를 제외 목록에 넣으면 → 컨텍스트가 익명이라 **거부(401)** 된다

또한 네 경로 모두 인증 주체를 쓰지 않는다. `reissue`조차 자격증명이 **body의 리프레시 토큰**이지
헤더의 Access가 아니다. A-3의 취지가 "가시성 판정이 있는 조회 경로에서 조용한 익명 강등을
막는 것"이므로, 판정 자체가 없는 auth 경로는 애초에 A-3의 적용 범위 밖이다.

> 다만 `login`/`reissue`는 rate limiting이 없는 상태 그대로다(S-8 #6). C-1이 이를 악화시키지는
> 않지만 — 토큰 없는 요청은 필터를 태워도 통과한다 — 무차별 대입 방어가 비어 있다는 사실은 변함없다.

### 스키마 v10 관련 확정 (D — 2건)

| # | 항목 | 결정 | 근거 |
|---|---|---|---|
| D-1 | 로그아웃이 유예 창 안에서 무효화되는 문제 | **`refresh_token.revoked_reason` 추가**, 유예는 `ROTATED`에만 적용 | `revokedAt`은 회전·로그아웃·재사용감지·비밀번호변경 **네 경로**에서 찍힌다. 시각만으로는 구분할 수 없다 |
| D-2 | 비밀번호 재설정 | **SMTP 도입 확정 → `password_reset_token`을 v10에 포함.** 구현은 S-J | 미도입 시 로컬 가입자가 락아웃되면 복구 경로가 없다. `chk_user_auth_method`(로컬 XOR OAuth) 때문에 소셜 경유 본인확인도 구조적으로 불가하다 |

#### 검토했으나 넣지 않은 컬럼

- **`replaced_by_id`(자기참조)** — 유예 창을 정석("직전 발급분을 그대로 반환")으로 바꾸려면
  토큰 **원문**이 필요한데 우리는 해시만 저장한다. 목적을 달성하지 못하므로 제외했다.
  **유예 창의 한계는 컬럼을 넣어도 남는다** — 근본 방어는 클라이언트 mutex다
- **`family_id`(토큰 계보)** — 재사용 감지 시 "전체 세션 폐기"는 S-3에서 의도적으로 내린 결정이다.
  기기별 범위 축소는 그 결정을 되돌리는 일이라 범위 밖

#### S-J 착수 전 정해둔 규칙 (스키마가 아니라 흐름) — **상세 설계는 S-10 참고**

- **이메일 열거 방지** — 재설정 요청은 계정 존재 여부·가입 방식과 무관하게 **항상 동일한 200**.
  메일은 조건에 맞을 때만 실제로 발송한다. 카카오 가입 계정에는 보내지 않는다
- **새 발급 시 해당 유저의 미사용 토큰 삭제** — 재발송을 반복해 유효 토큰이 누적되지 않게 한다.
  사용된 행(`used_at IS NOT NULL`)은 감사용으로 남긴다
- **재요청 억제** — 마지막 `created_at`이 N분(예: 3분) 이내면 발송을 건너뛴다.
  rate limiting 미도입 상태에서 **메일 폭탄을 막는 최소 장치**이고, 스키마 추가 없이 조회 하나로 된다
- **재설정 성공 시 리프레시 토큰 전체 폐기** — 사유 `PASSWORD_CHANGED`

### S-J 착수 전 확정 (F — 4건)

| # | 항목 | 결정 | 근거 |
|---|---|---|---|
| F-1 | **억제 판정과 미사용 토큰 삭제의 순서** | **억제 판정을 삭제보다 먼저** | 위 두 규칙이 충돌한다 — 아래 참고 |
| F-2 | 토큰 저장과 메일 발송의 트랜잭션 경계 | **트랜잭션 안에서 발송, 실패 시 롤백.** SMTP 타임아웃을 짧게 | 메일이 안 갔는데 토큰만 남으면 사용자가 재요청해도 억제에 걸려 아무것도 못 한다 |
| F-3 | 토큰 사전 검증 엔드포인트 | **둔다** (엔드포인트 3개) | 없으면 새 비밀번호를 다 입력한 뒤에야 만료를 알게 된다 |
| F-4 | 재설정 성공 후 자동 로그인 | **하지 않는다.** 로그인 화면으로 | 방금 비밀번호를 바꾼 상황이고, **전체 세션 폐기 결정과 모순**된다 |

#### ⚠️ F-1 — 확정해둔 규칙 두 개가 서로를 무력화한다

S-J 규칙 ②(**미사용 토큰 삭제**)와 ③(**마지막 `created_at`으로 재요청 억제**)이 부딪힌다.
②가 미사용 토큰을 지우면 ③이 볼 `created_at`도 함께 사라진다. 남는 것은 사용된 행뿐인데
그건 오래됐을 테니 **억제가 걸리지 않아 메일 폭탄을 막지 못한다.**

```
① 억제 판정   — 해당 유저의 최신 created_at 조회
② 미사용 삭제 — 통과했을 때만
③ 발급 + 발송
```

> **판정을 삭제보다 먼저** 두면 해결된다. nonce 소비를 ID 토큰 검증보다 먼저 둔 것과 같은 유형 —
> 각 규칙은 맞는데 조합 순서가 틀리면 한쪽이 조용히 무력화된다.
> S-C의 HS256 미고정, v10의 로그아웃 우회, S-G의 nonce 소비 순서에 이어 **네 번째**다.

#### F-2 보충 — 왜 롤백이 맞는가

메일 발송이 실패했을 때 토큰만 남기면 사용자는 메일을 못 받아 재요청하는데, **③ 억제에 걸려
아무것도 할 수 없는 상태**가 된다. 롤백하면 `created_at`도 함께 사라져 즉시 재요청이 가능하다.

트랜잭션 안에서 SMTP를 호출하므로 DB 커넥션이 발송 시간만큼 점유된다 —
`spring.mail.properties.mail.smtp.timeout`/`connectiontimeout`을 짧게 잡아 상한을 둔다.

### S-G 착수 전 확정 (E — 3건)

| # | 항목 | 결정 | 근거 |
|---|---|---|---|
| E-1 | 카카오 ID 토큰의 nonce 검증 | **도입한다.** nonce 발급 엔드포인트 + 인메모리 보관 + 1회용 소비 | 아래 |
| E-2 | JWKS 조회·캐싱·서명 검증 | **직접 구현** (`RestClient` + Caffeine + `KeyFactory`). Nimbus 미도입 | 새 의존성 0, JDK 표준 API만 사용해 버전 변동에 안전. 캐싱·재조회 쿨다운을 직접 제어 |
| E-3 | 카카오 `nickname` 부재 | **기본 닉네임 생성.** 가입을 막지 않는다 | `nickname`은 UNIQUE가 아니고 변경 가능하다. 이메일과 달리 대체값이 가짜 데이터로 남지 않는다 |

**판단 근거** — 비용이 지금은 작고, 나중에는 계단식으로 커진다.

- 서버 측 추가분은 발급 엔드포인트 하나와 인메모리 캐시뿐이다. **스키마 변경이 없다**
- 실제 비용은 **클라이언트 계약 변경**이다. 로그인이 1단계에서 2단계로 늘고 요청 본문이 달라진다.
  지금은 설치 기반이 0이라 로그인 화면 한 곳만 고치면 되지만, 스토어 배포 후에는
  구버전 앱을 위해 nonce 없는 경로를 함께 유지해야 하는 마이그레이션 창이 생긴다
- **카카오 ID 토큰의 수명은 약 2시간이다.** 재전송 창이 짧지 않아, nonce 없이는
  탈취된 ID 토큰이 그 시간 내내 로그인에 쓰일 수 있다. 초기 검토에서 "수명이 짧아 위험이 낮다"고
  본 것은 근거가 약했다
- 게다가 **"나중에"가 실제로 가능한 마지막 시점이 배포 전**이고, 지금이 그 창 안이다

> 카카오 공식 검증 항목은 `iss`/`aud`/`exp`/`nonce` 넷인데 설계에는 셋만 있었다.
> 스펙이 외부 문서의 요구사항을 부분적으로만 옮겨온 사례이므로, S-J의 SMTP 연동처럼
> **외부 스펙을 참조하는 절은 항목 누락 여부를 한 번 더 대조**할 것.

### 화이트리스트 작성 시 주의

`GET /api/users/*/{records,collections,reviews,…}` 같은 **중괄호 축약은 경로 매칭 문법이 아니다.**
경로 변수 캡처로 해석되므로 `requestMatchers`에는 하나씩 열거해야 한다.
`/api/auth/**`도 A-5 때문에 통째로 열 수 없으므로 `signup`/`login`/`oauth/*`/`reissue`만 개별 지정한다.

### 코드 외 선행 작업

- **카카오 개발자 콘솔** — 비즈앱 전환 또는 본인인증 → `account_email` 활성화 → **필수 동의** 설정
- **프론트(`cinemory-app`)** — axios 인터셉터 2건. 백엔드와 별개 작업이지만 없으면 A-3/A-4가 완성되지 않는다
  - 재발급 요청 **mutex** (A-4의 근본 해결책)
  - **401 전역 처리** — A-3에 따라 공개 조회 경로에서도 만료 토큰이면 401이 온다.
    `TOKEN_EXPIRED`면 재발급 후 재시도, `INVALID_TOKEN`이면 저장된 토큰을 지우고 로그인 화면으로.
    이 분기가 없으면 만료 시점에 공개 목록까지 함께 실패한다

---

## S-10. 비밀번호 재설정 (S-J 구현 스펙)

비로그인 상태의 "비밀번호를 잊었어요" 흐름. **로그인 상태의 변경(Step5)과는 다른 기능**이며,
경로 의미도 다르다 — 재설정은 비로그인이라 `AuthController` 소관이다.

확정 결정은 S-9 **D-2**(도입)와 **F-1~F-4**(흐름) 참고.

### 엔드포인트 3종

| 메서드 | 경로 | 인증 | 응답 |
|---|---|---|---|
| POST | `/api/auth/password-reset/request` | permitAll | **항상 200** (이메일 열거 방지) |
| POST | `/api/auth/password-reset/verify` | permitAll | 200 / `INVALID_RESET_TOKEN` |
| POST | `/api/auth/password-reset/confirm` | permitAll | 204 / `INVALID_RESET_TOKEN` |

> 세 경로 모두 `PUBLIC_POST_ENDPOINTS`에 추가한다. 상수 공유로 **C-1 필터 제외까지 자동 반영**되며,
> 이는 필수다 — 만료된 Access를 들고 재설정을 시도하는 흐름이기 때문이다.

### ① 요청 — `POST /api/auth/password-reset/request`

```
{email}
  ① 억제 판정: 해당 유저의 최신 created_at이 N분 이내면 → 아무것도 하지 않고 200 (F-1)
  ② 미사용 토큰 삭제
  ③ 토큰 발급 → 해시 저장 → 메일 발송  (같은 트랜잭션, 실패 시 롤백 — F-2)
  ④ 항상 200
```

**메일을 보내지 않는 경우에도 응답은 동일하다.**

| 상황 | 메일 | 응답 |
|---|---|---|
| 로컬 가입 계정 | 발송 | 200 |
| 존재하지 않는 이메일 | 미발송 | **200** |
| 카카오 가입 계정(`passwordHash == null`) | **미발송** | **200** |
| 억제 창 안 | 미발송 | **200** |

카카오 계정에 보내지 않는 이유는 재설정할 대상이 없기 때문이고, 응답을 구분하면
"이 주소는 소셜로 가입돼 있다"가 새어 나간다.

### ② 사전 검증 — `POST /api/auth/password-reset/verify` (F-3)

`{token}`만 받아 사용 가능 여부를 확인한다. **토큰을 소비하지 않는다.**
앱이 딥링크를 연 직후 호출해 "만료된 링크입니다"를 즉시 보여주기 위한 용도다.

### ③ 확정 — `POST /api/auth/password-reset/confirm`

```
{token, newPassword}
  ① 해시로 조회 → 없으면 INVALID_RESET_TOKEN
  ② isUsable(now) 아니면 INVALID_RESET_TOKEN  (사용됨 / 만료됨을 구분하지 않는다)
  ③ 비밀번호 변경 + markAsUsed(now)
  ④ 리프레시 토큰 전체 폐기 (PASSWORD_CHANGED)   ← 반드시 마지막
  ⑤ 204. 토큰을 발급하지 않는다 (F-4)
```

> **④를 마지막에 두는 이유** — `revokeAllByUserId`는 `REQUIRES_NEW`라 **별도 트랜잭션에서 커밋**된다.
> 먼저 호출하면 ③이 롤백돼도 폐기는 남아, **사용자는 로그아웃됐는데 비밀번호는 그대로**인
> 상태가 된다. 앞이 실패하면 폐기가 아예 일어나지 않도록 순서를 고정한다.

**사용됨과 만료됨을 구분하지 않는다** — 둘 다 "이 링크는 더 못 쓴다"이고,
구분하면 "이 토큰은 존재했다"는 정보가 새어 토큰 탐색에 쓰인다.

### 토큰 규약

`refresh_token`과 같은 골격을 그대로 따른다.

| 항목 | 값 |
|---|---|
| 생성 | 256bit `SecureRandom` → Base64URL(패딩 없음) |
| 저장 | **SHA-256 해시만.** `TokenHasher` 재사용 |
| TTL | 30분 (`auth.password-reset.ttl`, `Duration`) |
| 억제 창 | 3분 (`auth.password-reset.resend-cooldown`, `Duration`) |
| 소비 | `markAsUsed(now)` — 멱등, 최초 시각을 덮어쓰지 않는다 |

### 메일 발송

- `spring-boot-starter-mail` + `JavaMailSender`. 설정은 `global/infra/mail`
  (`global/infra/kofic`·`global/infra/kakao`와 같은 구조)
- Gmail 기준 `smtp.gmail.com:587` + STARTTLS + **앱 비밀번호**(2단계 인증 선행).
  자격증명은 `application-secret.yml`
- **타임아웃을 반드시 설정한다** — F-2에 따라 트랜잭션 안에서 발송하므로
  SMTP 지연이 DB 커넥션 점유로 이어진다
- 본문은 평문. 링크는 **커스텀 스킴 딥링크** `cinemory://reset-password?token=...`
  - 웹 폴백 페이지가 없어 유니버설 링크를 쓸 수 없다.
    **앱 미설치 시 링크가 동작하지 않는 것은 한계로 명시**한다
  - 원문 토큰이 URL에 담기지만 브라우저를 거치지 않아 히스토리·리퍼러 노출이 없다

### ErrorCode 추가분

| 상수 | HTTP | 용도 |
|---|---|---|
| `INVALID_RESET_TOKEN` | 400 | 미존재 / 만료 / 이미 사용됨 — **전부 동일 응답** |

비밀번호 형식 위반은 `@Valid`가 처리한다(`INVALID_INPUT`). 정책은 `SignUpLocalRequest`와
동일하게 8~64자를 재사용한다 — 가입과 재설정의 규칙이 다르면 사용자가 혼란스럽다.

### 구현 결과 (2026-08-05)

| 계층 | 산출물 |
|---|---|
| Controller | `AuthController`에 `password-reset/{request,verify,confirm}` 3개 추가 |
| DTO | `PasswordResetRequest` / `PasswordResetVerifyRequest` / `PasswordResetConfirmRequest` |
| Service | **`PasswordResetService` 신설** (`AuthService`와 분리) |
| Repository | `PasswordResetTokenRepository` — `findByTokenHash` / `findLatestCreatedAtByUserId` / `deleteUnusedByUserId` |
| 공통 | `UserService.updatePassword(User, raw)` + `User.changePassword(hash)` — **Step5 변경과 공유** |
| 메일 | `global/infra/mail` — `MailConfig` / `PasswordResetMailProperties` / `PasswordResetMailSender` |
| 설정 | `spring.mail.*`(타임아웃 포함) / `auth.password-reset.{ttl,resend-cooldown}` / `mail.password-reset.*` |

**`AuthService`에 넣지 않은 이유** — `AuthService`는 "자격증명 → 세션 토큰 발급"을 조율하는데
재설정은 **토큰을 발급하지 않는다**(F-4). 협력자 구성도 겹치지 않는다(메일·재설정 토큰 저장소).

**구현 중 확정한 것 2건**

- **메일 발송 실패의 응답** — F-2가 롤백을 요구하므로 예외를 삼킬 수 없고, 결과적으로
  이 경우만 200이 아니다(`EXTERNAL_API_ERROR` 502). 새 코드를 만들지 않고 기존 상수를 재사용했다.
  > **알려진 한계** — SMTP 장애 중에는 "로컬 가입 계정 → 502 / 미가입·소셜 → 200"으로 갈려
  > 그 시간 동안은 이메일 열거가 가능하다. 삼키면 사용자가 억제 창에 갇히므로(F-2) 롤백을 택했고,
  > 장애 상황에 한정된 노출이라 수용한다. 보고서에 한계로 명시한다.
- **`User.changePassword`가 OAuth 계정을 거부** — `chk_user_auth_method`(로컬 XOR 소셜) 위반을
  커밋 시점이 아니라 호출 시점에 막는다. 재설정 메일 자체가 소셜 계정에 나가지 않으므로
  정상 흐름으로는 도달하지 않는 방어선이다.

### 프론트 과제

- **딥링크 수신 설정** (Expo linking) — `cinemory://reset-password`
- 링크 진입 시 `verify` 먼저 호출 → 만료면 즉시 안내
- 재설정 성공 후 **로그인 화면으로** (자동 로그인 없음)

---

## S-11. 알려진 한계와 범위 밖 항목

Step S에서 **식별했으나 캡스톤 범위상 해결하지 않은** 항목이다. 각 절에 흩어져 있던 것을
여기 모았다 — 보고서의 "한계 및 향후 과제"에 그대로 옮길 수 있는 형태를 의도했다.

**"몰랐다"와 "알고 남겼다"는 다르다.** 아래는 전부 후자이며, 각 항목에 왜 남겼는지와
해결하려면 무엇이 필요한지를 함께 적었다.

### 미도입 — 의도적으로 넣지 않은 것

| # | 항목 | 영향 | 해결에 필요한 것 |
|---|---|---|---|
| L-1 | **Rate limiting 없음** | `login`·`reissue`·`password-reset/request`가 무제한 호출 가능. 무차별 대입 방어가 비어 있다 | 단일 인스턴스면 인메모리로 충분. 다중 인스턴스 확장 시 Redis (S-8 #6) |
| L-2 | **만료 리프레시 토큰 정리 배치 없음** | `refresh_token` 행이 계속 누적된다. 실사용 규모에선 문제되지 않으나 무한 증가 | `@Scheduled` 작업. 단 재사용 감지가 `revoked` 이력을 쓰므로 **만료 즉시가 아니라 유예 후** 삭제 (S-8 #4) |
| L-3 | **Access Token 즉시 무효화 불가** | 로그아웃해도 Access는 TTL(30분)까지 유효하다 | 블랙리스트 저장소. 도입하면 무상태 이점이 사라져 의도적으로 포기 (S-3 로그아웃) |

### 구조적 한계 — 현재 설계에서 완전히 닫히지 않는 것

| # | 항목 | 내용 |
|---|---|---|
| L-4 | **A-4 유예 창(30초) 안에서는 탈취 토큰도 통과** | 정석은 "직전 발급분을 그대로 반환"인데, **토큰 원문이 아니라 해시만 저장**하므로 돌려줄 값이 없다. `replaced_by_id`를 넣어도 해결되지 않는다(검토 후 제외). **근본 방어는 클라이언트 mutex**이고 서버 측은 안전망이다 |
| L-5 | **재설정 요청의 타이밍 사이드채널** | 아래 별도 항목 참고 |
| L-6 | **인메모리 상태의 단일 인스턴스 전제** | nonce 캐시와 JWKS 캐시가 프로세스 메모리에 있다. 다중 인스턴스로 늘리면 nonce는 **인스턴스 간 공유가 안 돼 로그인이 실패**하고, JWKS는 인스턴스마다 중복 조회한다. Redis 이전이 필요 |

#### L-5 — 응답 시간으로 계정 존재 여부가 드러난다

D-2에서 재설정 요청의 **응답 본문**을 항상 동일한 200으로 통일해 이메일 열거를 막았다.
그러나 F-2에 따라 메일 발송이 트랜잭션 안에 있어 **응답 시간이 갈린다.**

| 경로 | 실측 |
|---|---|
| 로컬 가입 계정 (메일 실제 발송) | **5,650ms** |
| 미가입 / 소셜 계정 / 억제 창 안 | **12~17ms** |

약 **376배** 차이라 타이밍만으로 "이 주소가 로컬 계정으로 가입돼 있다"를 판별할 수 있다.
억제 창(3분)도 방어가 되지 않는다 — **이메일당 한 번씩만 조회하면** 매번 느린 경로를 탄다.

**해결 방향** — 발송을 `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`로 빼면
응답 시간이 평준화된다. 다만 F-2의 원래 근거(발송 실패 시 토큰만 남아 억제에 걸려
사용자가 갇힌다)가 되살아나므로, **발송 실패 시 해당 토큰을 삭제**하는 처리가 함께 필요하다.
전용 스레드 풀과 `AsyncUncaughtExceptionHandler`도 필수다.

> 캡스톤 평가 비중을 고려해 **F-2를 유지하고 한계로 남긴다.** 해결 방향과 필요한 조치까지
> 확인된 상태이므로, 착수하면 스펙 변경 없이 구현만 하면 된다.

### 미검증 — 코드가 아니라 환경 때문에 확인하지 못한 것

| # | 항목 | 내용 |
|---|---|---|
| L-7 | **카카오 실토큰 E2E** | 단위 테스트는 우리가 정한 값끼리 대조하므로, `application-secret.yml`의 **네이티브 앱 키가 실제 `aud`와 맞는지**는 실기기 로그인으로만 확인된다. 선행: 콘솔 플랫폼 등록(Android 키 해시 / iOS 번들 ID) |
| L-8 | **딥링크 미설치 폴백 없음** | 재설정 링크가 커스텀 스킴(`cinemory://`)이라 **앱이 없으면 아무 일도 일어나지 않는다.** 웹 폴백 페이지가 없어 유니버설 링크를 쓸 수 없었다 |

### 배포 전 반드시 처리할 것

| # | 항목 | 이유 |
|---|---|---|
| L-9 | **카카오 이메일 필수 동의의 UX** | 사용자가 동의를 거부하면 **로그인 자체가 불가**하다(A-1). 안내 문구가 필요하다 |
| L-10 | **`jwt.secret` 환경변수 주입** | 현재 `application-secret.yml`. 배포 시 환경변수로 전환 |
| L-11 | **시간대 고정** | `Clock.systemDefaultZone()`과 JPA Auditing이 모두 JVM 기본 시간대를 따른다. `TZ` 또는 `-Duser.timezone`으로 **한 곳에서** 맞출 것 |

---

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-08-05 | **S-J 구현 완료 — 비밀번호 재설정 (테스트 93건 통과, 신규 19건).** 엔드포인트 3종 + `PasswordResetService`(신설) + `PasswordResetTokenRepository` + `global/infra/mail` 3건 + DTO 3건, `UserService.updatePassword`/`User.changePassword`(Step5 변경과 공유), `INVALID_RESET_TOKEN`, `PUBLIC_POST_ENDPOINTS` 3경로 추가(C-1 필터 제외 자동 반영). **테스트가 고정하는 것은 대부분 "단계의 순서"다** — 억제 판정이 미사용 삭제보다 먼저(F-1), 세션 폐기가 맨 마지막(REQUIRES_NEW라 앞당기면 "로그아웃됐는데 비밀번호는 그대로"), 사전 검증은 토큰을 소비하지 않음(F-3). 셋 다 뒤집혀도 컴파일과 나머지 테스트가 통과한다. **구현 중 확정 2건** — ① 메일 발송 실패는 `EXTERNAL_API_ERROR`(502)로 전파해 롤백시킨다. F-2가 롤백을 요구하므로 삼킬 수 없고, **SMTP 장애 중에는 로컬 계정만 502가 되어 그 시간 동안 이메일 열거가 가능하다는 한계를 수용**했다(삼키면 사용자가 억제 창에 갇힌다) ② `User.changePassword`가 OAuth 계정을 거부해 `chk_user_auth_method` 위반을 커밋 전에 막는다. **`SecurityErrorDispatchTest` 1건 수정** — "permitAll인데 핸들러 없음 → 404"를 `POST /api/auth/oauth/nonce`로 확인하고 있었으나 S-G에서 `{provider}` 매핑이 생기며 400으로 바뀌어 있었다(S-J와 무관한 기존 파손). 매핑이 생길 여지가 없는 다중 세그먼트 경로로 옮겼다 |
| 2026-08-04 | **S-I 문서 정리 — S-11(알려진 한계) 신설 및 S-9 결정 인덱스 추가.** 각 절에 흩어져 있던 한계·범위 밖 항목을 **L-1~L-11**로 모아 보고서에 그대로 옮길 수 있는 형태로 정리(미도입 / 구조적 한계 / 미검증 / 배포 전 필수 4분류). S-9에는 **A~F 24건의 결정 인덱스 표**를 추가해 "무엇을 정했고 상세가 어디 있는지"의 진입점을 만들었다 — 전면 재구조화 대신 내비게이션을 얹는 방식을 택한 이유는, 각 절 본문에 이미 상세 서술이 있어 중복이 심하지 않고 **재배치 과정의 정보 손실 위험이 이득보다 컸기** 때문이다. **L-5로 타이밍 사이드채널 기록** — 재설정 요청이 로컬 계정 5,650ms / 그 외 12~17ms로 **약 376배** 차이가 나 응답 본문을 통일한 이메일 열거 방지가 타이밍으로 뚫린다. 억제 창도 방어가 되지 않는다(이메일당 한 번씩만 조회하면 매번 느린 경로). 해결 방향(`AFTER_COMMIT`+`@Async`, 발송 실패 시 토큰 삭제, 전용 스레드 풀)까지 확인했으나 **캡스톤 평가 비중을 고려해 F-2를 유지하고 한계로 남긴다** |
| 2026-08-04 | **S-J 설계 확정 — S-10 신설 및 S-9에 F-1~F-4 추가.** 외부 스펙(Spring Boot Mail / Gmail SMTP) 원문 대조 결과 위험 표면이 작음을 확인(`spring-boot-starter-mail` + `JavaMailSender` + `jakarta.mail` 구조 유지, `smtp.gmail.com:587` + STARTTLS + 앱 비밀번호). **⚠️ 확정해둔 규칙 두 개의 충돌 발견(F-1)** — "새 발급 시 미사용 토큰 삭제"가 "마지막 `created_at`으로 재요청 억제"를 무력화한다. 삭제하면 판정 근거가 함께 사라져 **메일 폭탄을 막지 못한다.** 억제 판정을 삭제보다 먼저 두는 것으로 해결 — nonce 소비 순서와 같은 유형이며 **"각 규칙은 맞는데 조합이 틀린" 네 번째 사례**다. F-2: 메일 발송을 트랜잭션 안에 두고 실패 시 롤백(토큰만 남으면 재요청도 억제에 걸려 사용자가 갇힌다) + SMTP 타임아웃 필수. F-3: 사전 검증 엔드포인트를 둬 링크 진입 즉시 만료를 알린다(총 3개). F-4: 재설정 성공 후 자동 로그인하지 않는다(전체 세션 폐기와 모순). **세션 폐기를 맨 마지막에** — `REQUIRES_NEW`라 먼저 호출하면 비밀번호 변경이 롤백돼도 폐기만 커밋돼 "로그아웃됐는데 비밀번호는 그대로"가 된다. `INVALID_RESET_TOKEN`(400) 추가, 미존재·만료·사용됨을 구분하지 않는다 |
| 2026-08-02 | **S-G 구현 완료 — 카카오 소셜 로그인 (테스트 74건 통과).** S-G-2a JWKS 인프라(`KakaoOAuthProperties`/`Config`/`KakaoJwkSource`+`CachingKakaoJwkSource`/`JwkSetResponse`)와 S-G-2b 검증기·엔드포인트(`OAuthProvider`/`OAuthLoginRequest`/`OAuthUserInfo`/`OAuthIdTokenVerifier`/`KakaoIdTokenVerifier`, `AuthService.oauthLogin`, `POST /api/auth/oauth/{provider}`). **구현 중 조정 2건** — ① `UserService.signUpOAuth` 반환 타입을 `UserResponse` → **`User`**: Access Token에 담을 `role`이 `UserResponse`에 없다. `login()`과 대칭이고 서비스 간 호출이라 DTO 원칙에 어긋나지 않는다 ② `AuthService`에서 `@RequiredArgsConstructor` 제거 후 명시 생성자 — `List<OAuthIdTokenVerifier>` → `EnumMap` 변환이 필요(4-6 `CommentService`와 동일). **테스트를 JWT 빌더 대신 JDK `Signature`로 직접 조립** — 라이브러리 버전에 따라 빌더 API가 바뀌어도 흔들리지 않고, `aud` 배열·클레임 누락 같은 조작이 자유롭다. `AuthServiceOAuthLoginTest`는 **nonce 소비가 ID 토큰 검증보다 먼저**라는 순서를 고정한다 — 뒤바뀌어도 다른 테스트는 전부 통과하지만 같은 nonce로 반복 시도가 가능해져 nonce 도입 자체가 무력화된다 |
| 2026-08-02 | **S-G-2 설계 확정 (S-9 E-2·E-3) 및 카카오 `aud` 오기 정정.** 설명을 준비하며 카카오 문서를 재대조하다 **스펙의 `aud` 값이 틀렸음을 발견** — "REST API 키"로 적혀 있었으나 **네이티브 앱 SDK로 로그인하면 네이티브 앱 키**가 온다. 우리는 RN + 네이티브 SDK이므로 그대로 갔으면 **모든 소셜 로그인이 `INVALID_OAUTH_TOKEN`으로 실패**했을 것이고, 서명·`iss`·`exp`가 다 통과한 뒤 `aud`에서만 막혀 추적도 까다로웠을 유형이다. 허용 목록(`List<String>`)으로 정정. 같은 대조에서 **"ID 토큰 수명이 짧다"는 판단도 틀렸음**이 드러났다(약 2시간) — nonce 도입은 당시 근거보다 실제로 더 타당했다. **E-2**: JWKS는 Nimbus 대신 **직접 구현**(`RestClient`+Caffeine+`KeyFactory`) — 새 의존성 0, JDK 표준 API만 써 버전 변동에 안전. **`kid` 미스 시 재조회에 쿨다운 필수** — 없으면 임의 `kid` 토큰 반복 전송으로 JWKS 조회를 무한 유발해 카카오에 차단당할 수 있다. **E-3**: `nickname` 부재 시 기본 닉네임 생성(가입 허용) — 이메일과 달리 UNIQUE가 아니고 변경 가능해 대체값이 가짜 데이터로 남지 않는다. 처리 순서는 **nonce 소비를 ID 토큰 검증보다 먼저** — 반대면 검증 실패 시 nonce가 남아 같은 nonce로 반복 시도가 가능하다. `aud` 배열 허용과 clock skew 30초도 명시 |
| 2026-08-02 | **S-G-1(nonce 인프라) 구현 완료.** Caffeine 의존성, `OAuthNonceService`, `NonceResponse`, `INVALID_NONCE`, `POST /api/auth/nonce`, 화이트리스트 추가. `consumeOrThrow`는 **`asMap().remove()`의 원자성**에 의존해 동시 요청에도 정확히 하나만 성공하게 했다(조회 후 삭제로 나누면 둘 다 통과할 수 있다). `maximumSize`는 발급 엔드포인트가 `permitAll`이고 rate limiting이 없어 두는 메모리 방어. `/api/auth/nonce`를 `PUBLIC_POST_ENDPOINTS`에 넣어 **C-1 필터 제외까지 상수 공유로 자동 반영**. `OAuthNonceServiceTest` 12건 통과 |
| 2026-08-02 | **S-G 설계 확정 (S-9 E-1) — nonce 검증 도입.** 카카오 공식 검증 항목 4종 중 `nonce`가 설계에서 빠져 있던 것을 발견하고 도입을 확정했다. 서버 추가분은 발급 엔드포인트와 인메모리 캐시뿐이라 **스키마 변경이 없고**, 실제 비용인 클라이언트 계약 변경도 **설치 기반이 0인 지금은 로그인 화면 한 곳**이다 — 배포 후에는 구버전 호환 창이 생겨 계단식으로 커지므로 지금이 마지막 시점이라는 판단. 경로는 `/api/auth/oauth/{provider}`의 경로 변수와 겹치지 않도록 **`/api/auth/nonce`** 로 분리했고, `PUBLIC_POST_ENDPOINTS`에 추가돼 C-1 필터 제외까지 자동 반영된다. `INVALID_NONCE`(401)를 `INVALID_OAUTH_TOKEN`과 분리 — nonce 만료는 "다시 받아 재시도", 토큰 실패는 "로그인 실패"로 클라이언트 분기가 다르다(`TOKEN_EXPIRED`/`INVALID_TOKEN` 분리와 같은 논리). 저장소는 Caffeine `expireAfterWrite`로 두어 **정리 배치가 필요 없다** |
| 2026-08-02 | **S-F 검증 완료 — 누적 부채 5건 해소.** `./gradlew test` 13건 통과, 실제 HTTP로 로그인·회전·유예 창·`@AuthUser`·`TOKEN_EXPIRED`·C-1 필터 제외를 확인했다. **로그아웃 직후 재발급이 401 `REFRESH_TOKEN_REUSED`로 차단되어 v10(D-1)이 목적을 달성**했고, DB `revoked_reason` 실측으로 `ROTATED`/`LOGOUT`/`REUSE_DETECTED`가 제 자리에 기록되며 `revoke()` 멱등성도 함께 확인됐다. 환경 이슈 1건 — `bootRun`은 별도 JVM을 fork하므로 래퍼를 외부에서 죽이면 자식이 8080을 쥔 채 남는다(코드 무관). **고아 프로세스가 옛 코드로 응답해 테스트 결과를 오도할 수 있으므로** 기동 성공 로그를 확인한 뒤 요청할 것 |
| 2026-07-30 | **A-6 철회 — 비밀번호 변경을 Step5로 이관.** 원래 근거("세션 폐기 로직이 `AuthService`에 어차피 생긴다")가 v10 반영으로 **충족되면서 동시에 조기 구현의 이점이 사라졌다** — `revokeAllByUserId(..., reason)`와 `PASSWORD_CHANGED`가 이미 존재하므로 Step5에서 호출만 하면 된다. 경로 의미로도 변경은 로그인 상태라 `UserController`, 재설정은 비로그인이라 `AuthController` 소관이어서 단계를 나누는 편이 도메인 경계와 일치한다. 이로써 **Step S는 S-G → S-I → S-J만 남는다.** 또한 **S-F 코드 완료**(v10 반영분 포함 — `RevokedReason`, `revoke(now, reason)`, `TokenHasher` 리네임, `revokeAllByUserId` 파라미터 확장, `PasswordResetToken` 엔티티, `BoxOfficeRecord.openDate`). 엔티티 컬럼을 v10 델타와 대조해 차이 0 확인. **검증은 미실행 상태로 남아 있다** |
| 2026-07-30 | **스키마 v10 확정 및 적용 (21 → 22 테이블), S-9에 D-1·D-2 추가.** S-F 검증 중 **로그아웃이 유예 창 안에서 무효화되는 문제**를 발견 — `revokedAt`은 회전·로그아웃·재사용감지·비밀번호변경 네 경로에서 찍히는데 유예 판정이 시각만 보고 있어, 로그아웃 직후 30초간 같은 토큰으로 세션을 되살릴 수 있었다. `revoked_reason` 추가로 유예를 `ROTATED`에만 적용해 해결(D-1). 함께 검토한 **`replaced_by_id`는 제외** — 유예를 정석("직전 발급분 반환")으로 바꾸려면 토큰 원문이 필요한데 해시만 저장하므로 목적을 달성하지 못한다. **유예 창의 한계는 컬럼을 넣어도 남으며 근본 방어는 클라이언트 mutex라는 결론이 바뀌지 않는다.** `family_id`도 제외(전체 폐기는 S-3의 의도적 결정). **SMTP 도입을 확정**해 `password_reset_token`을 v10에 포함하고 S-J로 절을 신설(D-2) — 미도입 시 로컬 가입자 락아웃에 복구 경로가 없고, `chk_user_auth_method` 때문에 소셜 경유 본인확인도 불가하다. `box_office_record.open_date`도 함께 반영(4-7 재매칭 복원). **테이블 수 표기 정정** — 문서 전반의 v8=18/v9=20은 오기이며 실제는 v8=19/v9=21이다 |
| 2026-07-30 | **S-C 잔여 2건 해소.** ① 헤더 `alg` 단정 추가 — 64바이트 secret(운영 설정값 길이이자 `Keys.hmacShaKeyFor()`였다면 HS512가 됐을 길이)으로 발급해 확인. **다만 가드가 두 겹이라 어느 쪽이 실효인지는 여전히 미확인** — `SecretKeySpec` 우회를 제거하고 돌려보면 판별되고, 통과하면 걷어낼 수 있다 ② **`ClockConfig` 신설 + `JwtTokenProvider`가 `Clock` 주입** — `Thread.sleep(50)` 제거. **jjwt 파서에도 `.clock(...)`을 지정**해야 만료 판정까지 고정 시간을 따른다(미지정 시 파서가 시스템 시계 사용). `Clock.systemDefaultZone()`을 쓰는 이유는 JPA Auditing이 JVM 기본 시간대를 따르기 때문 — 여기서만 고정하면 `created_at`과 `expires_at`의 기준이 갈린다. 테스트 6건 → 9건(경계값·`Refresh`가 JWT 아님 추가). S-F의 `AuthService`도 만료 시각 계산과 A-4 유예 판정에 같은 `Clock` 빈을 쓴다 |
| 2026-07-30 | **S-D·S-E 구현 완료 및 구현 중 조정 2건.** `JwtAuthenticationException`(사유 전달자) / `JwtAuthenticationFilter` / `SecurityErrorResponseWriter` / `JwtAuthenticationEntryPoint` / `JwtAccessDeniedHandler` / `AuthUser` / `AuthUserArgumentResolver` / `WebConfig` 신규, `SecurityConfig`에 `exceptionHandling` + `addFilterBefore` 배선. ① **필터를 빈으로 만들지 않고 `SecurityConfig`에서 직접 생성한다** — Spring Boot는 `Filter` 타입 빈을 서블릿 컨테이너 필터 체인에도 자동 등록하므로 `@Component`를 붙이면 Security 체인 <b>밖에서</b> 한 번 더 돌아, `permitAll` 판정 전에 A-3이 적용되는 사고가 난다. 이에 따라 C-1의 경로 상수 공유는 필터가 `SecurityConfig`를 참조하는 대신 **생성자로 주입받는 방향**으로 구현해 의존 방향을 한쪽으로 유지 ② **`supportsParameter`가 타입을 보지 않는다** — 어노테이션 유무만으로 받고 타입 불일치는 `resolveArgument`에서 `IllegalStateException`으로 거부(S-5 본문에 근거 반영). 검증: S-D는 실제 HTTP 4건(비로그인 공개조회 / `permitAll`에 무효 토큰 → 401 `INVALID_TOKEN` / auth 경로 무효 토큰 → 필터 미적용 / 토큰 없는 `logout` → 401 `UNAUTHORIZED`) 통과. **S-E는 Controller가 없어 미검증** — S-F에서 `required=true`의 401과 `permitAll` 경로의 `viewerId == null` 주입을 함께 확인할 것 |
| 2026-07-30 | **S-C(S-2) 구현 완료 + S-9 선반영 2건.** `JwtProperties`(record, 컴팩트 생성자에서 secret 32바이트/TTL 양수 검증 → 잘못된 설정은 기동 시점에 실패), `JwtTokenProvider`, `AuthUserPrincipal`(record) 구현. **HS256 고정을 위해 `Keys.hmacShaKeyFor()`를 쓰지 않았다** — 키 길이에 따라 HmacSHA384/512로 알고리즘이 바뀌므로 `SecretKeySpec(bytes, "HmacSHA256")`으로 직접 지정했다(jjwt 0.12.6은 `MacAlgorithm#getJcaName`을 공개 API로 노출하지 않아 JCA 이름은 상수). `parseAccessToken`은 `ExpiredJwtException` → `TOKEN_EXPIRED`, 그 외 `JwtException`/`IllegalArgumentException` → `INVALID_TOKEN`으로 수렴(`sub` 파싱 실패·미지원 `role` 값 포함, `role` 클레임 누락은 명시적으로 거부). `@ConfigurationProperties` 등록은 `SecurityConfig`의 `@EnableConfigurationProperties`가 담당(앱 클래스 무변경). S-6 `ErrorCode` 9건 일괄 추가. **S-9 A-7** `TestController` 삭제, **S-9 A-2** `signUpOAuth`에 `existsByEmail` 사전 체크 추가(`uk_user_email` 위반 500 대신 409). A-6(비밀번호 변경)은 `AuthService` 선행이라 S-H로, A-3(무효 토큰 401)은 필터 담당이라 S-D로 남겼다 |
| 2026-07-30 | **S-2에 `JwtTokenProviderTest` 불변식 목록 추가.** 테스트 6건이 각각 어떤 결정을 고정하는지와 깨졌을 때의 증상을 표로 남겨 정리 대상 오해를 방지 — 특히 `TOKEN_EXPIRED`/`INVALID_TOKEN` 분리는 `catch`를 합치면 컴파일이 통과하고 증상이 앱의 재발급 루프로만 드러나며, S-9 프론트 계약이 이 분리에 의존한다. 잔여 2건 도출: ① **HS256 고정을 검증하는 단정이 없음** — 라운드트립은 양쪽이 HS512여도 통과하므로 정작 이 파일이 만들어진 계기가 고정되지 않았다. 헤더 `alg` 단정을 추가하면 현재 두 겹인 가드(`SecretKeySpec` JCA 이름 / `signWith` 명시 인자) 중 어느 쪽이 실효인지도 드러난다 ② `Thread.sleep(50)` → **`Clock` 주입** — `RefreshToken.isExpired(now)`와 같은 이유로 도입한 규칙인데 `JwtTokenProvider`만 `Instant.now()`를 내부 호출하고 있어 컨벤션이 어긋남 |
| 2026-07-30 | **S-D·S-E 설계 확정 (S-9 C-1~C-3).** ① A-3이 만드는 부작용 발견 — 만료 토큰을 단 채 `login`/`reissue`를 호출하면 401로 막혀 앱 재설치 전엔 복구 불가. auth 4경로를 `shouldNotFilter`로 제외하되 `logout`은 A-5 때문에 반드시 포함시키지 않으며(`/api/auth/**` 통짜 금지), 경로 상수를 `SecurityConfig`와 공유해 목록 이중 관리를 차단. 안전성 근거는 **인가가 `AuthorizationFilter` 소관이라 오류 방향이 fail-closed**라는 점. ② **A-3은 필터가 체인을 끊도록 강제한다** — 오류만 기록하고 계속 태우면 `permitAll` 경로가 200으로 통과해 A-3이 무력화되고, 필터 예외는 `ExceptionTranslationFilter`가 잡지 못하므로 `EntryPoint.commence()` 직접 호출로 규정. ③ `@AuthUser.required` 기본값을 **`true` → `false`** 로 변경(흔한 쪽인 `permitAll` 조회가 예외 표기를 지는 역전 해소). ④ 리졸버는 `Authentication != null`이 아니라 **`principal instanceof AuthUserPrincipal`** 로 판정해야 함을 명시(익명에도 `AnonymousAuthenticationToken`이 채워짐). ⑤ Boot 4는 Jackson 3 `JsonMapper`가 `@Primary`이므로 `com.fasterxml` `ObjectMapper` 주입 시 기동 실패 — 자동 구성본 주입 + `charset=UTF-8` 명시(한글 메시지). ⑥ 프론트 계약에 **401 전역 처리** 추가 |
| 2026-07-30 | **S-9 신설 — 착수 전 확정 결정 12건.** 설계 재검토에서 "확정" 절에도 케이스가 비어 있던 지점 7건(A-1~A-7)을 결정: 카카오 이메일 필수 동의(스키마 무변경), 로컬/소셜 이메일 충돌은 409 명시 응답, `permitAll` 경로의 무효 토큰도 401, 회전 오탐은 프론트 mutex + 서버 30초 유예, `logout`만 인증 필요, 비밀번호 변경 포함, `TestController` 삭제. 명세 보강 5건(B-1~B-5)에서 `ROLE_` 접두사·CORS·`RoleType` 위치를 확정하고, **B-4는 `@ManyToOne`으로 결론** — `Long userId`가 조인을 아낀다는 근거가 사실이 아니었고 CLAUDE.md 규칙만 깨는 선택이었음. `ErrorCode` 2건 추가(`EMAIL_ALREADY_REGISTERED_LOCALLY`, `OAUTH_EMAIL_NOT_PROVIDED`). **S-7의 "`signUpOAuth` 변경 없음"은 A-2로 무효화.** 중괄호 축약이 경로 매칭 문법이 아니라는 점을 화이트리스트 주의사항으로 명시 |
| 2026-07-30 | **S-A·S-B 구현 완료.** 의존성(starter-security, jjwt 0.12.6) 추가 — 스타터 추가 즉시 전 엔드포인트가 차단되므로 `SecurityConfig` 골격을 같은 커밋에 넣고 JWT 필터는 미배선 상태로 뒀다. 엔티티 3건 구현 후 컬럼 구성을 v9 덤프와 대조(차이 0), `validate` 기동 통과. 기존 `spring-security-crypto` 단독 선언은 starter에 포함되므로 제거. 구현 중 조정 4건은 `jpa-entity-spec.md` 변경 이력 참고 |
| 2026-07-29 | S-8 정리 및 **스키마 v9 확정**. 잔여 항목 6건을 스키마 영향 여부로 분류한 결과 DDL이 필요한 건 2건뿐임을 확인 — `notification`은 **v9에 포함 확정**(4-6부터 이월된 미결 항목 해소), `password_reset_token`은 SMTP 선행이 필요해 v10으로 분리. 만료 토큰 정리는 MySQL EVENT가 아닌 `@Scheduled`로 결정(event_scheduler 기본 OFF 의존, 로그 추적 불가, 로직이 SoT 밖으로 유출). `docs/schema/v9-delta-proposal.sql` → `v9-delta.sql`로 확정본 정리, 롤백 스크립트 추가. **알림도 comment와 같은 다형 참조라 고아 알림 문제가 그대로 재현됨** — 삭제 경로 정리를 체크리스트에 명시 |
| 2026-07-29 | Step S 설계 착수 및 확정. 토큰 = Access + Refresh(DB 저장, 회전 + 재사용 감지), 소셜 = 클라이언트 SDK ID 토큰 검증(`OAuthIdTokenVerifier` 전략 — 4-6 `CommentTargetResolver` 패턴 재사용), 관리자 = `user.role` 컬럼(v9), 팔로우 명단은 공개범위 적용 유지. `@AuthUser` 전용 어노테이션으로 "비로그인 = null" 계약을 명시화. 필터 단계 예외가 `@RestControllerAdvice`에 도달하지 않는 문제를 EntryPoint/AccessDeniedHandler 직접 응답으로 해결. **스키마 v9 델타 2건은 승인 대기 상태** |
