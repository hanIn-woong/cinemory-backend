-- ott_platform 표시 순서. 기본 0, '기타'만 99로 두어 항상 맨 뒤에 온다. 데이터 INSERT는 하지 않는다(덤프 이관 충돌 방지).
-- 이 파일은 동결 — 절대 수정 금지.
ALTER TABLE ott_platform
  ADD COLUMN sort_order INT NOT NULL DEFAULT 0 AFTER name;
