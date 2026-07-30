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
| S-2 | 토큰 발급/검증 (`JwtTokenProvider`) | ✅ | ✅ 완료 (`JwtProperties`/`JwtTokenProvider`/`AuthUserPrincipal`) — 테스트 잔여 2건 |
| S-3 | 인증 흐름 (로컬 / 소셜 / 재발급 / 로그아웃) | ✅ | ⬜ 미착수 |
| S-4 | 필터체인 및 엔드포인트 접근 정책 | ✅ | ✅ 완료 (필터 배선 + `shouldNotFilter`) |
| S-5 | 인증 주체 주입 (`@AuthUser`) | ✅ | ✅ 완료 — 동작 확인은 첫 Controller(S-F) 시점 |
| S-6 | 예외 응답 통일 | ✅ | ✅ 완료 (EntryPoint/AccessDeniedHandler/Writer) |
| S-7 | 기존 코드 영향 범위 | ✅ | 🔶 진행 중 (`TestController` 삭제·`signUpOAuth` 반영) |
| S-8 | 잔여 확인 항목 분류 | ✅ | — |
| S-9 | **착수 전 확정 결정** (A-1~A-7, B-1~B-5, **C-1~C-3**) | ✅ | 🔶 A-2·A-3·A-5·A-7·B-1·B-2·C-1~C-3 반영 (A-6은 S-H) |

> **구현 순서** — S-A(의존성 + `SecurityConfig` 골격) → S-B(엔티티 3건) → S-C(`JwtTokenProvider`)
> → S-D(필터 + 예외 핸들러) → S-E(`@AuthUser`) → S-F(`AuthService`) → S-G(카카오 로그인)
> → S-H(비밀번호 변경) → S-I(정리). **S-E까지 완료** (2026-07-30). S-D는 실제 HTTP 요청 4건으로
> 검증했고, S-E는 Controller가 없어 컴파일·기동까지만 확인했다.

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

`docs/schema/v9-delta.sql` 참고. **v8(18 테이블) → v9(20 테이블)**.

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
| 만료 → `TOKEN_EXPIRED` | **S-6의 `TOKEN_EXPIRED`/`INVALID_TOKEN` 분리** | `catch`를 한 줄로 합치면 컴파일은 통과하고, 증상은 **앱의 재발급 무한 루프**로만 나타난다 |
| 서명 불일치 → `INVALID_TOKEN` | **서명 검증이 실제로 수행됨** | 서명 미검증 파싱으로 바뀌어도 왕복 테스트는 통과한다. 이 테스트만 실패한다 |
| 비JWT 문자열 → `INVALID_TOKEN` | 라이브러리 예외가 500으로 누출되지 않음 | 잘못된 입력이 `BusinessException` 대신 500 |
| Refresh 43자 · 매번 다름 | S-2의 "256bit 불투명 랜덤값" | 인코딩·엔트로피 변경 시 실패 |
| 짧은 `secret` 거부 | **첫 로그인이 아니라 기동 시점에 실패** | 배포 후 첫 인증 요청에서야 발견 |

> 두 번째 항목이 가장 중요하다. S-9 프론트 계약(401 전역 처리)이 이 분리에 의존하므로,
> 서버에서 조용히 합쳐지면 앱 쪽에서 원인 불명의 루프로 드러난다.

**잔여 과제 2건** (S-C 마무리 항목)

1. **HS256 고정을 검증하는 단정이 없다.** 라운드트립 테스트는 발급·검증 양쪽이 똑같이 HS512여도
   통과하므로, 정작 이 파일이 만들어진 계기가 고정되지 않았다. 토큰 헤더의 `alg`를 직접 확인하는
   테스트를 추가한다. 부수 효과로 현재 두 겹인 가드
   (`SecretKeySpec`의 JCA 이름 / `signWith`의 명시적 `Jwts.SIG.HS256` 인자) 중
   어느 쪽이 실효인지 드러난다 — 후자만으로 충분하다면 `SecretKeySpec` 우회와 그 주석은 불필요하다.
