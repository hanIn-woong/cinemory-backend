-- 확장: nullable 컬럼 2개 + 신규 테이블 2개. 옛 코드는 모른 채 그대로 동작한다.
-- M3-b 임베딩 입력용 데이터 보강 (tmdb-sync-spec 6-11). 채우는 수단은 POST /api/admin/movies/resync 전량 실행이다.
-- 이 파일은 동결 — 절대 수정 금지.
ALTER TABLE movie
  ADD COLUMN tagline     VARCHAR(255)  NULL,
  ADD COLUMN overview_en VARCHAR(1000) NULL;

CREATE TABLE keyword (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  tmdb_keyword_id BIGINT       NOT NULL,
  name            VARCHAR(255) NOT NULL,
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_keyword_tmdb_id (tmdb_keyword_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE movie_keyword (
  id         BIGINT   NOT NULL AUTO_INCREMENT,
  movie_id   BIGINT   NOT NULL,
  keyword_id BIGINT   NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_movie_keyword (movie_id, keyword_id),
  KEY fk_movie_keyword_keyword (keyword_id),
  CONSTRAINT fk_movie_keyword_movie   FOREIGN KEY (movie_id)   REFERENCES movie (id)   ON DELETE CASCADE,
  CONSTRAINT fk_movie_keyword_keyword FOREIGN KEY (keyword_id) REFERENCES keyword (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
