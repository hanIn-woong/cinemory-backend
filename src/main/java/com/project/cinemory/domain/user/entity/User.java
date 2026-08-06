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

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private RoleType role;

    private User(String email, String passwordHash, String nickname, String profileImage,
                 String provider, String providerId) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.profileImage = profileImage;
        this.provider = provider;
        this.providerId = providerId;
        this.privacySetting = PrivacySetting.PRIVATE; // 기본값: DB 컬럼 default와 동일하게 명시
        this.role = RoleType.USER;                    // 팩토리는 권한을 받지 않는다 — 승격은 DB에서만
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

    /**
     * 비밀번호 해시 교체. 재설정(S-J)과 변경(Step5)이 함께 쓴다 —
     * 두 흐름의 차이는 "누가 자격을 증명했는가"뿐이고 갱신 자체는 같다.
     *
     * <p>OAuth 계정을 거부하는 이유는 {@code chk_user_auth_method}(로컬 XOR 소셜) 때문이다.
     * 여기를 통과시키면 DB 제약에 걸려 커밋 시점에 터지므로 원인에서 막는다.
     * 재설정 메일 자체를 소셜 계정에 보내지 않으므로 정상 흐름으로는 도달하지 않는다.
     */
    public void changePassword(String passwordHash) {
        if (isOAuthUser()) {
            throw new IllegalStateException("소셜 로그인 계정은 비밀번호를 가질 수 없습니다.");
        }
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("비밀번호 해시는 필수입니다.");
        }
        this.passwordHash = passwordHash;
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