-- 리뷰 본문 2,000 -> 10,000자 (service-layer-spec 4-4-A). 설계 근거·롤백은 docs/schema/v42-delta.sql.
-- 확장: VARCHAR 길이 늘리기. 2바이트 길이 접두어 범위 안(최대 40,000바이트)이라 MySQL 8이 in-place로 처리한다.
-- MODIFY COLUMN은 정의 전체를 다시 쓴다 - 현행 NOT NULL을 그대로 옮겼다.
-- 이 파일은 동결 - 절대 수정 금지.
ALTER TABLE review MODIFY COLUMN content VARCHAR(10000) NOT NULL;
