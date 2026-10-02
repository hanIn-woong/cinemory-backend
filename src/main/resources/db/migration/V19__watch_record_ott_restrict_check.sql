-- watch_record → ott_platform : SET NULL → RESTRICT + chk_watch_record_ott. 근거·사전 점검·롤백은 docs/schema/v19-delta.sql.
-- 이 파일은 동결 — 절대 수정 금지.

ALTER TABLE watch_record DROP FOREIGN KEY fk_watch_record_ott;
ALTER TABLE watch_record
  ADD CONSTRAINT fk_watch_record_ott
  FOREIGN KEY (ott_platform_id) REFERENCES ott_platform (id) ON DELETE RESTRICT;
ALTER TABLE watch_record
  ADD CONSTRAINT chk_watch_record_ott
  CHECK ((watch_type = 'OTT' AND ott_platform_id IS NOT NULL)
      OR ((watch_type IS NULL OR watch_type <> 'OTT') AND ott_platform_id IS NULL));
