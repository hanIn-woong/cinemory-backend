-- 가입 동의 기록 (settings-spec ST-7, S-1). 설계 근거·롤백은 docs/schema/v40-delta.sql.
-- 기존 사용자는 NULL로 둔다 — 동의 절차가 없던 가입을 소급해 채우지 않는다.
-- 이 파일은 동결 — 절대 수정 금지.
ALTER TABLE `user`
  ADD COLUMN terms_agreed_at DATETIME NULL;
