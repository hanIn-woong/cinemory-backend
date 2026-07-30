package com.project.cinemory.global.access;

import com.project.cinemory.domain.follow.repository.FollowRepository;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 사용자 공개범위(privacy_setting) 기반 조회 권한 판정 공통 컴포넌트.
 *
 * <p>판정 규칙
 * <ul>
 *   <li>본인({@code viewerId.equals(targetUserId)}) → 항상 허용</li>
 *   <li>{@code PUBLIC} → 허용</li>
 *   <li>{@code FRIENDS} → <b>상호 팔로우(맞팔)</b>일 때만 허용</li>
 *   <li>{@code PRIVATE} → 본인 외 거부</li>
 *   <li>{@code viewerId == null}(비로그인) → PUBLIC만 허용</li>
 * </ul>
 *
 * <p>접근 제어는 특정 도메인의 책임이 아니라 애플리케이션 전역 정책이므로
 * {@code domain.follow} 하위가 아닌 {@code global.access}에 둔다.
 * 의존 방향은 {@code global.access → domain.*.repository} 단방향이며 역참조는 금지한다.
 */
@Component
@RequiredArgsConstructor
public class UserAccessPolicy {

    private final UserRepository userRepository;
    private final FollowRepository followRepository;

    /** 조회 권한이 없으면 {@link ErrorCode#ACCESS_DENIED} */
    public void validateCanView(Long viewerId, Long targetUserId) {
        if (!canView(viewerId, targetUserId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
    }

    /**
     * 단건 판정. 최대 2쿼리(user 1 + FRIENDS인 경우에만 맞팔 판정 1).
     * 대상 사용자가 존재하지 않으면 {@link ErrorCode#USER_NOT_FOUND}.
     */
    public boolean canView(Long viewerId, Long targetUserId) {
        if (isSelf(viewerId, targetUserId)) {
            return true;
        }

        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        return switch (target.getPrivacySetting()) {
            case PUBLIC -> true;
            case PRIVATE -> false;
            // 비로그인(viewerId == null)이면 맞팔이 성립할 수 없으므로 쿼리 없이 즉시 거부
            case FRIENDS -> viewerId != null && isMutualFollow(viewerId, targetUserId);
        };
    }

    /**
     * N명 벌크 판정. 조회 가능한 targetUserId만 반환한다.
     * PUBLIC/본인은 쿼리 없이 통과시키고, 남은 FRIENDS 후보에 대해서만
     * "내가 팔로우한 집합" / "나를 팔로우한 집합"을 각각 1쿼리로 조회해 메모리에서 교집합을 구한다.
     * → 최대 3쿼리 고정 (4-2에서 확정한 "IN절 벌크 조회 + Service 조합" 표준 패턴)
     */
    public Set<Long> filterViewable(Long viewerId, Collection<Long> targetUserIds) {
        if (targetUserIds == null || targetUserIds.isEmpty()) {
            return Set.of();
        }

        Set<Long> distinctIds = new HashSet<>(targetUserIds);
        Set<Long> viewable = new HashSet<>();
        Set<Long> friendsCandidates = new HashSet<>();

        for (User target : userRepository.findAllById(distinctIds)) {
            Long targetId = target.getId();
            if (isSelf(viewerId, targetId)) {
                viewable.add(targetId);
                continue;
            }
            switch (target.getPrivacySetting()) {
                case PUBLIC -> viewable.add(targetId);
                case FRIENDS -> {
                    if (viewerId != null) {
                        friendsCandidates.add(targetId);
                    }
                }
                case PRIVATE -> { /* 본인 외 거부 — 쿼리 이전에 탈락시켜 IN절 크기를 줄인다 */ }
            }
        }

        if (!friendsCandidates.isEmpty()) {
            viewable.addAll(findMutualFollowIds(viewerId, friendsCandidates));
        }
        return viewable;
    }

    private boolean isSelf(Long viewerId, Long targetUserId) {
        return viewerId != null && viewerId.equals(targetUserId);
    }

    private boolean isMutualFollow(Long viewerId, Long targetUserId) {
        return followRepository.countMutual(viewerId, targetUserId) == 2L;
    }

    private Set<Long> findMutualFollowIds(Long viewerId, Set<Long> candidateIds) {
        Set<Long> mutual = new HashSet<>(followRepository.findFollowingIdsIn(viewerId, candidateIds));
        List<Long> followerIds = followRepository.findFollowerIdsIn(viewerId, candidateIds);
        mutual.retainAll(new HashSet<>(followerIds));
        return mutual;
    }
}
