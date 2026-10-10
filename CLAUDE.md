## 하는 역할
**구현**, **디버깅**, **한 일을 변경 이력에 기록**

## ⚠️ 세션 시작 시 먼저 할 것 — 문서의 진실의 원천

**스펙 문서의 진실의 원천은 언제나 이 리포의 `docs/` 디렉터리다.**

| 문서                                    | 범위 |
|---------------------------------------|---|
| `docs/jpa-entity-spec.md`             | 엔티티 설계 (Step1~3) |
| `docs/service-layer-spec.md`          | Repository / Service 계층 (Step4) |
| `docs/security-spec.md`               | 인증·인가 (Step S) |
| `docs/controller-layer-spec.md`       | Controller 계층 (Step5) + 상시 잔여 항목 표 |
| `docs/tmdb-sync-spec.md`              | TMDB 연동 (Step6) |
| `docs/M3a-report-spec.md`             | **M3-a 시청 분석 리포트 설계 확정본** — RA-1~RA-7 확정 기록과 지표 전체 목록. 계약은 5-8·4-8에 있고 이 문서는 근거를 남긴다 |
| `docs/deploy-spec.md`                | **실서버 배포 (2026-10~)** — D-1~D-5 확정 기록(EC2 · 하이브리드 추천 · Flyway · Nginx · 이관 범위)과 Phase 0~6 실행 순서. **Phase 1은 파일 단위 지시** |
| `docs/account-integrity-spec.md`     | **스키마 무결성 정리(V18~V22) · 프로필 사진(S3+CloudFront) · 회원 탈퇴** — 2026-10-01 확정 기록. Part A는 Flyway 도입 직후 |
| `docs/schema/cinemory_backup_v24.sql` | 현행 스키마 스냅샷 |
| `docs/movie-seed-runbook.md`          | 영화 데이터 적재 실행 절차 |
| `docs/kakao-login-runbook.md`         | 카카오 로그인 로컬 실토큰 검증 절차 |
| `docs/server-setup-runbook.md`        | 운영 서버(EC2) 구축 절차 — 콘솔·로컬·서버 명령과 기대 출력, 증상별 진단 |
| `docs/Conventional_Commits_가이드.md`    | 커밋 메시지 규칙 |
| `CineMory_기획노트.md`                    | 전체 마일스톤·미결 사항 |
| `DevLog.md`                           | 세션별 진행 기록 |

**규칙**

1. **문서를 수정할 때는 `docs/`의 실제 파일을 직접 편집.** 별도의 "적용 지시서"나
   임시 사본을 만들어 전달하지 않기.
2. Claude.ai 프로젝트에 **첨부된 사본이 보이더라도 그것을 기준으로 삼지 않기.**
   내용이 다르면 **항상 리포 파일이 옳음.**
3. 작업 시작 시 이 폴더에 실제로 접근 가능한지 먼저 확인. 접근 가능한데 사본을 읽는 실수를 막기 위함.
4. 문서를 고쳤으면 해당 문서의 **변경 이력 표에 항목을 추가**. 결정 근거까지 남기기.

---

## 아키텍처 원칙

- 계층: Controller - Service - Repository - Domain 책임 명확히 분리
- 패키지 구조: 도메인 중심(package-by-feature)
- Entity는 절대 API 외부로 직접 노출하지 말 것. Controller ↔ Client 간에는
  반드시 Request/Response 전용 DTO를 사용.
- 비즈니스 로직에서 발생 가능한 예외(예: 리소스 없음, 잘못된 상태 전이 등)는
  커스텀 예외(`ResourceNotFoundException` 등)로 던지고, `@RestControllerAdvice`
  글로벌 핸들러에서 일괄 처리.

---

## 엔티티(Entity) 공통 규칙

- **Base Class 상속**
  - 어떤 테이블이 어떤 Base를 쓰는지는 `docs/jpa-entity-spec.md` 표 참고
