-- 인생 영화 최대 3 -> 10편 (profile-spec V45). 설계 근거는 docs/schema/v45-delta.sql.
-- 이 파일은 동결 — 절대 수정 금지.
ALTER TABLE user_favorite_movie
  DROP CHECK chk_user_favorite_position,
  ADD CONSTRAINT chk_user_favorite_position CHECK (position BETWEEN 1 AND 10);
