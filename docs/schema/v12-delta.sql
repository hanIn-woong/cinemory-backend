-- =============================================================================
-- CineMory 스키마 델타 : v11 -> v12
-- =============================================================================
-- 대상 DB : cinemory (MySQL 8.0)
-- 작성일  : 2026-08-19
-- 근거    : docs/tmdb-sync-spec.md 6-3 확정 (D-4 정정 포함)
--
-- 변경 요약 (22 -> 22 테이블, 테이블 수 변동 없음)
--   (1) movie_actor.display_order : smallint -> int      [v11 정오]
--   (2) movie.overview : varchar(4000) -> varchar(1000)  [D-4 롤백]
--
-- 이 델타는 둘 다 v11 에서 내린 판단을 되돌리는 것이다. 각각의 근거는 아래에 남긴다.
--
-- !! 실행 전 확인 !!
--   - v11 이 적용된 상태에서 실행한다. 아래 (0) 사전 점검을 먼저 돌릴 것.
--   - (2) 는 컬럼 축소다. 1000 자를 넘는 행이 하나라도 있으면 STRICT 모드에서 ALTER 가
--     실패한다(비 STRICT 모드면 조용히 잘린다 — 더 나쁘다). 사전 점검에서 반드시 0 을 확인할 것.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- (0) 사전 점검 — 기대값과 다르면 중단할 것
-- -----------------------------------------------------------------------------
-- 테이블 수: 22 여야 한다
SELECT COUNT(*) AS table_count
FROM information_schema.tables
WHERE table_schema = 'cinemory' AND table_type = 'BASE TABLE';

-- 현재 타입 확인
--   기대: display_order = smallint / overview = varchar(4000)
SELECT table_name, column_name, column_type
FROM information_schema.columns
WHERE table_schema = 'cinemory'
  AND ((table_name = 'movie_actor' AND column_name = 'display_order')
    OR (table_name = 'movie'       AND column_name = 'overview'));

-- !! 가장 중요 !! overview 가 1000 자를 넘는 행 수: 0 이어야 한다
--   CHAR_LENGTH 다. LENGTH 는 바이트를 세므로 한국어에서 3 배로 나와 오판한다.
--   0 이 아니면 (2) 를 실행하지 말고 먼저 원인을 확인할 것 —
--   TMDB 가 overview 를 1000 자로 제한하므로 정상적으로는 나올 수 없는 값이다.
SELECT COUNT(*) AS overview_over_1000
FROM `movie`
WHERE CHAR_LENGTH(`overview`) > 1000;


-- -----------------------------------------------------------------------------
-- (1) movie_actor.display_order : smallint -> int   [v11 정오]
-- -----------------------------------------------------------------------------
-- v11 초판은 "TMDB order 가 200 을 넘는 일이 드무니 smallint 로 충분하다" 는 이유로
-- smallint 를 골랐다. 두 가지로 틀렸다.
--
--   (a) ddl-auto=validate 가 실패한다. Hibernate 는 엔티티 컬럼의 SQL 타입명 접두사
--       또는 JDBC 타입 코드가 일치할 때만 통과시킨다. Java Integer -> "integer"
--       (Types.INTEGER = 4) 이고 DB 는 "SMALLINT" (Types.SMALLINT = 5) 라 양쪽 다 어긋난다.
--       맞추려면 필드를 Short 로 바꿔야 하는데 tier 파생 산술과 리포지토리 파라미터에
--       캐스팅이 번진다.
--   (b) 기존 스키마와 어긋난다. 이 스키마에 smallint 컬럼은 하나도 없고
--       box_office_rank / screen_count / show_count / movie.runtime 이 전부 int 다.
--
-- 확장이라 데이터 손실이 없다.

ALTER TABLE `movie_actor`
  MODIFY COLUMN `display_order` int NOT NULL;


