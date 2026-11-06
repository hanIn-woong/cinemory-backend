-- 검색 정규화 키 (service-layer-spec 4-2-C). 설계 근거·롤백은 docs/schema/v49-delta.sql.
-- 확장: nullable 컬럼 추가 1문장. 옛 코드는 새 컬럼을 읽지도 쓰지도 않으므로 그대로 동작한다.
-- 채우기는 SQL이 아니라 앱 기동 후 관리자 일회성 작업(POST /api/admin/movies/search-key/rebuild) - 규칙을 자바 한 곳에 두기 위해서다.
-- 이 파일은 동결 - 절대 수정 금지.
ALTER TABLE movie ADD COLUMN search_key VARCHAR(600) NULL;
