# CineMory DevLog

> 개발 진행 기록 (시간순 작성)

---

## 2026-06 — 기술 스택 확정

- **결정**: React Native (Expo) + Spring Boot + MySQL + TMDB API
- 배경: 원래 Kotlin(Android Native) 고려했으나, React 경험 보유 + 4~5개월 일정상 새 언어 학습 리스크 회피를 위해 React Native로 전환
- Expo 선택 이유: 환경 세팅 시간 단축, 실기기 테스트(Expo Go) 용이, 추후 `expo prebuild`로 네이티브 전환 가능

---

## 백엔드 — 초기 작업 (완료)

- `cinemory-backend`, `cinemory-sandbox` 두 개 Spring Boot 프로젝트로 시작 (이후 구조 변경, 아래 참고)
- TMDB API 연동 완료
- MySQL 연결 및 JPA 기본 설정 완료
- ERD v1.0 설계 완료 — `collection`, `collection_movie`, `movie`, `watch_record`, `wish_movie` 5개 테이블
- ERD v1.0 범위까지 JPA 엔티티 클래스 작성 완료

### ERD v1.0 검토 결과 (수정 필요 — 진행 예정)
- `user` 테이블 없음 → 추가 필요
- `watch_record` 한 영화당 시청 1회만 기록 가능한 구조 → 시청 1회 = 1 row 방식으로 변경 결정
- `genre` 테이블 없음 → 선호 장르 리포트 기능을 위해 추가 필요
- 상세 내용은 `CineMory_기획노트.md` 1번 섹션 참고

---

## Repository 구조 — 변경 결정

- 기존: `cinemory-backend`(운영) + `cinemory-sandbox`(테스트) 2-repo 구조
- **변경**: 단일 repo + 브랜치 전략(main/develop/feature)으로 전환 결정
- 이유: 코드 중복, 수동 동기화 비용 문제. `cinemory-sandbox`는 추후 GitHub Archive 처리 예정
- 상세 내용은 `CineMory_기획노트.md` 5번 섹션 참고

---

## 프론트엔드 — 초기 환경 구축

### Figma
- 와이어프레임 구현 완료

### Expo 프로젝트 생성
- `cinemory-app` repo 생성 (GitHub)
- 프로젝트 생성 중 이슈 발생 및 해결
  - 이슈 1: 폴더에 기존 `README.md` 존재 → Expo CLI가 생성 거부 → 삭제 후 진행
  - 이슈 2: `--template` 옵션 값 누락 → `blank-typescript` 명시로 해결
  - 이슈 3: Expo Go 앱(Play 스토어 버전)이 SDK 56을 지원하지 않음 (Play 스토어 배포 지연 추정) → Expo 공식 사이트에서 최신 APK 직접 설치로 해결
- 최종 구성: **Expo SDK 56, TypeScript (blank-typescript 템플릿)**

### 폴더 구조 설계 및 생성
1차 — 기본 골격 생성: `navigation`, `screens`, `components`, `api`, `store`, `theme`, `types`

2차 — 최종 보고서 요구사항 반영해 확장
- `screens/` 10개 하위 폴더: `home`, `search`, `records`, `wishlist`, `recommend`, `report`, `cinemap`, `collection`, `social`, `mypage`
- `api/` 7개 파일: `movie.ts`, `wishlist.ts`, `recommend.ts`, `report.ts`, `cinemap.ts`, `collection.ts`, `social.ts`
- `hooks/` 7개 파일 신규 추가: `useMovies.ts`, `useWishlist.ts`, `useRecommend.ts`, `useReport.ts`, `useCinemap.ts`, `useCollection.ts`, `useSocial.ts`
- 빈 폴더(`components`, `navigation`, `store`, `theme`, `types`) Git 추적을 위해 `.gitkeep` 추가
- 상세 구조는 `CineMory_기획노트.md` 6번 섹션 참고

### Git 작업
- 폴더 구조 commit & push 완료 (`feat: 프론트엔드 기본 폴더 구조 생성`)
- React Navigation 관련 패키지 설치 진행
  - `@react-navigation/native`, `react-native-screens`, `react-native-safe-area-context`, `@react-navigation/bottom-tabs`, `@react-navigation/native-stack`
  - commit: `chore: React Navigation 관련 패키지 설치`

---

## 2026-07 — ERD v3 → v6: 스키마 확장 및 실제 DB 반영 (완료)

ERD v1.1 계획보다 범위가 커진 v3(12개 테이블: user, genre, movie_genre, person, movie_person, follow, comment 추가)를 실제 MySQL DB에 반영하고, 이후 점검·논의를 거쳐 v6까지 순차적으로 스키마를 확장/수정함. 전체 의사결정 상세는 `CineMory_기획노트.md` 8번 섹션 참고.

### v3 → v4 — 초기 반영 및 버그 수정
- `user`(소셜 로그인 포함), `genre`, `person`, `movie_genre`, `movie_person`, `follow`, `comment`, `review` 테이블 신규 생성
- `watch_record`, `wish_movie`, `collection`에 `user_id` FK 추가 (빈 테이블이라 3단계 마이그레이션 없이 한 번에 처리)
- **버그 발견 및 수정**: `wish_movie`에 v1.0 시절 잔재인 `movie_id` 단독 UNIQUE 제약이 남아있어 "영화 1개당 전체 시스템에서 1명만 찜 가능"한 치명적 결함 존재 → 제거
- `movie.tmdb_id` UNIQUE 제약 누락 발견 → 추가
- 제약조건명 대문자 잔재(`FK_...`) → 소문자 네이밍 규칙(`fk_`, `uk_`, `idx_`)으로 통일
- 한글 COMMENT 전체 삭제, COLLATE `utf8mb4_0900_ai_ci`로 통일
- `movie` 참조 FK 정책을 `RESTRICT`로 통일 (사용자 생성 데이터가 영화 삭제에 딸려 사라지는 것 방지), `movie_genre`/`movie_person`은 영화 자체의 메타데이터이므로 `CASCADE` 유지

### v4 — 개념 스키마 정리 및 리포트 설계 논의
- 개념 스키마(핵심 엔티티 10개 + N:M 관계 3개) 문서화
- **`review` 테이블 신설 결정**: `watch_record`(회차별 개인 기록)와 `review`(영화당 1개, 공개 대표 리뷰)를 분리. 대표 리뷰 판단 기준은 `created_at`이 아닌 `id`(AUTO_INCREMENT 순서)로 확정 — `watch_date`는 사용자가 임의 조정 가능해 신뢰 불가하기 때문
- "몇 번째 시청인지"는 컬럼 저장 대신 조회 시점 `ROW_NUMBER() OVER (PARTITION BY movie_id ORDER BY id)`로 계산하는 방식 채택 (삭제 시 정합성 깨짐 방지)

### v5 — 제작국가 다중화 및 장르/인물 가중치 설계
- `movie.origin_country`(단일 국가) 한계 발견 → `country`, `movie_country`(N:M + `weight`) 테이블로 분리
- **가중치 공식 확정**: 대표국가 `(N+1)/(N²+1)`, 나머지 국가 각 `N/(N²+1)` (2개국 6:4, 3개국 4:3:3 비율에서 역산해 일반화)
- `movie_genre`에 `weight DECIMAL(4,3)` 추가, `1/N` 균등분배 방식 채택
- **`movie_person` 폐기 → `movie_actor`/`movie_director` 분리**: 배우 전용 속성(`role_tier`)이 감독 행에서 항상 NULL로 남는 문제를 근본적으로 해결하기 위함. `role_tier`(LEAD/SUPPORTING/MINOR/CAMEO)는 TMDB `credits.cast[].order` 값을 영화별 상대 비율로 재해석해 산출하기로 결정 (TMDB에 공식 계층 데이터 없음을 확인 후)
- `person_role_weight` 참조 테이블로 등급별 가중치 관리 시작 (4단계, CAMEO 포함)

### v6 — 가중치 설계 재검토 및 최종 정리
- CAMEO 등급 삭제 결정 (LEAD/SUPPORTING/MINOR 5:4:1 비율로 재조정)
- **`person_role_weight` 테이블 자체를 폐기**하고 가중치 값을 애플리케이션 코드 상수(Enum)로 이관 결정 — 등급 값이 실험적으로 자주 바뀌는 튜닝 파라미터라 DB 테이블화의 변경 비용(FK 삭제→ENUM 수정→FK 재생성)이 오히려 손해라고 판단
- `movie_actor.role_tier`는 FK 없는 단순 ENUM 컬럼으로 최종 정리 (ENUM 자체가 유효값 무결성 보장)

