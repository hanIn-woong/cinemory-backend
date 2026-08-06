-- =============================================================================
-- CineMory 스키마 델타 : v9 -> v10
-- =============================================================================
-- 대상 DB : cinemory (MySQL 8.0)
-- 작성일  : 2026-07-30
--
-- 변경 요약 (21 -> 22 테이블)
--   (1) refresh_token.revoked_reason 추가 + CHECK 제약
--       - 폐기 사유를 구분하지 못해 "로그아웃 후 30초 안에 같은 토큰으로 재발급하면
--         세션이 되살아나는" 문제를 닫는다. 유예 창(A-4)은 ROTATED 에만 적용해야 한다.
--       - PASSWORD_CHANGED 는 S-H(로그인 상태 변경)와 S-J(재설정) 양쪽에서 쓴다.
--   (2) password_reset_token 신설
--       - 비로그인 비밀번호 재설정. SMTP 도입을 확정해 v10 에 포함한다.
--       - refresh_token 과 같은 골격(해시 저장 / 시각형 상태 컬럼)을 의도적으로 유지.
--   (3) box_office_record.open_date 추가
--       - 4-7 재매칭 2순위 전략("한글 제목 + 개봉연도") 복원용. KOFIC openDt 를 담는다.
--
-- 넣지 않은 것과 이유
--   - refresh_token.replaced_by_id : 유예 창을 "직전 발급분 반환"으로 바꾸려면 토큰
--     원문이 필요한데 우리는 해시만 저장한다. 목적을 달성하지 못하므로 제외.
--   - refresh_token.family_id : 재사용 감지 시 "전체 세션 폐기"는 S-3 에서 의도적으로
--     내린 결정이다. 기기별 범위 축소는 그 결정을 되돌리는 일이라 범위 밖.
--   - theater POINT + SPATIAL : 국내 극장 수백 개 규모라 Bounding Box + Haversine 으로
--     충분하다. 발동 조건("데이터 증가 시")이 아직 아니다.
--
-- !! 실행 전 확인 !!
--   - v9 가 적용된 상태에서 실행한다. 아래 (0) 사전 점검을 먼저 돌릴 것.
--   - 경고 "1681 Integer display width is deprecated" 는 정상이다. MySQL 에서 BOOLEAN 은
--     TINYINT(1) 의 동의어라 boolean 컬럼이 있는 한 피할 수 없다.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- (0) 사전 점검 — 기대값과 다르면 중단할 것
-- -----------------------------------------------------------------------------
-- 테이블 수: 21 이어야 한다
SELECT COUNT(*) AS table_count
FROM information_schema.tables
WHERE table_schema = 'cinemory' AND table_type = 'BASE TABLE';

-- 이미 적용됐는지 확인: 아래 두 쿼리 모두 0 이어야 한다
SELECT COUNT(*) AS already_has_revoked_reason
FROM information_schema.columns
WHERE table_schema = 'cinemory' AND table_name = 'refresh_token' AND column_name = 'revoked_reason';

SELECT COUNT(*) AS already_has_password_reset_token
FROM information_schema.tables
WHERE table_schema = 'cinemory' AND table_name = 'password_reset_token';


-- -----------------------------------------------------------------------------
-- (1) refresh_token.revoked_reason
-- -----------------------------------------------------------------------------
-- 순서에 주의한다. 컬럼 추가 -> 기존 행 백필 -> CHECK 추가 순서여야 한다.
-- CHECK 를 먼저 걸면 revoked_at 이 채워진 기존 행(테스트 중 폐기된 토큰)이 제약을
-- 위반해 ALTER 자체가 실패한다.

ALTER TABLE `refresh_token`
  ADD COLUMN `revoked_reason` enum('ROTATED','LOGOUT','REUSE_DETECTED','PASSWORD_CHANGED')
    DEFAULT NULL AFTER `revoked_at`;

