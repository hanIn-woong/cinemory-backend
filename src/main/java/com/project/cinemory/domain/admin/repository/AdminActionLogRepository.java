package com.project.cinemory.domain.admin.repository;

import com.project.cinemory.domain.admin.entity.AdminAction;
import com.project.cinemory.domain.admin.entity.AdminActionLog;
import com.project.cinemory.domain.admin.entity.AdminTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface AdminActionLogRepository extends JpaRepository<AdminActionLog, Long> {

    /**
     * 감사 로그 조회(admin-spec 11-4) — 조건은 모두 선택이고 AND다. {@code fromAt}은 포함, {@code toAt}은 <b>미포함</b>
     * (호출부가 {@code to}의 다음 날 0시를 넘긴다). 행 수가 작아 동적 조건 한 개로 둔다. 정렬은 호출한 Pageable(고정).
     */
    @Query("""
            select l from AdminActionLog l
            where (:action is null or l.action = :action)
              and (:targetType is null or l.targetType = :targetType)
              and (:targetId is null or l.targetId = :targetId)
              and (:adminUserId is null or l.adminUserId = :adminUserId)
              and (:fromAt is null or l.createdAt >= :fromAt)
              and (:toAt is null or l.createdAt < :toAt)
            """)
    Page<AdminActionLog> search(@Param("action") AdminAction action,
                                @Param("targetType") AdminTargetType targetType,
                                @Param("targetId") Long targetId,
                                @Param("adminUserId") Long adminUserId,
                                @Param("fromAt") LocalDateTime fromAt,
                                @Param("toAt") LocalDateTime toAt,
                                Pageable pageable);
}
