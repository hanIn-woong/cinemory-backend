## ⚠️ 세션 시작 시 먼저 할 것 — 문서의 진실의 원천

**스펙 문서의 진실의 원천은 언제나 이 리포의 `docs/` 디렉터리다.**

| 문서                                    | 범위 |
|---------------------------------------|---|
| `docs/jpa-entity-spec.md`             | 엔티티 설계 (Step1~3) |
| `docs/service-layer-spec.md`          | Repository / Service 계층 (Step4) |
| `docs/security-spec.md`               | 인증·인가 (Step S) |
| `docs/controller-layer-spec.md`       | Controller 계층 (Step5) + 상시 잔여 항목 표 |
| `docs/tmdb-sync-spec.md`              | TMDB 연동 (Step6) |
| `docs/schema/cinemory_backup_v15.sql` | 현행 스키마 스냅샷 |
| `docs/movie-seed-runbook.md`          | 영화 데이터 적재 실행 절차 |
| `docs/kakao-login-runbook.md`         | 카카오 로그인 로컬 실토큰 검증 절차 |
| `docs/Conventional_Commits_가이드.md`    | 커밋 메시지 규칙 |
| `CineMory_기획노트.md`                    | 전체 마일스톤·미결 사항 |
| `DevLog.md`                           | 세션별 진행 기록 |

**규칙**

1. **문서를 수정할 때는 `docs/`의 실제 파일을 직접 편집한다.** 별도의 "적용 지시서"나
   임시 사본을 만들어 전달하지 않는다.
2. Claude.ai 프로젝트에 **첨부된 사본이 보이더라도 그것을 기준으로 삼지 않는다.**
   자동 동기화가 아니라 수동 업로드본이라 **stale이며, 일부 문서는 아예 빠져 있다.**
   내용이 다르면 **항상 리포 파일이 옳다.**
3. 작업 시작 시 이 폴더에 실제로 접근 가능한지 먼저 확인한다
   (`ls docs/` 또는 `git status` 한 번이면 된다). 접근 가능한데 사본을 읽는 실수를 막기 위함이다.
4. 문서를 고쳤으면 해당 문서의 **변경 이력 표에 항목을 추가**한다. 결정의 근거까지 남긴다.

---

## 기술 스택

- Backend: Spring Boot 4 (Java 21), MySQL 8.0, JPA (Hibernate)
- Frontend: React Native (Expo, TypeScript)
- 외부 API: TMDB API, KOFIC KOBIS Open API, 전국영화상영관표준데이터

---

## 아키텍처 원칙

- 계층: Controller - Service - Repository - Domain 책임 명확히 분리
- 패키지 구조: 도메인 중심(package-by-feature)

```
com.cinemory
 ├─ domain
 │   ├─ common/entity
 │   ├─ {도메인명}
 │   │   ├─ entity
 │   │   ├─ repository
 │   │   ├─ service
 │   │   ├─ controller
 │   │   └─ dto             // Request/Response DTO, Entity 직접 노출 금지
 └─ global
     ├─ config
     └─ exception
```

- Entity는 절대 API 외부로 직접 노출하지 않는다. Controller ↔ Client 간에는
  반드시 Request/Response 전용 DTO를 사용한다.
- 비즈니스 로직에서 발생 가능한 예외(예: 리소스 없음, 잘못된 상태 전이 등)는
  커스텀 예외(`ResourceNotFoundException` 등)로 던지고, `@RestControllerAdvice`
  글로벌 핸들러에서 일괄 처리한다.

---

## 엔티티(Entity) 공통 규칙

- **Base Class 상속**
  - `created_at`만 있는 테이블 → `BaseCreatedAtEntity` 상속
  - `created_at` + `updated_at`이 모두 있는 테이블 → `BaseTimeEntity` 상속
  - 어떤 테이블이 어떤 Base를 쓰는지는 `docs/jpa-entity-spec.md` 표 참고
- **Setter 사용 금지**
  - `@NoArgsConstructor(access = AccessLevel.PROTECTED)` 필수
  - 생성은 정적 팩토리 메서드(필드 3개 이하) 또는 `@Builder`(필드 4개 이상)로만
  - 상태 변경은 의미 있는 이름의 비즈니스 메서드로 노출 (예: `changeNickname()`,
    `markAsRepresentative()`, `deactivate()`) — `set인지` 형태 메서드명 금지
- **연관관계 매핑**
  - 전부 단방향 `@ManyToOne(fetch = FetchType.LAZY)`
  - FK를 가진 엔티티 → 참조 대상을 바라보는 방향으로만 매핑
  - 참조 대상 엔티티(예: `Movie`, `User`)에 컬렉션 필드(`@OneToMany`)를 추가하지 않는다.
    특정 조회가 필요하면 해당 Repository에 쿼리 메서드/`@Query`로 해결한다.
  - `cascade` 옵션은 지정하지 않는다. 삭제 정책은 DB의 FK 제약(RESTRICT/CASCADE/SET NULL)이 전담한다.
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

- **진실의 원천(Source of Truth)**: `/docs/schema/cinemory_backup_v15.sql`
  - 엔티티 작업 시 반드시 이 파일 기준으로 컬럼/제약조건을 맞춘다.
  - 임의로 컬럼을 추가/변경/삭제하지 않는다. 스키마 변경이 필요하면 먼저 알린다.
- `ddl-auto`는 `validate`를 기본으로 사용한다 (엔티티가 스키마와 어긋나면 애플리케이션 기동 시 즉시 실패시켜 조기 발견).
- 네이밍: FK/UK/IDX 접두사는 `fk_`, `uk_`, `idx_` 소문자 통일, 한글 COMMENT 사용 금지,
  COLLATE는 `utf8mb4_0900_ai_ci`로 통일 (기존 스키마와 동일하게 유지)
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

---

## 코드 스타일 체크리스트 (PR 전 자가 점검용)

- [ ] Setter 없음
- [ ] Base Entity 올바르게 상속 (created_at only vs created_at+updated_at)
- [ ] `@ManyToOne(fetch = LAZY)`만 사용, 양방향 컬렉션 없음
- [ ] cascade 옵션 없음
- [ ] equals/hashCode id 기반 패턴 적용
- [ ] Enum은 `EnumType.STRING`
- [ ] Entity가 Controller까지 그대로 노출되지 않고 DTO로 변환됨
- [ ] 스펙 문서 범위 내에서만 구현함