-- 기존에 폐기된 행의 사유는 소급해서 알 수 없다. 회전으로 간주한다.
-- (revoked_at 이 이미 오래 지났으므로 유예 창 판정에는 영향이 없다)
--
-- SQL_SAFE_UPDATES 를 잠깐 끄는 이유:
--   MySQL Workbench 는 기본적으로 safe update mode 로 접속한다. 이 모드는 WHERE 에
--   키 컬럼이 없거나 LIMIT 이 없는 UPDATE/DELETE 를 Error 1175 로 거부한다.
--   아래 WHERE 는 비인덱스 컬럼 두 개만 쓰므로 여기에 걸린다.
--   Preferences 를 건드리는 대신 이 구문 범위에서만 세션 변수를 껐다 복원한다.
--   (mysql CLI 로 실행하면 원래 OFF 라 아무 영향이 없다)
SET @old_safe_updates = @@SQL_SAFE_UPDATES;
SET SQL_SAFE_UPDATES = 0;

UPDATE `refresh_token`
SET `revoked_reason` = 'ROTATED'
WHERE `revoked_at` IS NOT NULL AND `revoked_reason` IS NULL;

SET SQL_SAFE_UPDATES = @old_safe_updates;

-- revoked_at 과 revoked_reason 은 항상 함께 채워진다.
-- user 의 chk_user_auth_method 와 같은 방식으로 불변식을 스키마에 고정한다.
ALTER TABLE `refresh_token`
  ADD CONSTRAINT `chk_refresh_token_revocation` CHECK (
    (`revoked_at` IS NULL AND `revoked_reason` IS NULL)
    OR (`revoked_at` IS NOT NULL AND `revoked_reason` IS NOT NULL));


-- -----------------------------------------------------------------------------
-- (2) password_reset_token
-- -----------------------------------------------------------------------------
-- 설계 메모
--   - token_hash : SHA-256 hex 64자. 원문은 저장하지 않고 메일로만 나간다.
--     BCrypt 를 쓰지 않는 이유는 refresh_token 과 동일하다 — salt 가 붙으면
--     해시로 인덱스 조회를 할 수 없다.
--   - used_at   : boolean 이 아니라 시각. NULL = 미사용.
--     refresh_token.revoked_at 과 같은 형태이고 "언제 재설정됐나"가 감사 정보로 남는다.
--   - 폐기 사유 컬럼은 두지 않는다. 재설정 토큰은 소멸 경로가 "사용됨" 하나뿐이다.
--   - TTL(기본 30분)은 스키마가 아니라 application.yml 에서 관리한다.