### 최종 스키마 (v6 기준, 16개 테이블)
`user`, `movie`, `genre`, `movie_genre`, `country`, `movie_country`, `person`, `movie_actor`, `movie_director`, `watch_record`, `review`, `wish_movie`, `collection`, `collection_movie`, `follow`, `comment`

---

## 2026-07-11 — KOFIC API 도입 및 CineMap 기획 확정

### KOFIC(영화진흥위원회) Open API 도입 결정

TMDB 외 KOFIC KOBIS Open API를 추가로 활용하기로 확정. 두 API의 역할이 중복되지 않아 병행이 바람직한 구조임을 확인.

| API | 역할 | 비고 |
|---|---|---|
| TMDB | 영화 메타데이터 (포스터, 줄거리, 출연진, 장르 등) | 전 세계 영화 커버 |
| KOFIC KOBIS | 국내 박스오피스 통계 (일별/주간/주말 순위, 매출, 관객수) | 국내 개봉작 한정 |

**스키마 추가 (v6 이후)**
- `box_office_record` — KOFIC 박스오피스 데이터 저장. `movie_id`는 nullable FK(`ON DELETE SET NULL`) + `kofic_movie_cd` 컬럼으로 매칭 실패 시에도 원본 데이터 보존
- `theater` — 극장 위치/주소 저장 (공공데이터포털 전국영화상영관표준데이터 기반)
- `movie.kofic_movie_cd` 컬럼 추가 — TMDB와 KOFIC 영화 데이터 매칭 키
- `box_office_record.box_office_rank` — 최초 컬럼명 `rank`이 MySQL 8.0 예약어 충돌로 `box_office_rank`로 변경

**TMDB ↔ KOFIC 매칭 전략 (배치 구현 시 적용)**
```
1순위: kofic_movie_cd로 movie.kofic_movie_cd 직접 매칭
2순위: 한글 제목 + 개봉연도 fuzzy 매칭
3순위: 매칭 실패 → movie_id = NULL 유지 (박스오피스 기록은 보존)
```
매칭이 깨지는 주요 케이스: 제목 표기 불일치(한글/영문), 개봉일 기준 차이(국내/원산지), KOFIC에만 있고 TMDB에 없는 소규모 한국 영화

### CineMap 기획 확정

"극장별 상영 여부/시간표"를 제공하는 합법적 공식 API가 존재하지 않음을 확인 후 기능 범위를 확정. 상세 내용은 `CineMory_기획노트.md` 4단계 CineMap 섹션 참고.

- **구현 확정**: 주변 극장 지도 표시 + 현재 박스오피스 상영작 목록 + 예매 페이지 딥링크
- **제외 확정**: 극장별 상영 여부, 실시간 상영시간표
- **배제 근거**: CGV/롯데시네마/메가박스 공식 외부 API 미제공. 네이버 크롤링은 robots.txt Disallow + 원본 데이터 소유자 권리 침해 이중 위반 소지

---

## 2026-07-22 — ERD v7/v8 최종화 (19개 테이블) 및 JPA 엔티티 설계 착수

- ERD v6 이후 세부 리뷰를 거쳐 **ERD v7 확정** — `ott_platform` 참조 테이블 추가(향후 영화-OTT 매핑/유저 구독 추적 대비), `watch_record`에 `watch_type`/`place_detail`/`ott_platform_id`/`is_representative` 컬럼 확장
- **DB 백업 v8 반영**(`cinemory_backup_v8.sql`) — `watch_record.place_datail` 오타 수정 완료
- Claude와의 설계 세션을 **Step 단위**로 구조화하기로 결정:
  - Step1: 공통 Base Entity + 독립 참조/마스터 엔티티
  - Step2: 매핑/로그 엔티티
  - Step3: 사용자 활동 엔티티
  - Step4: Repository/Service 계층
- 프로젝트 문서 3종을 신규 작성하여 Claude Code 작업 전달 체계 confirm:
  - `CLAUDE.md` — 전역 컨벤션(Setter 금지, 연관관계 단방향 LAZY, cascade 미지정, equals/hashCode 패턴, `ddl-auto=validate` 등)
  - `docs/jpa-entity-spec.md` — 엔티티별 스펙 (Base class, 필드, unique 제약, 팩토리 메서드, 비즈니스 규칙)
  - `docs/service-layer-spec.md` — Repository/Service 계층 스펙 (Step4용, 신규)
- **작업 방식 전환 확정**: 대화창에서는 Claude Code가 실행할 **설계 스펙**만 정리하고, 실제 클래스 코드는 Claude Code가 스펙 문서를 보고 구현. 스펙 문서는 리포지토리에 커밋해 세션이 바뀌어도 연속성 유지.

### JPA 엔티티 설계 — Step1~3 완료

**Step1 (완료)** — 독립 참조/마스터 엔티티 7종: `Genre`, `Country`, `OttPlatform`, `Person`, `Theater`, `User`, `Movie`
- `BaseCreatedAtEntity`/`BaseTimeEntity` 2종 Base Class로 분리 (created_at만 있는 테이블과 updated_at까지 있는 테이블 구분)
- Setter 전면 금지, 정적 팩토리/`@Builder` + 비즈니스 메서드로 상태 변경
- `User`는 `chk_user_auth_method` 체크 제약(로컬 XOR OAuth)을 `createLocal()`/`createOAuth()` 정적 팩토리로 선반영

**Step2 (완료)** — 매핑/로그 엔티티 6종: `MovieGenre`, `MovieCountry`, `MovieActor`, `MovieDirector`, `WishMovie`, `BoxOfficeRecord` (`CollectionMovie`는 `Collection` 선행 필요로 Step3 이후 처리)
- 전부 단방향 `@ManyToOne(LAZY)`, cascade 미지정 (DB FK 정책이 전담)
- `RoleTier` enum(LEAD 0.5/SUPPORTING 0.4/MINOR 0.1)을 애플리케이션 상수로 확정
- `BoxOfficeRecord.movie`는 유일하게 nullable FK — TMDB 미매칭 상태로 선(先) 적재 가능, `movieTitleSnapshot`은 불변 스냅샷이라 수정 메서드 미제공

**Step3 (완료)** — 사용자 활동 엔티티 5종: `Follow`, `Collection`, `Comment`, `Review`, `WatchRecord` (+ 보류했던 `CollectionMovie`)
- `Follow`: `chk_follow_not_self`(자기팔로우 금지)를 정적 팩토리에서 `IllegalArgumentException`으로 선반영
- `Comment`: `target_type`/`target_id` 다형성 처리 — **A안 확정** (연관관계 매핑 없이 순수 컬럼 유지, 대상 조회는 서비스 레이어에서 분기)
- `Review`: `(user_id, movie_id)` unique, rating 0.0~10.0 검증(TMDB 스케일 가정)
- `WatchRecord`: 필드명 `note`로 확정(`review` 컬럼 매핑, `Review` 엔티티와 혼동 방지), `place_detail` 오타 수정 반영
  - **대표 기록(`is_representative`) 단일성**은 엔티티 단독으로 강제 불가 → `WatchRecordService`가 조율 (엔티티엔 `markAsRepresentative()`/`unmarkAsRepresentative()` 상태 전환 메서드만 제공)
  - 동시성(경쟁 조건)은 캡스톤 스코프상 낙관적으로 수용, 향후 개선 과제로 명시

---

## 2026-07-23 — Repository/Service 계층(Step4) 착수

- 공통 설계 원칙 확정
  - 예외 처리: 도메인별 예외 클래스 난립 대신 `BusinessException(ErrorCode)` 단일 구조 채택
  - 트랜잭션: Service 클래스 레벨 `@Transactional(readOnly = true)` 기본, 쓰기 메서드만 개별 오버라이드
  - DTO 매핑: MapStruct 미도입, Response DTO(`record`)에 정적 팩토리 `from(Entity)` 방식으로 확정 (투명성/디버깅 편의 우선)
  - Service는 Entity를 절대 반환하지 않고 항상 Response DTO 반환 — Controller는 Entity를 알지 못함
