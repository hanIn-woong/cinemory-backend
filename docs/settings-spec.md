# CineMory — 설정 화면 고도화 스펙 (백엔드 · 운영)

> **이 문서는 새 세션이 컨텍스트 없이 착수할 수 있도록 쓴 인수인계 문서다.** 2026-10-31 설정 설계 세션에서 확정한 결정·근거·계약.
> 앱 화면은 `cinemory-app/docs/settings-screen-spec.md`. 개인정보처리방침·이용약관·삭제 요청 페이지 **초안**은 `docs/legal/`.
> 계약은 구현 후 `controller-layer-spec.md`로 옮긴다.

| 단계 | 내용 | 비고 |
|---|---|---|
| **S-1** | 모든 기기 로그아웃 · 내 기록 내보내기 · 가입 동의 기록 · 개인정보 보정(위치 로그·보관 기간 정리) | 백엔드 |
| **S-2** | 법적 페이지 3종 정적 게시(Nginx) | **초안 검토·`[확인 필요]` 해소 후** — 그 전에는 게시하지 않는다 |
| **S-A** | 앱 설정 화면 재구성(앱 스펙) | 앱 화면 중 S-1·S-2가 필요 없는 부분(연결 관리·계정 정보·문의·버전·라이선스)은 먼저 해도 된다 |

**제약 — 네이티브 모듈을 추가하지 않는다**(EAS 빌드 한도, 2026-10-31). 아래 설계는 전부 JS 또는 서버로만 풀었다(ST-5 내보내기가 파일 저장 모듈 대신 브라우저 다운로드를 쓰는 이유).

---

## 1. 확정 결정 (2026-10-31)

| # | 항목 | 결정 | 이유 |
|---|---|---|---|
| ST-1 | 화면 구조 | **계정 / 내 데이터 / 정보 / 지원** 4구역 + 맨 아래 로그아웃·회원 탈퇴(앱 스펙 1절) | 지금은 비밀번호 변경·로그아웃·탈퇴 3개뿐이고, 들어올 항목이 9개라 묶음이 필요하다 |
| ST-2 | 계정 정보 | 이메일 · 가입일 · 로그인 수단(비밀번호 / 카카오 / 구글) 표시 — **백엔드 변경 없음**(`GET /me` + `GET /me/social-accounts`) | |
| ST-3 | **연결된 계정** | 카카오·구글 연결/해제 화면 — **백엔드 변경 없음**(account-integrity D-2-A API 3종, 10/10 구현). 앱만 | **API는 있는데 화면이 없어** 계정 연결(10월 우선순위 2번)이 앱에서 막혀 있었다 |
| ST-4 | **모든 기기에서 로그아웃** | `POST /api/auth/logout-all` — **이 기기를 포함해** 그 사용자의 리프레시 토큰을 전부 폐기. 사유는 기존 **`LOGOUT`** 재사용 | "이 기기 제외"는 현재 리프레시 토큰을 보내야 해 계약이 커진다 — 끊고 다시 로그인하는 편이 단순하고 비밀번호 변경의 전체 폐기와 같은 모양이다. `LOGOUT`은 유예 창 대상이 아니라 의미가 맞고, `revoked_reason`이 MySQL ENUM이라 새 값은 마이그레이션이 필요하다 — 재사용으로 피한다 |
| ST-5 | **내 기록 내보내기** | **ZIP(CSV 4개) — 1회용 다운로드 링크를 앱이 브라우저로 연다** | 파일 저장·공유는 `expo-file-system`·`expo-sharing`(네이티브)이 필요한데 빌드 한도로 추가하지 않는다. 브라우저 다운로드는 모듈이 필요 없다. 메일 첨부는 개인 기록이 메일함에 남아 기각 |
| ST-6 | **법적 페이지** | 개인정보처리방침 `/privacy` · 이용약관 `/terms` · 계정 삭제 요청 `/account-deletion` — **운영 Nginx 정적 HTML**(`deploy/web/`, `reset-password.html`과 같은 방식). 앱은 링크로 연다 | Play 출시(M5) 필수 항목(방침 URL, 탈퇴 W-7 웹 삭제 요청). 앱 번들에 넣으면 고칠 때마다 빌드가 필요하다 |
| ST-7 | **가입 동의** | 가입 시 **(필수) 이용약관 동의 · 개인정보처리방침 확인 · 만 14세 이상** 체크 — 서버에 `user.terms_agreed_at` 기록. 소셜 가입은 **닉네임 설정 화면(PA-2)에서** 함께 | 동의했다는 기록이 서버에 있어야 증명이 된다. 방침이 "만 14세 미만 가입 불가"라고 쓰는데 지금은 확인 장치가 없다 |
| ST-8 | 문의하기 · 앱 버전 | `mailto:cadmin295@gmail.com`(제목·앱 버전 자동 채움) · `app.config.ts`의 `version` 표시 — 앱만 | 문의 메일은 사용자 결정(2026-10-31) |
| ST-9 | 오픈소스 라이선스 · **데이터 출처** | 빌드 전 스크립트가 만든 JSON을 화면에 표시(앱만). 같은 화면에 **TMDB·KOBIS·공공데이터포털·카카오맵 출처** | TMDB는 API 약관상 출처 표기가 **의무**다("This product uses the TMDB API but is not endorsed or certified by TMDB." + 로고) — 지금 앱 어디에도 없다 |
| ST-10 | **개인정보 보정** | ① 주변 극장 요청의 **좌표가 Nginx 접속 로그에 남지 않게** ② 앱이 좌표를 **소수 3자리(약 110m)로 반올림**해 보낸다 ③ **보관 기간 정리 작업** — 관리자 감사 로그 1년, 만료·폐기된 리프레시 토큰 90일 | 방침 초안을 쓰며 코드와 대조하다 발견(3절). Nginx 기본 접속 로그가 요청 URL 전체를 남겨 `?latitude=…&longitude=…`가 IP와 함께 보관된다 — 방침의 "위치를 저장하지 않는다"가 거짓이 된다. 정리 작업이 없으면 방침에 보관 기간을 적을 수 없다 |

