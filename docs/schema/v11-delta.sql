-- =============================================================================
-- CineMory 스키마 델타 : v10 -> v11
-- =============================================================================
-- 대상 DB : cinemory (MySQL 8.0)
-- 작성일  : 2026-08-13
-- 근거    : docs/tmdb-sync-spec.md 6-0 D-1 / D-4 확정
--
-- !! 정오표 (2026-08-13, 최초 적용 후 발견) !!
--   초판은 (1) 을 smallint 로 적었다. Integer 필드와 JDBC 타입 코드가 어긋나
--   ddl-auto=validate 가 실패한다. 상세는 (1) 절 주석 참고.
--   이 교정은 v12-delta.sql (1) 에 포함되어 있다. v11 을 이미 적용했다면 v12 를 실행할 것.
--
-- !! 정오표 2 (2026-08-19) !!
--   (3) overview 확장(varchar(1000) -> varchar(4000)) 은 잘못된 전제 위에 있었다.
--   TMDB 는 overview 를 1000 자로 제한한다 — varchar(1000) 이 원래 옳았다.
--   v12-delta.sql (2) 에서 되돌린다.
--
-- 변경 요약 (22 -> 22 테이블, 테이블 수 변동 없음)
--   (1) movie_actor.display_order 추가 (int NOT NULL)
--       - TMDB credits.cast[].order 원본값. 출연진 표시 순서의 유일한 근거다.
--   (2) movie_actor.role_tier 에 EXTRA 값 확장
--       - enum('LEAD','SUPPORTING','MINOR') -> enum('LEAD','SUPPORTING','MINOR','EXTRA')
--       - order 21 번 이후에 부여한다. RoleTier.EXTRA 의 가중치는 0.0 이다.
--   (3) movie.overview 확장 : varchar(1000) -> varchar(4000)   [D-4]
--       - TMDB overview 가 1000 자를 넘으면 적재가 DataIntegrityViolationException 으로
--         죽는다. 실데이터를 넣기 전까지 드러나지 않는 유형의 실패다.
--
-- 왜 필요한가
--   D-1 은 "cast 를 상위 20명에서 자를지"를 물었고, 자르지 않기로 확정했다.
--   전체 출연진을 사용자에게 보여주기 위해서다. 그런데 전량 저장하면 D-1 을 연 원인인
--   가중치 오염이 되살아난다 — 200명 영화에서 MINOR 180명 x 0.1 = 18.0 이 LEAD 총합을
--   넘어, "이 영화를 좋아함"의 점수가 출연진 규모에 좌우된다.
--
--   그래서 표시 범위와 가중치 범위를 분리한다.
--     - 표시   : 전량 저장 + display_order 로 순서 복원
--     - 가중치 : 21번 이후는 EXTRA(0.0) 이라 집계에 기여하지 않는다
--   영화당 배우 선호 기여 총점이 출연진 수와 무관하게 5.6 으로 고정된다.
--     LEAD 5 x 0.5 = 2.5 / SUPPORTING 5 x 0.4 = 2.0 / MINOR 11 x 0.1 = 1.1 / EXTRA 0.0
--
-- 왜 (3) 을 여기에 묶는가
--   D-4 원안은 "Service 에서 절단"이었고, 그 근거가 "스키마 변경은 CLAUDE.md 상 별도
--   승인이 필요하다" 였다. 승인을 받았고, 이 델타가 아직 미적용이라 묶는 비용이 0 이다.
--   델타를 따로 내면 적용 순서를 한 번 더 관리해야 한다.
--
-- 넣지 않은 것과 이유
--   - overview 를 TEXT 로 바꾸는 안 : 두 가지 이유로 varchar(4000) 을 택했다.
--     (a) 현 스키마에 TEXT 컬럼이 하나도 없다. 최장이 review.content varchar(2000) 이다.
--     (b) @Column(columnDefinition = "TEXT") 는 Hibernate 가 매핑 타입(VARCHAR)과 실제
--         컬럼 타입(LONGVARCHAR)을 다르게 봐 ddl-auto=validate 에서 기동 실패할 수 있다.
--         length = 65535 로 두어 MySQLDialect 가 text 로 매핑하게 하는 우회가 필요하다.
--     varchar(4000) 은 엔티티에 length = 4000 한 줄이고 그 리스크가 없다.
--     TMDB overview 는 ko-KR 기준 대개 200~800 자, 최장 1500 자 안팎이라 헤드룸도 충분하다.
--   - role_tier 를 nullable 로 바꾸는 안 : "21번 이후는 role 없음"을 NULL 로 표현하면
--     null 이 소비 지점 전체로 전파된다. 결정적으로 ORDER BY role_tier ASC 에서 MySQL 은
--     NULL 을 맨 앞에 놓아 단역이 최상단에 오는 버그가 즉시 발생한다.
--     EXTRA(0.0) 은 집계 쿼리가 필터를 잊어도 기여가 0 이라 규칙이 데이터에 고정된다.
--   - idx_movie_actor_movie_display_order : 전량 저장으로 영화당 행 수가 20 -> 최대 수백이
--     되므로 정렬 인덱스가 필요할 수 있으나, 실측 전에 넣지 않는다.
--     tmdb-sync-spec 잔여 #8 로 등록했고 시드 적재 후 EXPLAIN 으로 판단한다.
--   - display_order 를 movie_actor 의 UNIQUE 에 넣는 안 : 같은 order 가 두 번 오는 응답을
--     본 적이 없지만 TMDB 가 보장하지도 않는다. 제약을 걸면 적재가 죽는다.
--     중복 제거는 서비스 계층(personId 기준, 최소 order 우선)이 맡는다.
--
-- !! 실행 전 확인 !!
--   - v10 이 적용된 상태에서 실행한다. 아래 (0) 사전 점검을 먼저 돌릴 것.
--   - (1) 은 NOT NULL 컬럼 추가다. movie_actor 에 기존 행이 있으면 전부 0 으로 채워져
--     "모두 LEAD" 상태가 된다. 아래 사전 점검에서 행 수를 반드시 확인할 것.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- (0) 사전 점검 — 기대값과 다르면 중단할 것
-- -----------------------------------------------------------------------------
-- 테이블 수: 22 여야 한다
SELECT COUNT(*) AS table_count
FROM information_schema.tables
WHERE table_schema = 'cinemory' AND table_type = 'BASE TABLE';