- **Setter 사용 금지**
  - `@NoArgsConstructor(access = AccessLevel.PROTECTED)` 필수
  - 생성은 정적 팩토리 메서드(필드 3개 이하) 또는 `@Builder`(필드 4개 이상)로만
  - 상태 변경은 의미 있는 이름의 비즈니스 메서드로 노출 (예: `changeNickname()`,
    `markAsRepresentative()`, `deactivate()`) — `set인지` 형태 메서드명 금지
- **연관관계 매핑**
  - 전부 단방향 `@ManyToOne(fetch = FetchType.LAZY)`
  - FK를 가진 엔티티 → 참조 대상을 바라보는 방향으로만 매핑
  - 참조 대상 엔티티(예: `Movie`, `User`)에 컬렉션 필드(`@OneToMany`)를 추가하지 말 것.
    특정 조회가 필요하면 해당 Repository에 쿼리 메서드/`@Query`로 해결.
  - `cascade` 옵션은 지정하지 않음. 삭제 정책은 DB의 FK 제약이 전담.
- **equals/hashCode**
  - `id` 기반, 프록시 안전 패턴 사용:
    ```java
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ClassName that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
    ```
- **Enum**: 컬럼 매핑 시 항상 `@Enumerated(EnumType.STRING)` 사용 (ORDINAL 금지)
- **다형성 연관관계 금지**: `comment.target_type/target_id`처럼 FK가 없는 컬럼은
  연관관계 매핑을 시도하지 말고 순수 컬럼(`Long` + `Enum`)으로만 유지한다.
  대상 조회는 서비스 레이어에서 `target_type` 기준으로 분기하여 처리한다.
- **불변 스냅샷 필드**: `box_office_record.movie_title_snapshot`처럼 특정 시점의
  값을 보존하기 위한 필드는 이후 수정 메서드를 만들지 않는다.

---

## DB / 스키마 원칙

- **진실의 원천(Source of Truth)**: `/docs/schema/cinemory_backup_v24.sql`
  - 엔티티 작업 시 반드시 이 파일 기준으로 컬럼/제약조건을 맞출 것.
  - 임의로 컬럼을 추가/변경/삭제하지 말 것. 스키마 변경이 필요하면 먼저 알리기.
- `ddl-auto`는 `validate`를 기본으로 사용.
- ⚠️ **DB가 둘이다 — `cinemory`(개발) / `cinemory_test`(테스트).**
  테스트는 `spring.profiles.active=test`로 고정돼 있고(`build.gradle`),
  `src/test/resources/application-test.yml`이 `datasource.url`만 `cinemory_test`로 덮어쓴다.
  - 분리한 이유: `MovieRepositoryTest`가 하드코딩한 `tmdbId`가 실 시드 데이터와 충돌해
    `uk_movie_tmdb_id` 위반으로 실패했다. 테스트가 실 개발 DB에 그대로 접속하고 있었다.
  - ~~스키마 델타를 `cinemory`에 적용하면 `cinemory_test`에도 반드시 같이 적용할 것~~ —
    **2026-10-02 Flyway 도입(`docs/deploy-spec.md` 1-4)으로 폐기.** 적용은 이제 앱 기동이 DB마다 알아서 한다.
- **스키마 변경 = Flyway 마이그레이션 + 설계 델타 한 쌍** (deploy-spec 1-4 ⑤, baseline v17)
  - **`src/main/resources/db/migration/V{n}__설명.sql`** — 실행 파일. ⚠️ **공유 브랜치(`main`)에 들어갔거나
    데이터를 유지하는 DB(`cinemory`·운영)에 적용되면 동결.** 고치면 체크섬 불일치로 **운영이 기동하지 못한다.**
    상태 표시·주석 수정도 금지 — 실수는 `V{n+1}`로 고친다. (예외: 머지 전 브랜치의 V가 `cinemory_test`에만 적용됐다면
    고쳐 쓰고 `cinemory_test`를 재생성 — deploy-spec D-3 조건 7)
  - **`docs/schema/v{n}-delta.sql`** — 설계 근거·데이터 보정·롤백·변경 이력. 자유롭게 고친다.
  - **적용은 앱 기동이 한다.** 수동 `mysql < delta.sql` 금지 — `flyway_schema_history`와 어긋난다.
    `cinemory`는 `bootRun`, `cinemory_test`는 `./gradlew test`가 각각 반영한다.
  - 진실의 원천은 여전히 덤프(`cinemory_backup_v{n}.sql`) — 적용 후 재덤프는 계속한다.
