-- 확장: nullable 컬럼 추가 1문장 — 옛 코드는 이 컬럼을 모른 채 그대로 동작한다. 영화 한글 제목 덮어쓰기 (admin-spec 12-B절, BD-10).
-- TMDB 재동기화는 title만 덮어쓰고 title_ko는 건드리지 않는다. 표시는 title_ko가 있으면 그것, 없으면 title.
-- 이 파일은 동결 — 절대 수정 금지.
ALTER TABLE movie ADD COLUMN title_ko VARCHAR(255) NULL;