-- 이미 적용됐는지 확인: 0 이어야 한다
SELECT COUNT(*) AS already_has_display_order
FROM information_schema.columns
WHERE table_schema = 'cinemory' AND table_name = 'movie_actor' AND column_name = 'display_order';

-- !! 가장 중요 !! 기존 행 수: 0 이어야 한다
--   0 이 아니면 (1) 의 NOT NULL 추가로 기존 행의 display_order 가 전부 0 이 되고,
--   role_tier 와 어긋난 채 남는다(0 은 LEAD 구간인데 role_tier 는 다른 값일 수 있다).
--   그 경우 아래 (3) 백필을 함께 실행할 것.
SELECT COUNT(*) AS existing_rows FROM `movie_actor`;

-- 현재 role_tier 정의 확인: enum('LEAD','SUPPORTING','MINOR') 여야 한다
SELECT column_type FROM information_schema.columns
WHERE table_schema = 'cinemory' AND table_name = 'movie_actor' AND column_name = 'role_tier';

-- 현재 overview 정의 확인: varchar(1000) 이어야 한다
SELECT column_type FROM information_schema.columns
WHERE table_schema = 'cinemory' AND table_name = 'movie' AND column_name = 'overview';


-- -----------------------------------------------------------------------------
-- (1) movie_actor.display_order
-- -----------------------------------------------------------------------------
-- !! 타입은 int 다. smallint 가 아니다 !!
--   초판은 "order 가 200 을 넘는 일이 드무니 smallint 로 충분하다" 는 이유로 smallint 로
--   적었는데, 이는 두 가지로 틀렸다.
--
--   (a) ddl-auto=validate 가 실패한다. Hibernate 는 엔티티 컬럼의 SQL 타입명 접두사
--       또는 JDBC 타입 코드가 일치할 때만 통과시킨다. Java Integer -> "integer"
--       (Types.INTEGER = 4) 이고 DB 는 "SMALLINT" (Types.SMALLINT = 5) 라 양쪽 다 어긋난다.
--       맞추려면 필드를 Short 로 바꿔야 하는데, 그러면 tier 파생 산술과 리포지토리
--       파라미터에 캐스팅이 번진다.
--   (b) 기존 스키마와 어긋난다. 이 스키마에 smallint 컬럼은 하나도 없고
--       box_office_rank / screen_count / show_count / movie.runtime 이 전부 int 다.
--
--   int 는 행당 2 바이트를 더 쓰지만 이 규모에서 무의미하다.
--
-- 음수는 오지 않지만 UNSIGNED 는 쓰지 않는다. Java 에 unsigned 정수형이 없어
-- 같은 유형의 validate 불일치를 만든다.
--
-- 수정 메서드를 두지 않는다. 재동기화는 전량 삭제 후 재삽입이라 갱신 경로가 없다.

