# 카카오 로그인 — 로컬 실토큰 검증 런북

> **목적** — 앱(React Native) 없이, **로컬 서버 + 브라우저만으로** 카카오 실토큰이
> 우리 인증 경로를 통과하는지 1회 확인한다.
> `security-spec.md` 미검증 E2E 2건 중 하나(**카카오 실토큰**)를 닫는 절차다.
>
> **설계 근거는 여기가 아니라 `docs/security-spec.md`(S-9 · S-G)에 있다.** 여기는 순서와 명령어만 담는다.

---

## 왜 지금 하는가

이 검증은 **배포 트리거와 정확히 같은 시점에 온다**(`CineMory_기획노트.md` 4-INF).
9월 중순에 *"카카오 로그인 처음 붙이기 + 실토큰 처음 검증 + 실서버 처음 올리기 +
redirect URI 처음 등록"* 이 겹치면, 실패했을 때 **어느 층이 문제인지 가려낼 수 없다.**

로컬에서 한 번 통과시켜 두면 배포 시점에 남는 미지수가 **redirect URI와 HTTPS 둘뿐**이 된다.

---

## 전체 그림 — 세 주체가 주고받는다

```
 ┌── 우리 서버 ──┐        ┌── 브라우저(당신) ──┐        ┌── 카카오 ──┐
 1. nonce 발급  ─────────────────────────────────────────▶ (nonce를 들고 로그인)
                          2. 로그인·동의                  ◀──── code
                          3. code → id_token 교환  ──────▶
 4. idToken + nonce ◀────────────────────────────────────
    → 우리 JWT 발급
```

**핵심은 `nonce`다.** 우리 서버가 발급한 값을 카카오 로그인 요청에 실어 보내고,
카카오가 그 값을 `id_token` 안에 넣어 돌려준다. 우리 서버는 그게 **자기가 방금 발급한
값인지** 대조해서 *"이 토큰이 우리가 방금 시작한 로그인에 대한 응답인가"* 를 확인한다.
(재전송 방지 — 카카오 ID 토큰 수명이 약 2시간이라 창이 짧지 않다.)

### ⚠️ nonce가 무효화되는 세 가지 경로

`OAuthNonceService`를 보면 nonce는 **Caffeine 인메모리 캐시**에 담긴 **1회용 5분짜리** 값이다.
수동 절차에서는 이 셋이 전부 현실적인 실패 원인이 된다.

| 조건 | 근거 |
|---|---|
| **이미 4단계를 한 번 시도했다** | `consumeOrThrow`가 대조 후 **즉시 제거**한다(`asMap().remove()`). 1회용이어야 재전송 방지가 성립하므로 **재시도는 무조건 실패**한다 |
| **5분이 지났다** | `expireAfterWrite(PT5M)` |
| **그 사이 서버를 재시작했다** | **인메모리라 재기동하면 전부 사라진다** (단일 인스턴스 전제. 다중화하면 Redis로 옮긴다) |

⏱ **5분 창이 1단계부터 4단계 전체를 덮는다.** nonce를 authorize URL에 넣어야 해서 순서를 앞당길 수도 없다.
앱에서는 몇 초면 끝나는 흐름이라 5분이 넉넉하지만, **브라우저로 손수 복사하며 하기엔 빠듯하다.**

> 💡 **로컬 검증 동안만 늘려도 된다.** 끝나면 되돌릴 것.
> ```yaml
> auth:
>   oauth:
>     nonce-ttl: PT15M   # 검증용. 원래 값은 PT5M
> ```

---

## 0. 사전 준비 — 카카오 개발자 콘솔

