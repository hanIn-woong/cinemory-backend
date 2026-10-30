-- 기존 소셜 제공자(카카오·구글) 프로필 사진 URL 일괄 제거 (profile-spec PR-11, P-1). 설계 근거·사전 점검·롤백 없음은 docs/schema/v37-delta.sql.
-- 우리 S3 키(profile/…)는 'http%'에 걸리지 않는다. 이 파일은 동결 — 절대 수정 금지.
UPDATE `user` SET profile_image = NULL WHERE profile_image LIKE 'http%';