ALTER TABLE `movie_actor`
  ADD COLUMN `display_order` int NOT NULL AFTER `character_name`;

-- 이미 smallint 로 적용한 경우의 교정 (초판 델타를 그대로 실행했다면 이것만 실행):
--   ALTER TABLE `movie_actor` MODIFY COLUMN `display_order` int NOT NULL;


-- -----------------------------------------------------------------------------
-- (2) movie_actor.role_tier 에 EXTRA 확장
-- -----------------------------------------------------------------------------
-- !! EXTRA 는 반드시 목록 맨 끝에 붙인다 !!
--   MySQL ENUM 은 값을 문자열이 아니라 1-based 인덱스로 저장한다.
--   중간에 끼워 넣으면 기존 행의 인덱스가 다른 값을 가리키게 되어 데이터가 통째로
--   재해석된다. 끝에 추가하는 경우에만 ALGORITHM=INSTANT 가 적용돼 비용이 없다.
--
-- 애플리케이션 측 RoleTier enum 의 선언 순서도 동일해야 한다.
-- (@Enumerated(STRING) 이라 저장값은 문자열이지만, ORDER BY role_tier 는 DB 인덱스
--  순서를 따르므로 두 순서가 어긋나면 정렬 결과가 스펙과 달라진다)

ALTER TABLE `movie_actor`
  MODIFY COLUMN `role_tier` enum('LEAD','SUPPORTING','MINOR','EXTRA') NOT NULL;


-- -----------------------------------------------------------------------------
-- (3) movie.overview 확장
-- -----------------------------------------------------------------------------
-- 확장이라 기존 데이터 손실이 없다. NULL 허용도 그대로 유지한다
-- (TMDB overview 가 비어 오는 경우가 있고, 6-4 폴백에서 nullable 을 전제한다).
--
-- utf8mb4 기준 4000 자 = 16,000 바이트. InnoDB 행 한계 65,535 바이트에 여유가 있고,
-- MySQL 8 기본 DYNAMIC 행 포맷에서는 긴 varchar 가 오버플로 페이지로 빠져 부담이 더 적다.
--
-- 엔티티: @Column(name = "overview", length = 1000) -> length = 4000

ALTER TABLE `movie`
  MODIFY COLUMN `overview` varchar(4000) DEFAULT NULL;


-- -----------------------------------------------------------------------------
-- (4) 기존 행 백필 — (0) 에서 existing_rows > 0 인 경우에만 실행
-- -----------------------------------------------------------------------------
-- movie 테이블을 채울 경로가 아직 없어(TestController 는 S-9 A-7 로 삭제됨)
-- 정상적으로는 0 행이다. 테스트 데이터가 남아 있다면 아래로 정리한다.
--
-- display_order 원본을 복원할 방법이 없으므로 (TMDB 재조회 없이는 불가)
-- 부분 백필보다 전량 삭제 후 재동기화가 옳다. 어차피 6-4 의 재동기화가
-- "전량 삭제 후 재삽입"이다.
--
-- SET @old_safe_updates = @@SQL_SAFE_UPDATES;
-- SET SQL_SAFE_UPDATES = 0;
-- DELETE FROM `movie_actor`;
-- SET SQL_SAFE_UPDATES = @old_safe_updates;


-- =============================================================================
-- 적용 후 체크리스트
-- =============================================================================
-- ddl-auto=validate 는 엔티티 -> 스키마 단방향으로만 검증하며
-- UNIQUE / FK / 인덱스 / CHECK 는 검증하지 않는다. 아래를 직접 확인할 것.

-- [1] 테이블 수: 22 (변동 없음)
SELECT COUNT(*) AS table_count
FROM information_schema.tables
WHERE table_schema = 'cinemory' AND table_type = 'BASE TABLE';

-- [2] movie_actor 컬럼 구성
--     character_name 다음에 display_order(int, NO), 그 다음 role_tier 여야 한다
--     !! smallint 로 보이면 아래를 실행할 것 !!
--        ALTER TABLE `movie_actor` MODIFY COLUMN `display_order` int NOT NULL;
SELECT ordinal_position, column_name, column_type, is_nullable
FROM information_schema.columns
WHERE table_schema = 'cinemory' AND table_name = 'movie_actor'
ORDER BY ordinal_position;

