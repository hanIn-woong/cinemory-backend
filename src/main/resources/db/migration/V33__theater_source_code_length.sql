-- 확장: 컬럼 길이 확대 1문장 — 옛 코드(길이 30 가정)는 짧은 값만 다루므로 그대로 동작한다. 극장 묶음 키 수용 (cinemap-spec C-4-2).
-- 묶음 키 = 자치단체코드|주소 기준부 라 30자를 넘는다. uk_theater_source_code는 유지(255자 × 4바이트 = 1020 < 3072).
-- 이 파일은 동결 — 절대 수정 금지.
ALTER TABLE theater MODIFY COLUMN source_code VARCHAR(255) NOT NULL;
