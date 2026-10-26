-- 확장: 신규 테이블 1개. 옛 코드는 이 테이블을 모른 채 그대로 동작한다.
-- M3-b 영화 임베딩 저장 (M3b-recommendation-spec E-4-1). 채우는 수단은 매일 06:00 배치와 POST /api/admin/embeddings/sync 이다.
-- 이 파일은 동결 — 절대 수정 금지.
CREATE TABLE movie_embedding (
  id              BIGINT          NOT NULL AUTO_INCREMENT,
  movie_id        BIGINT          NOT NULL,
  model_name      VARCHAR(50)     NOT NULL,
  dimensions      SMALLINT        NOT NULL,
  embedding       VARBINARY(3072) NOT NULL,
  source_text     VARCHAR(4000)   NOT NULL,
  source_hash     CHAR(64)        NOT NULL,
  overview_source VARCHAR(10)     NOT NULL,
  created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_movie_embedding_movie (movie_id),
  CONSTRAINT fk_movie_embedding_movie FOREIGN KEY (movie_id) REFERENCES movie (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
