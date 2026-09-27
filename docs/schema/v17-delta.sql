-- =============================================================================
-- CineMory 스키마 델타 : v16 -> v17
-- =============================================================================
-- 대상 DB : cinemory (MySQL 8.0) + cinemory_test
-- 작성일  : 2026-09-27
-- 근거    : docs/jpa-entity-spec.md "6) Collection 순서 컬럼",
--           docs/service-layer-spec.md 4-5-A, docs/controller-layer-spec.md 5-4-A
-- 상태    : ✅ 적용 완료 (2026-09-27, cinemory_backup_v17.sql 재덤프 반영)
--
-- 변경 요약 (22 -> 22 테이블, 테이블 수 변동 없음)
--   [1] collection.position        INT NOT NULL DEFAULT 0  + 기존 행 순번 채우기
--   [2] collection_movie.position  INT NOT NULL DEFAULT 0  + 기존 행 순번 채우기
--   [3] 조회 경로 인덱스 2개
--
-- =============================================================================
-- 왜 필요한가
-- =============================================================================
--
--   잔여 #18(순서 지정, 프론트 B-19) — 사용자가 컬렉션과 컬렉션 내 영화를 드래그로 배치한다.
--   잔여 #17(정렬 미지정, 프론트 B-18) — findByUserId / findByCollectionId 에 정렬이 없어
--     오프셋 무한스크롤에서 페이지 경계 중복·누락이 날 수 있었다.
--
--   ⚠️ 두 문제가 이 한 컬럼으로 함께 닫힌다 — 순서를 저장하면 정렬이 생긴다.
--
-- =============================================================================
-- ⚠️ DEFAULT 0 만으로는 부족하다 — 기존 행에 순번을 채운다
-- =============================================================================
--
--   ADD COLUMN ... DEFAULT 0 직후 기존 행은 전부 0 이다. 순서가 없는 것과 같다.
--   기준은 이전 동작(id DESC = 최근 것이 위)이며, 1 부터 매긴다.
--
--   신규 행은 서비스가 MIN(position) - 1 (맨 위)로 넣으므로 음수가 생길 수 있다 — 정상이다.
--   순서를 정하는 상대값일 뿐이며, 재작성(PATCH .../order) 시 0..N-1 로 정규화된다.
--
-- =============================================================================
-- ⚠️ UNIQUE 를 걸지 않는다
-- =============================================================================
--
--   (user_id, position) / (collection_id, position) 을 UNIQUE 로 묶으면
--   전량 재작성 중간 상태에서 값이 일시적으로 겹쳐 충돌한다.
--   유일성은 Service 재작성 로직이 보장하고, 조회는 id DESC 보조키로 전순서를 만든다.
--
-- =============================================================================
-- ⚠️ 적용 순서 — ddl-auto: validate 때문에 코드와 한 묶음이다
-- =============================================================================
--
--   엔티티에 position 필드가 추가되므로 SQL 없이 코드만 올리면 기동이 실패한다
--   (Schema-validation: missing column [position]).
--   반대로 SQL 만 적용하고 코드를 두는 것은 무해하다(엔티티가 모르는 컬럼은 validate 대상이 아니다).
--
--     1. 이 델타를 cinemory 에 적용한다
--     2. ⚠️ 같은 델타를 cinemory_test 에도 적용한다
--          mysql -u root -p cinemory_test < docs/schema/v17-delta.sql
--     3. 코드를 올린다
--     4. ./gradlew compileJava test 후 기동해 validate 통과를 확인한다
--
-- =============================================================================
-- 적용
-- =============================================================================

-- [1] collection.position
ALTER TABLE `collection`
  ADD COLUMN `position` INT NOT NULL DEFAULT 0;

UPDATE `collection` c
JOIN (SELECT id, ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY id DESC) AS rn
      FROM `collection`) t ON c.id = t.id
SET c.position = t.rn;

-- [2] collection_movie.position
ALTER TABLE `collection_movie`
  ADD COLUMN `position` INT NOT NULL DEFAULT 0;

UPDATE `collection_movie` cm
JOIN (SELECT id, ROW_NUMBER() OVER (PARTITION BY collection_id ORDER BY id DESC) AS rn
      FROM `collection_movie`) t ON cm.id = t.id
SET cm.position = t.rn;

-- [3] 조회 경로 인덱스
--   findByUserIdOrderByPositionAscIdDesc / findByCollectionIdOrderByPositionAscIdDesc 가 탄다.
CREATE INDEX `idx_collection_user_position`       ON `collection` (`user_id`, `position`);
CREATE INDEX `idx_collection_movie_coll_position` ON `collection_movie` (`collection_id`, `position`);


-- =============================================================================
-- 적용 후 확인
-- =============================================================================
--
-- [1][2] 기존 행에 순번이 채워졌는지 — 둘 다 0 이어야 한다
--
-- SELECT COUNT(*) FROM collection       WHERE position = 0;
-- SELECT COUNT(*) FROM collection_movie WHERE position = 0;
--
-- [1][2] 범위 안에서 순번이 겹치지 않는지 — 둘 다 빈 결과여야 한다
--
-- SELECT user_id, position, COUNT(*) FROM collection
--   GROUP BY user_id, position HAVING COUNT(*) > 1;
-- SELECT collection_id, position, COUNT(*) FROM collection_movie
--   GROUP BY collection_id, position HAVING COUNT(*) > 1;
--
-- [3] 인덱스
--
-- SHOW INDEX FROM collection;        -> idx_collection_user_position
-- SHOW INDEX FROM collection_movie;  -> idx_collection_movie_coll_position
--
-- [4] 기동 검증 — ./gradlew compileJava test 후 실제 기동, ddl-auto: validate 통과
--
-- =============================================================================
-- 롤백
-- =============================================================================
--
-- DROP INDEX `idx_collection_movie_coll_position` ON `collection_movie`;
-- DROP INDEX `idx_collection_user_position` ON `collection`;
-- ALTER TABLE `collection_movie` DROP COLUMN `position`;
-- ALTER TABLE `collection`       DROP COLUMN `position`;
--
--   ⚠️ 사용자가 저장한 순서가 사라진다. 코드를 되돌리지 않고 SQL 만 롤백하면 validate 가 실패한다.
--
-- =============================================================================
-- 재덤프 (진실의 원천 갱신)
-- =============================================================================
--   mysqldump -u root -p --no-data cinemory --result-file=docs/schema/cinemory_backup_v17.sql
--
-- !! > 리다이렉션을 쓰지 말 것 !! (v16-delta.sql 재덤프 절 참고 — 개행·인코딩 오염)
--
-- 덤프 후
--   - CLAUDE.md 와 jpa-entity-spec.md 의 "진실의 원천" 경로를 v17 으로 갱신할 것
--   - ⚠️ cinemory_test 에도 적용됐는지 확인할 것
--       mysql -u root -p -e "SHOW COLUMNS FROM cinemory_test.collection LIKE 'position';"
-- =============================================================================
