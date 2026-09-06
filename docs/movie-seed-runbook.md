# 영화 데이터 적재 런북

> `tmdb-sync-spec.md` 6-5(적재 전략)를 실제로 실행하기 위한 명령어 모음.
> 설계 근거·프로필 값의 이유는 이 문서가 아니라 `tmdb-sync-spec.md` 6-5를 참고할 것 —
> 여기는 순서와 명령어만 담는다.

**PowerShell**과 **bash(Git Bash)** 두 버전을 함께 적어둔다. 일반 Windows Terminal은
PowerShell이므로 그쪽을 쓰면 된다 — bash 문법(`curl -d`, `$(...)`, `tee -a`)은 Git Bash
전용이라 PowerShell에서는 그대로 실행되지 않는다.

참조 테이블(genre/country)은 이미 시드돼 있다는 전제다 — 비어 있으면
`REFERENCE_DATA_NOT_SEEDED`(500)가 나므로 먼저 `POST /api/admin/genres/seed`,
`POST /api/admin/countries/seed`를 호출할 것.

---

## 0. 서버 기동 (로그 파일로 남기기)

별도 터미널 창(탭)에서 띄워두고, 아래 단계는 다른 창에서 진행한다.

**PowerShell**
```powershell
Set-Location "C:\Users\hiw73\capstone\cinemory-backend"
./gradlew bootRun *> seed.log
```

**bash**
```bash
cd /c/Users/hiw73/capstone/cinemory-backend
./gradlew bootRun > seed.log 2>&1 &
# "Started CinemoryApplication"이 seed.log에 뜰 때까지 대기 후 다음 단계
```

## 1. 관리자 로그인 → 토큰 발급

**PowerShell**
```powershell
$body = @{ email = "hiu8525@gmail.com"; password = "12345678!" } | ConvertTo-Json
$login = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/login" -Method Post -Body $body -ContentType "application/json"
$token = $login.accessToken
$refresh = $login.refreshToken
$token
```

**bash**
```bash
LOGIN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"hiu8525@gmail.com","password":"12345678!"}')
TOKEN=$(echo "$LOGIN" | grep -o '"accessToken":"[^"]*' | cut -d'"' -f4)
REFRESH=$(echo "$LOGIN" | grep -o '"refreshToken":"[^"]*' | cut -d'"' -f4)
echo "$TOKEN"
```

⚠️ **accessToken은 30분 만료**다. 아래 시퀀스 전체가 30분을 넘길 수 있으니, 중간에
401(Unauthorized)이 나오면 재발급한다.

**PowerShell**
```powershell
$body = @{ refreshToken = $refresh } | ConvertTo-Json
$login = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/reissue" -Method Post -Body $body -ContentType "application/json"
$token = $login.accessToken
```

**bash**
```bash
LOGIN=$(curl -s -X POST http://localhost:8080/api/auth/reissue \
  -H "Content-Type: application/json" \
  -d "{\"refreshToken\":\"$REFRESH\"}")
TOKEN=$(echo "$LOGIN" | grep -o '"accessToken":"[^"]*' | cut -d'"' -f4)
```

## 2. 박스오피스 수집 (2주치 이상) + 재매칭

`box-office/sync`는 하루 단위라 `targetDate`를 바꿔가며 반복 호출해야 한다.

**PowerShell**
```powershell
for ($i = 1; $i -le 14; $i++) {
    $date = (Get-Date).AddDays(-$i).ToString("yyyy-MM-dd")
    try {
        $result = Invoke-RestMethod -Uri "http://localhost:8080/api/admin/box-office/sync?targetDate=$date" `
            -Method Post -Headers @{ Authorization = "Bearer $token" }
        $result | ConvertTo-Json -Compress | Out-File -FilePath seed.log -Append -Encoding utf8
    } catch {
        $_.Exception.Message | Out-File -FilePath seed.log -Append -Encoding utf8
    }
}

$result = Invoke-RestMethod -Uri "http://localhost:8080/api/admin/box-office/rematch" `
    -Method Post -Headers @{ Authorization = "Bearer $token" }
$result | ConvertTo-Json -Compress | Tee-Object -FilePath seed.log -Append
```

**bash**
```bash
for i in $(seq 1 14); do
  DATE=$(date -d "-$i day" +%F)
  curl -s -X POST "http://localhost:8080/api/admin/box-office/sync?targetDate=$DATE" \
    -H "Authorization: Bearer $TOKEN" >> seed.log
  echo "" >> seed.log
done

curl -s -X POST "http://localhost:8080/api/admin/box-office/rematch" \
  -H "Authorization: Bearer $TOKEN" | tee -a seed.log
```

## 3. 박스오피스 역방향 영화 시드

⚠️ **다른 시드보다 먼저 실행한다.** discover가 먼저 돌면 역방향 시드 대상이
`alreadyExists`로 빠져 잔여 #10(제목 매칭 실패율) 표본이 줄어든다.