2. **`Thread.sleep(50)` 제거 → `Clock` 주입.** 타이밍 의존이라 흔들릴 수 있고, 무엇보다
   `RefreshToken.isExpired(now)`/`revoke(now)`가 시간을 인자로 받는 것과 어긋난다.
   같은 이유(테스트에서 시간 고정)로 도입한 규칙인데 `JwtTokenProvider`만 `Instant.now()`를
   내부에서 호출하고 있다.

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

```
{idToken} → OAuthIdTokenVerifier(provider별) → 검증 및 사용자 정보 추출
          → UserService.signUpOAuth (기존 멱등 설계 그대로 재사용)
          → Access + Refresh 발급
```

**클라이언트 SDK 방식을 택한 이유**: React Native에서 서버 리다이렉트 기반 OAuth2는
브라우저 왕복과 딥링크 처리가 필요해 UX와 구현 모두 무겁다. 앱이 네이티브 SDK로 로그인하고
서버는 받은 ID 토큰만 검증하면, 4-1에서 이미 확정한 `signUpOAuth(멱등)` 설계와 그대로 맞물린다.

**전략 인터페이스** — 4-6 `CommentTargetResolver`와 동일한 패턴을 재사용한다.

```java
public interface OAuthIdTokenVerifier {
    OAuthProvider supports();
    OAuthUserInfo verify(String idToken); // 실패 시 BusinessException(INVALID_OAUTH_TOKEN)
}
```

- 구현체는 **`KakaoIdTokenVerifier` 하나만 만든다** (S-9 A-1). JWKS로 서명 검증
  - JWKS: `https://kauth.kakao.com/.well-known/jwks.json` — ID 토큰의 `kid`로 공개키를 찾아 검증하고,
    **공개키는 캐싱한다**(빈번한 요청은 차단될 수 있음)
  - `iss` == `https://kauth.kakao.com`, `aud` == 앱 REST API 키, `exp` 검증
- `OAuthProvider` enum에는 **`KAKAO`만 정의한다.** 미구현 provider 값을 미리 넣어두면
  실패가 런타임까지 미뤄진다. 구글/애플은 구현체가 생기는 시점에 값을 함께 추가한다
- `List<OAuthIdTokenVerifier>`를 주입받아 **생성자에서** `EnumMap`으로 변환
  (Spring의 `Map` 자동 주입은 키가 빈 이름이라 enum 키로 쓸 수 없음 — 4-6과 동일한 이유)
- 미지원 provider는 `UNSUPPORTED_OAUTH_PROVIDER`(400)
- `OAuthUserInfo(providerId, email, nickname, profileImage)` — provider별 응답 차이를 여기서 흡수

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
| `permitAll` | POST | `/api/auth/signup`, `/api/auth/login`, `/api/auth/oauth/*`, `/api/auth/reissue` |
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

스키마 영향 여부를 기준으로 정리했다. DDL은 `docs/schema/v9-delta-proposal.sql`에 섹션 (3)(4)와
부록 A로 반영돼 있다.

| # | 항목 | 스키마 영향 | 상태 |
|---|---|---|---|
| 1 | 소셜 provider 우선순위 (카카오/구글/애플) | **없음** — `provider`/`provider_id`/`uk_user_provider`가 이미 있어 enum만 추가 | ✅ **카카오 확정** (S-9 A-1) |
| 2 | 비밀번호 **변경** (로그인 상태) | **없음** | ✅ **Step S 범위에 포함** (S-9 A-6) |
| 3 | 비밀번호 **재설정** (비로그인) | `password_reset_token` 테이블 | ⏸ 보류 — SMTP 선행, v10으로 분리 |
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

### 착수 전 결정 (A — 7건)

