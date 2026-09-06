-- =============================================================================
-- CineMory 스키마 델타 : v13 -> v14
-- =============================================================================
-- 대상 DB : cinemory (MySQL 8.0)
-- 작성일  : 2026-08-24
-- 근거    : docs/tmdb-sync-spec.md 6-7 (4,609편 실측), 잔여 #11
-- 상태    : ✅ 사용자가 직접 적용 완료
--
-- 변경 요약 (22 -> 22 테이블, 테이블 수 변동 없음)
--   movie_actor.character_name : varchar(100) -> varchar(255)
--
-- 왜 필요한가 — 실측된 절단이다
--   6-3 ④에서 길이 초과 4곳을 "추정으로 컬럼을 넓히지 않고 절단 + WARN 후 실측"으로
--   확정했고, 6-7의 60편 표본에서는 character_name 최대가 30자라 절단이 0건이었다.
--   4,609편으로 표본을 키우자 실제로 나왔다.
--
--     표본 60편    : MAX(CHAR_LENGTH) = 30    절단 0건
--     표본 4,609편 : MAX(CHAR_LENGTH) = 100   절단 29건   ← 상한에 정확히 걸림
--
--   MAX 가 정확히 100 인 것이 절단의 증거다 — truncate() 가 "97자 + ...로 100자를 만든다.
--   자연 발생한 배역명이 우연히 정확히 100자일 확률은 낮다.
--
--   6-3 ④의 예측이 맞았다 — 네 컬럼 중 character_name 만 근거가 있었고
--   (TMDB 공식 가이드가 다역을 `Character 1 / Character 2 / Character 3` 슬래시 연결로 규정),
--   6-7이 경고한 표본 편향(인기작 60편은 데이터가 가장 정돈된 부류)도 그대로 확인됐다.
--
-- 왜 255 인가
--   title / original_title / backdrop_path 와 같은 값이다. 이 스키마의 문자열 기본 폭이며,
--   실측 원본이 100자를 갓 넘는 수준이라 2.5배 여유면 충분하다.
--
--   ⚠️ 정오 (2026-08-27) — 위 문장의 근거가 약했다. 잔여 #28 참고.
--     "실측 원본이 100자를 갓 넘는 수준" 이라고 썼지만, 그 값은 이미 100자로 잘린 뒤라
--     진짜 길이를 알 수 없는 상태였다. 상한에 걸린 값으로 상한을 정한 셈이다.
--
--     resync(2026-08-27)로 자르지 않은 원본을 처음 보니 255 를 넘는 것이 3건 있었다:
--       tmdbId=35    원본 300자
--       tmdbId=35    원본 270자
--       tmdbId=9473  원본 348자
--
--     절단은 29건 -> 3건으로 줄었으나 0 은 아니다. 아래 체크리스트 [4] 의
--     at_new_limit 이 "0 이 아니면 255 도 부족하다는 신호" 라고 한 그 경우다.
--
--     ⚠️ 그래도 지금 확장하지 않는다 — 186,717행 중 3건(0.0016%)이고, 절단이 WARN 과 함께
--     graceful 하며, 348자짜리 배역명은 애초에 UI 에서 전부 표시할 값이 아니다.
--     복구에 resync 30분이 또 든다. 다음 resync 때 v15 와 함께 판단한다.
--
-- ⚠️ INSTANT 로 처리된다
--   utf8mb4 에서 varchar 길이 접두사는 최대 바이트 수가 255 이하면 1바이트, 초과면 2바이트다.
--   varchar(100) = 400바이트, varchar(255) = 1020바이트로 둘 다 2바이트라
--   접두사 크기가 바뀌지 않아 MySQL 8.0 이 ALGORITHM=INSTANT 로 처리한다.
--   movie_actor 가 18만 행이어도 테이블 재구축이 없다.
--
--   (경계는 varchar(63)/varchar(64) 다 — 63x4=252 <= 255, 64x4=256 > 255.
--    100 -> 255 는 그 경계를 넘지 않는다.)
--
-- 넣지 않은 것과 이유
--   - person.name (100) : 4,609편 실측에서도 절단 0건. 사람 이름이 100자에 닿지 않는다.
--   - movie.title (255) : 실측 최대 66자. 여유 3.9배.
--   - movie.overview (1000) : 실측 최대 978자로 상한의 97.8%지만 절단 0건.
--     TMDB 가 overview 를 1000자로 제한하므로(D-4) 구조적으로 넘을 수 없다.
--     ⚠️ 다만 6-7 이 "여유 1.5배"라고 한 판정은 60편 표본(684자) 기준이었고,
--     실제로는 TMDB 가 제한선까지 꽉 채워 쓴다. 절단 로직을 남긴 판단이 옳았다.
--
-- !! 실행 전 확인 !!
--   - v13 이 적용된 상태에서 실행한다.
--   - 확장이라 데이터 손실이 없다. nullable 도 그대로 유지한다.
--   - ⚠️ 이미 잘린 29건은 이 ALTER 로 복구되지 않는다. resync 가 필요하다(맨 아래).
-- =============================================================================


