-- 소셜 계정 연결 구조 — 확장(expand)만: user_social_account 생성 + 기존 연결 복사 + chk_user_auth_method 완화 (account-integrity-spec S-2·D-4).
-- user.provider/provider_id/uk_user_provider 삭제는 보류된 축소 — 다음 릴리스(deploy-spec D-3 조건 6). 근거·롤백은 docs/schema/v24-delta.sql.
-- 이 파일은 동결 — 절대 수정 금지.

CREATE TABLE `user_social_account` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `provider` varchar(20) NOT NULL,
  `provider_id` varchar(255) NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_social_account_provider` (`provider`, `provider_id`),
  UNIQUE KEY `uk_user_social_account_user_provider` (`user_id`, `provider`),
  CONSTRAINT `fk_user_social_account_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO `user_social_account` (`user_id`, `provider`, `provider_id`, `created_at`)
SELECT `id`, `provider`, `provider_id`, `created_at` FROM `user` WHERE `provider` IS NOT NULL;

ALTER TABLE `user` DROP CHECK `chk_user_auth_method`;
