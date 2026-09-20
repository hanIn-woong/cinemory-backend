# 영화 데이터 적재 런북

> `tmdb-sync-spec.md` **6-10**(적재 규모 확대 — 계층형 수집 전략)을 실제로 실행하기 위한
> 명령어 모음. 설계 근거·프로필 값의 이유는 이 문서가 아니라 **6-10**을 참고할 것 —
> 여기는 순서와 명령어만 담는다.
>
> ⚠️ **6-5의 프로필 v1(5,000편 목표)은 6-10으로 대체됐다.** 이 런북도 v2 기준으로 갱신됐다.

**PowerShell**과 **bash(Git Bash)** 두 버전을 함께 적어둔다. 일반 Windows Terminal은
PowerShell이므로 그쪽을 쓰면 된다 — bash 문법(`curl -d`, `$(...)`, `tee -a`)은 Git Bash
전용이라 PowerShell에서는 그대로 실행되지 않는다.

---

## ⚠️ 이전 실행(2026-08-24)과 달라진 점

| | v1 (4,609편) | **v2 (이번)** |
|---|---|---|
| discover 호출 | 7회 | **33회** |
| 예상 소요 | 약 40분 | **약 2시간** |
| 예상 적재 | 5,000 목표 → 4,609 | **약 1.8만~2만편** |
| 실행 순서 기준 | 박스오피스 먼저(잔여 #10 표본) | **좁은 프로필 먼저**(프로필별 기여도 계측) |
| 로그 | `*>` 덮어쓰기 → **유실** | **`*>>` + 결과 전용 로그 분리** |

**세 가지가 새로 중요해졌다.**

1. **토큰이 반드시 만료된다.** 2시간 실행에 accessToken은 30분짜리다. 지난번에도 24분
   시점에 실제로 터졌다. 이번엔 **401을 만나면 자동 재발급하는 헬퍼**를 쓴다(§2).
2. **로그를 잃으면 계측이 무산된다.** 33회 호출의 `SeedResult`가 잔여 #29의 유일한 근거다.
   PowerShell `*>`는 **덮어쓰기**라 서버를 재기동하는 순간 날아간다 — `*>>`를 쓰고,
   **API 응답은 `seed-result.log`에 따로 남긴다.**
3. **프로필 ④(전역 `pages=400`)는 단일 호출로 약 1시간이다.** 중단되면 **처음부터**다
   (시작 페이지 지정 파라미터가 없다). 그래서 **맨 마지막에 돌린다** — 실패해도 나머지
   32회의 결과는 이미 확보돼 있다.

---

## 0. 선행 확인

```
□ 잔여 #30 코드 반영 — TmdbClient.discoverMovies 에 withGenres 추가
   (AdminController 의 genres 쿼리 파라미터까지 3계층 pass-through)
   ⚠️ 미반영이면 §5-② 장르 보강 19회를 돌릴 수 없다. 나머지는 그대로 실행 가능
□ 참조 테이블 시드 — 비어 있으면 REFERENCE_DATA_NOT_SEEDED(500)
   POST /api/admin/genres/seed
   POST /api/admin/countries/seed
□ 스키마 v15 적용 상태 (= review.rating 제거본). 6-10 은 스키마 변경을 요구하지 않는다
□ 디스크 — movie_actor 가 약 81만 행까지 늘어난다
```

## 1. 서버 기동 (로그를 덮어쓰지 않는다)

별도 터미널 창(탭)에서 띄워두고, 아래 단계는 다른 창에서 진행한다.

**PowerShell**
```powershell
Set-Location "C:\Users\hiw73\capstone\cinemory-backend"
./gradlew bootRun *>> seed.log      # ⚠️ *> 가 아니라 *>> (덮어쓰기 방지)
```

**bash**
```bash
cd /c/Users/hiw73/capstone/cinemory-backend
./gradlew bootRun >> seed.log 2>&1 &
# "Started CinemoryApplication"이 seed.log에 뜰 때까지 대기 후 다음 단계
```

## 2. 관리자 로그인 + 토큰 자동 재발급 헬퍼

**PowerShell**
```powershell
$base = "http://localhost:8080"

$body = @{ email = "hiu8525@gmail.com"; password = "lukaku5698" } | ConvertTo-Json
$login = Invoke-RestMethod -Uri "$base/api/auth/login" -Method Post -Body $body -ContentType "application/json"
$global:token   = $login.accessToken
$global:refresh = $login.refreshToken

function Reissue-Token {
    $b = @{ refreshToken = $global:refresh } | ConvertTo-Json
    $r = Invoke-RestMethod -Uri "$using:base/api/auth/reissue" -Method Post -Body $b -ContentType "application/json"
    $global:token = $r.accessToken
    if ($r.refreshToken) { $global:refresh = $r.refreshToken }   # 회전형 대응
    Write-Host "[token reissued]"
}

function Invoke-Seed([string]$url) {
    foreach ($try in 1..2) {
        try {
            $r = Invoke-RestMethod -Uri $url -Method Post -TimeoutSec 0 `
                    -Headers @{ Authorization = "Bearer $global:token" }
            "$(Get-Date -Format s)`t$url`t$($r | ConvertTo-Json -Compress)" |
                Tee-Object -FilePath seed-result.log -Append
            return
        } catch {
            $code = $_.Exception.Response.StatusCode.value__
            if ($code -eq 401 -and $try -eq 1) { Reissue-Token; continue }
            "$(Get-Date -Format s)`t$url`tERROR $code $($_.Exception.Message)" |
                Tee-Object -FilePath seed-result.log -Append
            return
        }
    }
}
```

> ⚠️ `Reissue-Token` 안의 `$using:base`는 함수를 **스크립트 파일로 저장해 실행할 때** 문제가
> 된다. 콘솔에 그대로 붙여넣어 쓸 경우 `$base`를 `$global:base = "http://localhost:8080"`으로
> 선언하고 함수 안에서도 `$global:base`로 참조하면 안전하다.
>
> ⚠️ **`-TimeoutSec 0`(무제한)이 필요하다.** 프로필 ④가 한 시간짜리 단일 요청이다.

**bash**
```bash
BASE="http://localhost:8080"