-- -----------------------------------------------------------------------------
-- (0) 사전 점검 — 기대값과 다르면 중단할 것
-- -----------------------------------------------------------------------------
-- 현재 타입: varchar(100) 이어야 한다
SELECT column_type, is_nullable
FROM information_schema.columns
WHERE table_schema = 'cinemory' AND table_name = 'movie_actor' AND column_name = 'character_name';

-- 절단된 행 수 (적용 전 기록용). 실측 시점 기준 29건
SELECT COUNT(*) AS truncated_rows
FROM `movie_actor`
WHERE CHAR_LENGTH(`character_name`) >= 99;


-- -----------------------------------------------------------------------------
-- (1) movie_actor.character_name 확장
-- -----------------------------------------------------------------------------
-- nullable 유지 — TMDB cast[].character 는 비어 오는 경우가 있다.

ALTER TABLE `movie_actor`
  MODIFY COLUMN `character_name` varchar(255) DEFAULT NULL;


-- =============================================================================
-- 적용 후 체크리스트
-- =============================================================================

-- [1] 타입 확인 — varchar(255), YES(nullable)
SELECT column_type, is_nullable
FROM information_schema.columns
WHERE table_schema = 'cinemory' AND table_name = 'movie_actor' AND column_name = 'character_name';

-- [2] 기존 인덱스 3건이 그대로인지 (PRIMARY / uk_movie_actor / fk_movie_actor_person)
SELECT index_name, non_unique, seq_in_index, column_name
FROM information_schema.statistics
WHERE table_schema = 'cinemory' AND table_name = 'movie_actor'
ORDER BY index_name, seq_in_index;

-- [3] 엔티티 반영 후 기동
--     MovieActor.characterName 의 @Column(length = 100) -> 255
--     MovieSyncPersister 의 CHARACTER_NAME_MAX_LENGTH 상수도 함께 100 -> 255
--     -> ./gradlew bootRun -> validate 통과
--
--     ⚠️ 상수를 놓치면 컬럼만 넓어지고 절단은 그대로 100자에서 계속된다.
--        ddl-auto=validate 는 길이를 검증하지 않으므로(v12 델타 [3] 참고) 조용히 지나간다.

-- [4] resync 후 실제로 복구됐는지
SELECT MAX(CHAR_LENGTH(`character_name`)) AS max_len,
       SUM(CHAR_LENGTH(`character_name`) >= 99)  AS at_old_limit,
       SUM(CHAR_LENGTH(`character_name`) >= 254) AS at_new_limit
FROM `movie_actor`;
--   max_len 이 100 을 넘으면 복구된 것이다(원본이 100자보다 길었다는 뜻).
--   at_new_limit 이 0 이 아니면 255 도 부족하다는 신호다.


-- =============================================================================
-- ⚠️ 재적재 (resync) — 이 델타도 적용만으로 끝나지 않는다
-- =============================================================================
-- 이미 잘린 29건은 DB 에 100자로 저장돼 있고, ALTER 로는 복구되지 않는다.
-- 원본은 TMDB 가 갖고 있으므로 다시 읽어야 한다:
--
--   POST /api/admin/movies/resync    (tmdb-sync 6-9, 잔여 #23)
--
-- v13 도 같은 이유로 resync 가 필요하다(v13 이전에 적재된 약 60편의 신규 컬럼이 NULL).
-- 두 건을 한 번의 resync 로 함께 해소할 수 있다 — 4,609편 x 왕복 약 200ms ≈ 15분.


-- =============================================================================
-- 롤백 스크립트
-- =============================================================================
-- ⚠️ 축소이므로 255자에 가까운 행이 있으면 STRICT 모드에서 실패한다.
--    resync 후에는 100자를 넘는 행이 존재할 수 있으니 먼저 확인할 것.
--
-- SELECT COUNT(*) FROM `movie_actor` WHERE CHAR_LENGTH(`character_name`) > 100;
-- ALTER TABLE `movie_actor` MODIFY COLUMN `character_name` varchar(100) DEFAULT NULL;


-- =============================================================================
-- 재덤프 (진실의 원천 갱신)
-- =============================================================================
--   mysqldump -u root -p --no-data cinemory --result-file=docs/schema/cinemory_backup_v14.sql
--
-- !! > 리다이렉션을 쓰지 말 것 !!
--   (a) Windows 리다이렉션이 개행을 \n -> \r\n 으로 바꿔 덤프를 오염시킨다.
--   (b) PowerShell 5.1 의 > 는 기본 인코딩이 UTF-16LE 라 파일이 통째로 깨진다.
--   반드시 --result-file 을 쓴다.
--
-- 덤프 후
--   - CLAUDE.md 와 jpa-entity-spec.md 의 "진실의 원천" 경로를 v14 로 갱신할 것
--   - git check-ignore -v docs/schema/v14-delta.sql   (아무것도 출력되지 않아야 정상)
-- =============================================================================
