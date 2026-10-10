package com.project.cinemory.domain.user.entity;

import com.project.cinemory.domain.auth.entity.OAuthProvider;
import com.project.cinemory.domain.common.entity.BaseCreatedAtEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자에게 연결된 소셜 계정 (account-integrity-spec S-2, V24). 한 사용자가 제공자마다 하나씩 가질 수 있다.
 *
 * <p>연결 자체가 사실의 전부라 수정 메서드가 없다 — 바꾸려면 해제 후 다시 연결한다.
 * {@code created_at}은 응답에서 "연결 시각"({@code linkedAt})으로 쓰인다.
 */
@Entity
@Table(name = "user_social_account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserSocialAccount extends BaseCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private OAuthProvider provider;

    @Column(name = "provider_id", nullable = false)
    private String providerId;

    private UserSocialAccount(User user, OAuthProvider provider, String providerId) {
        this.user = user;
        this.provider = provider;
        this.providerId = providerId;
    }

    public static UserSocialAccount link(User user, OAuthProvider provider, String providerId) {
        if (user == null || provider == null || providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("소셜 계정 연결은 user/provider/providerId가 필수입니다.");
        }
        return new UserSocialAccount(user, provider, providerId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserSocialAccount that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
