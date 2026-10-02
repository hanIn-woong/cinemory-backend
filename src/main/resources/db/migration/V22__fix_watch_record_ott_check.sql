-- V19 chk_watch_record_ott 정정 — watch_type=NULL + 플랫폼이 CHECK를 통과하던 NULL 구멍을 NULL 안전 비교(<=>)로 막는다.
-- 근거·사전 점검·롤백은 docs/schema/v22-delta.sql. V19는 이미 적용돼 동결이라 고치지 않고 새 버전으로 바로잡는다.
-- 이 파일은 동결 — 절대 수정 금지.

ALTER TABLE watch_record DROP CHECK chk_watch_record_ott;
ALTER TABLE watch_record
  ADD CONSTRAINT chk_watch_record_ott
  CHECK ((watch_type <=> 'OTT' AND ott_platform_id IS NOT NULL)
      OR (NOT (watch_type <=> 'OTT') AND ott_platform_id IS NULL));
