-- 새 가입자의 공개 범위 기본값을 FRIENDS로 (social-feed-spec F-4 SF-12). 설계 근거·롤백은 docs/schema/v36-delta.sql.
-- 컬럼 DEFAULT만 바꾼다 — 기존 행은 건드리지 않는다. 이 파일은 동결 — 절대 수정 금지.
ALTER TABLE `user` ALTER COLUMN `privacy_setting` SET DEFAULT 'FRIENDS';
