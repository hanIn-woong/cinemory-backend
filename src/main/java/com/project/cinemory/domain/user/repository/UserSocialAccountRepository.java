package com.project.cinemory.domain.user.repository;

import com.project.cinemory.domain.auth.entity.OAuthProvider;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.entity.UserSocialAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserSocialAccountRepository extends JpaRepository<UserSocialAccount, Long> {

    /**
     * 소셜 계정의 주인. 로그인(그 사용자로 로그인)과 연결(다른 사용자의 것인지 판정)이 함께 쓴다.
     * 사용자를 바로 꺼내 쿼리 한 번으로 끝낸다.
     */
    @Query("select s.user from UserSocialAccount s where s.provider = :provider and s.providerId = :providerId")
    Optional<User> findUserByProviderAndProviderId(@Param("provider") OAuthProvider provider,
                                                   @Param("providerId") String providerId);

    boolean existsByUserIdAndProvider(Long userId, OAuthProvider provider);

    List<UserSocialAccount> findAllByUserIdOrderByCreatedAtAscIdAsc(Long userId);

    /**
     * 해제용 — 사용자의 연결 전부를 <b>잠금 읽기</b>({@code SELECT … FOR UPDATE})로 가져온다(account-integrity D-4 #3).
     * 잠금 읽기는 스냅샷이 아니라 항상 최신 커밋 값을 읽으므로, 트랜잭션 안에서 앞서 일반 조회가 있었더라도
     * 동시 해제가 지운 행을 놓치지 않는다. JPA {@code COUNT}에는 락을 걸기 어려워 목록을 잠그고 호출부가 센다(사용자당 최대 제공자 수만큼).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from UserSocialAccount s where s.user.id = :userId")
    List<UserSocialAccount> findAllByUserIdForUpdate(@Param("userId") Long userId);
}