- 진행 순서(우선순위) 확정: `공통 인프라 → User → Movie+참조엔티티 → WatchRecord → Review/WishMovie → Collection/CollectionMovie → Follow/Comment → Theater/BoxOfficeRecord(외부 API 연동, 별도 설계 필요)`
- **4-0 공통 인프라 설계 확정**: `ErrorCode`(enum) / `BusinessException` / `ErrorResponse` / `GlobalExceptionHandler(@RestControllerAdvice)`
- **4-1 User 도메인 설계 확정**: `UserRepository`(이메일/OAuth 조회), `SignUpLocalRequest`/`UserResponse` DTO, `UserService`(로컬/OAuth 회원가입, 닉네임/공개설정 변경)
  - OAuth 회원가입은 존재 시 기존 유저 반환하는 멱등 구조로 설계 (재로그인이 흔한 흐름이라 컨트롤러 중복 체크 방지)
  - **선행 필요**: Spring Security `PasswordEncoder`(BCrypt) Bean 등록 — 아직 미착수, Step4 진행 중 별도로 다뤄야 함
- `docs/service-layer-spec.md` 신규 작성 — 이후 도메인(4-2 Movie부터)은 이 문서에 스펙만 계속 추가하는 방식으로 진행

---

## 2026-07-25 — Step4 중반: 조회 전략 표준화 및 개인 기록 도메인 확정

### 4-2 Movie — N+1 회피 패턴을 프로젝트 표준으로 승격

`movie_genre`/`movie_country`/`movie_actor`/`movie_director`가 전부 `movie` 기준 컬렉션이라, 한 쿼리에서 둘 이상을 fetch join하면 `MultipleBagFetchException` 또는 카테시안 곱이 발생한다. 이를 계기로 **조회 유형별 쿼리 전략을 표준으로 못박음.**

- **상세 조회**: 관계별 개별 쿼리 4개 + movie 1개 = **고정 5쿼리**
- **목록 조회**: 관계별 `IN`절 벌크 조회 후 `movieId` 기준 서비스 레이어 그룹핑 = **페이지당 고정 3쿼리**
- 이 패턴은 4-3 "내 영화", 4-4 위시리스트, 4-5 컬렉션 목록, 4-6 팔로우 목록까지 **다섯 차례 재사용**되며 사실상 프로젝트의 조회 관용구로 자리잡음
- 참조 테이블(Genre/Country/Person/OttPlatform)은 **Service를 만들지 않기로 결정** — 사용자 대상 CRUD API가 없고 매핑 Repository가 이미 `@EntityGraph`로 함께 가져오므로, 위임만 하는 계층이 되기 때문
- TMDB 연동(`MovieSyncService`)은 **시그니처만 확정**하고 구현은 분리 — 아직 미확정 세부사항(가중치 계산, 매칭 전략)이 사용자 조회 API의 안정성에 영향을 주지 않도록

### 4-3 WatchRecord — 대표 기록 조율과 FK 검증 원칙

- 대표 기록 삭제 시 **남은 기록 중 최신 것을 자동 재선정**, 별도로 수동 재지정 API(`setRepresentative`)도 제공
- `watch_type` ↔ `ott_platform` 정합성은 DB CHECK 제약이 아닌 **Service 레벨 검증**으로 확정
- **FK 검증 원칙 확립**: 사용자 입력으로 들어오는 FK는 `findById().orElseThrow()`로 실존을 검증하고, 인증된 호출자 본인의 `userId`는 신뢰값이므로 `getReferenceById()` 사용. 4-4부터 프로젝트 전역 원칙으로 표준화

### 4-4 Review / WishMovie — 도메인 성격에 따른 API 형태 분기

- `Review`는 **upsert 방식**(`writeReview` — 있으면 수정, 없으면 생성). 영화당 1개라는 제약과 잘 맞음
- `WishMovie`는 **단일 토글**(`toggleWish`). 찜은 개인적·즉흥적 행위라 상태 조회 없이 한 번의 호출로 끝나는 편이 자연스러움
- `GlobalExceptionHandler`에 `IllegalArgumentException` 핸들러 추가 — 엔티티 레벨 검증 예외 처리를 일원화

### 반복 발견된 문제 — 엔티티 스펙과 구현의 불일치

`jpa-entity-spec.md`에 확정된 내용이 실제 엔티티에 빠져 있는 사례가 4-3(`note` 필드명), 4-4(`Review.of()`/`validateRating()`)에서 연달아 발견됨. 이후 도메인은 **Service 착수 전 엔티티-스펙 대조 확인**을 절차로 편입.

---

## 2026-07-29 — Step4 완료: 소셜 도메인 · 공개범위 정책 · 외부 API 연동 확정

Step4의 남은 세 덩어리(4-5 Collection, 4-6 Follow/Comment, 4-7 Theater/BoxOfficeRecord)를 마무리하며 Repository/Service 계층 설계를 전부 확정. 이 시점에서 **스키마 v8의 18개 테이블 전부가 서비스 계층까지 구현 완료**됨.

### 4-5 Collection — 스키마를 건드리지 않고 서비스로 푸는 판단

- `collection_movie → collection` FK가 `RESTRICT`임을 발견. 스키마를 `CASCADE`로 바꾸는 대신 **`deleteCollection`에서 하위 행을 먼저 정리하는 서비스 레벨 대응**을 택함
- **판단 근거**: 이미 확정·백업된 스키마(v8)를 되돌리는 비용이, 서비스 코드 한 줄을 추가하는 비용보다 크다. 이 "확정된 설계를 되돌리기보다 서비스 레이어에서 명시적으로 처리한다"는 기조는 4-6 고아 댓글 문제에서도 그대로 재사용됨
- 영화 추가는 **벌크 + 멱등**, 제거는 단건으로 분리(토글 아님) — 한 번에 여러 개를 담는 UX와 하나씩 빼는 UX가 다르기 때문

### 4-6 Follow / Comment — 공개범위(privacy_setting) 정책 확정

4-6은 **타인의 데이터를 조회하는 첫 도메인**이다. 여기서 `user.privacy_setting`(PRIVATE/FRIENDS/PUBLIC)을 어떻게 해석할지 결정하지 않으면 이후 모든 소셜 화면이 막히므로, 도메인 CRUD가 아니라 **접근 제어 정책부터 확정**했다.

**정책 결정**

| 항목 | 결정 | 근거 |
|---|---|---|
| `FRIENDS`의 의미 | **상호 팔로우(맞팔)** | 단방향 팔로우만으로 비공개 데이터가 노출되는 것을 방지 |
| 비로그인 조회 | **허용** (`viewerId == null`을 정상 입력으로 취급, PUBLIC만 통과) | 로그인 없이 앱을 둘러보는 최초 진입 경험 확보 |
| 거부 응답 | **403** (404로 존재를 숨기지 않음) | 닉네임 검색으로 유저 존재를 이미 알 수 있어 은닉 실익이 없고, 404로 통일하면 "없는 유저"와 "비공개 유저"를 구분해 안내할 수 없음 |
| 프로필 헤더 | 비공개여도 **노출** | 헤더까지 막으면 팔로우 요청을 보낼 화면 자체가 사라짐. 차단 대상은 시청기록/컬렉션/리뷰 등 **콘텐츠** |

- 판정 로직은 도메인마다 중복 구현하지 않고 **`UserAccessPolicy` 전역 컴포넌트**로 분리. 접근 제어는 특정 도메인의 책임이 아니라 애플리케이션 정책이라는 판단
- 벌크 판정(`filterViewable`)은 4-2에서 표준화한 "IN절 벌크 조회 + 서비스 조합" 패턴을 그대로 적용해 **최대 3쿼리 고정**

**Follow / Comment 설계 결정**

- **`follow`/`unfollow` 엔드포인트 분리** — 4-4에서 위시리스트를 `toggleWish` 단일 토글로 확정했지만, 팔로우는 **상대에게 노출되는 관계 행위**라 네트워크 재시도로 의도치 않게 해제되면 안 된다고 판단해 다른 선택을 함. 대신 양쪽 모두 멱등 처리해 클라이언트가 사전 상태 조회를 하지 않아도 되게 함
- **댓글 권한 비대칭**: 수정은 작성자만, 삭제는 작성자 + 대상(컬렉션/리뷰) 소유자. 타인이 남의 글 내용을 바꾸는 건 위조지만, 자기 게시물의 악성 댓글을 제거하는 건 정당한 관리 권한이라는 구분
- Step3의 **다형성 A안**(FK 없이 순수 컬럼 유지)을 구체화 — `targetType` 분기가 존재 검증·소유자 확인·공개범위 판정 세 지점으로 흩어지지 않도록 "대상의 소유자 userId를 반환한다"는 단일 인터페이스로 통합
- 알림(notification) 기능은 **범위에서 제외** — ERD v8에 테이블이 없고, 추가하려면 스키마 v9 논의가 선행돼야 함

