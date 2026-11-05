-- 포스터 고정 (admin-spec 18, DD-2). 설계 근거·롤백은 docs/schema/v47-delta.sql.
-- 확장: nullable 컬럼 추가 1문장. 옛 코드는 새 컬럼을 읽지도 쓰지도 않으므로 그대로 동작한다.
-- 이 파일은 동결 - 절대 수정 금지.
ALTER TABLE movie ADD COLUMN poster_path_pinned VARCHAR(255) NULL;
