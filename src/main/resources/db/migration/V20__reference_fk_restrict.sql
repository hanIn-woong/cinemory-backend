-- movie_genre → genre, movie_actor/movie_director → person : CASCADE → RESTRICT. 근거·롤백은 docs/schema/v20-delta.sql.
-- 이 파일은 동결 — 절대 수정 금지.

ALTER TABLE movie_genre    DROP FOREIGN KEY fk_movie_genre_genre;
ALTER TABLE movie_genre    ADD CONSTRAINT fk_movie_genre_genre
  FOREIGN KEY (genre_id)  REFERENCES genre (id)  ON DELETE RESTRICT;

ALTER TABLE movie_actor    DROP FOREIGN KEY fk_movie_actor_person;
ALTER TABLE movie_actor    ADD CONSTRAINT fk_movie_actor_person
  FOREIGN KEY (person_id) REFERENCES person (id) ON DELETE RESTRICT;

ALTER TABLE movie_director DROP FOREIGN KEY fk_movie_director_person;
ALTER TABLE movie_director ADD CONSTRAINT fk_movie_director_person
  FOREIGN KEY (person_id) REFERENCES person (id) ON DELETE RESTRICT;
