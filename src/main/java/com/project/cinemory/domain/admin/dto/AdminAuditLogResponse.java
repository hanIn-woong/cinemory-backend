package com.project.cinemory.domain.admin.dto;

import com.project.cinemory.domain.admin.entity.AdminAction;
import com.project.cinemory.domain.admin.entity.AdminActionLog;
import com.project.cinemory.domain.admin.entity.AdminTargetType;

import java.time.LocalDateTime;
import java.util.Map;

/** 감사 로그 한 행(admin-spec 11-4). {@code adminEmail}은 기록 시점의 스냅샷이다. */
public record AdminAuditLogResponse(
        Long id,
        Long adminUserId,
        String adminEmail,
        AdminAction action,
        AdminTargetType targetType,
        Long targetId,
        Map<String, Object> detail,
        String ip,
        LocalDateTime createdAt
) {

    public static AdminAuditLogResponse from(AdminActionLog log) {
        return new AdminAuditLogResponse(log.getId(), log.getAdminUserId(), log.getAdminEmailSnapshot(), log.getAction(),
                log.getTargetType(), log.getTargetId(), log.getDetail(), log.getIp(), log.getCreatedAt());
    }
}
