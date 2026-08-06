package com.project.cinemory.domain.auth.repository;

import com.project.cinemory.domain.auth.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    /** 원문이 아니라 해시로 조회한다. {@code uk_password_reset_token_hash} 단건 조회. */
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /**
     * 재요청 억제(S-9 F-1) 판정용 — 해당 유저에게 마지막으로 발급된 시각.
     *
     * <p><b>미사용/사용됨을 가리지 않는다.</b> 아래 {@code deleteUnusedByUserId}가 미사용 행을
     * 지우기 때문에, 미사용만 보면 "직전 발급"의 흔적이 사라져 억제가 무력화된다.
     * 사용된 행까지 함께 보는 이 조회 + <b>삭제보다 먼저 판정</b>한다는 순서가 세트다.
     *
     * <p>토큰이 하나도 없으면 {@code max()}가 NULL을 돌려주고 Spring Data가
     * {@code Optional.empty()}로 변환한다 — "억제할 이력이 없다"와 같은 뜻이다.
     */
    @Query("select max(t.createdAt) from PasswordResetToken t where t.user.id = :userId")
    Optional<LocalDateTime> findLatestCreatedAtByUserId(@Param("userId") Long userId);

    /**
     * 해당 유저의 <b>미사용</b> 토큰을 삭제한다. 재발송을 반복해 유효한 링크가 여러 개
     * 살아 있지 않게 하기 위함이다 — 마지막으로 받은 메일 하나만 동작해야 한다.
     *
     * <p><b>사용된 행({@code used_at IS NOT NULL})은 남긴다</b> — "언제 재설정됐나"가 감사 기록이고,
     * 위 억제 판정의 근거이기도 하다.
     *
     * <p>{@code clearAutomatically}는 쓰지 않는다 — 영속성 컨텍스트를 비우면 앞선 미flush 변경이
     * 함께 폐기된다(4-6 전례). 이 메서드는 새 토큰을 저장하기 <b>전에</b>만 호출되므로
     * 컨텍스트에 남은 재설정 토큰이 없다.
     */
    @Modifying
    @Query("delete from PasswordResetToken t where t.user.id = :userId and t.usedAt is null")
    int deleteUnusedByUserId(@Param("userId") Long userId);
}
