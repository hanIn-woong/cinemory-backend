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

## 2026-07-22 — ERD v7/v8 최종화 (18개 테이블) 및 JPA 엔티티 설계 착수

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

## 2026-07-29 — Step S: 인증·인가 설계 확정 및 스키마 v9 반영 (18 → 20 테이블)

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

**v9에 반영 (18 → 20 테이블)**

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

## 현재 상태 요약 (작성 시점 기준: 2026-07-29)

| 영역 | 상태 |
|---|---|
| 기술 스택 | 확정 (React Native/Expo + Spring Boot + MySQL + TMDB + KOFIC) |
| 백엔드 ERD | **v9 확정 및 DB 적용 완료** (20개 테이블) |
| 백엔드 JPA 엔티티 | Step1~3 완료 (v8 18개) / **Step4(v9 신규 3건)는 스펙만 확정, 구현 미착수** |
| 백엔드 Repository/Service | **Step4 전체 완료** (4-0 공통 인프라 ~ 4-7 외부 API 연동) |
| 공개범위(privacy) 정책 | **확정 및 전역 적용 완료** (FRIENDS = 맞팔, 비로그인 조회 허용) |
| 외부 API 연동 | KOFIC 박스오피스 **구현 완료**(수집/재매칭 배치) / TMDB 동기화는 **미착수** |
| Spring Security | **설계 확정 완료**(`docs/security-spec.md`) — 구현 미착수 |
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

1. **Step S 구현** — 설계는 끝났고 코드만 남음
   - Step4 엔티티 3건(`User.role`/`RefreshToken`/`Notification`) 구현 후 `validate` 재확인
     (지금은 엔티티가 없어 신규 테이블이 검증 대상이 아님)
   - `JwtTokenProvider` → `JwtAuthenticationFilter` → `SecurityConfig` → `AuthService`/`OAuthIdTokenVerifier`
   - ⚠️ `spring-boot-starter-security`를 추가하는 순간 **전 엔드포인트가 기본 차단**되므로 화이트리스트 정의가 첫 작업
   - 착수 전 결정 필요(구현을 막지는 않음): 소셜 provider 우선순위(애플은 앱스토어 배포 시 심사 요구사항),
     만료 리프레시 토큰 정리 배치 도입 여부
2. **알림 도메인 설계** — Step S 구현 이후 별도 절.
   생성 지점이 `FollowService.follow()` / `CommentService.createComment()` **안**이라 기존 도메인 서비스에
   손이 닿으므로, Security 구현과 섞지 않는다
3. **Step5 — Controller 계층 + `@Valid` 검증**
   - `spring-boot-starter-validation` 도입, DTO Bean Validation, `MethodArgumentNotValidException` 핸들러
4. **TMDB 동기화 배치 설계** — 수집 시점에 계산/처리해야 하는 항목들
   - `movie_country.weight` ((N+1)/(N²+1) 공식), `movie_genre.weight` (1/N)
   - `movie_actor.role_tier` (TMDB `order` 기반 상대 비율 분류, 경계값 미확정)
   - 극장 표준데이터 CSV의 좌표계 확인 (WGS84 vs EPSG:5174 — 후자면 적재 전 변환 필요)
5. **정리 과제**
   - `TestController` 삭제 — 인증 없이 열린 TMDB 프록시라 호출 쿼터를 임의 소진시킬 수 있음.
     4-2 `MovieQueryService`로 역할이 대체됨
   - `cinemory-sandbox` repo 정리 (의미 있는 코드만 이전 후 Archive)
6. **스키마 v10 후보 누적** — 필요해지는 시점에 일괄 논의
   - `password_reset_token` (비밀번호 재설정 — SMTP 인프라 도입 시)
   - `box_office_record.open_date` (2순위 매칭 정확도 복원)
   - `theater` POINT 컬럼 + SPATIAL 인덱스 (극장 데이터 증가 시)

---

## 참고 문서
- `CineMory_기획노트.md` — 기획·설계 의사결정 전체 기록
- `Conventional_Commits_가이드.md` — 커밋 메시지 컨벤션
- `CLAUDE.md` — Claude Code 작업 규칙 (전역 컨벤션)
- `docs/jpa-entity-spec.md` — JPA 엔티티 설계 스펙 (Step1~3 완료 + Step4 스펙 확정)
- `docs/service-layer-spec.md` — Repository/Service 계층 설계 스펙 (Step4 전체 완료)
- `docs/security-spec.md` — Spring Security 설계 스펙 (Step S, 설계 확정)
- `docs/schema/cinemory_backup_v9.sql` — DB 스키마 스냅샷 (진실의 원천, 20 테이블)
- `docs/schema/v9-delta.sql` — v8 → v9 델타 DDL (롤백 스크립트·재덤프 명령어 포함)
