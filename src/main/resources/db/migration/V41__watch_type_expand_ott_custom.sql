-- 관람 방식 확장(TV·VOD·DISC) + OTT 직접 입력 플래그 (service-layer-spec 4-3-B). 설계 근거·롤백은 docs/schema/v41-delta.sql.
-- ① 새 값은 ENUM의 끝에 붙인다 — MySQL 8은 끝에 추가하는 변경을 테이블 재작성 없이 처리한다(중간 삽입은 재작성).
--    MODIFY COLUMN은 컬럼 정의 전체를 다시 쓴다 — 현행 정의(NULL DEFAULT NULL)를 그대로 옮겼다.
-- ③ is_custom: '기타' 행을 이름이 아니라 플래그로 식별한다(표기가 바뀌어도 조용히 깨지지 않게).
-- 이 파일은 동결 — 절대 수정 금지.
ALTER TABLE watch_record
  MODIFY COLUMN watch_type ENUM('THEATER','OTT','ETC','TV','VOD','DISC') NULL DEFAULT NULL;

ALTER TABLE ott_platform ADD COLUMN is_custom TINYINT(1) NOT NULL DEFAULT 0;
UPDATE ott_platform SET is_custom = 1 WHERE name = '기타';
