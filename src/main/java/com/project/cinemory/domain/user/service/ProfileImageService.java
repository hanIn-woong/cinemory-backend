package com.project.cinemory.domain.user.service;

import com.project.cinemory.domain.user.dto.ProfileImageUploadUrlResponse;
import com.project.cinemory.domain.user.dto.UserResponse;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.event.ProfileImageReplacedEvent;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.media.MediaProperties;
import com.project.cinemory.global.infra.media.MediaStorage;
import com.project.cinemory.global.infra.media.ProfileImageUrlResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 프로필 사진 업로드 3단계 (account-integrity-spec B-3): 업로드 URL 발급 → 앱이 S3에 직접 PUT → 키 확정.
 * 확정 단계에서 키 소유·존재·크기·Content-Type을 검사한다(P-9) — presigned PUT은 크기를 서명으로 강제하기 어렵다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProfileImageService {

    static final String CONTENT_TYPE = "image/jpeg";

    private final UserRepository userRepository;
    private final MediaStorage mediaStorage;
    private final MediaProperties mediaProperties;
    private final ProfileImageUrlResolver profileImageUrlResolver;
    private final ApplicationEventPublisher eventPublisher;

    public ProfileImageUploadUrlResponse issueUploadUrl(Long userId) {
        String key = keyPrefix(userId) + UUID.randomUUID() + ".jpg";
        var presigned = mediaStorage.presignPut(key, CONTENT_TYPE, mediaProperties.uploadUrlTtl());
        return new ProfileImageUploadUrlResponse(presigned.url(), key, presigned.expiresAt());
    }

    @Transactional
    public UserResponse confirm(Long userId, String key) {
        validate(userId, key);
        return replace(userId, key);
    }

    @Transactional
    public UserResponse remove(Long userId) {
        return replace(userId, null);
    }

    private void validate(Long userId, String key) {
        if (!key.startsWith(keyPrefix(userId))) {
            throw new BusinessException(ErrorCode.INVALID_PROFILE_IMAGE);
        }
        var meta = mediaStorage.head(key).orElseThrow(() -> new BusinessException(ErrorCode.INVALID_PROFILE_IMAGE));
        if (meta.size() > mediaProperties.profileMaxBytes() || !CONTENT_TYPE.equals(meta.contentType())) {
            throw new BusinessException(ErrorCode.INVALID_PROFILE_IMAGE);
        }
    }

    /**
     * 이전 값이 우리 키일 때만 정리 이벤트를 낸다 — 제공자 URL({@code http})은 지울 파일이 없다.
     * 같은 키를 다시 확정하면 방금 확정한 파일을 지우게 되므로 내지 않는다.
     */
    private UserResponse replace(Long userId, String newKey) {
        User user = userRepository.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        String old = user.getProfileImage();
        user.changeProfileImage(newKey);
        if (old != null && !old.startsWith("http") && !old.equals(newKey)) {
            eventPublisher.publishEvent(new ProfileImageReplacedEvent(userId, old));
        }
        return UserResponse.from(user, profileImageUrlResolver.resolve(user.getProfileImage()));
    }

    private static String keyPrefix(Long userId) {
        return "profile/" + userId + "/";
    }
}