CREATE TABLE `password_reset_token` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `token_hash` varchar(64) NOT NULL,
  `expires_at` datetime NOT NULL,
  `used_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_password_reset_token_hash` (`token_hash`),
  KEY `idx_password_reset_token_user_id` (`user_id`),
  KEY `idx_password_reset_token_expires_at` (`expires_at`),
  CONSTRAINT `fk_password_reset_token_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- -----------------------------------------------------------------------------
-- (3) box_office_record.open_date
-- -----------------------------------------------------------------------------
-- 기존 행은 NULL 로 남는다. 옛 데이터는 개봉연도 축소를 쓸 수 없고
-- 앞으로 수집되는 분부터 2순위 매칭 정확도가 올라간다.

ALTER TABLE `box_office_record`
  ADD COLUMN `open_date` date DEFAULT NULL AFTER `movie_title_snapshot`;


-- =============================================================================
-- 적용 후 체크리스트
-- =============================================================================
-- ddl-auto=validate 는 엔티티 -> 스키마 단방향으로만 검증하며
-- UNIQUE / FK / 인덱스 / CHECK 는 검증하지 않는다. 아래를 직접 확인할 것.

-- [1] 테이블 수: 22
SELECT COUNT(*) AS table_count
FROM information_schema.tables
WHERE table_schema = 'cinemory' AND table_type = 'BASE TABLE';

-- [2] 신규 컬럼 3건이 모두 보여야 한다
SELECT table_name, column_name, column_type, is_nullable
FROM information_schema.columns
WHERE table_schema = 'cinemory'
  AND ((table_name = 'refresh_token'     AND column_name = 'revoked_reason')
    OR (table_name = 'box_office_record' AND column_name = 'open_date')
    OR (table_name = 'password_reset_token'))
ORDER BY table_name, ordinal_position;

-- [3] CHECK 제약: chk_refresh_token_revocation
SELECT cc.constraint_name, cc.check_clause
FROM information_schema.check_constraints cc
JOIN information_schema.table_constraints tc
  ON tc.constraint_schema = cc.constraint_schema
 AND tc.constraint_name   = cc.constraint_name
WHERE tc.table_schema = 'cinemory' AND tc.table_name = 'refresh_token';

-- [4] password_reset_token 의 UNIQUE / 인덱스 4건
SELECT index_name, non_unique, seq_in_index, column_name
FROM information_schema.statistics
WHERE table_schema = 'cinemory' AND table_name = 'password_reset_token'
ORDER BY index_name, seq_in_index;

-- [5] password_reset_token 의 FK 삭제 정책: CASCADE
SELECT rc.constraint_name, rc.delete_rule, kcu.referenced_table_name
FROM information_schema.referential_constraints rc
JOIN information_schema.key_column_usage kcu
  ON kcu.constraint_schema = rc.constraint_schema
 AND kcu.constraint_name   = rc.constraint_name
WHERE rc.constraint_schema = 'cinemory' AND rc.table_name = 'password_reset_token';

-- [6] CHECK 제약이 실제로 동작하는지 (아래는 반드시 실패해야 정상이다)
--     확인 후 주석을 다시 씌울 것.
-- INSERT INTO `refresh_token` (`user_id`,`token_hash`,`expires_at`,`revoked_at`)
-- VALUES (1, REPEAT('0',64), NOW(), NOW());

-- [7] 애플리케이션 기동 확인 (엔티티 반영 후)
--     ./gradlew bootRun  -> validate 통과


-- =============================================================================
-- 롤백 스크립트
-- =============================================================================
-- 적용 역순으로 되돌린다. 엔티티를 이미 v10 기준으로 고쳤다면
-- 롤백 후에는 애플리케이션이 validate 에서 기동 실패한다 — 코드도 함께 되돌릴 것.
--
-- ALTER TABLE `box_office_record` DROP COLUMN `open_date`;
--
-- DROP TABLE IF EXISTS `password_reset_token`;
--
-- ALTER TABLE `refresh_token` DROP CHECK `chk_refresh_token_revocation`;
-- ALTER TABLE `refresh_token` DROP COLUMN `revoked_reason`;


-- =============================================================================
-- 재덤프 (진실의 원천 갱신)
-- =============================================================================
-- v9 덤프와 동일한 옵션으로 뽑아야 diff 가 의미를 갖는다 (--no-data, 단일 DB 지정).
--
--   mysqldump -u root -p --no-data cinemory --result-file=docs/schema/cinemory_backup_v10.sql
--
-- !! > 리다이렉션을 쓰지 말 것 !!
--   (a) Windows 리다이렉션이 개행을 \n -> \r\n 으로 바꿔 덤프를 오염시킨다.
--   (b) PowerShell 5.1 의 > 는 기본 인코딩이 UTF-16LE 라 파일이 통째로 깨진다.
--   반드시 --result-file 을 쓴다.
--
-- 덤프 후
--   - .gitignore 의 docs/schema/cinemory_backup_*.sql 패턴이 v10 도 잡는지 확인
--     git check-ignore -v docs/schema/cinemory_backup_v10.sql
--   - 이 델타 파일은 리뷰 대상이므로 계속 추적되어야 한다
--     git check-ignore -v docs/schema/v10-delta.sql   (아무것도 출력되지 않아야 정상)
-- =============================================================================
