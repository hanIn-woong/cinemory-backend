-- collection_movie → collection : RESTRICT → CASCADE. 근거·롤백은 docs/schema/v18-delta.sql.
-- 이 파일은 동결 — 절대 수정 금지.

ALTER TABLE collection_movie DROP FOREIGN KEY fk_collection_movie_collection;
ALTER TABLE collection_movie
  ADD CONSTRAINT fk_collection_movie_collection
  FOREIGN KEY (collection_id) REFERENCES collection (id) ON DELETE CASCADE;
