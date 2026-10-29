-- 소셜 피드 부하 측정 ① 시드 (social-feed-spec 6절) — cinemory_perf 전용. 다른 DB에 실행하지 말 것.
--
-- 실행: SocialFeedPerfTest가 cinemory_perf의 user 테이블이 비어 있으면 이 파일을 스스로 실행한다(./gradlew perfTest).
--   수동으로 하려면 Flyway가 V35까지 올린 빈 cinemory_perf에 mysql < 이 파일 (같은 세션 변수를 쓰므로 한 번에 실행한다)
--
-- 데이터 (spec 6절)
--   사용자 1만. 측정 대상(viewer) = user 1 이 2..1001 (1000명)을 팔로우한다. 개인 피드 대상 = user 5000 (활동 약 5천 건)
--   최근 30일 활동 30만 건: 기록 50% · 찜 20% · 리뷰 10% · 컬렉션 영화 추가 10% · 팔로우 7% · 컬렉션 3%
--   그 이전(31~365일) 노이즈 100만 건: 같은 분포
--   공개 범위: PUBLIC 90% · PRIVATE 5% (n % 20 = 0) · FRIENDS 5% (n % 20 = 10, 그중 절반은 viewer를 맞팔)
--
-- 재현성: 난수는 RAND(seed) — 문장마다, 용도마다 시드를 달리한다(같은 시드를 한 문장에서 두 번 쓰면 값이 겹친다).
--   시각은 @anchor(실행 시각) 기준 상대값이라 날짜가 달라도 모양이 같다. 유니크 제약이 있는 표는 INSERT IGNORE(충돌은 극소수).
--   주의 문장 안의 세미콜론은 문장 구분자뿐이다(스크립트 러너가 세미콜론으로 나눈다).

SET SESSION cte_max_recursion_depth = 1000000;
SET SESSION foreign_key_checks = 0;
SET @anchor = NOW();

DROP TABLE IF EXISTS perf_seq;
CREATE TABLE perf_seq (n INT NOT NULL PRIMARY KEY);
INSERT INTO perf_seq
WITH RECURSIVE s(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM s WHERE n < 700000)
SELECT n FROM s;

-- ---- 사용자 · 영화 -------------------------------------------------------------------------------------------
INSERT INTO `user` (email, password_hash, nickname, privacy_setting, role, created_at)
SELECT CONCAT('perf', n, '@perf.test'), 'x', CONCAT('perf', n),
       CASE WHEN MOD(n, 20) = 0 THEN 'PRIVATE' WHEN MOD(n, 20) = 10 THEN 'FRIENDS' ELSE 'PUBLIC' END,
       'USER', @anchor - INTERVAL 400 DAY
FROM perf_seq WHERE n <= 10000 ORDER BY n;

-- 개인 피드 대상(user 5000)은 n % 20 = 0 규칙으로 PRIVATE이 되므로 PUBLIC으로 고정한다
UPDATE `user` SET privacy_setting = 'PUBLIC' WHERE id = 5000;

INSERT INTO movie (tmdb_id, title, poster_path, release_date)
SELECT 700000000 + n, CONCAT('Perf Movie ', n), CONCAT('/perf', n, '.jpg'), DATE('2015-01-01') + INTERVAL MOD(n, 3000) DAY
FROM perf_seq WHERE n <= 2000 ORDER BY n;

-- ---- 측정 대상의 팔로우 1000건 ---------------------------------------------------------------------------------
INSERT INTO follow (follower_id, following_id, created_at)
SELECT 1, n, @anchor - INTERVAL 100 DAY FROM perf_seq WHERE n BETWEEN 2 AND 1001 ORDER BY n;

INSERT INTO follow (follower_id, following_id, created_at)
SELECT n, 1, @anchor - INTERVAL 100 DAY FROM perf_seq WHERE n BETWEEN 2 AND 1001 AND MOD(n, 40) = 10 ORDER BY n;

-- ---- 31~365일 전 노이즈 (100만 건) — 컬렉션을 먼저 넣어 id 1..30000이 과거 컬렉션이 되게 한다 ----------------------
INSERT INTO collection (user_id, name, position, created_at)
SELECT 1 + FLOOR(RAND(101) * 10000), CONCAT('c', n), 0, @anchor - INTERVAL (2592000 + FLOOR(RAND(102) * 28944000)) SECOND
FROM perf_seq WHERE n <= 30000 ORDER BY n;

INSERT INTO watch_record (user_id, movie_id, watch_type, rating, private_review, created_at)
SELECT 1 + FLOOR(RAND(111) * 10000), 1 + FLOOR(RAND(112) * 2000), 'ETC', 1 + FLOOR(RAND(114) * 10), 'memo',
       @anchor - INTERVAL (2592000 + FLOOR(RAND(113) * 28944000)) SECOND
FROM perf_seq WHERE n <= 500000 ORDER BY n;

INSERT IGNORE INTO wish_movie (user_id, movie_id, created_at)
SELECT 1 + FLOOR(RAND(121) * 10000), 1 + FLOOR(RAND(122) * 2000), @anchor - INTERVAL (2592000 + FLOOR(RAND(123) * 28944000)) SECOND
FROM perf_seq WHERE n <= 200000 ORDER BY n;

INSERT IGNORE INTO review (user_id, movie_id, content, created_at)
SELECT 1 + FLOOR(RAND(131) * 10000), 1 + FLOOR(RAND(132) * 2000), 'perf review', @anchor - INTERVAL (2592000 + FLOOR(RAND(133) * 28944000)) SECOND
FROM perf_seq WHERE n <= 100000 ORDER BY n;