-- -----------------------------------------------------------------------------
-- (2) movie.overview : varchar(4000) -> varchar(1000)   [D-4 롤백]
-- -----------------------------------------------------------------------------
-- v11 (D-4) 는 "TMDB overview 가 1000 자를 넘을 수 있어 적재가 죽는다" 를 전제로
-- 4000 으로 넓혔다. 그 전제가 틀렸다.
--
--   TMDB 는 overview 를 1000 자로 제한한다 (TMDB 스태프 명시:
--   "we limit movie overviews to 1000 characters").
--   https://www.themoviedb.org/talk/5b59e5a09251414d1b012d9b
--
-- 즉 varchar(1000) 은 임의로 정해진 값이 아니라 TMDB 입력 제한에 맞춰 설계된 값이었다.
-- 실데이터를 확인하지 않고 넓힌 것이므로 원래 값으로 되돌려 정책을 스키마에 다시 고정한다.
--
-- 성능이 롤백 사유는 아니다. varchar 는 가변 길이라 같은 데이터면 저장 바이트가 같고,
-- 선언 길이가 문제되던 경로(MEMORY 임시 테이블의 고정 폭 패딩, filesort 고정 폭 행)는
-- MySQL 8.0 의 TempTable 엔진과 packed addon field 로 대부분 해소됐다.
-- overview 에는 인덱스도 없다. 되돌리는 이유는 오로지 "근거가 없었다" 는 것이다.
--
-- !! 축소이므로 (0) 의 overview_over_1000 이 0 임을 확인한 뒤 실행할 것 !!

ALTER TABLE `movie`
  MODIFY COLUMN `overview` varchar(1000) DEFAULT NULL;


-- =============================================================================
-- 적용 후 체크리스트
-- =============================================================================

-- [1] 타입 2건
--     기대: display_order = int / overview = varchar(1000)
SELECT table_name, column_name, column_type, is_nullable
FROM information_schema.columns
WHERE table_schema = 'cinemory'
  AND ((table_name = 'movie_actor' AND column_name = 'display_order')
    OR (table_name = 'movie'       AND column_name = 'overview'));

-- [2] movie_actor 인덱스 3건이 그대로인지 (PRIMARY / uk_movie_actor / fk_movie_actor_person)
SELECT index_name, non_unique, seq_in_index, column_name
FROM information_schema.statistics
WHERE table_schema = 'cinemory' AND table_name = 'movie_actor'
ORDER BY index_name, seq_in_index;

-- [3] 애플리케이션 기동 확인
--     ./gradlew bootRun -> validate 통과
--
--     !! 주의 — (1) 과 (2) 는 위험 성격이 다르다 !!
--       Hibernate 의 스키마 검증은 컬럼 "타입"은 보지만 "길이"는 보지 않는다.
--       - (1) display_order : 타입 불일치(INTEGER vs SMALLINT)라 validate 가 실제로 실패한다.
--         이 델타를 적용해야 기동이 된다.
--       - (2) overview      : 길이는 검증 대상이 아니므로 엔티티(1000)와 DB(4000)가 어긋나도
--         validate 는 통과한다. 대신 반대 방향(엔티티 4000 + DB 1000)으로 어긋난 상태에서
--         1000 자를 넘는 값을 넣으면 기동이 아니라 "적재 시점"에 터진다.
--         조용히 어긋나는 쪽이므로 엔티티와 DB 를 함께 맞춰 두는 것이 중요하다.


-- =============================================================================
-- 롤백 스크립트
-- =============================================================================
-- ALTER TABLE `movie`       MODIFY COLUMN `overview` varchar(4000) DEFAULT NULL;
-- ALTER TABLE `movie_actor` MODIFY COLUMN `display_order` smallint NOT NULL;
--
-- 단 display_order 를 smallint 로 되돌리면 validate 가 다시 깨진다. (1) 은 되돌리지 말 것.


-- =============================================================================
-- 재덤프 (진실의 원천 갱신)
-- =============================================================================
--   mysqldump -u root -p --no-data cinemory --result-file=docs/schema/cinemory_backup_v12.sql
--
-- !! > 리다이렉션을 쓰지 말 것 !!
--   (a) Windows 리다이렉션이 개행을 \n -> \r\n 으로 바꿔 덤프를 오염시킨다.
--   (b) PowerShell 5.1 의 > 는 기본 인코딩이 UTF-16LE 라 파일이 통째로 깨진다.
--   반드시 --result-file 을 쓴다.
--
-- 덤프 후
--   - CLAUDE.md 와 jpa-entity-spec.md 의 "진실의 원천" 경로를 v12 로 갱신할 것
--   - git check-ignore -v docs/schema/v12-delta.sql   (아무것도 출력되지 않아야 정상)
-- =============================================================================