**⚠️ 신규 발견 — 고아 댓글**

`comment.target_id`는 다형 참조라 FK가 없어, `Collection`/`Review`를 삭제해도 댓글이 DB에 남는다. **재사용된 AUTO_INCREMENT id에 과거 댓글이 붙어 보이는 데이터 오염**으로 이어질 수 있는 구조적 결함. v4의 `wish_movie` UNIQUE 제약 버그처럼, 설계 확정 후에도 드러나는 유형의 문제. 4-5와 동일한 원칙(서비스 레이어에서 명시적 정리)으로 대응.

**소급 정리 — 조회 메서드의 암묵적 전제**

`UserAccessPolicy` 확정으로, 4-2~4-5의 조회 메서드가 **"본인 데이터만 조회"를 암묵적으로 전제**하고 있었다는 사실이 드러남. 타인 조회가 가능한 조회 메서드는 `(viewerId, targetUserId, …)` 시그니처를 따르도록 일괄 정리하고, 이를 컨벤션으로 확정.

### 4-7 Theater / BoxOfficeRecord — 외부 API 연동

앞선 도메인과 성격이 다르다. **사용자 소유 데이터가 아니라 외부에서 수집한 공용 데이터**이므로 공개범위 적용 대상이 아니며, 조회와 수집의 책임을 분리(4-2 Query/Sync 분리 원칙 재사용).

**결정 사항**

| 항목 | 결정 | 근거 |
|---|---|---|
| 주변 극장 검색 | **Bounding Box 1차 필터 + 서비스 Haversine** | 스키마 변경 없이 기존 `idx_theater_lat_lng` 활용. 거리 계산식을 `ORDER BY`에 넣으면 인덱스를 못 탐 |
| 극장 데이터 적재 | **1회성 시드** (주기 배치 미도입) | 극장은 개·폐점이 드물어 주기 동기화의 실익이 적음 |
| 박스오피스 배치 | **`@Scheduled` 자동 + 관리자 수동 트리거 병행** | 자동 수집 + 시연/장애 복구용 재수집 모두 대응. 둘이 **같은 서비스 메서드**를 호출해 로직 이원화 방지 |
| TMDB 미매칭 레코드 | **스냅샷으로 노출 + 재매칭 배치** | 포스터 없는 항목이 섞이는 것보다 순위 목록에 구멍이 나는 편이 더 나쁨 |

- 매칭 전략을 **수집 경로와 보정 경로로 분리** — 수집 배치는 `kofic_movie_cd` 직접 매칭만(빠르고 결정적이어야 재실행·복구가 쉬움), 비용이 크고 오매칭 위험이 있는 휴리스틱은 재매칭 배치로 격리
- 수집 멱등성은 4-5 패턴 재사용 — 기존 행 삭제 후 재적재가 아니라 **이미 적재된 코드 집합을 읽어 차집합만 저장**
- `global/infra/kofic` 패키지 신설 (TMDB 클라이언트도 같은 위치 예정). API 키는 `application-secret.yml`로 분리하고, 미설정 시 배치가 경고 로그만 남기고 건너뛰도록 해 미설정 환경에서 기동을 막지 않음
- 스케줄러는 **단일 인스턴스 전제**. 다중 인스턴스 확장 시 분산 락(ShedLock 등) 필요 — 캡스톤 범위 밖으로 명시

**⚠️ 기획 전략 축소 — 2순위 매칭**

2026-07-11에 세운 매칭 전략의 2순위는 "한글 제목 + **개봉연도** fuzzy 매칭"이었으나, 구현 단계에서 **`box_office_record`에 KOFIC의 `openDt`(개봉일)를 담을 컬럼이 없다**는 사실이 드러남. 개봉연도로 후보를 좁힐 수 없어 **제목 완전 일치 + 후보가 유일할 때만 연결**로 축소(2건 이상은 동명 영화이므로 오매칭 방지를 위해 보류). 정확도를 되돌리려면 스키마 v9에 `open_date` 컬럼 추가가 선행돼야 함.

보완책으로, 재매칭에 성공하면 해당 `movie`에 KOFIC 코드를 역으로 채워 다음 수집부터는 1순위 매칭이 걸리도록 설계.

### 부수 정리

- `application.yml`의 `ddl-auto`가 `update`로 되어 있어 `CLAUDE.md`의 "스키마 v8이 진실의 원천" 원칙과 충돌하고 있던 것을 **`validate`로 수정**
- 4-2에서 정리했던 flat 패키지(`com.project.cinemory.repository`) 잔존분 삭제

---

## 2026-07-29 — Step S: 인증·인가 설계 확정 및 스키마 v9 반영 (19 → 21 테이블)

Step5(Controller)보다 인증 설계를 먼저 진행했다. Controller 시그니처가 "인증된 호출자를 어떻게 받는가"에
직접 의존하기 때문이다. 이번 세션은 **설계 확정과 스키마 적용까지**이며 구현 코드는 아직 없다.

### 인증 방식 결정

| 항목 | 결정 | 근거 |
|---|---|---|
| 토큰 | **Access + Refresh, Refresh는 DB 저장** (회전 + 재사용 감지) | Access 30분/Refresh 14일. 재로그인 없이 앱을 쓰되 강제 로그아웃도 가능해야 함 |
| 소셜 로그인 | **클라이언트 SDK가 받은 ID 토큰을 서버가 검증** | RN에서 서버 리다이렉트 방식은 브라우저 왕복 + 딥링크 처리가 무겁다. 4-1의 `signUpOAuth`(멱등) 설계와 그대로 맞물린다 |
| 관리자 권한 | **`user.role` 컬럼** + `/api/admin/**` | 설정 파일 allowlist보다 정석적이고, 마침 v9를 여는 시점이라 추가 비용이 없음 |
| 팔로우 명단 | **현행 유지** (수는 공개, 명단은 공개범위 적용) | 비공개 계정의 인간관계가 드러나지 않게. 인스타그램 비공개 계정과 동일한 동작 |
| 알림 | **`notification` 테이블 도입** | 4-6부터 이월된 미결 항목. 어차피 v9를 여는 김에 함께 반영 |

**책임 분리 원칙 명문화** — Spring Security는 "인증 여부"(당신이 누구인가), `UserAccessPolicy`는
"가시성"(그 사람의 데이터를 볼 자격이 있는가)을 판정한다. 따라서 `permitAll`은 "누구나 데이터를 본다"는
뜻이 아니다. 이 분리를 지키면 Security 설정을 건드리지 않고 공개범위 정책을 바꿀 수 있다.

**4-6-E의 회수** — 조회 메서드 시그니처를 `(viewerId, targetUserId, …)`로 미리 정리해 둔 덕분에
**Service 계층은 한 줄도 바뀌지 않는다.** Controller가 인증 주체를 받아 그대로 넘기면 된다.

### 스키마 v9 — 잔여 항목을 "스키마 영향 여부"로 분류

Security 설계에서 파생된 잔여 항목 6건을 검토한 결과, 실제로 DDL이 필요한 것은 **2건뿐**이었다.
나머지는 근거를 남기고 애플리케이션 레벨에서 처리하기로 정리했다.

**v9에 반영 (19 → 21 테이블)**

- `user.role` — `ENUM('USER','ADMIN')`. 고정 폐쇄 집합이고 FK 참조가 없어 ENUM 컬럼으로 충분
- `refresh_token` — 토큰 원문이 아니라 **SHA-256 해시**를 저장. DB가 유출돼도 그대로 재사용 가능한 값이
  남지 않게 하기 위함이며, BCrypt를 쓰지 않는 이유는 salt가 붙으면 인덱스 조회 자체가 불가능해지기 때문
- `notification` — 수신자(CASCADE)와 행위자(SET NULL)를 분리. 행위자가 탈퇴해도 수신자의 알림 목록이
  통째로 사라지면 안 되기 때문

**v9에 넣지 않은 것과 그 이유**

- `password_reset_token` → **v10으로 분리.** 이메일 발송 인프라(SMTP) 없이는 테이블만 있고 동작하지 않는다.
  반면 비밀번호 *변경*(로그인 상태)은 스키마 없이 구현 가능하므로 먼저 넣는다