| # | 항목 | 결정 | 근거 |
|---|---|---|---|
| A-1 | 카카오 이메일 vs `email NOT NULL` | **비즈앱 전환(또는 개인 개발자 본인인증) 후 이메일 필수 동의** | 스키마·코드 변경이 0이다. 플레이스홀더 이메일은 `uk_user_email`에 가짜 데이터를 남겨 v10 비밀번호 재설정에서 터진다 |
| A-2 | 로컬 가입 이메일 == 소셜 이메일 | **`EMAIL_ALREADY_REGISTERED_LOCALLY`(409) 명시적 응답** | 로그인은 계정 존재 탐색을 막아야 하지만, 여기는 본인이 자기 계정으로 들어오려는 상황이라 알려줘야 한다 |
| A-3 | `permitAll` 경로의 무효 토큰 | **항상 401** (익명으로 강등하지 않음) | 조용한 강등은 "로그인했는데 안 보임"을 만들고 로그도 남지 않아 추적이 사실상 불가하다 |
| A-4 | 회전 + 동시 요청 오탐 | **클라이언트 mutex(프론트) + 서버 30초 유예(백엔드)** | 프론트 단독으로는 백엔드가 막을 수 없고, 서버 유예가 안전망이 된다 |
| A-5 | `/api/auth/**` 전체 `permitAll` | **`logout`만 `authenticated()`** 로 분리 | 로그아웃은 인증된 상태가 정상 흐름. 토큰 소유자 일치 검증도 가능해진다 |
| A-6 | 비밀번호 변경 | **Step S 범위에 포함** | 변경 시 리프레시 토큰 전체 폐기가 필요한데 그 로직이 `AuthService`에 이미 생긴다 |
| A-7 | `TestController` | **삭제** (별도 `chore` 커밋) | 인증 없는 TMDB 프록시라 호출 쿼터를 임의 소진시킬 수 있다. 4-2 `MovieQueryService`로 대체됨 |

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
    ├─ now - revoked_at <= 30초  → 유예. 재사용으로 보지 않고 새 토큰 쌍 발급
    └─ 초과                      → REFRESH_TOKEN_REUSED + 해당 유저 전체 폐기
