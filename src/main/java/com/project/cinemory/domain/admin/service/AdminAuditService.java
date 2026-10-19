package com.project.cinemory.domain.admin.service;

import com.project.cinemory.domain.admin.entity.AdminAction;
import com.project.cinemory.domain.admin.entity.AdminActionLog;
import com.project.cinemory.domain.admin.entity.AdminTargetType;
import com.project.cinemory.domain.admin.repository.AdminActionLogRepository;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.security.resolver.AuditActor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 관리자 감사 로그 기록(admin-spec 11-3, BD-3 — 어노테이션/AOP가 아닌 <b>명시 호출</b>).
 *
 * <p>호출 규칙 — DB 쓰기가 있는 동작은 쓰기 서비스 안에서 같은 트랜잭션으로, 트랜잭션 밖에서 외부 API를 부르는 동작(재동기화·시드)과
 * 조회는 <b>동작이 성공한 뒤</b> Controller가 호출한다. 로그 저장이 실패하면 예외를 그대로 올려 500으로 알린다(조용히 삼키지 않는다 —
 * 동작은 이미 끝났다). 실패한 동작은 남기지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AdminAuditService {

    private final AdminActionLogRepository adminActionLogRepository;
    private final UserRepository userRepository;

    /**
     * @param targetType {@code null}이면 대상 없는 일괄 작업
     * @param detail     {@link AuditDetail#of}로 만든다. {@code null} 가능
     */
    @Transactional
    public void record(AuditActor actor, AdminAction action, AdminTargetType targetType, Long targetId,
                       Map<String, Object> detail) {
        // 이메일은 그 시점 값을 스냅샷으로 남긴다 — 이후 계정이 바뀌거나 사라져도 로그는 그대로다(BD-4)
        String email = userRepository.findById(actor.adminUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND))
                .getEmail();

        adminActionLogRepository.save(AdminActionLog.builder()
                .adminUserId(actor.adminUserId())
                .adminEmailSnapshot(email)
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .detail(detail == null || detail.isEmpty() ? null : detail)
                .ip(actor.ip())
                .build());
    }
}