- 만료 토큰 정리 → **MySQL EVENT를 쓰지 않는다.** `event_scheduler`가 기본 OFF라 서버 설정에 의존하고,
  정리 이력이 애플리케이션 로그에 남지 않으며, 무엇보다 비즈니스 로직이 SoT(스키마 덤프) 밖으로 새어 나간다
- Rate limiting → `login_attempt` 테이블을 만들지 않는다. 로그인 시도마다 쓰기가 발생해 DB에 부하를 준다.
  단일 인스턴스에서는 인메모리로 충분하며, 캡스톤 범위 밖으로 두되 보고서에 한계로 명시

**⚠️ 반복되는 구조적 문제 — 고아 알림**

`notification`도 `comment`와 동일한 다형 참조(`target_type`/`target_id`, FK 없음) 구조다. 따라서
4-6에서 겪은 **고아 댓글 문제가 그대로 재현된다.** 컬렉션/리뷰 삭제 시 댓글을 정리하는 바로 그 자리에
알림 정리도 함께 호출해야 하며, 이는 DDL로 막을 수 없고 서비스 레이어에서만 처리된다.
v4의 `wish_movie` UNIQUE 버그, 4-6의 고아 댓글에 이어 **같은 계열의 문제가 세 번째로 반복**된 사례다.

### 문서·운영 정비

- `docs/security-spec.md` 신규 — Step S 전체 설계
- `docs/schema/v9-delta.sql` — 델타 DDL + **롤백 스크립트** + **재덤프 명령어**. 적용 후 체크리스트 포함
- `docs/jpa-entity-spec.md` — 기준 스키마를 v8 → **v9**로 갱신하고 Step4(인증/알림 엔티티) 스펙 추가
- `.gitignore` — 스키마 덤프 제외가 `cinemory_backup_v8.sql` **파일명으로 박혀 있어** v9 덤프가 그대로
  커밋될 상황이었다. 패턴(`cinemory_backup_*.sql`)으로 변경
- 적용 시 뜨는 경고 `1681 Integer display width is deprecated`는 **정상**이다. MySQL에서 `BOOLEAN`은
  `TINYINT(1)`의 동의어라 boolean 컬럼을 만드는 한 피할 수 없으며, v8의 `is_new`/`is_active`/
  `is_representative`도 모두 동일하다. 근거를 델타 파일 주석에 남겼다

### 🐛 `@EnableJpaAuditing` 중복 — 기동 검증 절차가 잡아낸 잠복 버그

`validate` 기동 확인 중 `BeanDefinitionOverrideException`(`jpaAuditingHandler`)으로 컨텍스트 로딩이 실패했다.
`@EnableJpaAuditing`이 `CinemoryApplication`과 `JpaAuditingConfig` **양쪽에** 선언돼 있어 같은 빈이 두 번
등록된 것이 원인이었다. `compileJava`로는 잡히지 않고 컨텍스트를 실제로 띄울 때만 드러나는 유형이라
그동안 잠복해 있었으며, v9/Security와는 무관한 기존 문제였다. 수정 후 통과.

> 다만 이번 통과는 "v9 전체 검증"이 아니다. `validate`는 **엔티티 → 스키마** 단방향으로만 검증하므로
> 엔티티가 없는 신규 두 테이블은 검증 대상이 아니고, **UNIQUE/FK/인덱스도 검증하지 않는다.**
> 실질적 의미는 "v9 적용이 기존 18개 엔티티를 깨지 않았다"는 확인이다.

---

## 2026-07-30 — Step S 구현 착수: 확정 설계의 빈칸 12건을 메우고 S-A~S-E 완료

### "설계 확정"이 구현 가능을 뜻하지 않았다

착수 직전 `security-spec.md`를 구현 관점에서 다시 읽자, ✅ 확정으로 표시된 절에도 **케이스 자체가
비어 있어 손대는 순간 막히는 지점**이 7건 나왔다. 결정 없이 넘기면 임의로 채워지고 되돌리기 비싼
것들이라 A-1~A-7로 확정하고, 명세만 보강하면 되는 5건을 B-1~B-5로 분리했다(S-9 신설).

| 결정 | 내용 | 판단 근거 |
|---|---|---|
| A-1 | 카카오 이메일을 **필수 동의**로 (비즈앱 전환/본인인증 선행) | `user.email`이 `NOT NULL`인데 선택 동의면 클레임이 안 온다. 플레이스홀더 이메일은 `uk_user_email`에 가짜 데이터를 남겨 v10 비밀번호 재설정에서 터진다. 스키마·코드 변경 0인 쪽을 택했다 |
| A-2 | 로컬/소셜 이메일 충돌은 **409로 명시 응답** | 로그인은 계정 존재 탐색을 막아야 하지만, 여기는 *본인이 자기 계정으로 들어오려는* 상황이라 알려주는 게 맞다. 방향이 반대다 |
| A-3 | `permitAll` 경로여도 무효 토큰이면 **항상 401** | 조용한 익명 강등은 "로그인했는데 안 보임"을 만들고 서버에 흔적도 안 남는다 |
| A-4 | 회전 오탐은 **프론트 mutex + 서버 30초 유예** | 동시 재발급 2건 중 두 번째가 탈취로 판정돼 정상 사용자가 튕기는 문제. 프론트 단독으로는 백엔드가 못 막아 안전망을 둔다 |
| A-5 | `logout`만 `authenticated()` | 토큰 소유자 일치 검증이 가능해진다 |
| A-6 | 비밀번호 **변경**을 Step S 범위에 포함 | 변경 시 리프레시 토큰 전체 폐기가 필요한데 그 로직이 `AuthService`에 이미 생긴다 |
| A-7 | `TestController` 삭제 | 인증 없는 TMDB 프록시 — 4-6부터 이월된 항목 해소 |

**소셜 provider는 카카오 하나로 확정**했다(국내 캡스톤 기준 검증 부담이 가장 적음).
전략 인터페이스가 확장을 담당하므로 `OAuthProvider` enum에도 `KAKAO`만 넣는다 —
미구현 값을 미리 정의하면 실패 시점이 런타임까지 미뤄진다.
**만료 리프레시 토큰 정리 배치는 보류**하고 보고서에 한계로 명시하기로 했다(rate limiting과 동일 취급).

### ⚠️ 확정 결정끼리 충돌한 사례 — A-3 × 인증 엔드포인트

A-3을 그대로 적용하면 **만료 토큰을 헤더에 단 채 재로그인하려는 순간 401로 막힌다.**
재발급도 같아서 앱을 지우기 전엔 복구가 안 되는 상태가 된다.

auth 4경로만 필터 대상에서 빼되(`logout`은 A-5 때문에 제외 불가), 안전성 근거를 명확히 남겼다 —
**인가는 이 필터가 아니라 체인 뒤쪽 `AuthorizationFilter`가 판정하므로 오류 방향이 fail-closed다.**
제외 목록이 잘못돼도 문이 열리는 게 아니라 401이 난다. 네 경로 모두 인증 주체를 쓰지 않으며,
재발급조차 자격증명이 헤더의 Access가 아니라 body의 리프레시 토큰이다.

> v4 `wish_movie` UNIQUE 버그, 4-6 고아 댓글, v9 고아 알림에 이어
> **확정 사항이 서로 어긋나는 유형이 반복되고 있다.** 다만 이번엔 구현 전에 잡혔다.

### 🐛 문서 사본이 오래돼 결정이 한 번 뒤집혔다

`RefreshToken`의 `user`를 연관관계 없이 `Long userId`로 두자고 제안했다가 `@ManyToOne(LAZY)`로
되돌렸다. "조인을 아낀다"는 근거가 **사실이 아니었고**(LAZY는 조인하지 않으며 `getUser().getId()`도
프록시라 쿼리가 없다), CLAUDE.md의 "FK 보유 엔티티는 전부 단방향 `@ManyToOne(LAZY)`" 규칙만
깨는 선택이었다.

원인은 **프로젝트 지식에 동기화된 문서 사본이 저장소보다 오래됐던 것**이다
(`jpa-entity-spec.md` 263 vs 342줄, `service-layer-spec.md` 238 vs 997줄).
저장소 원본에는 이미 `@ManyToOne`으로 명시돼 있었다. 같은 조건이면 다음 세션에도 재현되므로
**사본 재동기화를 운영 과제로 등록**한다.

### 구현 — S-A ~ S-E 완료

