package com.project.cinemory.domain.auth.entity;

import com.project.cinemory.domain.common.entity.BaseCreatedAtEntity;
import com.project.cinemory.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 비로그인 비밀번호 재설정 토큰 (v10 신규). 원문은 메일로만 나가고 DB에는 SHA-256 해시만 남는다.
 *
 * <p><b>{@link RefreshToken}과 같은 골격을 의도적으로 유지한다</b> — 해시만 저장하고,
 * 상태를 boolean이 아니라 시각({@code usedAt})으로 남기며, 현재 시각은 인자로 주입받는다.
 * 시각으로 남기면 "언제 재설정됐나"가 감사 정보로 함께 보존된다.
 *
 * <p>폐기 사유 컬럼을 두지 않는 이유는 재설정 토큰의 소멸 경로가 <b>"사용됨" 하나뿐</b>이라
 * 구분할 것이 없기 때문이다. TTL(기본 30분)도 스키마가 아니라 설정값이며,
 * {@code expiresAt}을 계산해 넘기는 것은 서비스 책임이다.
 */
@Entity
@Table(name = "password_reset_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PasswordResetToken extends BaseCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** SHA-256 hex 64자 */
    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** NULL이면 미사용. */
    @Column(name = "used_at")
    private LocalDateTime usedAt;

    private PasswordResetToken(User user, String tokenHash, LocalDateTime expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public static PasswordResetToken issue(User user, String tokenHash, LocalDateTime expiresAt) {
        return new PasswordResetToken(user, tokenHash, expiresAt);
    }

    /**
     * 사용 처리. 이미 사용된 토큰의 {@code usedAt}은 덮어쓰지 않는다 —
     * {@link RefreshToken#revoke}와 같은 멱등 규칙이며, 최초 사용 시각이 감사 기록이다.
     */
    public void markAsUsed(LocalDateTime now) {
        if (this.usedAt == null) {
            this.usedAt = now;
        }
    }

    public boolean isUsed() {
        return this.usedAt != null;
    }

    public boolean isExpired(LocalDateTime now) {
        return this.expiresAt.isBefore(now);
    }

    public boolean isUsable(LocalDateTime now) {
        return !isUsed() && !isExpired(now);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PasswordResetToken that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
