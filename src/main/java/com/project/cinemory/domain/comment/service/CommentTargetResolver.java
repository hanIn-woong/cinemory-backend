package com.project.cinemory.domain.comment.service;

import com.project.cinemory.domain.comment.entity.TargetType;

/**
 * 댓글 대상(다형 참조)의 소유자를 해석하는 전략.
 *
 * <p>{@code target_type} 분기가 (1) 대상 존재 검증 (2) 소유자 확인 (3) 공개범위 판정
 * 세 지점으로 흩어지는 것을 막기 위해, "대상의 소유자 userId를 반환한다"는
 * 단일 책임으로 통합했다. 구현체는 id 프로젝션만 조회하므로 엔티티 로딩 없이 1쿼리로 끝난다.
 */
public interface CommentTargetResolver {

    TargetType supports();

    /** 대상이 존재하지 않으면 BusinessException. 존재 검증과 소유자 조회를 동시에 처리한다. */
    Long findOwnerIdOrThrow(Long targetId);
}