의존성 추가 시점부터 전 엔드포인트가 차단되므로 `SecurityConfig` 골격을 같은 커밋에 묶었고,
이후 엔티티 3건 → 토큰 발급/검증 → 인증 필터 → 인증 주체 주입 순으로 진행했다.
**단위마다 컴파일·기동을 확인하고 넘어가는 방식**을 유지했다.

- **검증 방식** — 컨트롤러가 0개라 상태 코드만으로는 판정이 안 돼
  **응답 바디의 `code` 값으로 구분**했다. S-D는 실제 HTTP 4건으로 확인
- **미검증 잔존** — `TOKEN_EXPIRED` 경로(발급 엔드포인트 부재)와 S-E 전체(리졸버를 태울 경로 부재).
  둘 다 **예외 없이 조용히 틀리는** 유형이라 S-F에서 반드시 확인해야 한다
- 알고리즘 고정 문제를 잡는 과정에서 나온 `JwtTokenProviderTest`는 **고정하는 불변식을 문서화**해
  정리 대상 오해를 방지했다. 특히 `TOKEN_EXPIRED`/`INVALID_TOKEN` 분리는 `catch`를 합쳐도
  컴파일이 통과하고 증상이 앱의 재발급 루프로만 드러나며, 프론트 계약이 여기 의존한다

### 스키마 v10 — 확정 결정이 또 한 번 서로 어긋났다

S-F 검증 중 **로그아웃이 30초간 무효화된다**는 사실이 드러났다. A-4에서 도입한 유예 창이
`revoked_at`만 보는데, 그 컬럼은 회전·로그아웃·재사용감지·비밀번호변경 **네 경로**에서 찍힌다.
회전 경합을 구제하려던 장치가 로그아웃까지 구제해버린 것이다.

> A-3 × 인증 엔드포인트에 이어 **같은 세션에서 두 번째로 나온 "확정 결정끼리의 충돌"** 이다.
> 개별 결정은 각각 타당했고, 어긋남은 둘이 만나는 지점에서만 드러났다.

**v10 확정 (21 → 22 테이블)**

| 변경 | 판단 근거 |
|---|---|
| `refresh_token.revoked_reason` | 사유가 두 개라서가 아니라 **이미 네 개**이기 때문. S-H가 바로 다음 단계에서 `PASSWORD_CHANGED`를 가져온다 |
| `password_reset_token` | **SMTP 도입 확정.** 미도입 시 로컬 가입자가 락아웃되면 복구 경로가 없고, `chk_user_auth_method`(로컬 XOR OAuth) 때문에 소셜 경유 본인확인도 구조적으로 불가하다 |
| `box_office_record.open_date` | 4-7에서 이미 근거가 확정돼 있던 항목. 여는 김에 함께 |

**되돌린 판단 두 건**

- `replaced_by_id`를 넣어 유예를 정석으로 바꾸자고 했다가 철회했다. 정석 구현은 직전 발급분을
  그대로 돌려주는 것인데, **우리는 토큰 원문이 아니라 해시만 저장한다.** 돌려줄 값이 없어
  목적을 달성하지 못한다. 해시만 저장하는 결정이 더 중요하므로 컬럼 쪽을 접었다 —
  **유예 창의 한계는 남고, 근본 방어는 프론트 mutex라는 결론이 그대로다**
- 로그아웃/회전을 "후속 토큰 존재"로 구분하는 안도 철회했다. **다중 기기를 허용**하는 이상
  다른 기기가 회전시킨 토큰이 후속으로 잡혀 오판한다

**S-J 신설** — 비밀번호 재설정은 SMTP·딥링크가 붙어 인증 코어와 성격이 달라 별도 절로 분리했다.
스키마보다 **흐름 규칙**이 안전성을 결정한다고 보고 네 가지를 미리 확정했다: 이메일 열거 방지(항상
동일 응답), 새 발급 시 미사용 토큰 삭제, 재요청 억제(마지막 발급 후 N분), 성공 시 세션 전체 폐기.

### 🐛 진실의 원천과 문서가 어긋나 있었다 — 테이블 수

v9 덤프의 `CREATE TABLE`은 **21개**인데 모든 문서가 20이라고 적고 있었다.
`CineMory_기획노트.md`만 v8을 19개로 올바르게 적고 있었고, **오차는 2026-07-22 v7/v8 문서화
시점에 생겨 이후 문서로 계속 전파됐다.**

함께 드러난 문제로 **`v9-delta.sql`이 git에 커밋된 적이 없었다.** ".gitignore에서 델타는 추적한다"고
정리해뒀지만 `docs/schema/` 전체가 추적되지 않는 상태였고, 파일을 적용 후 지우면서 문서 9곳의
참조가 끊겼다.

> `@EnableJpaAuditing` 중복이나 v9 검증 누락은 **절차가 잡아낸** 문제였는데,
> 이 둘은 **절차의 빈틈이 만든** 문제다. 성격이 다르다.
> 재발 방지는 "델타 파일을 반드시 커밋한다"와 "수치는 덤프에서 세어 넣는다" 두 가지.

### Step S 범위 조정 — 결정의 근거가 사라지면 결정도 옮긴다

**A-6(비밀번호 변경을 Step S에 포함)을 철회하고 Step5로 이관했다.**

A-6의 근거는 "변경 시 세션 전체 폐기가 필요한데 그 로직이 `AuthService`에 어차피 생긴다"였다.
v10 반영으로 `revokeAllByUserId(..., reason)`와 `PASSWORD_CHANGED`가 **이미 만들어졌으므로
그 근거는 충족됐고, 동시에 조기 구현의 이점도 사라졌다.** 남은 것은 호출뿐이다.

경로 의미로도 Step5가 맞다. 변경은 **로그인 상태**라 `/api/users/me/password`(`UserController`),
재설정은 **비로그인**이라 `/api/auth/password-reset/*`(`AuthController`)가 자연스럽다.
둘을 다른 단계에 두는 편이 오히려 도메인 경계와 일치한다.

> 이번 세션에서 결정을 되돌린 것이 이걸로 네 번째다(B-4 매핑 방식, `replaced_by_id`,
> 후속 토큰 추론, A-6 이관). 앞의 셋은 **근거가 틀렸던** 경우고, 이번은 **근거가 충족돼
> 결정이 불필요해진** 경우다. 성격이 다르므로 철회 사유를 구분해 남긴다.

이로써 Step S는 인증 코어(S-G 카카오 → S-I 정리 → S-J 재설정)만 남는다.

### ⏸ S-G 착수 시 결정할 것 — nonce 검증

