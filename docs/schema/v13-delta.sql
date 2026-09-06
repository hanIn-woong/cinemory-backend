-- =============================================================================
-- CineMory 스키마 델타 : v12 -> v13
-- =============================================================================
-- 대상 DB : cinemory (MySQL 8.0)
-- 작성일  : 2026-08-23
-- 근거    : docs/tmdb-sync-spec.md 6-9
--
-- 변경 요약 (22 -> 22 테이블, 테이블 수 변동 없음)
--   movie 에 컬럼 4개 추가. 전부 TMDB 응답에 이미 들어오는 값인데 저장하지 않던 것들이다.
--
--   (1) original_title  varchar(255)  -- 원어 제목. 검색 매칭용
--   (2) backdrop_path   varchar(255)  -- 가로(16:9) 배경 이미지 경로. 상세 화면
--   (3) vote_average    decimal(3,1)  -- TMDB 평점
--   (4) vote_count      int           -- TMDB 투표 수
--
-- 왜 (1) 이 필요한가 — 실측된 문제다
--   GET /api/movies/search?query=avatar  →  registered 0건
--   GET /api/movies/search?query=아바타  →  registered 1건
--
--   우리 movie.title 은 language=ko-KR 로 받은 한국어 제목이라 "아바타"로 저장돼 있고,
--   LIKE '%avatar%' 에 걸리지 않는다. 반면 TMDB /search/movie 는 공식 설명상
--   "original, translated and alternative titles" 를 전부 검색한다.
--
--   그래서 같은 검색어로 두 섹션의 매칭 기준이 어긋난다 —
--   사용자가 이미 기록해 둔 영화가 registered 에 안 나오고 suggestions 에만 나온다.
--   데이터는 안전하지만(POST /api/movies/sync 가 멱등) "내 기록이 검색에 없다"는 체감이 나쁘다.
--
--   ⚠️ original_title 은 이미 받고 있었다. TmdbMovieDetailResponse.originalTitle 이
--      title 이 빌 때의 폴백으로만 쓰이고 저장되지 않았다.
--
-- 넣지 않은 것과 이유
--   - tagline : 홍보 문구 한 줄. 상세 화면 완성도에 기여하나 한국어 번역률이 overview 보다
--     낮을 것으로 보이고 실측하지 않았다. 이 프로젝트는 D-4(overview 4000 확장 후 롤백)와
--     잔여 #11(길이 초과)에서 "추정으로 스키마를 넓히지 않는다"를 두 번 지켰다.
--     resync 엔드포인트가 생기므로 나중에 추가해도 7분이면 채워진다.
--   - status : Rumored / Planned / In Production / Post Production / Released / Canceled.
--     "개봉 예정" 판정은 release_date > today 로 대체된다. 고유 가치는 "제작 취소"와
--     "개봉일 미정" 구분뿐인데 현재 화면 요구에 없다.
--   - popularity : TMDB 공식 문서상 "그날의 투표/조회/즐겨찾기/워치리스트 수 + 개봉일
--     + 총 투표 수 + 전날 점수"로 매일 재계산된다. 값 자체에 절대적 의미가 없고 계속 흐른다.
--     저장하면 그날의 스냅샷일 뿐이다. 콜드 스타트에는 vote_average + vote_count 하한이
--     더 안정적이다.
--   - original_language : 현재 용도가 없다. 필요해지면 그때 추가한다(사용자 판단).
--   - original_title 인덱스 : 검색이 LIKE '%q%' 라 선행 와일드카드다.
--     B-tree 인덱스가 원리적으로 무의미하다(잔여 #20 과 같은 이유).
--
-- !! 실행 전 확인 !!
--   - v12 가 적용된 상태에서 실행한다. 아래 (0) 사전 점검을 먼저 돌릴 것.
--   - 전부 nullable 컬럼 추가라 기존 행은 NULL 로 남는다. 데이터 손실이 없다.
--   - ⚠️ 기존 적재분(약 60편)은 NULL 인 채로 남으므로 resync 가 필요하다. 맨 아래 참고.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- (0) 사전 점검 — 기대값과 다르면 중단할 것
-- -----------------------------------------------------------------------------
-- 테이블 수: 22 여야 한다
SELECT COUNT(*) AS table_count
FROM information_schema.tables
WHERE table_schema = 'cinemory' AND table_type = 'BASE TABLE';

-- 이미 적용됐는지 확인: 0 이어야 한다
SELECT COUNT(*) AS already_applied
FROM information_schema.columns
WHERE table_schema = 'cinemory' AND table_name = 'movie'
  AND column_name IN ('original_title', 'backdrop_path', 'vote_average', 'vote_count');

-- v12 상태 확인: overview = varchar(1000) 이어야 한다
SELECT column_type FROM information_schema.columns
WHERE table_schema = 'cinemory' AND table_name = 'movie' AND column_name = 'overview';

-- 재적재 대상 규모 (참고용)
SELECT COUNT(*) AS existing_movies FROM `movie`;


-- -----------------------------------------------------------------------------
-- (1) ~ (4) movie 컬럼 4개
-- -----------------------------------------------------------------------------
-- 타입 선정 근거
--   original_title : title 과 대칭으로 varchar(255).
--   backdrop_path  : poster_path 와 대칭으로 varchar(255). 경로만 저장하고 베이스 URL 은
--                    프론트가 조립한다(poster_path 와 동일 원칙). null 이 흔하므로
--                    프론트에 폴백이 필요하다 — 인지도 낮은 작품일수록 없다.
--   vote_average   : TMDB 가 소수 첫째 자리까지 주고 최대 10.0 이라 decimal(3,1) 이 정확히 맞는다.
--                    double 을 쓰면 8.433 이 그대로 들어와 표시 때마다 반올림 처리가 필요해진다.
--   vote_count     : int. 인기작은 수만 표다.
--
-- ⚠️ vote_average / vote_count 를 세트로 넣는 이유
--   평점만 있으면 "투표 3명짜리 10.0" 과 "22,061명짜리 8.4" 가 구별되지 않는다.
--   신뢰도 표시에도, M3 콜드 스타트의 "평점 높은 영화" 선정에도 vote_count 하한이 필요하다.
--
-- ⚠️ 전부 nullable 이다
--   TMDB 가 항상 주지만 보장은 없고, backdrop_path 는 실제로 null 이 흔하다.
--   not null 로 묶으면 적재가 죽는다.

ALTER TABLE `movie`
  ADD COLUMN `original_title` varchar(255) DEFAULT NULL AFTER `title`,
  ADD COLUMN `backdrop_path`  varchar(255) DEFAULT NULL AFTER `poster_path`,
  ADD COLUMN `vote_average`   decimal(3,1) DEFAULT NULL AFTER `runtime`,
  ADD COLUMN `vote_count`     int          DEFAULT NULL AFTER `vote_average`;


-- =============================================================================
-- 적용 후 체크리스트
-- =============================================================================
-- ddl-auto=validate 는 엔티티 -> 스키마 단방향으로만 검증하며
-- UNIQUE / FK / 인덱스 / CHECK 는 검증하지 않는다. 아래를 직접 확인할 것.

-- [1] 테이블 수: 22 (변동 없음)
SELECT COUNT(*) AS table_count
FROM information_schema.tables
WHERE table_schema = 'cinemory' AND table_type = 'BASE TABLE';

-- [2] movie 컬럼 구성 — 위치와 타입 확인
--     기대: title 다음 original_title, poster_path 다음 backdrop_path,
--           runtime 다음 vote_average, 그다음 vote_count. 전부 YES(nullable)
SELECT ordinal_position, column_name, column_type, is_nullable
FROM information_schema.columns
WHERE table_schema = 'cinemory' AND table_name = 'movie'
ORDER BY ordinal_position;

-- [3] 기존 인덱스 3건이 그대로인지 (PRIMARY / uk_movie_tmdb_id / uk_movie_kofic_cd)
SELECT index_name, non_unique, seq_in_index, column_name
FROM information_schema.statistics
WHERE table_schema = 'cinemory' AND table_name = 'movie'
ORDER BY index_name, seq_in_index;

-- [4] 애플리케이션 기동 확인 (엔티티 반영 후)
--     Movie 에 originalTitle / backdropPath / voteAverage / voteCount 추가
--     -> ./gradlew bootRun -> validate 통과

-- [5] 재적재 후 채워졌는지
SELECT COUNT(*)                                   AS total,
       SUM(`original_title` IS NOT NULL)          AS has_original_title,
       SUM(`backdrop_path`  IS NOT NULL)          AS has_backdrop,
       SUM(`vote_average`   IS NOT NULL)          AS has_vote_average
FROM `movie`;

-- [6] 원어 검색이 실제로 되는지 (이 델타의 존재 이유)
--     GET /api/movies/search?query=avatar  ->  registered 에 "아바타"가 나와야 한다


-- =============================================================================
-- ⚠️ 재적재 (resync) — 이 델타는 적용만으로 끝나지 않는다
-- =============================================================================
-- 기존 적재분은 새 컬럼이 전부 NULL 이다. 채우려면 TMDB 를 다시 읽어야 한다.
--
-- 그런데 시드(MovieSeedService)는 existsByTmdbId 로 이미 있는 영화를 건너뛰므로
-- 다시 돌려도 채워지지 않는다. 전체 재동기화 진입점이 필요하다:
--
--   POST /api/admin/movies/resync    (tmdb-sync 6-9, 잔여 #23)
--
-- 비용은 크지 않다 — 2,000편 x 1왕복 x 약 200ms ≈ 7분.
-- syncFromTmdb 가 멱등이고 기존 영화도 updateMetadata 로 갱신한다.
--
-- ⚠️ vote_average 는 시간에 따라 변한다(표가 쌓이며 8.4 -> 8.6). 저장하는 순간
--    stale 해지므로 resync 는 이 델타의 일회성 보정이 아니라 상시 필요한 수단이다.


-- =============================================================================
-- 롤백 스크립트
-- =============================================================================
-- 컬럼 추가만 했으므로 되돌리기 단순하다. 엔티티를 이미 v13 기준으로 고쳤다면
-- 롤백 후 validate 에서 기동 실패한다 — 코드도 함께 되돌릴 것.
--
-- ALTER TABLE `movie`
--   DROP COLUMN `vote_count`,
--   DROP COLUMN `vote_average`,
--   DROP COLUMN `backdrop_path`,
--   DROP COLUMN `original_title`;


-- =============================================================================
-- 재덤프 (진실의 원천 갱신)
-- =============================================================================
--   mysqldump -u root -p --no-data cinemory --result-file=docs/schema/cinemory_backup_v13.sql
--
-- !! > 리다이렉션을 쓰지 말 것 !!
--   (a) Windows 리다이렉션이 개행을 \n -> \r\n 으로 바꿔 덤프를 오염시킨다.
--   (b) PowerShell 5.1 의 > 는 기본 인코딩이 UTF-16LE 라 파일이 통째로 깨진다.
--   반드시 --result-file 을 쓴다.
--
-- 덤프 후
--   - CLAUDE.md 와 jpa-entity-spec.md 의 "진실의 원천" 경로를 v13 으로 갱신할 것
--   - git check-ignore -v docs/schema/v13-delta.sql   (아무것도 출력되지 않아야 정상)
-- =============================================================================
