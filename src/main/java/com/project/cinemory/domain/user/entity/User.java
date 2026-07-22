package com.project.cinemory.domain.user.entity;

import com.project.cinemory.domain.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "nickname", nullable = false, length = 50)
    private String nickname;

    @Column(name = "profile_image", length = 500)
    private String profileImage;

    @Column(name = "provider", length = 20)
    private String provider;

    @Column(name = "provider_id")
    private String providerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "privacy_setting", nullable = false, length = 20)
    private PrivacySetting privacySetting;

    private User(String email, String passwordHash, String nickname, String profileImage,
                 String provider, String providerId) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.profileImage = profileImage;
        this.provider = provider;
        this.providerId = providerId;
        this.privacySetting = PrivacySetting.PRIVATE; // 기본값: DB 컬럼 default와 동일하게 명시
    }

    /** 로컬(이메일/비밀번호) 회원가입 */
    public static User createLocal(String email, String passwordHash, String nickname) {
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("로컬 회원가입은 비밀번호 해시가 필수입니다.");
        }
        return new User(email, passwordHash, nickname, null, null, null);
    }

    /** OAuth(소셜) 회원가입 */
    public static User createOAuth(String email, String nickname, String profileImage,
                                   String provider, String providerId) {
        if (provider == null || providerId == null) {
            throw new IllegalArgumentException("OAuth 회원가입은 provider/providerId가 필수입니다.");
        }
        return new User(email, null, nickname, profileImage, provider, providerId);
    }

    public void changeNickname(String nickname) {
        this.nickname = nickname;
    }

    public void changePrivacySetting(PrivacySetting privacySetting) {
        this.privacySetting = privacySetting;
    }

    public boolean isOAuthUser() {
        return this.provider != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User user)) return false;
        return id != null && id.equals(user.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}