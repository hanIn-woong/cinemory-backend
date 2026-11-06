package com.project.cinemory.domain.notification.repository;

import com.project.cinemory.domain.notification.entity.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 키셋 페이지(NT-10). 내부 조인이라 actor가 NULL인 행(NT-5 안전망)은 빠진다. */
    @Query("""
            SELECT n FROM Notification n JOIN FETCH n.actor
            WHERE n.user.id = :userId AND (:cursor IS NULL OR n.id < :cursor)
            ORDER BY n.id DESC
            """)
    List<Notification> findPage(@Param("userId") Long userId, @Param("cursor") Long cursor, Pageable pageable);

    long countByUserIdAndReadFalse(Long userId);

    // 벌크 쓰기는 clearAutomatically를 쓰지 않는다 — 앞선 미flush 변경을 폐기해 FK 위반을 낸 옵션이다(deleteByTarget과 동일).
    @Modifying
    @Query("UPDATE Notification n SET n.read = true WHERE n.user.id = :userId AND n.read = false AND n.id <= :upToId")
    int markReadUpTo(@Param("userId") Long userId, @Param("upToId") Long upToId);

    @Modifying
    @Query("DELETE FROM Notification n WHERE n.user.id = :userId AND n.actor.id = :actorId "
            + "AND n.type = com.project.cinemory.domain.notification.entity.NotificationType.FOLLOW")
    int deleteFollow(@Param("userId") Long userId, @Param("actorId") Long actorId);

    @Modifying
    @Query("DELETE FROM Notification n WHERE n.actor.id = :actorId")
    int deleteByActorId(@Param("actorId") Long actorId);
}
