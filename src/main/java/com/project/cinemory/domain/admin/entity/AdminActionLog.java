package com.project.cinemory.domain.admin.entity;

import com.project.cinemory.domain.common.entity.BaseCreatedAtEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

/**
 * 관리자 감사 로그(admin-spec 11-1). <b>불변</b>이다 — 수정 메서드도 삭제 API도 없다.
 *
 * <p>FK가 없다(BD-4·5): 관리자 계정이 탈퇴하거나 대상이 지워져도 로그는 남아야 한다. 관리자는
 * {@code adminUserId} + 그 시점의 이메일 스냅샷, 대상은 {@code targetType} + {@code targetId}(다형 참조)로 둔다.
 */
@Entity
@Table(name = "admin_action_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminActionLog extends BaseCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "admin_user_id", nullable = false)
    private Long adminUserId;

    @Column(name = "admin_email_snapshot", nullable = false)
    private String adminEmailSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 40)
    private AdminAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", length = 20)
    private AdminTargetType targetType;

    @Column(name = "target_id")
    private Long targetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail")
    private Map<String, Object> detail;

    @Column(name = "ip", length = 45)
    private String ip;

    @Builder
    private AdminActionLog(Long adminUserId, String adminEmailSnapshot, AdminAction action,
                           AdminTargetType targetType, Long targetId, Map<String, Object> detail, String ip) {
        this.adminUserId = adminUserId;
        this.adminEmailSnapshot = adminEmailSnapshot;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.detail = detail;
        this.ip = ip;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AdminActionLog that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