한 번만 하면 된다. [카카오 개발자 콘솔](https://developers.kakao.com)에서:

| # | 항목 | 주의 |
|---|---|---|
| 1 | 애플리케이션 생성 → **REST API 키** 확보 | 앱 키가 4종(네이티브/REST/JavaScript/Admin)이다. **REST API 키**를 쓴다 |
| 2 | **카카오 로그인 → 활성화 ON** | |
| 3 | ⚠️ **OpenID Connect → 활성화 ON** | **이게 꺼져 있으면 `id_token`이 아예 오지 않는다.** 가장 흔한 실패 원인이고, 에러가 아니라 *응답에 필드가 없는* 형태로 나타나서 알아채기 어렵다 |
| 4 | **Redirect URI 등록** — 예: `http://localhost:8080/login/oauth2/code/kakao` | 우리 서버가 이 경로를 처리하지 않아도 된다(아래 2단계 참고). **콘솔 등록값과 요청값이 문자 단위로 정확히 같아야 한다** — 끝 슬래시 하나 차이도 실패한다 |
| 5 | **동의항목 → 카카오계정(이메일)을 "필수 동의"로** | 우리 설계상 이메일이 없으면 가입이 불가하다(S-9 A-1). 사용자가 거부하면 **로그인 자체가 안 되는** 것이 정상 동작이며, 안내 문구는 별도 항목이다(`security-spec.md` L-9) |

### 설정 파일 — `application-secret.yml`

`oauth.kakao.allowed-audiences`에 **REST API 키를 추가**한다.

```yaml
oauth:
  kakao:
    allowed-audiences:
      - <REST API 키>      # 웹 플로우(이 런북)
      # - <네이티브 앱 키>  # 나중에 RN SDK 붙일 때 추가
```

> ⚠️ **왜 목록인가** — 카카오는 **로그인한 플랫폼에 따라 `id_token`의 `aud`가 다르다.**
> 웹에서 하면 REST API 키, 네이티브 SDK로 하면 네이티브 앱 키가 들어온다.
> `KakaoOAuthProperties`가 이걸 예상하고 목록으로 설계돼 있으니(주석 참고),
> **두 값을 나란히 넣어두면 이 런북과 실제 앱이 같은 설정으로 동작한다.**
> 비워 두면 **기동 자체가 실패한다** — `aud` 검증이 조용히 무력화되느니 죽는 편이 낫다는 판단.

`application-secret.yml`은 `.gitignore` 대상이다. **키 값을 커밋하거나 로그에 남기지 말 것.**

---

## 1. 서버 기동 + nonce 발급

⏱ **여기서 5분 타이머가 시작된다.**

> 💡 **권장 — 1단계와 2단계를 한 번에 실행한다.**
> URL을 손으로 조립하면 플레이스홀더 괄호가 딸려 들어가기 쉽고(2단계 경고),
> nonce를 받은 뒤 URL을 만드는 동안 5분이 흘러간다. 아래는 그 둘을 없앤다 —
> **`$nonce` 변수와 URL의 nonce가 같은 값으로 보장**되고 인코딩도 자동이다.
>
> ```powershell
> $REST_KEY = "<REST API 키>"
> $REDIRECT = "http://localhost:8080/login/oauth2/code/kakao"
>
> $nonce = (Invoke-RestMethod -Uri "http://localhost:8080/api/auth/nonce" -Method Post).nonce
> $authUrl = "https://kauth.kakao.com/oauth/authorize" +
>            "?client_id=$REST_KEY" +
>            "&redirect_uri=$([uri]::EscapeDataString($REDIRECT))" +
>            "&response_type=code" +
>            "&scope=openid" +
>            "&nonce=$nonce"
> Start-Process $authUrl     # 브라우저 자동 실행
> $nonce
> ```
> (동의항목을 설정했다면 `scope=openid,account_email`로 바꾼다.)

**PowerShell — 단계별로 하는 경우**
```powershell
Set-Location "C:\Users\hiw73\capstone\cinemory-backend"
# 별도 창에서: ./gradlew bootRun

$nonceResp = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/nonce" -Method Post
$nonce = $nonceResp.nonce
$nonce          # 이 값을 2단계 URL에 넣는다
$nonceResp.expiresIn   # 300 (초)
```

⚠️ **`nonce-ttl`을 바꿨다면 서버를 재시작한 *뒤에* 1단계를 실행한다.**
재시작 자체가 인메모리 캐시를 비우므로, 순서가 바뀌면 방금 받은 nonce가 사라진다.

**bash**
```bash
NONCE=$(curl -s -X POST http://localhost:8080/api/auth/nonce | grep -o '"nonce":"[^"]*' | cut -d'"' -f4)
echo "$NONCE"
```

---

## 2. 브라우저에서 카카오 로그인 → `code` 받기

아래 URL을 만들어 **브라우저 주소창에** 붙여넣는다.

```
https://kauth.kakao.com/oauth/authorize
  ?client_id=<REST API 키>
  &redirect_uri=<0단계에서 등록한 URI>
  &response_type=code
  &scope=openid,account_email
  &nonce=<1단계의 nonce>
```

(실제로는 줄바꿈 없이 한 줄로 붙인다.)

⚠️ **`<`, `>`는 "여기에 값을 넣으라"는 표시이지 값의 일부가 아니다.** 괄호까지 같이
붙여넣으면 카카오가 그것도 키의 일부로 읽는다. 주소창에 `%3C`/`%3E`(= `<`, `>`) 또는
`%7B`/`%7D`(= `{`, `}`)가 보이면 **괄호가 딸려 들어간 것**이다.

```
잘못  ?client_id={abc123...}      →  주소창에 %7Babc123...%7D
맞음  ?client_id=abc123...
```

⚠️ **`scope`에 `openid`가 반드시 있어야 한다.** 없으면 3단계에서 `id_token`이 오지 않는다.
0단계 3번(OIDC 활성화)과 함께, `id_token`이 없을 때 의심할 두 곳이다.

로그인하고 동의하면 브라우저가 `redirect_uri`로 이동한다. 이때:

> **주소창에 에러 페이지(404 등)가 떠도 정상이다.** 우리 서버는 이 경로를 처리하지 않는다.
> 필요한 것은 **주소창에 붙은 `?code=...` 값 하나**뿐이다. 그것만 복사한다.

⏱ `code`는 **1회용이고 약 10분**이면 만료된다. 실패하면 2단계부터 다시 한다.

---

## 3. `code` → `id_token` 교환 (카카오에 직접 요청)

**PowerShell**
```powershell
$form = @{
    grant_type   = "authorization_code"
    client_id    = "<REST API 키>"
    redirect_uri = "<0단계에서 등록한 URI>"
    code         = "<2단계에서 복사한 code>"
    # 클라이언트 시크릿을 활성화한 앱이면 아래를 반드시 포함한다 (없으면 401)
    # client_secret = "<클라이언트 시크릿>"
}

try {
    $tokenResp = Invoke-RestMethod -Uri "https://kauth.kakao.com/oauth/token" -Method Post -Body $form
} catch {
    $_.ErrorDetails.Message      # ⚠️ 이 줄이 없으면 401만 보이고 원인을 못 본다
    throw
}

$idToken = $tokenResp.id_token
$idToken.Substring(0, 20) + "..."     # 전체를 출력하지 말 것
```

⚠️ **`try/catch`가 필요한 이유** — PowerShell의 `Invoke-RestMethod`는 4xx/5xx를 **예외로 던지면서
응답 본문을 숨긴다**(bash `curl`은 상태 코드와 무관하게 본문을 그대로 준다).
`$_.ErrorDetails.Message`를 찍지 않으면 *"401"* 이라는 사실만 알고 **어느 파라미터가 문제인지는
알 수 없다.** 카카오는 원인을 전부 본문의 `error_code`로 준다.

**bash**
```bash
ID_TOKEN=$(curl -s -X POST "https://kauth.kakao.com/oauth/token" \
  -d "grant_type=authorization_code" \
  -d "client_id=<REST API 키>" \
  -d "redirect_uri=<등록한 URI>" \
  -d "code=<2단계 code>" | grep -o '"id_token":"[^"]*' | cut -d'"' -f4)
echo "${ID_TOKEN:0:20}..."
# 클라이언트 시크릿을 활성화한 앱이면: -d "client_secret=<시크릿>" 를 추가한다
```

### ⚠️ 3단계에서 401이 나면 — 대부분 `client_secret`이다

카카오는 **클라이언트 시크릿이 활성화된 앱**에서 `client_secret`이 없으면
`KOE010 invalid_client`(`Bad client credentials`)로 **401**을 준다.
콘솔 **[앱] > [플랫폼 키] > [REST API 키]** 에서 시크릿 사용 여부와 코드를 확인한다.

| 본문의 코드 | 원인 | 조치 |
|---|---|---|
| `KOE010` | **`client_secret` 누락 또는 오값** | 파라미터 추가 |
| `KOE101` | 앱 키 오타 / 타입 불일치 | **REST API 키**가 맞는지 |
| `KOE114` | 인가 코드의 `client_id`와 토큰 요청의 `client_id`가 다른 앱 | 같은 앱 키로 2단계부터 다시 |
| `KOE303` | 인가 코드 요청과 토큰 요청의 `redirect_uri`가 다름 | **두 요청에 같은 값**을 쓴다 |

⚠️ **실패했으면 2단계부터 다시 한다.** `code`는 1회용이라 401이 났어도 소진됐을 수 있고,
`nonce`도 5분 만료라 1단계부터 새로 받는 편이 확실하다.

시크릿을 쓰기로 했다면 `application-secret.yml`에 함께 둔다(커밋 대상 아님).

⚠️ **`id_token`은 자격증명이다.** 터미널·로그·문서에 전문을 남기지 말 것.
응답에 `id_token` 필드가 **아예 없다면** → 0단계 3번(OIDC 비활성) 또는 2단계 `scope` 누락이다.

---

## 4. 우리 서버에 전달 → JWT 발급 (여기가 검증 대상)

**보내기 전에 변수부터 확인한다.** 둘 중 하나가 비면 요청이 성립하지 않는다.

```powershell
"idToken 길이: " + $idToken.Length     # 수백 자여야 정상
"nonce: " + $nonce                     # 비어 있으면 1단계부터
```

**PowerShell**
```powershell
$body = @{ idToken = $idToken; nonce = $nonce } | ConvertTo-Json

try {
    $login = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/oauth/kakao" `
        -Method Post -Body $body -ContentType "application/json"

    "✅ 성공 — 응답 필드: " + ($login.PSObject.Properties.Name -join ", ")
    if ($login.accessToken) { "accessToken: " + $login.accessToken.Substring(0, 20) + "..." }
}
catch {
    "❌ 실패: " + $_.Exception.Message
    if ($_.ErrorDetails.Message) {
        $_.ErrorDetails.Message
    }
    elseif ($_.Exception.Response) {
        $sr = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
        $sr.ReadToEnd()
    }
}
```

⚠️ **아무것도 출력되지 않는 경우가 있다 — 이 형태를 쓰는 이유다.**

- 성공 출력을 `try` 안에 두고 `$login.accessToken.Substring(...)`처럼 쓰면, **값이 `null`일 때
  메서드 호출이 예외를 던져 `catch`로 넘어간다.** 그런데 그건 HTTP 오류가 아니라
  `ErrorDetails`가 비어 있어 **catch 블록도 아무것도 찍지 않는다.** 결과는 완전한 침묵이다.
- **PowerShell 5.1은 HTTP 오류에서도 `ErrorDetails`가 `null`인 경우가 있다.**
  그래서 응답 스트림을 직접 읽는 폴백이 필요하다.

우리 서버는 `@RestControllerAdvice`가 **구조화된 에러 본문**을 주는데 `Invoke-RestMethod`가
그것을 예외에 묻는다. `INVALID_NONCE`인지 `INVALID_OAUTH_TOKEN`인지가 본문에 있고,
**둘은 원인이 전혀 다르다.**

> 💡 **막히면 서버 로그를 본다** — PowerShell의 변덕을 완전히 우회하는 경로다.
> `bootRun` 창에 요청이 **찍히지도 않았다면** 요청이 서버까지 못 간 것이고(포트·URL 오타, 미기동),
> 찍혔다면 `ErrorCode`가 거기 그대로 있다.

**bash**
```bash
curl -s -X POST http://localhost:8080/api/auth/oauth/kakao \
  -H "Content-Type: application/json" \
  -d "{\"idToken\":\"$ID_TOKEN\",\"nonce\":\"$NONCE\"}"