## 2. API · 서버

### 2-1. `POST /api/auth/logout-all` (ST-4, 인증 필수)

- `refreshTokenRepository.revokeAllByUserId(userId, now, RevokedReason.LOGOUT)` → **204**.
- ⚠️ 기존 `revokeAllByUserId`는 `REQUIRES_NEW`다(비밀번호 변경 주석) — 이 API는 그 한 동작뿐이라 순서 문제는 없다.
- 남은 Access Token은 TTL(30분)까지 유효 — L-3 그대로(앱은 성공 즉시 로컬 로그아웃).
- Nginx 인증 레이트 리밋 대상에 넣지 않는다(인증 필수라 남용 경로가 아니다).

### 2-2. 내보내기 (ST-5)

| 메서드 · 경로 | 인증 | 동작 |
|---|---|---|
| `POST /api/users/me/export` | 필수 | 1회용 토큰 발급 → `{ downloadUrl, expiresAt }`. 같은 사용자 **1분에 1회**(초과 429 `TOO_MANY_REQUESTS` 형식 — 기존 `RateLimitedException` 재사용) |
| `GET /api/export/download?token=` | **없음**(토큰이 인증) | 토큰 검증·소비 → ZIP 스트리밍, `Content-Disposition: attachment; filename="cinemory-export-YYYYMMDD.zip"` |

- 토큰: 32바이트 `SecureRandom` → Base64URL. 서버에는 **SHA-256 해시 → userId**만 Caffeine에(TTL **10분**, 단일 인스턴스 전제). 소비 시 즉시 제거(**1회용**). 서버 재시작으로 사라지면 사용자가 다시 누른다 — 테이블을 만들지 않는다.
- 실패(없음·만료·사용됨)는 구분 없이 **410 Gone + 짧은 HTML 안내**("링크가 만료됐어요. 앱에서 다시 시도해 주세요") — 브라우저에서 열리는 응답이라 JSON이 아니다.
- ⚠️ 토큰이 URL에 있다 — Nginx `location = /api/export/download { access_log off; }` + `Referrer-Policy: no-referrer`, `Cache-Control: no-store`(재설정 링크 L-17과 같은 대응). `SecurityConfig` 화이트리스트에 GET 한 경로 추가.
- **ZIP 구성** — UTF-8 **BOM 포함**(엑셀에서 한글이 깨지지 않게), 쉼표·따옴표·줄바꿈은 RFC 4180 따옴표 처리:

