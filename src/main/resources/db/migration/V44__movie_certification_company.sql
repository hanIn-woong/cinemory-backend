-- 영화 정보 보강: 관람등급 + 제작사 (tmdb-sync-spec 6-12). 설계 근거·롤백은 docs/schema/v44-delta.sql.
-- 확장: nullable 컬럼 추가 + 새 테이블 2개. 옛 코드는 새 컬럼·테이블을 몰라도 그대로 동작한다.
-- 이 파일은 동결 - 절대 수정 금지.
ALTER TABLE movie ADD COLUMN kr_certification VARCHAR(20) NULL;

CREATE TABLE company (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tmdb_company_id BIGINT NOT NULL,
  name VARCHAR(255) NOT NULL,
  logo_path VARCHAR(255) NULL,
  origin_country CHAR(2) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_company_tmdb_id (tmdb_company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE movie_company (
  id BIGINT NOT NULL AUTO_INCREMENT,
  movie_id BIGINT NOT NULL,
  company_id BIGINT NOT NULL,
  display_order INT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_movie_company (movie_id, company_id),
  KEY fk_movie_company_company (company_id),
  CONSTRAINT fk_movie_company_movie FOREIGN KEY (movie_id) REFERENCES movie (id) ON DELETE CASCADE,
  CONSTRAINT fk_movie_company_company FOREIGN KEY (company_id) REFERENCES company (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
