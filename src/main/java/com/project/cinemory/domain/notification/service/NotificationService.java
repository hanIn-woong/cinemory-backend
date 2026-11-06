package com.project.cinemory.domain.notification.service;

import com.project.cinemory.domain.follow.dto.FollowUserResponse;
import com.project.cinemory.domain.follow.repository.FollowRepository;
import com.project.cinemory.domain.notification.dto.NotificationPageResponse;
import com.project.cinemory.domain.notification.dto.NotificationResponse;
import com.project.cinemory.domain.notification.dto.UnreadCountResponse;
import com.project.cinemory.domain.notification.entity.Notification;
import com.project.cinemory.domain.notification.entity.NotificationType;
import com.project.cinemory.domain.notification.repository.NotificationRepository;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.media.ProfileImageUrlResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 알림 (notification-spec). 의존 방향은 follow → notification 단방향 — FollowService를 주입하지 않는다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 50;

    private final NotificationRepository notificationRepository;
    private final FollowRepository followRepository;
    private final ProfileImageUrlResolver profileImageUrlResolver;

    /** 목록 쿼리 1 + 팔로우 여부 벌크 1 (NT-8, NT-10). */
    public NotificationPageResponse getNotifications(Long userId, Long cursor, Integer size) {
        int pageSize = size == null ? DEFAULT_SIZE : size;
        if (pageSize < 1 || pageSize > MAX_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }

        List<Notification> fetched = notificationRepository.findPage(userId, cursor, PageRequest.of(0, pageSize + 1));
        boolean hasNext = fetched.size() > pageSize;
        List<Notification> page = hasNext ? fetched.subList(0, pageSize) : fetched;

        Set<Long> followingIds = page.isEmpty() ? Set.of() : new HashSet<>(followRepository.findFollowingIdsIn(
                userId, page.stream().map(n -> n.getActor().getId()).toList()));

        List<NotificationResponse> content = page.stream()
                .map(n -> NotificationResponse.from(n, FollowUserResponse.from(n.getActor(),
                        profileImageUrlResolver.resolve(n.getActor().getProfileImage()),
                        followingIds.contains(n.getActor().getId()))))
                .toList();
        Long nextCursor = hasNext ? page.get(page.size() - 1).getId() : null;
        return new NotificationPageResponse(content, nextCursor, hasNext);
    }

    public UnreadCountResponse getUnreadCount(Long userId) {
        return new UnreadCountResponse(notificationRepository.countByUserIdAndReadFalse(userId));
    }

    /** 멱등 — 남의 알림 id를 보내도 user_id 조건으로 영향이 없다. */
    @Transactional
    public void markRead(Long userId, Long upToId) {
        notificationRepository.markReadUpTo(userId, upToId);
    }

    /** FollowService.follow()가 새 팔로우를 저장한 직후, 같은 트랜잭션에서 호출. targetType·targetId는 NULL (NT-3). */
    @Transactional
    public void notifyFollow(User recipient, User actor) {
        notificationRepository.save(Notification.builder()
                .user(recipient).actor(actor).type(NotificationType.FOLLOW)
                .build());
    }

    /** FollowService.unfollow()가 실제로 행을 지웠을 때만 호출 (NT-4). */
    @Transactional
    public void removeFollow(Long recipientId, Long actorId) {
        notificationRepository.deleteFollow(recipientId, actorId);
    }
}