- ⚠️ **확장/축소 — 옛 코드를 깨는 변경은 한 릴리스에 넣지 않는다** (deploy-spec D-3 조건 6).
  스키마는 항상 **지금 코드와 바로 이전 코드 둘 다**에서 돌아가야 한다(CI 자동 롤백·로컬 브랜치 전환).
  - 한 번에 해도 되는 것(확장): 테이블·nullable 컬럼·인덱스 추가, 제약 완화, 백필.
  - **다음 릴리스로 미룰 것(축소): 테이블·컬럼 삭제, 이름 변경, NOT NULL 추가, 타입 좁히기.** 확장할 때 축소 할 일을 스펙에 바로 기록.
  - 마이그레이션을 작성하면 **각 문장이 확장인지 축소인지** 분류해 보고할 것.
- **로컬 DB 초기화용 역덤프는 콘텐츠 10개 테이블만** (deploy-spec D-5) — 사용자·인증 테이블과 `flyway_schema_history`는 받지 않는다.
  데이터의 원본은 운영, **스키마의 원본은 리포의 마이그레이션**이다.
- 네이밍: FK/UK/IDX 접두사는 `fk_`, `uk_`, `idx_` 소문자 통일, 한글 COMMENT 사용 금지,
  COLLATE는 `utf8mb4_0900_ai_ci`로 통일.
- N:M 관계는 이미 대리키(Surrogate Key)를 가진 매핑 엔티티로 승격되어 있음
  (`movie_genre`, `movie_country`, `movie_actor`, `movie_director`, `collection_movie` 등)
  → 새로운 다대다 관계 추가 시에도 동일하게 매핑 엔티티로 승격할 것

---

## 참조/애플리케이션 상수 vs DB 테이블 판단 기준

- 다른 엔티티가 FK로 참조해야 하거나, 런타임에 종류가 늘어날 수 있는 안정적 앵커
  → DB 참조 테이블 (예: `ott_platform`, `genre`, `country`)
- 자주 변하지 않고 도메인 로직에서만 쓰이는 튜닝 파라미터
  → 애플리케이션 레벨 Enum 상수 (예: `RoleTier`의 가중치 LEAD 0.5 / SUPPORTING 0.4 / MINOR 0.1)
- 고정된 폐쇄 집합이며 관계형 데이터가 필요 없는 경우 → 단순 `EnumType.STRING` 컬럼

---

## 작업 진행 방식

- 큰 작업(여러 엔티티/여러 계층)은 한 번에 몰아서 시키지 않고 **작은 단위로 쪼개서** 진행.
  - 엔티티는 선행 의존성이 있는 것부터 순서대로 (예: `Collection` 구현 후 `CollectionMovie`)
  - 단위 작업 후 컴파일 확인 → 리뷰 → 커밋 순으로 진행
- 스펙 문서(경로: `docs/`)에 명시된 항목만 구현하고,
  스펙에 없는 임의 필드/메서드를 추가하지 말 것. 스펙이 불명확하면 먼저 질문.
- 성능 우려(N+1 등)나 요구사항 모호함이 있으면 코드 작성 전에 먼저 확인 요청.
- **브랜치 — GitHub Flow** (2026-10-10, 기획노트 5절): `feature/*`는 **`main`에서 분기**하고 PR로 `main`에 머지한다.
  `main` 머지는 곧 CI 배포다 — 머지 전 `./gradlew test` 통과 필수. **`develop`은 보관 브랜치라 사용하지 않는다.**