LOGIN=$(curl -s -X POST "$BASE/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"email":"hiu8525@gmail.com","password":"12345678!"}')
TOKEN=$(echo "$LOGIN"   | grep -o '"accessToken":"[^"]*'  | cut -d'"' -f4)
REFRESH=$(echo "$LOGIN" | grep -o '"refreshToken":"[^"]*' | cut -d'"' -f4)

reissue() {
  local r
  r=$(curl -s -X POST "$BASE/api/auth/reissue" \
        -H "Content-Type: application/json" -d "{\"refreshToken\":\"$REFRESH\"}")
  TOKEN=$(echo "$r" | grep -o '"accessToken":"[^"]*' | cut -d'"' -f4)
  local nr; nr=$(echo "$r" | grep -o '"refreshToken":"[^"]*' | cut -d'"' -f4)
  [ -n "$nr" ] && REFRESH="$nr"
  echo "[token reissued]"
}

seed() {
  local url="$1" out code
  out=$(curl -s -w '\n%{http_code}' -X POST "$url" -H "Authorization: Bearer $TOKEN")
  code=$(echo "$out" | tail -1)
  if [ "$code" = "401" ]; then
    reissue
    out=$(curl -s -w '\n%{http_code}' -X POST "$url" -H "Authorization: Bearer $TOKEN")
    code=$(echo "$out" | tail -1)
  fi
  printf '%s\t%s\t%s %s\n' "$(date -Is)" "$url" "$code" "$(echo "$out" | sed '$d')" \
    | tee -a seed-result.log
}
```

## 3. 박스오피스 수집 (2주치 이상) + 재매칭

`box-office/sync`는 하루 단위라 `targetDate`를 바꿔가며 반복 호출해야 한다.

**PowerShell**
```powershell
foreach ($i in 1..14) {
    $date = (Get-Date).AddDays(-$i).ToString("yyyy-MM-dd")
    Invoke-Seed "$base/api/admin/box-office/sync?targetDate=$date"
}
Invoke-Seed "$base/api/admin/box-office/rematch"
```

**bash**
```bash
for i in $(seq 1 14); do
  seed "$BASE/api/admin/box-office/sync?targetDate=$(date -d "-$i day" +%F)"
done
seed "$BASE/api/admin/box-office/rematch"
```

## 4. 박스오피스 역방향 영화 시드

> 6-5는 *"다른 시드보다 먼저"* 를 **잔여 #10 표본 확보**를 이유로 요구했다. 그 항목은
> 6-7-b에서 **90.7%로 종결**됐으므로 순서 강제는 풀렸다. 다만 대상이 140여 건으로 가장
> 좁으므로 **6-10의 "좁은 것부터" 원칙에도 여전히 맨 앞**이다.

**PowerShell**
```powershell
Invoke-Seed "$base/api/admin/movies/seed/box-office"
```

**bash**
```bash
seed "$BASE/api/admin/movies/seed/box-office"
```

## 5. discover 시드 — 프로필 v2 (33회, 약 2시간)

**순서를 지킬 것.** 넓은 프로필을 먼저 돌리면 나머지가 전부 `alreadyExists`로 빠져
**프로필별 기여 편수를 계측할 수 없다**(잔여 #29의 근거가 사라진다).

| 순서 | 프로필 | 호출 수 | 요청 편수 |
|---|---|---|---|
| ① | 한국 영화 | 1 | 3,000 |
| ② | 장르 보강 | 19 | 5,700 |
| ③ | 최근작 | 12 | 4,800 |
| ④ | 전역 인지도 | 1 | 8,000 |

**PowerShell**
```powershell
# ① 한국 영화 — minVotes 30→10, pages 50→150
Invoke-Seed "$base/api/admin/movies/seed/discover?pages=150&lang=ko&minVotes=10&sortBy=vote_count.desc"

# ② 장르 보강 (신규) — TMDB 장르 19종
$genres = 28,12,16,35,80,99,18,10751,14,36,27,10402,9648,10749,878,10770,53,10752,37
foreach ($g in $genres) {
    Invoke-Seed "$base/api/admin/movies/seed/discover?pages=15&genres=$g&minVotes=30&sortBy=vote_count.desc"
}

# ③ 최근작 — 2015~2026, 하한 100→30 (2026년작은 아직 표가 안 쌓였다)
foreach ($year in 2015..2026) {
    Invoke-Seed "$base/api/admin/movies/seed/discover?pages=20&year=$year&minVotes=30&sortBy=popularity.desc"
}

# ④ 전역 인지도 — pages 100→400 (⚠️ 단일 호출 약 1시간, 중단 시 처음부터)
Invoke-Seed "$base/api/admin/movies/seed/discover?pages=400&minVotes=50&sortBy=vote_count.desc"
```

**bash**
```bash
# ① 한국 영화
seed "$BASE/api/admin/movies/seed/discover?pages=150&lang=ko&minVotes=10&sortBy=vote_count.desc"

# ② 장르 보강 (신규) — TMDB 장르 19종
for G in 28 12 16 35 80 99 18 10751 14 36 27 10402 9648 10749 878 10770 53 10752 37; do
  seed "$BASE/api/admin/movies/seed/discover?pages=15&genres=$G&minVotes=30&sortBy=vote_count.desc"
done

# ③ 최근작 — 2015~2026
for YEAR in $(seq 2015 2026); do
  seed "$BASE/api/admin/movies/seed/discover?pages=20&year=$YEAR&minVotes=30&sortBy=popularity.desc"
done

# ④ 전역 인지도 (⚠️ 약 1시간, 맨 마지막)
seed "$BASE/api/admin/movies/seed/discover?pages=400&minVotes=50&sortBy=vote_count.desc"
```

> **장르 ID는 TMDB 값 그대로 넘긴다**(우리 `genre.id`가 아니라 `genre.tmdb_genre_id`).
> 파라미터가 TMDB `with_genres`로 pass-through되기 때문이다.
> ⚠️ **`with_genres`는 콤마가 AND, 파이프(`|`)가 OR다.** 위처럼 하나씩 넘기면 무관하지만,
> 나중에 묶어 넘길 때 콤마로 이으면 **교집합이 되어 결과가 급감**한다.
>
> ⚠️ **프로필 ④의 `pages=400`은 TMDB 상한 500 이내지만 450을 넘기지 말 것.**

## 6. 완료 후 로그 확인

**PowerShell**
```powershell
# SeedResult 33건 — 프로필별 기여 편수 (잔여 #29의 근거)
Get-Content seed-result.log

(Select-String -Path seed.log -Pattern "길이 초과로 절단").Count   # 4,609편에선 29건(character_name)
(Select-String -Path seed.log -Pattern "제목 매칭 실패").Count     # 잔여 #10 — 90.7% 대비 변화
Select-String -Path seed.log -Pattern "rate limit" -CaseSensitive:$false
(Select-String -Path seed.log -Pattern "폴백").Count               # 잔여 #19 — 11.9% 대비 변화
```

**bash**
```bash
cat seed-result.log
grep -c "길이 초과로 절단" seed.log
grep -c "제목 매칭 실패" seed.log
grep -i "rate limit" seed.log
grep -c "폴백" seed.log
```

⚠️ **절단 로그는 어느 컬럼인지까지 볼 것.** 4,609편에서는 **`character_name`만** 29건이었고
`title`·`original_title`·`person.name`은 **0건**이었다. 규모가 4.3배가 되면서 **이 세 컬럼에서
처음 절단이 나오는지**가 관전 포인트다 — 나온다면 그때는 `varchar(255)` 확장(v16)이
**실제 가치를 갖는다**(잔여 #28을 여기에 묶어서 처리하면 된다).

## 7. 적재 결과 검증 (잔여 #29)

```sql
-- ① 프로필 ④ pages=400 의 효과 — vote_count 2,000~6,000 구간이 6-10의 표적이다
SELECT title, vote_count FROM movie
 WHERE title IN ('올드보이','미드소마','살인의 추억','기생충','버닝')
 ORDER BY vote_count DESC;
SELECT COUNT(*) FROM movie WHERE vote_count BETWEEN 2000 AND 6000;

-- ② 한국 영화 — 6-7-b 실측 843편 대비 (minVotes 30→10, pages 50→150)
SELECT c.name, COUNT(*) AS cnt
  FROM movie_country mc JOIN country c ON c.id = mc.country_id
 GROUP BY c.name ORDER BY cnt DESC LIMIT 10;

-- ③ 장르별 편수 — 프로필 ②의 존재 이유. 하위 장르(다큐·서부·음악)가 핵심
SELECT g.name, COUNT(*) AS cnt
  FROM movie_genre mg JOIN genre g ON g.id = mg.genre_id
 GROUP BY g.name ORDER BY cnt ASC;

-- ④ 전체 규모 — 6-10의 추정(movie 2만 / person 38만 / movie_actor 81만)과 대조
SELECT (SELECT COUNT(*) FROM movie)        AS movies,
       (SELECT COUNT(*) FROM person)       AS persons,
       (SELECT COUNT(*) FROM movie_actor)  AS movie_actors;

-- ⑤ v13 컬럼 — 신규 적재분은 syncFromTmdb가 그 자리에서 채우므로 resync 불필요
SELECT COUNT(*), SUM(original_title IS NOT NULL), SUM(vote_average IS NOT NULL) FROM movie;
```

**결과는 `tmdb-sync-spec.md`에 6-7-c로 기록한다**(6-7 / 6-7-b와 같은 형식).

---

## 참고

- `SEED_ALREADY_RUNNING`(409)이 뜨면 이전 호출이 아직 안 끝난 것 — 순차 실행이라 겹쳐
  부르면 안 된다. `Invoke-Seed`/`seed`는 **동기 호출**이라 순서대로 기다린다.
- 각 discover 응답의 `stoppedByRateLimit: true`를 확인한다 — true면 429로 중단된 것이니
  몇 페이지째인지 로그에서 확인 후 나중에 다시 실행한다. **시작 페이지 지정은 없어
  처음부터 다시 돌지만**, `existsByTmdbId`로 이미 적재분은 detail 호출을 건너뛰므로
  비용은 discover 페이지 재조회분뿐이다.
- 실제 적재 건수는 겹치는 영화가 `alreadyExists`로 빠져 21,500에 맞지 않는다 —
  **1.8만~2만편 예상**(6-10 참고). 프로필 ②는 ④와 겹침이 특히 크다.
- PowerShell에서 `Invoke-RestMethod`는 HTTP 4xx/5xx를 **예외로 던진다**(bash `curl`이 상태
  코드와 무관하게 본문을 반환하는 것과 다르다) — `Invoke-Seed`의 `try/catch`가 그래서 필요하다.
  이게 없으면 429·409·401을 만나는 순간 스크립트 전체가 멈춘다.
- **`resync`는 이번에 돌리지 않는다.** 신규 적재분은 `syncFromTmdb`가 v13 컬럼까지 그 자리에서
  채운다. 다만 적재가 2만편이 되면 **다음 `resync` 전량 실행은 약 2시간 10분**이 된다
  (4,609편 / 29분 30초 = 분당 155.5건 기준) — 주기 실행을 걸 때 잔여 #31을 함께 볼 것.
