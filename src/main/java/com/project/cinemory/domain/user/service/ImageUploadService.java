package com.project.cinemory.domain.user.service;

import com.project.cinemory.domain.user.dto.ProfileImageUploadUrlResponse;
import com.project.cinemory.domain.user.event.ProfileImageReplacedEvent;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.media.MediaProperties;
import com.project.cinemory.global.infra.media.MediaStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 사용자 이미지 업로드의 공통 부분 — 프로필 사진({@code profile/})과 커버({@code cover/})가 키 접두사와 오류 코드만 다르고 같은 흐름이다
 * (account-integrity-spec Part B · profile-spec 3-2): 업로드 URL 발급 → 앱이 S3에 직접 PUT → 키 확정.
 * 확정 때 키 소유·존재·크기·Content-Type을 검사한다(P-9) — presigned PUT은 크기를 서명으로 강제하기 어렵다.
 *
 * <p>트랜잭션은 호출하는 서비스가 연다 — 이벤트는 그 트랜잭션의 {@code AFTER_COMMIT}에서 받는다.
 */
@Service
@RequiredArgsConstructor
public class ImageUploadService {

    static final String CONTENT_TYPE = "image/jpeg";

    /** 종류별 정책 — 키 접두사(슬래시 없음)와 검증 실패 코드. */
    public record Policy(String prefix, ErrorCode invalidCode) {

        /** {@code profile/12/} — 슬래시까지 포함해 비교해야 {@code profile/129/}가 12번 경로로 오인되지 않는다. */
        String userPrefix(Long userId) {
            return prefix + "/" + userId + "/";
        }
    }

    public static final Policy PROFILE = new Policy("profile", ErrorCode.INVALID_PROFILE_IMAGE);
    public static final Policy COVER = new Policy("cover", ErrorCode.INVALID_COVER_IMAGE);

    private final MediaStorage mediaStorage;
    private final MediaProperties mediaProperties;
    private final ApplicationEventPublisher eventPublisher;

    public ProfileImageUploadUrlResponse issueUploadUrl(Long userId, Policy policy) {
        String key = policy.userPrefix(userId) + UUID.randomUUID() + ".jpg";
        var presigned = mediaStorage.presignPut(key, CONTENT_TYPE, mediaProperties.uploadUrlTtl());
        return new ProfileImageUploadUrlResponse(presigned.url(), key, presigned.expiresAt());
    }

    /** 확정할 키가 내 경로의 존재하는 ≤2MB {@code image/jpeg}인지. 아니면 정책의 오류 코드. */
    public void validate(Long userId, String key, Policy policy) {
        if (!key.startsWith(policy.userPrefix(userId))) {
            throw new BusinessException(policy.invalidCode());
        }
        var meta = mediaStorage.head(key).orElseThrow(() -> new BusinessException(policy.invalidCode()));
        if (meta.size() > mediaProperties.profileMaxBytes() || !CONTENT_TYPE.equals(meta.contentType())) {
            throw new BusinessException(policy.invalidCode());
        }
    }

    /**
     * 이전 값이 우리 키일 때만 정리 이벤트를 낸다 — 제공자 URL({@code http})은 지울 파일이 없다.
     * 같은 키를 다시 확정하면 방금 확정한 파일을 지우게 되므로 내지 않는다. 호출자의 트랜잭션 안에서 불러야 한다.
     */
    public void publishReplacedIfOurs(Long userId, String oldKey, String newKey) {
        if (oldKey != null && !oldKey.startsWith("http") && !oldKey.equals(newKey)) {
            eventPublisher.publishEvent(new ProfileImageReplacedEvent(userId, oldKey));
        }
    }
}
