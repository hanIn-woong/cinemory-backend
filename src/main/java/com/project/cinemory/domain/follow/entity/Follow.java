package com.project.cinemory.domain.follow.entity;

import com.project.cinemory.domain.common.entity.BaseCreatedAtEntity;
import com.project.cinemory.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.*;

@Entity
@Table(
        name = "follow",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_follow", columnNames = {"follower_id", "following_id"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Follow extends BaseCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "follower_id", nullable = false)
    private User follower;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "following_id", nullable = false)
    private User following;

    private Follow(User follower, User following) {
        this.follower = follower;
        this.following = following;
    }

    /** DB의 chk_follow_not_self 제약을 애플리케이션 레벨에서도 선반영 */
    public static Follow of(User follower, User following) {
        if (follower.equals(following)) {
            throw new IllegalArgumentException("자기 자신을 팔로우할 수 없습니다.");
        }
        return new Follow(follower, following);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Follow that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
