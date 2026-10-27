-- 확장: nullable 컬럼 추가 1문장(두 컬럼) — 옛 코드는 이 컬럼을 모른 채 그대로 동작한다. 극장 예매 링크(관리자 입력)와 폐업 숨김 시각 (cinemap-spec C-8-2, C-5-1).
-- closed_at이 NULL인 행만 주변 극장 조회에 나온다. 옛 코드는 이 컬럼을 보지 않으므로 롤백해도 숨긴 극장이 다시 보일 뿐 깨지지 않는다.
-- 이 파일은 동결 — 절대 수정 금지.
ALTER TABLE theater
    ADD COLUMN booking_url VARCHAR(500) NULL,
    ADD COLUMN closed_at   DATETIME(6)  NULL;