카카오 ID 토큰의 검증 항목은 `iss`/`aud`/`exp`/**`nonce`** 넷인데 설계에는 앞의 셋만 있다.
nonce는 재전송 공격 방지용이고 **클라이언트가 로그인 요청 시 보낸 값과 대조**하는 방식이라,
서버가 nonce를 발급하지 않는 현재 흐름에서는 대조 기준 자체가 없다.

- 넣으려면 nonce 발급 엔드포인트(인메모리 저장 — **스키마 변경 없음**)와
  **클라이언트 로그인 흐름 변경**(1단계 → 2단계)이 따라온다
- **설치 기반이 0인 지금은 비용이 작다.** 스토어 배포 후에는 구버전 앱을 위해 nonce 없는 경로를
  함께 유지해야 해서 계단식으로 뛴다 — 즉 **배포 전에는 반드시 결론을 내야 하는 항목**이다
- 위험도 자체는 낮다. ID 토큰은 HTTPS로만 오가고 수명이 짧아 탈취하려면 기기 장악이나 TLS 우회가 필요하다

판단은 S-G 착수 시점으로 미룬다.

### S-G 완료 — 검증할 수 없는 것을 검증 가능하게 만든 과정

카카오 소셜 로그인을 세 덩어리로 나눠 끝냈다. 테스트 74건.

**핵심은 "실제 카카오 토큰 없이 어디까지 검증할 수 있는가"였다.** 콘솔 승인이 늦어질 수 있어
E2E를 기다리면 진도가 멈추는데, `KakaoJwkSource`를 인터페이스로 빼고 **자체 RSA 키쌍으로 토큰을
직접 서명**하니 서명 위조·`aud` 불일치·만료·nonce 불일치를 전부 태울 수 있었다.

> 오히려 실토큰보다 촘촘하다. **실제 카카오 토큰으로는 "정상 케이스"밖에 만들 수 없다.**
> 오늘 발견한 `aud` 오기도 이 방식이면 구현 시점에 잡혔을 것이다.
> 남는 것은 "설정값이 실제와 맞는가" 하나뿐이고, 그건 원래 코드로 검증할 수 없는 종류다.

### ⚠️ 커버리지가 높을수록 착각하기 쉬운 것 — 조율 로직

부품별 테스트가 65건까지 쌓였는데, **부품을 엮는 순서**를 고정하는 테스트가 없었다.
`AuthService.oauthLogin`에서 **nonce 소비를 ID 토큰 검증보다 먼저** 하기로 한 결정이 그것이다.

순서가 뒤바뀌어도 65건은 전부 통과한다. 그러나 뒤바뀌면 검증 실패 시 nonce가 살아남아
같은 nonce로 반복 시도가 가능해지고, **nonce를 도입한 이유 자체가 사라진다.**

> v10의 로그아웃 우회, S-C의 HS256 미고정에 이어 **"각 부품은 맞는데 조합이 틀린"
> 유형이 세 번째**다. 공통점은 셋 다 **컴파일도 되고 기존 테스트도 통과한다**는 것이다.
> 단위 커버리지가 높아질수록 이 유형만 남는다는 뜻이기도 하다.

### 프론트로 넘어간 과제

A-3·A-4는 서버만으로 완성되지 않는다. `cinemory-app`의 axios 인터셉터에 **재발급 mutex**와
**401 전역 처리**(`TOKEN_EXPIRED` → 재발급 후 재시도 / `INVALID_TOKEN` → 토큰 삭제 후 로그인 화면)가
필요하다. 없으면 만료 시점에 공개 목록 조회까지 함께 실패한다.

---

## 2026-08-02 — S-F 검증 완료 및 S-G 확정: nonce는 "지금이 마지막으로 싼 시점"

### 미뤄둔 검증이 한 번에 정리됐다

컨트롤러가 없어 확인할 수 없던 항목들(S-E 리졸버, `TOKEN_EXPIRED` 경로)을 `AuthController` 등장과
함께 한꺼번에 검증했다. 테스트 13건 통과, HTTP 6종 확인.

**핵심은 로그아웃 직후 재발급이 `REFRESH_TOKEN_REUSED`로 차단된 것** — v10을 연 이유였고
목적을 달성했다. DB 실측에서 `revoked_reason`이 사유별로 제 자리에 기록됐고,
로그아웃된 행이 `LOGOUT`으로 남아 `revoke()`의 멱등성까지 함께 확인됐다.

> **"검증 가능한 시점까지 부채를 명시적으로 들고 간다"는 방식이 실제로 작동했다.**
> 미검증 항목을 문서에 눈에 띄게 남겨둔 덕분에 잊히지 않고 회수됐다.
> 반대로 그때 "구현 완료"로만 적었다면 검증된 것으로 착각한 채 넘어갔을 것이다.

### S-G 확정 — nonce를 넣기로 한 이유는 위험이 아니라 시한

카카오 공식 검증 항목 넷 중 `nonce`가 설계에서 빠져 있었다. 도입을 확정했는데,
**판단 기준은 "위험이 크다"가 아니라 "지금이 싸게 넣을 수 있는 마지막 시점"** 이었다.

| | 지금 | 스토어 배포 후 |
|---|---|---|
| 서버 | 발급 엔드포인트 + 인메모리 캐시 (**스키마 무변경**) | 동일 |
| 클라이언트 | 로그인 화면 한 곳 | 구버전 앱을 위해 **nonce 없는 경로를 병행 유지** |

위험 자체는 낮다(HTTPS + 짧은 ID 토큰 수명). 그러나 **설치 기반이 0인 창은 지금뿐**이고,
그 창을 넘기면 같은 결정의 비용이 계단식으로 뛴다. 캡스톤에서 "나중에"가 실제로 오지 않는
항목들과 달리, 이건 **오지 않으면 영구히 못 넣는 쪽**에 가깝다.

> 스펙이 외부 문서의 요구사항을 **부분적으로만 옮겨온** 사례다. 문서 사본이 오래돼 생긴 착오,
> 테이블 수 오기에 이어 **"참조한 원본과 어긋나는" 문제가 세 번째**다.
> S-J의 SMTP 연동도 외부 스펙 참조 절이므로 착수 전 항목 대조를 절차에 넣는다.

### 🐛 같은 문서를 세 번째로 다시 읽고서야 맞췄다 — 카카오 `aud`

S-G 설명을 준비하며 카카오 문서를 재대조하다 스펙의 **`aud` 값이 틀렸다**는 것을 발견했다.
"REST API 키"로 적혀 있었으나 **네이티브 앱 SDK로 로그인하면 네이티브 앱 키가 온다.**
우리는 RN + 네이티브 SDK이므로 그대로 갔으면 **모든 소셜 로그인이 실패**했을 것이다.

같은 대조에서 **"ID 토큰 수명이 짧다"던 판단도 틀렸음**이 드러났다. 약 2시간짜리다.
nonce 없이는 탈취 토큰이 2시간 내내 유효하다는 뜻이라, **nonce 도입은 당시 내가 든 근거보다
실제로 더 타당했다.** 결론은 같았지만 근거가 약했던 것이라 함께 정정했다.

> **동일한 외부 문서에서 세 번에 걸쳐 서로 다른 누락이 나왔다** — nonce 항목 누락,
> `aud` 값 오기, 수명 오판. 공통점은 **"필요한 부분만 발췌해 옮기는" 과정에서 생겼다**는 것이다.
> 셋 다 구현 전에 잡혔지만, 잡힌 이유가 "절차"가 아니라 "설명하려고 다시 읽어서"였다.
>
> **재발 방지** — 외부 스펙을 참조하는 절(S-G 카카오, S-J SMTP)은 **착수 직전 원문 대조를
> 절차로 넣는다.** 설계 시점의 발췌를 그대로 믿지 않는다.

### 🔧 개발 환경 — `bootRun` 고아 프로세스

래퍼를 죽여도 fork된 자식 JVM이 8080을 쥔 채 남아 다음 기동이 실패했다. Gradle의 동작이지
애플리케이션 문제가 아니다. IntelliJ Run으로 띄우면 사라진다.

주의할 점은 포트 충돌 자체보다 **고아 프로세스가 옛 코드로 응답한다**는 것이다.
기동 실패를 놓치고 요청하면 이전 빌드가 답하는데 겉보기엔 정상이라, 검증 결과를 오도한다.

---

## 2026-08-05 — Step S 종료: 남긴 것을 남겼다고 적는 일

### S-J 완료 — 비밀번호 재설정으로 Step S의 마지막 기능 단위를 닫았다

S-10 스펙과 S-9 F-1~F-4를 그대로 코드로 옮겼다. `AuthController`에 재설정 3단계
(`request`/`verify`/`confirm`), `PasswordResetService` 신설, `UserService.updatePassword` +
`User.changePassword`(Step5 비밀번호 **변경**과 공유). 테스트 93건(신규 19건), 실패 0.

신규 테스트의 절반은 로직이 아니라 **순서**를 고정한다 — 억제 판정과 미사용 토큰 삭제의 순서,
비밀번호 변경과 세션 폐기의 순서, 사전 검증이 토큰을 소비하지 않는다는 것. 셋 다 뒤집혀도
컴파일과 나머지 테스트는 통과하는 유형이라 S-G의 nonce 소비 순서와 같은 계열이다.

검증 중 **재설정 요청의 응답 시간이 경로에 따라 최대 376배 차이 난다**는 사실을 발견했다
(로컬 가입 계정 5,650ms vs 그 외 12~17ms). 응답 본문은 통일했지만 타이밍으로 이메일 열거가
가능하다는 뜻이다. 캡스톤 평가 비중을 고려해 고치지 않고 L-5로 한계에 남긴다.

### S-I — 정리의 방향을 바꿨다

원래 계획은 `security-spec.md`(1000줄 초과)를 **재구조화**하는 것이었다. S-9의 결정들을
각 절 본문에 흡수시키고 S-9는 목록만 남기는 방식. 그런데 착수 직전에 실제 중복 정도를
확인해보니 **각 절에 이미 상세 서술이 있어 옮길 것이 많지 않았다.** 재배치 과정에서
근거를 빠뜨릴 위험이 이득보다 컸다.

**재구조화 대신 진입점을 얹기로 바꿨다** — S-9에 결정 인덱스(A~F 24건), 그리고 S-11 신설.
**"S-9를 각 절에 흡수시킨다"는 이전 세션의 계획은 이 결정으로 대체됐다** — 문서 정리
과제로 다시 올라오면 이 절을 근거로 반려할 것.

> 문서 정리의 목적이 "짧게 만들기"가 아니라 **"찾기 쉽게 만들기"** 였다는 점을 착수 직전에
> 다시 확인한 셈이다. 목적을 다시 물었더니 수단이 바뀌었다.

### 알고 남긴 것을 기록하는 이유

S-11에 한계 11건(L-1~L-11)을 모았다. rate limiting 미도입, A-4 유예 창,
Access Token 즉시 무효화 불가처럼 **이미 각 절에 흩어져 적어둔 것들**을 한곳으로 옮기고,
S-J에서 새로 측정된 **타이밍 사이드채널**을 추가했다.

**"몰랐다"와 "알고 남겼다"는 다르다.** 후자는 각 항목에 왜 남겼는지와 해결에 무엇이
필요한지를 함께 적어두면, 나중에 착수할 때 재조사가 필요 없고 보고서에도 그대로 쓸 수 있다.

이번에 추가한 L-5(재설정 타이밍)가 그 예다. 응답 본문은 통일했는데 **응답 시간이 376배
차이 나** 이메일 열거 방지가 사실상 뚫려 있었다. 캡스톤 평가 비중을 고려해 고치지 않기로
했지만, **측정값과 해결 방향까지 확보한 상태로** 남겼다.

> 이 프로젝트에서 미뤄둔 항목이 조용히 사라진 적이 여러 번 있었다.
> v9 델타 파일이 커밋 없이 삭제된 것, 문서 사본이 오래돼 결정을 한 번 뒤집게 한 것.
> **미룬 것을 미뤘다고 적어두는 것 자체가 절차**라는 걸 반복해서 확인했다.

---

## 현재 상태 요약 (작성 시점 기준: 2026-08-05)

| 영역 | 상태 |
|---|---|
| 기술 스택 | 확정 (React Native/Expo + Spring Boot + MySQL + TMDB + KOFIC) |
| 백엔드 ERD | **v10 확정 및 DB 적용 완료** (22개 테이블) |
| 백엔드 JPA 엔티티 | Step1~4 완료(v10 22개 대응) — `revoked_reason`/`PasswordResetToken`/`open_date` 전부 반영 완료 |
| 백엔드 Repository/Service | **Step4 전체 완료** (4-0 공통 인프라 ~ 4-7 외부 API 연동) |
| 공개범위(privacy) 정책 | **확정 및 전역 적용 완료** (FRIENDS = 맞팔, 비로그인 조회 허용) |
| 외부 API 연동 | KOFIC 박스오피스 **구현 완료**(수집/재매칭 배치) / TMDB 동기화는 **미착수** |
| Spring Security | **Step S 전체(S-A~S-J) 구현 및 검증 완료** — 토큰·필터체인·`@AuthUser`·로그인/재발급/로그아웃·카카오 소셜 로그인(nonce 포함)·비밀번호 재설정. 테스트 93건. **실토큰 E2E만 잔여**(S-H는 Step5로 이관) |
| 알림(notification) | 스키마만 반영 — 도메인 설계·구현 미착수 |
| Controller 계층 | 미착수 (Step5 예정) |
| CineMap 기획 | 기능 범위 확정 완료, 주변 극장 조회 API 구현 완료 |
| Repository 구조 | 단일 repo + 브랜치 전략 적용 중 |
| 프론트 — Figma | 와이어프레임 완료 |
| 프론트 — Expo 환경 | 구축 완료 (SDK 56, TypeScript) |
| 프론트 — 폴더 구조 | 생성 및 GitHub push 완료 |
| 프론트 — React Navigation | 패키지 설치 완료, 탭/스택 구조 설계는 보류 |

---

## 다음 작업 (예정)

1. **Step S 잔여 — 실토큰 E2E뿐** (S-A~S-J 전부 완료, S-H는 Step5로 이관)
   - **카카오 실토큰 E2E** — 단위 74건은 통과했으나 **설정한 네이티브 앱 키가 실제 `aud`와 맞는지**는
     실기기로만 확인된다. 선행: 콘솔 **플랫폼 등록**(Android 키 해시 / iOS 번들 ID) — 프론트와 맞물림
   - **실제 SMTP 발송 E2E** — `application-secret.yml`에 Gmail 앱 비밀번호를 넣어야 발송이 성립한다.
     없어도 기동은 되고 발송 시점에만 실패하므로 조용히 넘어가지 않도록 실제로 한 번 보낼 것
   - ⚠️ A-4의 30초 유예는 **정석이 아니다.** 자기참조 컬럼이 없어 직전 발급분을 되돌려주는 구현이
     불가하고, **`replaced_by_id`를 넣어도 해시만 저장하는 이상 해결되지 않는다.**
     창 안에서는 탈취 토큰도 통과하므로 보고서에 한계로 명시할 것
   - ⚠️ L-5(재설정 타이밍 사이드채널) — 로컬 가입 계정 재설정 요청만 응답이 최대 376배 느려
     이메일 열거가 가능하다. 캡스톤 평가 비중을 고려해 고치지 않고 한계로 남기기로 함
2. **알림 도메인 설계** — Step S 구현 이후 별도 절.
   생성 지점이 `FollowService.follow()` / `CommentService.createComment()` **안**이라 기존 도메인 서비스에
   손이 닿으므로, Security 구현과 섞지 않는다
3. **Step5 — Controller 계층 + `@Valid` 검증**
   - `spring-boot-starter-validation` 도입, DTO Bean Validation, `MethodArgumentNotValidException` 핸들러
4. **TMDB 동기화 배치 설계** — 수집 시점에 계산/처리해야 하는 항목들
   - `movie_country.weight` ((N+1)/(N²+1) 공식), `movie_genre.weight` (1/N)
   - `movie_actor.role_tier` (TMDB `order` 기반 상대 비율 분류, 경계값 미확정)
   - 극장 표준데이터 CSV의 좌표계 확인 (WGS84 vs EPSG:5174 — 후자면 적재 전 변환 필요)
5. **코드 외 선행 작업**
   - **카카오 개발자 콘솔** — 비즈앱 전환 또는 본인인증 → `account_email` 활성화 → **필수 동의** 설정.
     이게 안 되면 S-G에서 막힌다
   - **프론트 axios 인터셉터 2건** — 재발급 mutex(A-4), 401 전역 처리(A-3)
6. **정리 과제**
   - ~~`TestController` 삭제~~ → **완료** (A-7)
   - **프로젝트 지식의 문서 사본 재동기화** — 저장소보다 오래돼 설계 착오를 한 번 유발했다
   - `cinemory-sandbox` repo 정리 (의미 있는 코드만 이전 후 Archive)
   - ~~`security-spec.md` 구조 정리 (S-9 흡수)~~ → **완료(S-I, 2026-08-05).** 흡수 대신
     결정 인덱스(S-9)·한계 목록(S-11) 추가로 방향을 바꿔 마무리했다
7. **스키마 v11 후보 누적** — 필요해지는 시점에 일괄 논의
   - `theater` POINT 컬럼 + SPATIAL 인덱스 (극장 데이터 증가 시)

---

## 참고 문서
- `CineMory_기획노트.md` — 기획·설계 의사결정 전체 기록
- `Conventional_Commits_가이드.md` — 커밋 메시지 컨벤션
- `CLAUDE.md` — Claude Code 작업 규칙 (전역 컨벤션)
- `docs/jpa-entity-spec.md` — JPA 엔티티 설계 스펙 (Step1~4 전체 완료)
- `docs/service-layer-spec.md` — Repository/Service 계층 설계 스펙 (Step4 전체 완료 + Step S 반영)
- `docs/security-spec.md` — Spring Security 설계 스펙 (Step S 전체(S-A~S-J) 구현 완료).
  **S-9는 앞 절 서술과 충돌 시 우선하는 확정 결정 모음**
- `DevLog.md` — 구현 로그 (이 문서는 기획·의사결정 로그)
- `docs/schema/cinemory_backup_v10.sql` — DB 스키마 스냅샷 (**진실의 원천, 22 테이블**)
- `docs/schema/v10-delta.sql` — v9 → v10 델타 DDL (사전 점검·체크리스트·롤백·재덤프 포함).
  **반드시 커밋할 것** — v9 델타는 커밋되지 않은 채 삭제돼 남아 있지 않다
