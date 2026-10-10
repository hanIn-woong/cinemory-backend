package com.project.cinemory.domain.user.repository;

import com.project.cinemory.domain.user.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

    /**
     * {@code user} 행을 {@code SELECT … FOR UPDATE}로 잡는다 — 소셜 연결·해제의 직렬화 지점(D-2-A).
     * "인증 수단 최소 1개"는 테이블을 넘나드는 서비스 불변식이라, 두 기기에서 동시에 서로 다른 제공자를
     * 해제하면 각자 "2개 남음"을 보고 둘 다 지울 수 있다. 같은 사용자 행을 먼저 잡게 해 한 줄로 세운다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);
}