INSERT IGNORE INTO collection_movie (collection_id, movie_id, position, created_at)
SELECT 1 + FLOOR(RAND(141) * 30000), 1 + FLOOR(RAND(142) * 2000), 0, @anchor - INTERVAL (2592000 + FLOOR(RAND(143) * 28944000)) SECOND
FROM perf_seq WHERE n <= 100000 ORDER BY n;

INSERT IGNORE INTO follow (follower_id, following_id, created_at)
SELECT 1 + FLOOR(RAND(151) * 10000), 1 + FLOOR(RAND(152) * 10000), @anchor - INTERVAL (2592000 + FLOOR(RAND(153) * 28944000)) SECOND
FROM perf_seq WHERE n <= 70000 ORDER BY n;

-- ---- 최근 30일 (30만 건) ------------------------------------------------------------------------------------
INSERT INTO collection (user_id, name, position, created_at)
SELECT 1 + FLOOR(RAND(201) * 10000), CONCAT('c', 30000 + n), 0, @anchor - INTERVAL FLOOR(RAND(202) * 2592000) SECOND
FROM perf_seq WHERE n <= 9000 ORDER BY n;

INSERT INTO watch_record (user_id, movie_id, watch_type, rating, private_review, created_at)
SELECT 1 + FLOOR(RAND(211) * 10000), 1 + FLOOR(RAND(212) * 2000), 'ETC', 1 + FLOOR(RAND(214) * 10), 'memo',
       @anchor - INTERVAL FLOOR(RAND(213) * 2592000) SECOND
FROM perf_seq WHERE n <= 150000 ORDER BY n;

INSERT IGNORE INTO wish_movie (user_id, movie_id, created_at)
SELECT 1 + FLOOR(RAND(221) * 10000), 1 + FLOOR(RAND(222) * 2000), @anchor - INTERVAL FLOOR(RAND(223) * 2592000) SECOND
FROM perf_seq WHERE n <= 60000 ORDER BY n;

INSERT IGNORE INTO review (user_id, movie_id, content, created_at)
SELECT 1 + FLOOR(RAND(231) * 10000), 1 + FLOOR(RAND(232) * 2000), 'perf review', @anchor - INTERVAL FLOOR(RAND(233) * 2592000) SECOND
FROM perf_seq WHERE n <= 30000 ORDER BY n;

INSERT IGNORE INTO collection_movie (collection_id, movie_id, position, created_at)
SELECT 1 + FLOOR(RAND(241) * 39000), 1 + FLOOR(RAND(242) * 2000), 0, @anchor - INTERVAL FLOOR(RAND(243) * 2592000) SECOND
FROM perf_seq WHERE n <= 30000 ORDER BY n;

-- 자기 자신 팔로우(chk_follow_not_self)는 IGNORE로 건너뛴다. 측정 대상(user 1)의 팔로우는 위 1000건뿐이어야 하므로 나머지를 지운다
INSERT IGNORE INTO follow (follower_id, following_id, created_at)
SELECT 1 + FLOOR(RAND(251) * 10000), 1 + FLOOR(RAND(252) * 10000), @anchor - INTERVAL FLOOR(RAND(253) * 2592000) SECOND
FROM perf_seq WHERE n <= 21000 ORDER BY n;

DELETE FROM follow WHERE follower_id = 1 AND following_id > 1001;

-- ---- 개인 피드 대상 user 5000 — 1년에 걸쳐 약 5천 건 (기록 2500 · 찜 1000 · 리뷰 500 · 컬렉션 50 · 추가 700 · 팔로우 250) ---------
INSERT INTO watch_record (user_id, movie_id, watch_type, rating, private_review, created_at)
SELECT 5000, 1 + MOD(n, 2000), 'ETC', 1 + MOD(n, 10), 'memo', @anchor - INTERVAL (n * 12000) SECOND
FROM perf_seq WHERE n <= 2500 ORDER BY n;

INSERT IGNORE INTO wish_movie (user_id, movie_id, created_at)
SELECT 5000, n, @anchor - INTERVAL (n * 30000 + 7) SECOND FROM perf_seq WHERE n <= 1000 ORDER BY n;

INSERT IGNORE INTO review (user_id, movie_id, content, created_at)
SELECT 5000, n, 'perf review', @anchor - INTERVAL (n * 60000 + 11) SECOND FROM perf_seq WHERE n <= 500 ORDER BY n;

INSERT INTO collection (user_id, name, position, created_at)
SELECT 5000, CONCAT('heavy', n), 0, @anchor - INTERVAL (n * 600000 + 13) SECOND FROM perf_seq WHERE n <= 50 ORDER BY n;

INSERT IGNORE INTO collection_movie (collection_id, movie_id, position, created_at)
SELECT c.id, 1 + FLOOR((s.n - 1) / 50), 0, @anchor - INTERVAL (s.n * 40000 + 17) SECOND
FROM perf_seq s
JOIN (SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM collection WHERE user_id = 5000 AND name LIKE 'heavy%') c
  ON c.rn = 1 + MOD(s.n - 1, 50)
WHERE s.n <= 700 ORDER BY s.n;

INSERT IGNORE INTO follow (follower_id, following_id, created_at)
SELECT 5000, 5000 + n, @anchor - INTERVAL (n * 120000 + 19) SECOND FROM perf_seq WHERE n <= 250 ORDER BY n;

DROP TABLE perf_seq;
SET SESSION foreign_key_checks = 1;

ANALYZE TABLE `user`, movie, watch_record, review, wish_movie, collection, collection_movie, follow;
