-- 확장: 테이블 추가 1문장 — 옛 코드는 이 테이블을 모른 채 그대로 동작한다. 관리자 감사 로그 (admin-spec 11절, BD-2~BD-6).
-- FK 없음 — 로그는 관리자 계정·대상이 지워져도 남아야 한다(BD-4·5).
-- 이 파일은 동결 — 절대 수정 금지.
CREATE TABLE admin_action_log (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    admin_user_id        BIGINT       NOT NULL,
    admin_email_snapshot VARCHAR(255) NOT NULL,
    action               VARCHAR(40)  NOT NULL,
    target_type          VARCHAR(20)  NULL,
    target_id            BIGINT       NULL,
    detail               JSON         NULL,
    ip                   VARCHAR(45)  NULL,
    created_at           DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_admin_action_log_created_at (created_at),
    KEY idx_admin_action_log_target (target_type, target_id, created_at),
    KEY idx_admin_action_log_admin (admin_user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
