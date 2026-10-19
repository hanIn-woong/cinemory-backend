package com.project.cinemory.domain.admin.service;

import com.project.cinemory.domain.admin.dto.AdminAuditLogResponse;
import com.project.cinemory.domain.admin.entity.AdminAction;
import com.project.cinemory.domain.admin.entity.AdminTargetType;
import com.project.cinemory.domain.admin.repository.AdminActionLogRepository;
import com.project.cinemory.global.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/** 감사 로그 조회(admin-spec 11-4). <b>이 조회 자체는 기록하지 않는다</b>(11-6). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminAuditQueryService {

    /** 정렬 파라미터 없음 — 최신순 고정. */
    private static final Sort LATEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final AdminActionLogRepository adminActionLogRepository;

    /**
     * @param from 포함(그날 0시부터), {@code to} 포함(그날 끝까지 — 다음 날 0시 미만). 서버 시간대(운영 KST) 기준
     */
    public PageResponse<AdminAuditLogResponse> getLogs(AdminAction action, AdminTargetType targetType, Long targetId,
                                                       Long adminUserId, LocalDate from, LocalDate to,
                                                       int page, int size) {
        return PageResponse.from(adminActionLogRepository.search(
                        action, targetType, targetId, adminUserId,
                        from == null ? null : from.atStartOfDay(),
                        to == null ? null : to.plusDays(1).atStartOfDay(),
                        PageRequest.of(page, size, LATEST_FIRST))
                .map(AdminAuditLogResponse::from));
    }
}