-- [3] role_tier 에 EXTRA 가 맨 끝에 있는지
--     기대: enum('LEAD','SUPPORTING','MINOR','EXTRA')
SELECT column_type FROM information_schema.columns
WHERE table_schema = 'cinemory' AND table_name = 'movie_actor' AND column_name = 'role_tier';

-- [4] 기존 인덱스 3건이 그대로인지 (PRIMARY / uk_movie_actor / fk_movie_actor_person)
--     MODIFY COLUMN 은 인덱스를 건드리지 않지만 확인한다
SELECT index_name, non_unique, seq_in_index, column_name
FROM information_schema.statistics
WHERE table_schema = 'cinemory' AND table_name = 'movie_actor'
ORDER BY index_name, seq_in_index;

-- [4-1] movie.overview: varchar(4000), NULL 허용 유지
SELECT column_type, is_nullable, character_maximum_length
FROM information_schema.columns
WHERE table_schema = 'cinemory' AND table_name = 'movie' AND column_name = 'overview';

-- [5] EXTRA 가 실제로 들어가는지 (movie/person 이 있을 때만 의미가 있다)
--     확인 후 반드시 롤백할 것.
-- START TRANSACTION;
-- INSERT INTO `movie_actor` (`movie_id`,`person_id`,`character_name`,`display_order`,`role_tier`)
-- SELECT m.id, p.id, 'probe', 21, 'EXTRA' FROM `movie` m, `person` p LIMIT 1;
-- SELECT * FROM `movie_actor` WHERE `character_name` = 'probe';
-- ROLLBACK;

-- [6] 애플리케이션 기동 확인 (엔티티 반영 후)
--     MovieActor.displayOrder / RoleTier.EXTRA / Movie.overview length=4000 반영
--     -> ./gradlew bootRun -> validate 통과


-- =============================================================================
-- 롤백 스크립트
-- =============================================================================
-- 적용 역순으로 되돌린다. 엔티티를 이미 v11 기준으로 고쳤다면
-- 롤백 후에는 애플리케이션이 validate 에서 기동 실패한다 — 코드도 함께 되돌릴 것.
--
-- !! 순서 주의 !!
--   (a) EXTRA 를 제거하기 전에 EXTRA 행이 남아 있으면 ALTER 가 실패하거나
--       (비 STRICT 모드) 값이 빈 문자열로 잘린다. 반드시 먼저 지운다.
--   (b) overview 축소는 1000 자를 넘는 행이 있으면 STRICT 모드에서 실패한다.
--       되돌릴 일이 생기면 절단이 먼저다 — 그 시점에 데이터가 실제로 잘린다.
--
-- ALTER TABLE `movie` MODIFY COLUMN `overview` varchar(1000) DEFAULT NULL;
--
-- DELETE FROM `movie_actor` WHERE `role_tier` = 'EXTRA';
-- ALTER TABLE `movie_actor`
--   MODIFY COLUMN `role_tier` enum('LEAD','SUPPORTING','MINOR') NOT NULL;
-- ALTER TABLE `movie_actor` DROP COLUMN `display_order`;


-- =============================================================================
-- 재덤프 (진실의 원천 갱신)
-- =============================================================================
-- v10 덤프와 동일한 옵션으로 뽑아야 diff 가 의미를 갖는다 (--no-data, 단일 DB 지정).
--
--   mysqldump -u root -p --no-data cinemory --result-file=docs/schema/cinemory_backup_v11.sql
--
-- !! > 리다이렉션을 쓰지 말 것 !!
--   (a) Windows 리다이렉션이 개행을 \n -> \r\n 으로 바꿔 덤프를 오염시킨다.
--   (b) PowerShell 5.1 의 > 는 기본 인코딩이 UTF-16LE 라 파일이 통째로 깨진다.
--   반드시 --result-file 을 쓴다.
--
-- 덤프 후
--   - .gitignore 의 docs/schema/cinemory_backup_*.sql 패턴이 v11 도 잡는지 확인
--     git check-ignore -v docs/schema/cinemory_backup_v11.sql
--   - 이 델타 파일은 리뷰 대상이므로 계속 추적되어야 한다
--     git check-ignore -v docs/schema/v11-delta.sql   (아무것도 출력되지 않아야 정상)
--   - CLAUDE.md 와 jpa-entity-spec.md 의 "진실의 원천" 경로를 v11 로 갱신할 것
-- =============================================================================
