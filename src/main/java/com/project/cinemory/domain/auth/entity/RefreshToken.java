package com.project.cinemory.domain.auth.entity;

import com.project.cinemory.domain.common.entity.BaseCreatedAtEntity;
import com.project.cinemory.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 리프레시 토큰. <b>토큰 원문이 아니라 SHA-256 해시를 저장한다.</b>
 * DB가 유출돼도 그대로 재사용 가능한 값이 남지 않게 하기 위함이며,
 * BCrypt를 쓰지 않는 이유는 salt가 붙으면 해시로 인덱스 조회를 할 수 없기 때문이다.
 *
 * <p>UNIQUE는 {@code token_hash}에만 걸려 있어 <b>다중 기기 동시 로그인이 허용</b>된다.
 * 폐기는 {@code revoked_at}을 채우는 soft revoke이며, 하드 삭제하면
 * "이미 폐기된 토큰이 다시 왔다"는 탈취 정황을 감지할 수 없다.
 */
@Entity
@Table(name = "refresh_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken extends BaseCreatedAtEntity {

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

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    private RefreshToken(User user, String tokenHash, LocalDateTime expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public static RefreshToken issue(User user, String tokenHash, LocalDateTime expiresAt) {
        return new RefreshToken(user, tokenHash, expiresAt);
    }

    /**
     * 폐기. 이미 폐기된 토큰의 {@code revokedAt}은 덮어쓰지 않는다 —
     * 최초 폐기 시각이 유예 판정({@link #isWithinReuseGrace})의 기준이기 때문이다.
     */
    public void revoke(LocalDateTime now) {
        if (this.revokedAt == null) {
            this.revokedAt = now;
        }
    }

    public boolean isRevoked() {
        return this.revokedAt != null;
    }

    public boolean isExpired(LocalDateTime now) {
        return this.expiresAt.isBefore(now);
    }

    /**
     * 회전 직후 짧은 창 안에 들어온 재요청인지 판정한다.
     *
     * <p>모바일 앱이 화면 두 곳에서 동시에 재발급을 시도하면 두 번째 요청이 이미 폐기된
     * 토큰을 들고 오는데, 이를 탈취로 보면 정상 사용자가 강제 로그아웃된다.
     * 이 창 안에서는 재사용 감지를 발동시키지 않는다.
     *
     * <p><b>한계</b>: v9 스키마에 "이 토큰이 무엇으로 대체됐는가"를 가리키는 자기참조 컬럼이
     * 없어 직전 발급분을 되돌려주는 정석 구현이 불가하다. 창 안에서는 탈취 토큰도 통과하므로
     * 근본 방어는 클라이언트 측 재발급 직렬화(mutex)이고 이것은 서버 측 안전망이다.
     */
    public boolean isWithinReuseGrace(LocalDateTime now, Duration grace) {
        return this.revokedAt != null && this.revokedAt.plus(grace).isAfter(now);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RefreshToken that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
