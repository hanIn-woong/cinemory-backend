-- 프로필 필드: 자기소개 · 영화 커버 · 업로드 커버 (profile-spec PR-2 · PR-4, P-2). 설계 근거·확인 결과는 docs/schema/v38-delta.sql.
-- chk_user_cover_single(커버는 둘 중 하나만)은 만들지 않는다 — MySQL이 ON DELETE SET NULL 컬럼의 CHECK를 거부한다(ERROR 3823). User 엔티티 메서드가 강제한다.
-- 이 파일은 동결 — 절대 수정 금지.
ALTER TABLE `user`
  ADD COLUMN bio VARCHAR(150) NULL,
  ADD COLUMN cover_movie_id BIGINT NULL,
  ADD COLUMN cover_image VARCHAR(500) NULL,
  ADD CONSTRAINT fk_user_cover_movie FOREIGN KEY (cover_movie_id) REFERENCES movie (id) ON DELETE SET NULL;
