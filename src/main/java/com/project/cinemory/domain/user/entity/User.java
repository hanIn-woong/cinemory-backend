package com.project.cinemory.domain.user.entity;

import com.project.cinemory.domain.common.entity.BaseTimeEntity;
import com.project.cinemory.domain.movie.entity.Movie;
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

    @Column(name = "bio", length = 150)
    private String bio;

    // 커버는 영화 배경 또는 업로드 이미지 중 하나만 — DB CHECK를 만들 수 없어(MySQL이 SET NULL 컬럼의 CHECK를 거부, V38) 아래 메서드가 강제한다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cover_movie_id")
    private Movie coverMovie;

    @Column(name = "cover_image", length = 500)
    private String coverImage;

    @Enumerated(EnumType.STRING)
    @Column(name = "privacy_setting", nullable = false, length = 20)
    private PrivacySetting privacySetting;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private RoleType role;

    private User(String email, String passwordHash, String nickname, String profileImage) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.profileImage = profileImage;
        this.privacySetting = PrivacySetting.FRIENDS; // 기본값: DB 컬럼 default와 동일하게 명시 (V36 — social-feed-spec SF-12)
        this.role = RoleType.USER;                    // 팩토리는 권한을 받지 않는다 — 승격은 DB에서만
    }

    /** 로컬(이메일/비밀번호) 회원가입 */
    public static User createLocal(String email, String passwordHash, String nickname) {
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("로컬 회원가입은 비밀번호 해시가 필수입니다.");
        }
        return new User(email, passwordHash, nickname, null);
    }

    /**
     * OAuth(소셜) 회원가입 — 비밀번호 없는 사용자. 소셜 계정 자체는 {@code UserSocialAccount}로
     * 같은 트랜잭션에서 연결한다(V24). 이 사용자는 비밀번호를 얻을 수 없다(S-6).
     */
    public static User createOAuth(String email, String nickname, String profileImage) {
        return new User(email, null, nickname, profileImage);
    }

    /**
     * 비밀번호 해시 교체. 재설정(S-J)과 변경(Step5)이 함께 쓴다 —
     * 두 흐름의 차이는 "누가 자격을 증명했는가"뿐이고 갱신 자체는 같다.
     *
     * <p><b>비밀번호가 없던 계정(소셜 전용)은 거부한다</b> — "교체"만 허용하고 "추가"는 허용하지 않는다(S-6).
     * V24로 {@code chk_user_auth_method}가 사라져 DB는 더 이상 막지 않으므로 이 검사가 유일한 방어선이다.
     * 재설정 메일 자체를 소셜 전용 계정에 보내지 않으므로 정상 흐름으로는 도달하지 않는다.
     */
    public void changePassword(String passwordHash) {
        if (!hasPassword()) {
            throw new IllegalStateException("비밀번호가 없는 계정(소셜 전용)에는 비밀번호를 추가할 수 없습니다.");
        }
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("비밀번호 해시는 필수입니다.");
        }
        this.passwordHash = passwordHash;
    }

    public void changeNickname(String nickname) {
        this.nickname = nickname;
    }

    /** 프로필 사진 교체. {@code null}이면 기본 아바타로 되돌린다. */
    public void changeProfileImage(String profileImage) {
        this.profileImage = profileImage;
    }

    /**
     * 프로필 통합 저장(PUT /api/users/me/profile)의 필드 갱신. 닉네임은 호출부가 규칙을 통과시킨 값이고, 자기소개는 정규화된 값(없으면 null)이다.
     * 공개 범위는 {@link #changePrivacySetting}과 같은 필드를 같은 방식으로 바꾼다.
     */
    public void updateProfile(String nickname, String bio, PrivacySetting privacySetting) {
        changeNickname(nickname);
        this.bio = bio;
        changePrivacySetting(privacySetting);
    }

    /** 영화 배경으로 바꾼다 — 업로드 커버는 해제된다(둘 중 하나만). */
    public void changeCoverToMovie(Movie movie) {
        if (movie == null) {
            throw new IllegalArgumentException("커버 영화는 필수입니다.");
        }
        this.coverMovie = movie;
        this.coverImage = null;
    }

    /** 업로드 이미지로 바꾼다 — 영화 커버는 해제된다(둘 중 하나만). */
    public void changeCoverToImage(String coverImage) {
        if (coverImage == null || coverImage.isBlank()) {
            throw new IllegalArgumentException("커버 이미지 키는 필수입니다.");
        }
        this.coverImage = coverImage;
        this.coverMovie = null;
    }

    /** 커버를 없앤다(앱 기본 그라데이션). 둘 중 하나만 채워져 있으므로 어느 쪽이었든 이 호출 하나로 비워진다. */
    public void clearCover() {
        this.coverMovie = null;
        this.coverImage = null;
    }

    public boolean hasMovieCover() {
        return coverMovie != null;
    }

    public boolean hasImageCover() {
        return coverImage != null;
    }

    public void changePrivacySetting(PrivacySetting privacySetting) {
        this.privacySetting = privacySetting;
    }

    /**
     * 로컬 가입자인가. 인증 수단 = 비밀번호(로컬 가입자만) + 연결된 소셜들(S-6) — 로컬 가입자도 소셜을
     * 연결할 수 있어서, V24 이전의 {@code isOAuthUser()}("소셜 가입자인가")로는 더 이상 판정할 수 없다.
     */
    public boolean hasPassword() {
        return this.passwordHash != null;
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