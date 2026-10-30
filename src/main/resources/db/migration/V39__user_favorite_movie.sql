-- 인생 영화: 사용자당 최대 3편, 순서 있음 (profile-spec PR-3, P-2). 설계 근거는 docs/schema/v39-delta.sql.
-- 이 파일은 동결 — 절대 수정 금지.
CREATE TABLE user_favorite_movie (
  id         BIGINT   NOT NULL AUTO_INCREMENT,
  user_id    BIGINT   NOT NULL,
  movie_id   BIGINT   NOT NULL,
  position   TINYINT  NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_favorite_movie (user_id, movie_id),
  UNIQUE KEY uk_user_favorite_position (user_id, position),
  KEY fk_user_favorite_movie_movie (movie_id),
  CONSTRAINT fk_user_favorite_movie_user  FOREIGN KEY (user_id)  REFERENCES `user` (id) ON DELETE CASCADE,
  CONSTRAINT fk_user_favorite_movie_movie FOREIGN KEY (movie_id) REFERENCES movie (id) ON DELETE RESTRICT,
  CONSTRAINT chk_user_favorite_position CHECK (position BETWEEN 1 AND 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