```

**성공 기준** — `accessToken` / `refreshToken`이 담긴 `TokenResponse`가 200으로 돌아온다.
이 시점에 서버가 확인한 것은 **4가지**다: 서명(JWKS) · `iss` · `aud` · `nonce`
(`exp`는 파싱 단계에서 처리). 하나라도 빠지면 그만큼 구멍이 생긴다는 것이 S-G의 판단이다.

### `INVALID_NONCE`가 나면 — 원인을 둘로 가른다

`id_token` 안의 `nonce` 클레임을 직접 열어 우리 변수와 비교하면 **값 불일치**와
**서버 쪽 소실**이 즉시 갈린다.

```powershell
$p = $idToken.Split('.')[1].Replace('-','+').Replace('_','/')
switch ($p.Length % 4) { 2 { $p += '==' } 3 { $p += '=' } }
[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($p)) |
    ConvertFrom-Json | Select-Object nonce, aud, iss

"내 변수 nonce: $nonce"
```

| 결과 | 의미 | 조치 |
|---|---|---|
| 토큰의 `nonce` ≠ `$nonce` | **값 불일치** — authorize URL에 넣은 값과 4단계에 보낸 값이 어긋났다 | 위 "1+2단계 한 번에" 스니펫을 쓴다. 같은 변수를 쓰므로 어긋날 수 없다 |
| 토큰의 `nonce` = `$nonce` | **서버 쪽에서 이미 사라졌다** — 소비됨 / 5분 초과 / 재시작 | 1단계부터 다시. `nonce-ttl`을 올린다 |

💡 함께 보이는 **`aud`가 `allowed-audiences`의 REST API 키와 같은지** 미리 확인해 두면
다음 단계에서 `INVALID_OAUTH_TOKEN`을 만나지 않는다.

### 5. 확인 (선택)

```sql
SELECT id, email, provider, provider_id, password_hash FROM user WHERE provider = 'KAKAO';
```

- `password_hash`가 `NULL`이어야 한다 — 소셜 계정과 로컬 계정의 상호배타 CHECK 제약
- 같은 계정으로 한 번 더 돌리면 **새 row가 생기지 않고 기존 계정으로 로그인**되어야 한다
  (`(provider, provider_id)` 복합 UNIQUE)

---

## 실패했을 때 — 증상별 진단

| 증상 | 원인 | 조치 |
|---|---|---|
| 2단계에서 **로그인 화면이 안 뜨고 카카오 에러 페이지가 나온다** | 대부분 **플레이스홀더 괄호를 지우지 않은 것**이다. 주소창에 `%7B`(`{`)·`%3C`(`<`)가 보이면 확정 | 괄호를 빼고 값만 남긴다 (2단계 경고 참고) |
| **로그인은 됐는데 `code`가 안 보인다** | 아직 리다이렉트 전이거나, 보고 있는 것이 **요청 URL**(`kauth.kakao.com/oauth/authorize?...`)이다 | `code`는 **로그인·동의를 마친 뒤** 주소창이 `redirect_uri`로 바뀌면서 붙는다. 주소가 `localhost:8080/...`으로 바뀌었는지부터 확인 |
| 3단계 응답에 **`id_token` 필드가 없다** | OIDC 비활성 **또는** `scope`에 `openid` 누락 | 0단계 3번 확인 → 2단계 URL 확인 |
| **`KOE033`** ("잘못된 요청") | 공식 문서상 *"지원하지 않는 SDK로 인가 코드 요청"* 인데, **이 런북은 SDK를 쓰지 않으므로 설명이 들어맞지 않는다.** 요청이 카카오가 분류하지 못할 만큼 어긋났을 때 여기로 떨어지는 것으로 보인다 | 아래 **KOE033 좁혀가기** 참고 |
| `KOE101` | 잘못된 앱 키 | **REST API 키**가 맞는지, 괄호가 딸려 들어가지 않았는지 |
| `KOE004` | 카카오 로그인 미활성 | 0단계 2번 |
| `KOE205` `invalid_scope` | **앱에 설정하지 않은 동의항목을 `scope`에 넣은 경우** | 콘솔 [카카오 로그인] > [동의항목]에서 설정하거나, 해당 scope를 뺀다 |
| `KOE006` / `redirect_uri mismatch` | 콘솔 등록값과 요청값 불일치 | **문자 단위로** 대조. 끝 슬래시·`http`/`https`·포트까지 |
| `KOE320` | `code` 재사용 또는 만료 | 2단계부터 다시 (1회용, 약 10분) |
| **`INVALID_NONCE`** | ① 이미 소비됨(1회용) ② 5분 초과 ③ **서버 재시작으로 소실**(인메모리) ④ 값 불일치 | **1단계부터 다시.** 2단계 URL의 `nonce`와 4단계 body의 `nonce`가 같은 값인지, 그 사이 `bootRun`을 다시 띄우지 않았는지 확인 (위 "무효화 세 경로" 참고) |
| `INVALID_OAUTH_TOKEN` | `aud` 또는 `iss` 또는 서명 실패 | 대부분 **`allowed-audiences`에 REST API 키가 없는 경우**다(웹 플로우는 `aud`가 REST API 키). 0단계 설정 절 참고 |
| **기동 자체가 실패** | `allowed-audiences`가 비어 있음 | 의도된 동작이다. 값을 채운다 |
| 이메일 없이 진행돼 가입 실패 | 사용자가 이메일 동의 거부 | **정상 동작**이다(S-9 A-1). 콘솔에서 "필수 동의"인지 확인 |

---

### KOE033 좁혀가기

공식 문서의 설명(*"지원하지 않는 SDK"*)이 이 런북 상황과 맞지 않으므로,
**요청을 최소 형태까지 줄여가며 어디서 갈리는지 찾는다.** 위에서부터 하나씩.

| # | 확인 | 왜 |
|---|---|---|
| 1 | **주소창에 `%7B`(`{`)가 없는지** | 남아 있으면 카카오가 앱 자체를 못 찾아 뒤쪽 검증이 전부 엉뚱하게 흘러간다. **가장 먼저 배제할 것** |
| 2 | **`scope=openid` 하나로 줄여서 재시도** (`account_email` 제거) | **설정되지 않은 동의항목은 거부된다**(KOE205). 개인 개발자 계정에서 `account_email`이 막히는 사례가 보고돼 있다. **이걸로 통과하면 원인이 scope로 확정된다.** `openid`만 있어도 `id_token`은 나오고, 이메일은 콘솔에서 필수 동의로 설정해 두면 따라온다 |
| 3 | **콘솔 [앱] > [플랫폼]에 Web 사이트 도메인 등록** (`http://localhost:8080`) | **Redirect URI와 별개 항목**이다. 브라우저에서 오는 요청이라 플랫폼 등록이 없으면 거부될 수 있다 |
| 4 | 시간을 두고 재시도 | 데브톡에 *"어제까지 잘 되다가 갑자기 KOE033"* 사례가 다수 있다. 1~3이 정상인데도 지속되면 [데브톡](https://devtalk.kakao.com/) 문의 |

---

## 이 런북이 닫는 것 / 닫지 못하는 것

**닫는다** — 서명·`iss`·`aud`·`nonce` 4종 검증, 계정 생성·재로그인, JWT 발급까지의 서버 경로.

**닫지 못한다** (배포·M2 시점으로 남는다)

- **네이티브 앱 키의 `aud`** — 이 런북은 REST API 키로 검증한다. RN SDK를 붙이면 `aud`가
  네이티브 앱 키로 바뀌므로 `allowed-audiences`에 **추가**해야 한다(목록이라 교체가 아니라 추가다)
- **실기기 redirect URI / HTTPS** — 배포 시점 항목(4-INF)
- **이메일 미동의 안내 UX** — `security-spec.md` **L-9**

---

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-08-27 | **✅ 첫 실행 성공 — 실행 중 막힌 지점을 전부 반영해 개정.** 통과까지 **KOE033(플레이스홀더 괄호·Redirect URI) → 401(`client_secret` 누락) → `INVALID_NONCE`(TTL) → `INVALID_OAUTH_TOKEN`(`aud`)** 네 단계를 거쳤다. 반영한 것 — ① **`client_secret` 파라미터**(초안 누락). ② **PowerShell `try/catch` + `ErrorDetails`/응답 스트림 폴백** — 없으면 *"401"* 만 보이고 `ErrorCode`를 못 본다. 성공 출력을 `try` 안에 두면 `null` 접근 예외가 `catch`로 흘러 **완전한 침묵**이 나는 것도 확인. ③ **`id_token` 디코딩으로 `nonce`·`aud` 대조** — `consumeOrThrow`가 먼저 nonce를 소비해 **값 불일치도 만료처럼 보이는** 함정(security-spec L-14) 때문에 필요했다. ④ **1~2단계 통합 스크립트** — URL 수동 조립(괄호 사고)과 5분 TTL 압박을 동시에 없앤다. 최종 성공은 **15초**. ⑤ **nonce 무효화 3경로**(1회용·TTL·인메모리라 재시작 시 소실) 명시 |
| 2026-08-27 | 최초 작성. `CineMory_기획노트.md` 4-M2 선행 7번에 대응. **배포 트리거와 실토큰 검증이 같은 시점에 겹치는 것을 분리**하려는 목적이 배경이다(4-INF) |
