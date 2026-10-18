-- "내 영화" 최근 관람일순(RecordSort.WATCH_DATE_DESC)은 영화별 MAX(watch_date)로 정렬한다. 대표 행마다 같은 (user, movie)의
-- 기록을 훑는 상관 서브쿼리라, watch_date까지 담은 인덱스로 테이블 행을 읽지 않고 인덱스만으로 계산한다.
-- 기존 idx_watch_record_user_id_movie_id_id (user_id, movie_id, id DESC)는 watch_date가 없어 행 조회가 따라붙는다.
-- 이 파일은 동결 — 절대 수정 금지.
CREATE INDEX idx_watch_record_user_movie_watch_date ON watch_record (user_id, movie_id, watch_date);
