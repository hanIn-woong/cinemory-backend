-- 극장명 정리 (admin-spec 19, DD-7·DD-8). 설계 근거·롤백은 docs/schema/v48-delta.sql.
-- 확장: nullable 컬럼 추가 2문장 + 백필 1문장. 옛 코드는 새 컬럼을 읽지도 쓰지도 않으므로 그대로 동작한다.
-- 이 파일은 동결 - 절대 수정 금지.
ALTER TABLE theater
  ADD COLUMN source_name   VARCHAR(255) NULL,
  ADD COLUMN name_override VARCHAR(100) NULL;
UPDATE theater SET source_name = name WHERE source_name IS NULL;
