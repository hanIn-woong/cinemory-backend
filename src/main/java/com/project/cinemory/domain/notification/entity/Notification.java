package com.project.cinemory.domain.notification.entity;

import com.project.cinemory.domain.common.entity.BaseCreatedAtEntity;
import com.project.cinemory.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 알림. <b>Step S 범위에서는 엔티티만 만든다</b> — Repository/Service는 아직 없다.
 * 스키마 v9를 여는 김에 테이블을 함께 반영했고, 도메인 설계는 Step S 구현 이후 별도 절로 진행한다.
 * 생성 지점이 {@code FollowService.follow()} / {@code CommentService.createComment()} 안이라
 * 기존 도메인 서비스에 손이 닿기 때문에 Security 구현과 섞지 않는다.
 *
 * <p><b>⚠️ 고아 알림</b> — {@code comment}와 동일한 다형 참조({@code target_type}/{@code target_id},
 * FK 없음) 구조라 고아 댓글과 같은 문제가 그대로 재현된다. Collection/Review 삭제 경로에서
 * 댓글 정리와 같은 자리에 알림 정리도 호출해야 하며, 이는 DDL로 막을 수 없다.
 */
@Entity
@Table(name = "notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 수신자. 탈퇴 시 알림도 함께 삭제된다 (FK CASCADE) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * 행위자. 탈퇴 시 NULL로 남는다 (FK SET NULL).
     * 행위자가 사라졌다고 수신자의 알림 목록이 통째로 없어지면 안 되기 때문이다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private User actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type")
    private NotificationTargetType targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Column(name = "is_read", nullable = false)
    private boolean isRead;

    @Builder
    private Notification(User user, User actor, NotificationType type,
                         NotificationTargetType targetType, Long targetId) {
        this.user = user;
        this.actor = actor;
        this.type = type;
        this.targetType = targetType;
        this.targetId = targetId;
        this.isRead = false;
    }

    public void markAsRead() {
        this.isRead = true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Notification that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
