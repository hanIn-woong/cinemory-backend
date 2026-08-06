package com.project.cinemory.domain.auth.repository;

import com.project.cinemory.domain.auth.entity.RefreshToken;
import com.project.cinemory.domain.auth.entity.RevokedReason;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /** 원문이 아니라 해시로 조회한다. {@code uk_refresh_token_hash} 단건 조회. */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * 해당 유저의 살아 있는 리프레시 토큰을 모두 폐기한다. 재사용 감지 시 전체 세션을 끊는 용도.
     *
     * <p><b>⚠️ {@code REQUIRES_NEW}인 이유</b> — 호출부가 이 메서드를 부른 <b>직후에
     * {@code REFRESH_TOKEN_REUSED}를 던진다.</b> 같은 트랜잭션이면 그 예외로 롤백되면서
     * 폐기까지 함께 취소돼 <b>탈취 대응이 무효화된다.</b> 별도 트랜잭션으로 분리해
     * 예외와 무관하게 커밋되도록 한다.
     *
     * <p>{@code clearAutomatically}는 쓰지 않는다 — 4-6에서 영속성 컨텍스트를 비우는 바람에
     * 앞선 미flush 변경이 폐기돼 FK 제약을 위반한 전례가 있다.
     *
     * <p><b>{@code revokedReason}을 반드시 함께 세팅한다</b> — 벌크 UPDATE는 엔티티의
     * {@code revoke()}를 거치지 않으므로, 시각만 채우면 v10의
     * {@code chk_refresh_token_revocation}에 걸려 쿼리 자체가 실패한다.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update RefreshToken rt set rt.revokedAt = :now, rt.revokedReason = :reason "
            + "where rt.user.id = :userId and rt.revokedAt is null")
    int revokeAllByUserId(@Param("userId") Long userId,
                          @Param("now") LocalDateTime now,
                          @Param("reason") RevokedReason reason);
}