| 파일 | 열 |
|---|---|
| `watch_records.csv` | `WatchedDate, Title, OriginalTitle, Year, tmdbID, Rating10, Rating5, WatchType, Place, OttPlatform, PrivateMemo, Rewatch, CreatedAt` |
| `reviews.csv` | `Title, Year, tmdbID, Review, CreatedAt, UpdatedAt` |
| `wishes.csv` | `Title, Year, tmdbID, AddedAt` |
| `collections.csv` | `Collection, Description, CollectionOrder, Title, Year, tmdbID, MovieOrder` |

  - 열 이름은 기획노트 8절 "영화 기록 가져오기"의 템플릿(Letterboxd 가져오기 형식과 같은 이름 — `tmdbID`·`Title`·`Year`·`Rating10`·`WatchedDate`·`Rewatch`·`Review`)과 맞춘다 → **나중에 가져오기를 만들면 그대로 되돌려 넣을 수 있다.**
  - `Rewatch` = 같은 영화의 두 번째 이후 기록이면 `true`(관람일 오름차순 기준). `PrivateMemo`는 본인 데이터라 포함한다.
- 생성은 사용자 한 명 범위의 **IN절 벌크 조회 + 스트리밍 쓰기**(`ZipOutputStream` — 의존성 추가 없음). 기록 수천 건이 기준. `@Transactional(readOnly = true)`.

### 2-3. 가입 동의 기록 (ST-7)

- V-다음: `ALTER TABLE user ADD COLUMN terms_agreed_at DATETIME NULL` — 확장. **기존 사용자는 `NULL`**(소급하지 않는다 — 지금까지의 가입 화면에 동의 절차가 없었다는 사실을 그대로 남긴다).
- 로컬 가입 `SignUpLocalRequest`에 `agreeToTerms: boolean` — `@AssertTrue`(아니면 400). 저장 시 `terms_agreed_at = now`.
- 소셜 가입은 가입 순간에 화면이 없다 → `POST /api/users/me/terms-agreement`(인증 필수, 204, **멱등** — 이미 있으면 그대로). 앱의 닉네임 설정 화면(PA-2)이 "시작하기"에서 닉네임 저장과 함께 호출.
- 서버는 동의 없는 사용자를 막지 않는다(기존 사용자·앱 종료 케이스) — 기록만 한다. 약관 개정 시 재동의가 필요해지면 `terms_version`을 그때 추가(지금은 하나뿐).

### 2-4. 개인정보 보정 (ST-10)

| # | 대상 | 변경 |
|---|---|---|
| ① | Nginx `api.` 서버 블록 | `location = /api/theaters/nearby { access_log off; proxy_pass …; }` — 다른 API 로그는 그대로. 기존 `/api/` location의 프록시 헤더·타임아웃을 **그대로 복사**해야 한다(Nginx location은 상속되지 않는 지시어가 있다 — `add_header` 함정은 이미 문서화됨) |
| ② | 앱 | 좌표를 `Math.round(x * 1000) / 1000`으로 보낸다(앱 스펙) — 서버 변경 없음 |
| ③ | 앱 로그 | 서버 코드가 좌표·자연어 질의를 로그에 남기지 않는지 확인(`grep`), 남기면 제거 |
| ④ | 보관 정리 | `RetentionCleanupScheduler`(매일 04:10, `SchedulingConfig` 기존 사용) — `admin_action_log`의 `created_at < now − 1년` 삭제, `refresh_token`의 `expires_at < now − 90일` 삭제(BD-9 — 대시보드 최대 기간 90일 이상 보관 조건 충족). 1회 최대 5,000행씩 반복 삭제(긴 잠금 방지). 삭제 건수 `INFO` 로그 |
| ⑤ | 운영 확인 | 서버 `/etc/logrotate.d/nginx`의 보관 일수 확인(우분투 기본 `daily` · `rotate 14`) → 방침 초안의 `[확인 필요: 14일]` 확정 |

- ⚠️ 위치정보: 위치를 저장하지 않고 요청 순간에만 쓰더라도, 출시 시 **「위치정보의 보호 및 이용 등에 관한 법률」상 위치기반서비스사업 신고 대상인지** 확인이 필요하다(법률 자문 범위). 캡스톤 범위에서는 ①·② 보정까지 하고 기획노트 M5 항목으로 남긴다.

### 2-5. 법적 페이지 게시 (ST-6, S-2)

