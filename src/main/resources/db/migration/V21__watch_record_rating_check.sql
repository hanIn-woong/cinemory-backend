-- watch_record.rating : 1~10 정수 CHECK. 근거·사전 점검·롤백은 docs/schema/v21-delta.sql.
-- 이 파일은 동결 — 절대 수정 금지.

ALTER TABLE watch_record
  ADD CONSTRAINT chk_watch_record_rating
  CHECK (rating IS NULL OR (rating BETWEEN 1 AND 10 AND rating = FLOOR(rating)));
