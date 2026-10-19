-- 확장: nullable 컬럼 추가 1문장 — 옛 코드는 이 컬럼을 모른 채 그대로 동작한다. 인물 한글명 덮어쓰기 (admin-spec 12절, BD-7).
-- TMDB 재동기화는 name만 덮어쓰고 name_ko는 건드리지 않는다. 표시는 name_ko가 있으면 그것, 없으면 name.
-- 이 파일은 동결 — 절대 수정 금지.
ALTER TABLE person ADD COLUMN name_ko VARCHAR(100) NULL;
