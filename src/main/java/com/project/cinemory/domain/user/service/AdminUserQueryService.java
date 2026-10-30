package com.project.cinemory.domain.user.service;

import com.project.cinemory.domain.auth.entity.OAuthProvider;
import com.project.cinemory.domain.collection.repository.CollectionRepository;
import com.project.cinemory.domain.follow.repository.FollowRepository;
import com.project.cinemory.domain.review.repository.ReviewRepository;
import com.project.cinemory.domain.user.dto.AdminMeResponse;
import com.project.cinemory.domain.user.dto.AdminUserDetailResponse;
import com.project.cinemory.domain.user.dto.AdminUserSort;
import com.project.cinemory.domain.user.dto.AdminUserSummaryResponse;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.entity.UserSocialAccount;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.domain.user.repository.UserSocialAccountRepository;
import com.project.cinemory.domain.watch.repository.WatchRecordRepository;
import com.project.cinemory.domain.wish.repository.WishMovieRepository;
import com.project.cinemory.global.dto.LikePattern;
import com.project.cinemory.global.dto.PageResponse;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.media.ProfileImageUrlResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 관리자용 회원 조회(admin-spec 3-1~3-3).
 *
 * <p>⚠️ <b>{@code UserAccessPolicy}를 적용하지 않는다</b> — 관리자는 {@code PRIVATE} 사용자도 본다.
 * 그래서 앱용 Service를 재사용하지 않고 전용 조회 Service·DTO를 둔다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminUserQueryService {

    private final UserRepository userRepository;
    private final UserSocialAccountRepository userSocialAccountRepository;
    private final WatchRecordRepository watchRecordRepository;
    private final ReviewRepository reviewRepository;
    private final WishMovieRepository wishMovieRepository;
    private final CollectionRepository collectionRepository;
    private final FollowRepository followRepository;
    private final ProfileImageUrlResolver profileImageUrlResolver;

    public AdminMeResponse getMe(Long userId) {
        return AdminMeResponse.from(findUser(userId));
    }

    /** 목록 1페이지 = 쿼리 3회(목록·count·소셜) — N+1 금지. */
    public PageResponse<AdminUserSummaryResponse> getUsers(String q, AdminUserSort sort, int page, int size) {
        Page<User> users = userRepository.searchForAdmin(
                LikePattern.contains(q), PageRequest.of(page, size, sort.toSort()));

        List<Long> userIds = users.getContent().stream().map(User::getId).toList();
        Map<Long, List<OAuthProvider>> providersByUserId = providersByUserId(userIds);

        return PageResponse.from(users.map(user -> AdminUserSummaryResponse.of(
                user, providersByUserId.getOrDefault(user.getId(), List.of()))));
    }

    public AdminUserDetailResponse getUser(Long userId) {
        User user = findUser(userId);
        Map<Long, List<OAuthProvider>> providers = providersByUserId(List.of(userId));

        AdminUserDetailResponse.Activity activity = new AdminUserDetailResponse.Activity(
                watchRecordRepository.countByUserId(userId),
                watchRecordRepository.countByUserIdAndRepresentativeTrue(userId),
                reviewRepository.countByUserId(userId),
                wishMovieRepository.countByUserId(userId),
                collectionRepository.countByUserId(userId),
                followRepository.countByFollowingId(userId),
                followRepository.countByFollowerId(userId),
                watchRecordRepository.findLastWatchDateByUserId(userId));

        return AdminUserDetailResponse.of(user, profileImageUrlResolver.resolve(user.getProfileImage()),
                providers.getOrDefault(userId, List.of()), activity);
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    private Map<Long, List<OAuthProvider>> providersByUserId(List<Long> userIds) {
        Map<Long, List<OAuthProvider>> result = new LinkedHashMap<>();
        if (userIds.isEmpty()) {
            return result;
        }
        for (UserSocialAccount account : userSocialAccountRepository.findAllByUserIdIn(userIds)) {
            result.computeIfAbsent(account.getUser().getId(), id -> new ArrayList<>()).add(account.getProvider());
        }
        return result;
    }
}
