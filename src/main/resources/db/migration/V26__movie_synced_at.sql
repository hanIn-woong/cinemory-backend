-- 확장: nullable 컬럼 추가(옛 코드는 이 컬럼을 모른 채 그대로 동작). 관리자 화면의 "마지막 TMDB 동기화 시각" (admin-spec AD-10).
-- 이 파일은 동결 — 절대 수정 금지.
ALTER TABLE movie ADD COLUMN synced_at DATETIME(6) NULL;