**PowerShell**
```powershell
$result = Invoke-RestMethod -Uri "http://localhost:8080/api/admin/movies/seed/box-office" `
    -Method Post -Headers @{ Authorization = "Bearer $token" }
$result | ConvertTo-Json -Compress | Tee-Object -FilePath seed.log -Append
```

**bash**
```bash
curl -s -X POST "http://localhost:8080/api/admin/movies/seed/box-office" \
  -H "Authorization: Bearer $TOKEN" | tee -a seed.log
```

## 4. discover 시드 — 프로필 3종 (순서대로, 각 5~10분)

**PowerShell**
```powershell
# ① 한국 영화
$result = Invoke-RestMethod -Uri "http://localhost:8080/api/admin/movies/seed/discover?pages=50&lang=ko&minVotes=30&sortBy=vote_count.desc" `
    -Method Post -Headers @{ Authorization = "Bearer $token" }
$result | ConvertTo-Json -Compress | Tee-Object -FilePath seed.log -Append

# ② 전역 인지도
$result = Invoke-RestMethod -Uri "http://localhost:8080/api/admin/movies/seed/discover?pages=100&minVotes=300&sortBy=vote_count.desc" `
    -Method Post -Headers @{ Authorization = "Bearer $token" }
$result | ConvertTo-Json -Compress | Tee-Object -FilePath seed.log -Append

# ③ 최근작 (2021~2025, 연도별 호출)
foreach ($year in 2021..2025) {
    $result = Invoke-RestMethod -Uri "http://localhost:8080/api/admin/movies/seed/discover?pages=20&year=$year&minVotes=100" `
        -Method Post -Headers @{ Authorization = "Bearer $token" }
    $result | ConvertTo-Json -Compress | Tee-Object -FilePath seed.log -Append
}
```

**bash**
```bash
# ① 한국 영화
curl -s -X POST "http://localhost:8080/api/admin/movies/seed/discover?pages=50&lang=ko&minVotes=30&sortBy=vote_count.desc" \
  -H "Authorization: Bearer $TOKEN" | tee -a seed.log

# ② 전역 인지도
curl -s -X POST "http://localhost:8080/api/admin/movies/seed/discover?pages=100&minVotes=300&sortBy=vote_count.desc" \
  -H "Authorization: Bearer $TOKEN" | tee -a seed.log

# ③ 최근작 (2021~2025, 연도별 호출)
for YEAR in 2021 2022 2023 2024 2025; do
  curl -s -X POST "http://localhost:8080/api/admin/movies/seed/discover?pages=20&year=$YEAR&minVotes=100" \
    -H "Authorization: Bearer $TOKEN" | tee -a seed.log
  echo "" >> seed.log
done
```

## 5. 완료 후 로그 확인 (6-5 검증 체크리스트)

**PowerShell**
```powershell
(Select-String -Path seed.log -Pattern "길이 초과로 절단").Count   # 60편에선 0건 — 5,000편 규모에서 재확인
(Select-String -Path seed.log -Pattern "제목 매칭 실패").Count     # 잔여 #10 — 실패율 판단 근거
Select-String -Path seed.log -Pattern "rate limit" -CaseSensitive:$false
(Select-String -Path seed.log -Pattern "폴백").Count
```

**bash**
```bash
grep -c "길이 초과로 절단" seed.log
grep -c "제목 매칭 실패" seed.log
grep -i "rate limit" seed.log
grep -c "폴백" seed.log
```

## 6. v13 컬럼 검증

신규 적재분은 `syncFromTmdb`가 그 자리에서 v13 컬럼(originalTitle/backdropPath/
voteAverage/voteCount)까지 채우므로 이번엔 `resync`가 필요 없다. 전체 채움 여부만 확인:

```sql
SELECT COUNT(*), SUM(original_title IS NOT NULL), SUM(vote_average IS NOT NULL) FROM movie;
```

---

## 참고

- `SEED_ALREADY_RUNNING`(409)이 뜨면 이전 호출이 아직 안 끝난 것 — 순차 실행이라 겹쳐
  부르면 안 된다.
- 각 discover 호출 응답의 `stoppedByRateLimit: true`를 확인한다 — true면 429로 중단된
  것이니 몇 페이지째인지 로그에서 확인 후 필요시 나중에 이어서 실행하면 된다(현재
  `pages`만 받고 시작 페이지 지정은 없어 처음부터 다시 돌지만, `existsByTmdbId`로 이미
  적재분은 건너뛰므로 비용만 좀 더 든다).
- 실제 적재 건수는 겹치는 영화가 `alreadyExists`로 빠져 5,000에 정확히 맞지 않는다 —
  4,000~4,500 예상(`tmdb-sync-spec.md` 6-5 참고).
- PowerShell에서 `Invoke-RestMethod`는 HTTP 4xx/5xx 응답을 **예외로 던진다**(bash의
  `curl`은 상태 코드와 무관하게 본문을 반환하는 것과 다르다) — 위 스크립트의 `try/catch`가
  그래서 필요하다. `catch` 블록 없이 그냥 실행하면 429·409를 만나는 순간 스크립트가 멈춘다.