- `deploy/web/privacy.html` · `terms.html` · `account-deletion.html` — `docs/legal/*.md`를 HTML로 옮긴다. 외부 리소스 없음(인라인 CSS, 모바일 폭 대응), `reset-password.html`과 같은 헤더(CSP·`X-Content-Type-Options`).
- Nginx: `location = /privacy`·`/terms`·`/account-deletion` → 각 파일(`api.cinemory.co.kr`). 루트 도메인으로 옮기는 것은 나중(주소 체계를 바꾸면 앱 링크와 Play 등록 URL을 같이 바꿔야 한다 — 지금 정하는 주소가 오래 간다는 점에 유의).
- **게시 조건**: 초안의 `[확인 필요]`가 전부 해소되고 사용자 검토가 끝난 뒤. 원문 단일 출처는 `docs/legal/*.md` — HTML을 고칠 때는 md를 먼저 고친다.

## 3. 수집·전송 항목 대조표 (방침 초안의 근거, 2026-10-31 코드 확인)

| 데이터 | 저장 위치 | 외부 전송 | 확인 근거 |
|---|---|---|---|
| 이메일 · 비밀번호(bcrypt) · 닉네임 | `user` | — | `PasswordEncoderConfig`(BCrypt) |
| 소셜 식별값 | `user_social_account` | 카카오·구글(로그인 검증) | 검증기 — 닉네임·사진은 PR-9 이후 읽지 않음 |
| 시청 기록·리뷰·찜·컬렉션·팔로우·프로필 | 각 테이블, 이미지는 S3 | CloudFront(이미지 전송, 해외 엣지 캐시 가능) | Part B |
| 자연어 추천 질의 | 저장 안 함 · Caffeine 결과 캐시 10분 | **Google Gemini API(미국)** — 질의 문장만 | `RecommendationCache` · M3-b. ⚠️ **유료 등급 전환 여부**(deploy-spec 11-A)에 따라 "모델 개선 사용" 문구가 달라진다 |
| 현재 위치(주변 극장) | 저장 안 함 — **단, Nginx 접속 로그에 URL로 남음 → ST-10 ①로 제거** | 카카오맵(기기에서 지도 표시) | `TheaterController` `@RequestParam latitude/longitude` |
| 접속 IP·URL·UA | Nginx 접속 로그(logrotate) · journald | — | `cinemory.conf`에 `access_log` 기본값 |
| 재설정 메일 | `password_reset_token`(해시, 30분) | **Gmail SMTP(미국)** — 이메일 주소 | `PasswordResetMailSender` |
| 리프레시 토큰 | `refresh_token`(해시) — **정리 작업 없음 → ST-10 ④** | — | `RefreshToken` |
| 관리자 감사 로그 | `admin_action_log`(관리자 이메일 스냅샷, 대상 id, IP) — **정리 작업 없음 → ST-10 ④** | — | V27 |
| 백업 | 서버 디스크 일일 덤프 7일 | — | `cinemory-backup.sh` `KEEP_DAYS=7` |
| 분석·광고 SDK | 없음 | — | 앱 `package.json` 확인 |

## 4. 테스트

| # | 시나리오 | 기대 |
|---|---|---|
| ST-T1 | 두 기기 로그인 → `logout-all` | 두 리프레시 토큰 모두 `LOGOUT` 폐기, 재발급 401 |
| ST-T2 | 내보내기 토큰 발급 → 다운로드 → 같은 링크 재사용 | 200 ZIP(4개 파일, BOM, 따옴표 처리) → 410 |
| ST-T3 | 만료(시계 이동) · 잘못된 토큰 | 410(구분 없음) |
| ST-T4 | 1분 안에 두 번 발급 | 두 번째 429 |
| ST-T5 | 남의 데이터가 섞이지 않음(사용자 2명) | 각 ZIP에 본인 행만 |
| ST-T6 | 재관람 3회 기록 | `Rewatch` = false, true, true(관람일 오름차순) |
| ST-T7 | 리뷰 본문에 쉼표·따옴표·줄바꿈 | 엑셀·`csv` 파서로 원문 복원 |
| ST-T8 | 로컬 가입 `agreeToTerms` false/누락 | 400 / true면 `terms_agreed_at` 기록 |
| ST-T9 | `POST /me/terms-agreement` 두 번 | 처음 값 유지(멱등) |
| ST-T10 | 정리 작업 — 1년 1일 전 감사 로그, 91일 전 만료 토큰, 89일 전 만료 토큰 | 앞 둘만 삭제 |

## 5. 기획노트 M5로 넘기는 것

