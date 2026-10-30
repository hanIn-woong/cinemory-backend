package com.project.cinemory.domain.follow.service;

import com.project.cinemory.domain.follow.dto.FollowUserResponse;
import com.project.cinemory.domain.follow.dto.UserProfileResponse;
import com.project.cinemory.domain.follow.entity.Follow;
import com.project.cinemory.domain.follow.repository.FollowRepository;
import com.project.cinemory.domain.user.entity.PrivacySetting;
import com.project.cinemory.domain.user.entity.RoleType;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.global.access.UserAccessPolicy;
import com.project.cinemory.global.dto.LikePattern;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.media.ProfileImageUrlResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FollowService {

    private final FollowRepository followRepository;
    private final UserRepository userRepository;
    private final UserAccessPolicy userAccessPolicy;
    private final ProfileImageUrlResolver profileImageUrlResolver;

    static final int SEARCH_MIN_LENGTH = 2;
    static final int SEARCH_MAX_LENGTH = 20;
    /** PRIVATE는 팔로우해도 볼 것이 없어 뺀다(SF-14). */
    private static final List<PrivacySetting> SEARCHABLE_PRIVACY = List.of(PrivacySetting.PUBLIC, PrivacySetting.FRIENDS);

    /** 클라이언트가 보낸 sort는 쓰지 않는다 — 정렬은 쿼리가 고정한다(페이지 번호·크기만 취한다). */
    private static Pageable unsorted(Pageable pageable) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
    }

    /**
     * 팔로우. 이미 팔로우 중이면 예외 없이 정상 종료(멱등).
     *
     * <p>찜(toggleWish)과 달리 팔로우는 상대에게 노출되는 관계 행위이므로
     * 네트워크 재시도로 의도치 않게 해제되는 토글 방식을 쓰지 않고 follow/unfollow를 분리한다.
     */
    @Transactional
    public void follow(Long followerId, Long followingId) {
        requireAuthenticated(followerId);

        if (followerId.equals(followingId)) {
            throw new BusinessException(ErrorCode.CANNOT_FOLLOW_SELF);
        }

        // followingId는 사용자 입력값 → findById로 실존 검증 (프로젝트 전역 FK 검증 규칙)
        User following = userRepository.findById(followingId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (followRepository.existsByFollowerIdAndFollowingId(followerId, followingId)) {
            return;
        }

        // followerId는 인증된 호출자 → getReferenceById (신뢰 가능, 추가 조회 불필요)
        followRepository.save(Follow.of(userRepository.getReferenceById(followerId), following));
    }

    /** 언팔로우. 팔로우 중이 아니어도 예외 없이 정상 종료(멱등). */
    @Transactional
    public void unfollow(Long followerId, Long followingId) {
        requireAuthenticated(followerId);
        followRepository.deleteByFollowerIdAndFollowingId(followerId, followingId);
    }

    /** 팔로워 목록(나를 따르는 사람들). 고정 쿼리: 접근판정 + 목록 1 + 팔로우여부 벌크 1 */
    public Page<FollowUserResponse> getFollowers(Long viewerId, Long targetUserId, Pageable pageable) {
        userAccessPolicy.validateCanView(viewerId, targetUserId);

        Page<Follow> followPage = followRepository.findByFollowingIdOrderByIdDesc(targetUserId, unsorted(pageable));
        return toFollowUserResponsePage(viewerId, followPage, Follow::getFollower);
    }

    /** 팔로잉 목록(내가 따르는 사람들). */
    public Page<FollowUserResponse> getFollowings(Long viewerId, Long targetUserId, Pageable pageable) {
        userAccessPolicy.validateCanView(viewerId, targetUserId);

        Page<Follow> followPage = followRepository.findByFollowerIdOrderByIdDesc(targetUserId, unsorted(pageable));
        return toFollowUserResponsePage(viewerId, followPage, Follow::getFollowing);
    }

    /**
     * 사용자 검색(social-feed-spec 10-2) — 닉네임 부분 일치, PUBLIC·FRIENDS의 일반 사용자만(PRIVATE·ADMIN·본인 제외).
     * 팔로우 여부는 목록과 같은 벌크 조회를 재사용한다.
     */
    public Page<FollowUserResponse> searchUsers(Long viewerId, String q, Pageable pageable) {
        requireAuthenticated(viewerId);
        String keyword = q == null ? "" : q.trim();
        if (keyword.length() < SEARCH_MIN_LENGTH || keyword.length() > SEARCH_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        Page<User> users = userRepository.searchByNickname(
                keyword, LikePattern.contains(keyword), LikePattern.startsWith(keyword),
                SEARCHABLE_PRIVACY, RoleType.USER, viewerId, unsorted(pageable));
        Set<Long> followingIds = findFollowingIds(viewerId, users.getContent().stream().map(User::getId).toList());
        return users.map(user -> FollowUserResponse.from(user, profileImageUrlResolver.resolve(user.getProfileImage()),
                followingIds.contains(user.getId())));
    }

    /** 프로필 헤더. 공개범위와 무관하게 노출된다. */
    public UserProfileResponse getUserProfile(Long viewerId, Long targetUserId) {
        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        boolean me = viewerId != null && viewerId.equals(targetUserId);
        boolean following = !me
                && viewerId != null
                && followRepository.existsByFollowerIdAndFollowingId(viewerId, targetUserId);

        return UserProfileResponse.from(
                target,
                profileImageUrlResolver.resolve(target.getProfileImage()),
                followRepository.countByFollowingId(targetUserId),
                followRepository.countByFollowerId(targetUserId),
                following,
                me
        );
    }

    /**
     * 페이지 내 대상 사용자들에 대해 viewer의 팔로우 여부를 IN절 1쿼리로 벌크 판정한 뒤 매핑한다.
     * (사용자 수만큼 exists를 반복 호출하는 N+1을 막기 위함)
     */
    private Page<FollowUserResponse> toFollowUserResponsePage(Long viewerId,
                                                              Page<Follow> followPage,
                                                              Function<Follow, User> userExtractor) {
        List<Long> targetUserIds = followPage.getContent().stream()
                .map(follow -> userExtractor.apply(follow).getId())
                .toList();

        Set<Long> followingIds = findFollowingIds(viewerId, targetUserIds);

        return followPage.map(follow -> {
            User user = userExtractor.apply(follow);
            return FollowUserResponse.from(user, profileImageUrlResolver.resolve(user.getProfileImage()),
                    followingIds.contains(user.getId()));
        });
    }

    private Set<Long> findFollowingIds(Long viewerId, List<Long> targetUserIds) {
        // 비로그인 조회는 팔로우 여부가 항상 false이므로 쿼리 자체를 생략한다.
        if (viewerId == null || targetUserIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(followRepository.findFollowingIdsIn(viewerId, targetUserIds));
    }

    /**
     * 쓰기 계열은 비로그인을 허용하지 않는다.
     * Spring Security 적용 후에는 SecurityFilterChain이 선차단하므로 이 검사는 이중 방어로 남는다.
     */
    private void requireAuthenticated(Long viewerId) {
        if (viewerId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}