정상 → 기존 revoke + 새 토큰 쌍 발급 (회전)
```

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

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-07-30 | **S-D·S-E 구현 완료 및 구현 중 조정 2건.** `JwtAuthenticationException`(사유 전달자) / `JwtAuthenticationFilter` / `SecurityErrorResponseWriter` / `JwtAuthenticationEntryPoint` / `JwtAccessDeniedHandler` / `AuthUser` / `AuthUserArgumentResolver` / `WebConfig` 신규, `SecurityConfig`에 `exceptionHandling` + `addFilterBefore` 배선. ① **필터를 빈으로 만들지 않고 `SecurityConfig`에서 직접 생성한다** — Spring Boot는 `Filter` 타입 빈을 서블릿 컨테이너 필터 체인에도 자동 등록하므로 `@Component`를 붙이면 Security 체인 <b>밖에서</b> 한 번 더 돌아, `permitAll` 판정 전에 A-3이 적용되는 사고가 난다. 이에 따라 C-1의 경로 상수 공유는 필터가 `SecurityConfig`를 참조하는 대신 **생성자로 주입받는 방향**으로 구현해 의존 방향을 한쪽으로 유지 ② **`supportsParameter`가 타입을 보지 않는다** — 어노테이션 유무만으로 받고 타입 불일치는 `resolveArgument`에서 `IllegalStateException`으로 거부(S-5 본문에 근거 반영). 검증: S-D는 실제 HTTP 4건(비로그인 공개조회 / `permitAll`에 무효 토큰 → 401 `INVALID_TOKEN` / auth 경로 무효 토큰 → 필터 미적용 / 토큰 없는 `logout` → 401 `UNAUTHORIZED`) 통과. **S-E는 Controller가 없어 미검증** — S-F에서 `required=true`의 401과 `permitAll` 경로의 `viewerId == null` 주입을 함께 확인할 것 |
| 2026-07-30 | **S-C(S-2) 구현 완료 + S-9 선반영 2건.** `JwtProperties`(record, 컴팩트 생성자에서 secret 32바이트/TTL 양수 검증 → 잘못된 설정은 기동 시점에 실패), `JwtTokenProvider`, `AuthUserPrincipal`(record) 구현. **HS256 고정을 위해 `Keys.hmacShaKeyFor()`를 쓰지 않았다** — 키 길이에 따라 HmacSHA384/512로 알고리즘이 바뀌므로 `SecretKeySpec(bytes, "HmacSHA256")`으로 직접 지정했다(jjwt 0.12.6은 `MacAlgorithm#getJcaName`을 공개 API로 노출하지 않아 JCA 이름은 상수). `parseAccessToken`은 `ExpiredJwtException` → `TOKEN_EXPIRED`, 그 외 `JwtException`/`IllegalArgumentException` → `INVALID_TOKEN`으로 수렴(`sub` 파싱 실패·미지원 `role` 값 포함, `role` 클레임 누락은 명시적으로 거부). `@ConfigurationProperties` 등록은 `SecurityConfig`의 `@EnableConfigurationProperties`가 담당(앱 클래스 무변경). S-6 `ErrorCode` 9건 일괄 추가. **S-9 A-7** `TestController` 삭제, **S-9 A-2** `signUpOAuth`에 `existsByEmail` 사전 체크 추가(`uk_user_email` 위반 500 대신 409). A-6(비밀번호 변경)은 `AuthService` 선행이라 S-H로, A-3(무효 토큰 401)은 필터 담당이라 S-D로 남겼다 |
| 2026-07-30 | **S-2에 `JwtTokenProviderTest` 불변식 목록 추가.** 테스트 6건이 각각 어떤 결정을 고정하는지와 깨졌을 때의 증상을 표로 남겨 정리 대상 오해를 방지 — 특히 `TOKEN_EXPIRED`/`INVALID_TOKEN` 분리는 `catch`를 합치면 컴파일이 통과하고 증상이 앱의 재발급 루프로만 드러나며, S-9 프론트 계약이 이 분리에 의존한다. 잔여 2건 도출: ① **HS256 고정을 검증하는 단정이 없음** — 라운드트립은 양쪽이 HS512여도 통과하므로 정작 이 파일이 만들어진 계기가 고정되지 않았다. 헤더 `alg` 단정을 추가하면 현재 두 겹인 가드(`SecretKeySpec` JCA 이름 / `signWith` 명시 인자) 중 어느 쪽이 실효인지도 드러난다 ② `Thread.sleep(50)` → **`Clock` 주입** — `RefreshToken.isExpired(now)`와 같은 이유로 도입한 규칙인데 `JwtTokenProvider`만 `Instant.now()`를 내부 호출하고 있어 컨벤션이 어긋남 |
| 2026-07-30 | **S-D·S-E 설계 확정 (S-9 C-1~C-3).** ① A-3이 만드는 부작용 발견 — 만료 토큰을 단 채 `login`/`reissue`를 호출하면 401로 막혀 앱 재설치 전엔 복구 불가. auth 4경로를 `shouldNotFilter`로 제외하되 `logout`은 A-5 때문에 반드시 포함시키지 않으며(`/api/auth/**` 통짜 금지), 경로 상수를 `SecurityConfig`와 공유해 목록 이중 관리를 차단. 안전성 근거는 **인가가 `AuthorizationFilter` 소관이라 오류 방향이 fail-closed**라는 점. ② **A-3은 필터가 체인을 끊도록 강제한다** — 오류만 기록하고 계속 태우면 `permitAll` 경로가 200으로 통과해 A-3이 무력화되고, 필터 예외는 `ExceptionTranslationFilter`가 잡지 못하므로 `EntryPoint.commence()` 직접 호출로 규정. ③ `@AuthUser.required` 기본값을 **`true` → `false`** 로 변경(흔한 쪽인 `permitAll` 조회가 예외 표기를 지는 역전 해소). ④ 리졸버는 `Authentication != null`이 아니라 **`principal instanceof AuthUserPrincipal`** 로 판정해야 함을 명시(익명에도 `AnonymousAuthenticationToken`이 채워짐). ⑤ Boot 4는 Jackson 3 `JsonMapper`가 `@Primary`이므로 `com.fasterxml` `ObjectMapper` 주입 시 기동 실패 — 자동 구성본 주입 + `charset=UTF-8` 명시(한글 메시지). ⑥ 프론트 계약에 **401 전역 처리** 추가 |
| 2026-07-30 | **S-9 신설 — 착수 전 확정 결정 12건.** 설계 재검토에서 "확정" 절에도 케이스가 비어 있던 지점 7건(A-1~A-7)을 결정: 카카오 이메일 필수 동의(스키마 무변경), 로컬/소셜 이메일 충돌은 409 명시 응답, `permitAll` 경로의 무효 토큰도 401, 회전 오탐은 프론트 mutex + 서버 30초 유예, `logout`만 인증 필요, 비밀번호 변경 포함, `TestController` 삭제. 명세 보강 5건(B-1~B-5)에서 `ROLE_` 접두사·CORS·`RoleType` 위치를 확정하고, **B-4는 `@ManyToOne`으로 결론** — `Long userId`가 조인을 아낀다는 근거가 사실이 아니었고 CLAUDE.md 규칙만 깨는 선택이었음. `ErrorCode` 2건 추가(`EMAIL_ALREADY_REGISTERED_LOCALLY`, `OAUTH_EMAIL_NOT_PROVIDED`). **S-7의 "`signUpOAuth` 변경 없음"은 A-2로 무효화.** 중괄호 축약이 경로 매칭 문법이 아니라는 점을 화이트리스트 주의사항으로 명시 |
| 2026-07-30 | **S-A·S-B 구현 완료.** 의존성(starter-security, jjwt 0.12.6) 추가 — 스타터 추가 즉시 전 엔드포인트가 차단되므로 `SecurityConfig` 골격을 같은 커밋에 넣고 JWT 필터는 미배선 상태로 뒀다. 엔티티 3건 구현 후 컬럼 구성을 v9 덤프와 대조(차이 0), `validate` 기동 통과. 기존 `spring-security-crypto` 단독 선언은 starter에 포함되므로 제거. 구현 중 조정 4건은 `jpa-entity-spec.md` 변경 이력 참고 |
| 2026-07-29 | S-8 정리 및 **스키마 v9 확정**. 잔여 항목 6건을 스키마 영향 여부로 분류한 결과 DDL이 필요한 건 2건뿐임을 확인 — `notification`은 **v9에 포함 확정**(4-6부터 이월된 미결 항목 해소), `password_reset_token`은 SMTP 선행이 필요해 v10으로 분리. 만료 토큰 정리는 MySQL EVENT가 아닌 `@Scheduled`로 결정(event_scheduler 기본 OFF 의존, 로그 추적 불가, 로직이 SoT 밖으로 유출). `docs/schema/v9-delta-proposal.sql` → `v9-delta.sql`로 확정본 정리, 롤백 스크립트 추가. **알림도 comment와 같은 다형 참조라 고아 알림 문제가 그대로 재현됨** — 삭제 경로 정리를 체크리스트에 명시 |
| 2026-07-29 | Step S 설계 착수 및 확정. 토큰 = Access + Refresh(DB 저장, 회전 + 재사용 감지), 소셜 = 클라이언트 SDK ID 토큰 검증(`OAuthIdTokenVerifier` 전략 — 4-6 `CommentTargetResolver` 패턴 재사용), 관리자 = `user.role` 컬럼(v9), 팔로우 명단은 공개범위 적용 유지. `@AuthUser` 전용 어노테이션으로 "비로그인 = null" 계약을 명시화. 필터 단계 예외가 `@RestControllerAdvice`에 도달하지 않는 문제를 EntryPoint/AccessDeniedHandler 직접 응답으로 해결. **스키마 v9 델타 2건은 승인 대기 상태** |
