-- 시청 기록 <-> 극장 연결 (service-layer-spec 4-3-C). 설계 근거·롤백은 docs/schema/v43-delta.sql.
-- 확장: nullable 컬럼 + FK + CHECK 추가. 기존 행은 모두 theater_id NULL이라 CHECK를 만족한다.
-- FK는 ON DELETE RESTRICT - 참조 중인 극장의 물리 삭제를 막는다(폐업은 closed_at 숨김이라 행이 남는다).
-- 이 파일은 동결 - 절대 수정 금지.
ALTER TABLE watch_record
  ADD COLUMN theater_id BIGINT NULL,
  ADD CONSTRAINT fk_watch_record_theater FOREIGN KEY (theater_id) REFERENCES theater (id) ON DELETE RESTRICT,
  ADD CONSTRAINT chk_watch_record_theater CHECK (theater_id IS NULL OR watch_type <=> 'THEATER');