- 위치기반서비스사업 신고 대상 여부 확인(2-4 ⚠️) · 방침·약관 법률 검토 · Gemini 유료 등급 확인 후 방침 문구 확정 · 공지사항(관리자 웹 + 앱, admin-spec 3단계) · 다크 모드 · 이미지 캐시 비우기 · 약관 개정 시 재동의(`terms_version`).

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-10-31 | **신설 — ST-1~ST-10 확정.** 설정 설계 세션: 후보 14개 중 계정(정보·연결 관리·모든 기기 로그아웃)·내보내기·법적 페이지·문의·버전·라이선스를 채택(사용자 결정 — 내보내기·라이선스 포함), 공지사항·다크 모드·캐시 비우기는 출시 단계로. **연결 관리 API가 10/10에 구현됐는데 앱 화면이 없었음**을 발견. EAS 빌드 한도로 네이티브 모듈 없이 설계 — 내보내기는 1회용 링크 + 브라우저 다운로드. 방침 초안을 코드와 대조하다 **주변 극장 좌표가 Nginx 접속 로그에 IP와 함께 남는 문제**, **감사 로그·리프레시 토큰 정리 작업 부재**, **TMDB 출처 표기 누락**을 찾아 ST-9·ST-10에 넣었다. 문의 메일 `cadmin295@gmail.com`(사용자 지정) |
| 2026-10-31 | **S-1 구현 완료(ST-4·ST-5·ST-7·ST-10, 테스트 ST-T1~T10). 계약은 `controller-layer-spec.md` 5-17로 이관**(이 문서 2절은 근거로 남긴다). 스펙과 다르게 한 것은 없다. 스펙이 정하지 않아 구현이 정한 것: ① 429의 code는 `ErrorCode.TOO_MANY_REQUESTS`(Nginx 429와 같은 code·message) ② CSV 칸 규칙 — `Title`은 `displayTitle()`, `Rating5` = `Rating10`÷2(소수 첫째 자리), `Rewatch`는 관람일 없는 기록을 맨 뒤에 놓고 같은 규칙, **빈 컬렉션도 한 줄 유지**(영화 칸만 빈 칸) ③ 핸들러 반환 타입은 `ResponseEntity<StreamingResponseBody>` 고정(성공·410 모두) ④ `expiresAt`은 타임존 표기 없는 서버 시계(KST). Nginx 두 location(`/api/export/download` · `/api/theaters/nearby`)은 `deploy/nginx/cinemory.conf`에 반영 — **서버 적용(`nginx -t` → reload)과 `logrotate` 보관 일수 확인은 운영 체크리스트(DevLog)**. ST-10 ③ 서버 코드 로그 점검 결과는 DevLog. ⚠️ `SignUpLocalRequest.agreeToTerms`가 필수가 돼 **구버전 앱의 로컬 가입은 400** — 앱 가입 화면 릴리스와 같이 나가야 한다. 스키마 V40(`user.terms_agreed_at`, 확장) |
| 2026-10-31 | **S-2 구현 — 법적 페이지 3종 게시(ST-6, 2-5절대로).** `docs/legal/*.md`를 `deploy/web/privacy.html`·`terms.html`·`account-deletion.html`로 옮기고(인라인 CSS·외부 리소스 없음·모바일 폭, 표는 가로 스크롤), Nginx `location = /privacy`·`/terms`·`/account-deletion` 추가(`default-src 'none'` CSP·`Referrer-Policy`·`X-Content-Type-Options`; reset-password와 달리 토큰이 없어 `Cache-Control: no-cache`·접근 로그 기본). **`[확인 필요]` 해소**는 사용자 확인(2026-10-31)에 따라 md의 표시를 지웠다 — Gemini 행은 "모델 개선에 쓰이지 않고 Google 정책상 짧은 기간 후 삭제"(유료 등급 전제 문구) 그대로, 이용약관 제8조는 제재 문구 그대로, 시행일·공고일은 2026년 10월 31일로 채웠다. 접속 기록 보관 14일(logrotate 확인)·공개 범위 명칭 '친구 공개'는 PR #72. md에서 내보내기 범위에 "컬렉션"을 추가(실제 ZIP이 컬렉션을 포함). 방침 6절 "[계정 삭제 요청 페이지]"는 `/account-deletion` 링크로. **서버 설치(파일 3개 복사 + Nginx 블록 + reload)는 사용자 몫 — DevLog** |
