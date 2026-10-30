package com.project.cinemory.domain.user.service;

import com.project.cinemory.domain.user.dto.ProfileImageUploadUrlResponse;
import com.project.cinemory.domain.user.dto.UserResponse;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 커버 업로드 3종 (profile-spec 3-2 · PR-4 ②) — 프로필 사진과 같은 흐름({@link ImageUploadService})에 키 접두사 {@code cover/}만 다르다.
 * 커버는 영화 또는 업로드 중 하나만이라 {@code User}의 커버 메서드가 반대쪽을 비운다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CoverImageService {

    private final UserRepository userRepository;
    private final ImageUploadService imageUploadService;
    private final ProfileViewAssembler profileViewAssembler;

    public ProfileImageUploadUrlResponse issueUploadUrl(Long userId) {
        return imageUploadService.issueUploadUrl(userId, ImageUploadService.COVER);
    }

    /** 확정 — 영화 커버는 해제되고, 이전 업로드 키는 커밋 뒤 삭제된다. */
    @Transactional
    public UserResponse confirm(Long userId, String key) {
        imageUploadService.validate(userId, key, ImageUploadService.COVER);
        User user = findUser(userId);
        String old = user.getCoverImage();
        user.changeCoverToImage(key);
        imageUploadService.publishReplacedIfOurs(userId, old, key);
        return profileViewAssembler.userResponse(user);
    }

    /** 업로드 커버만 해제한다. 영화 커버는 이 API의 대상이 아니다(없으면 변화 없음). */
    @Transactional
    public UserResponse remove(Long userId) {
        User user = findUser(userId);
        if (user.hasImageCover()) {
            String old = user.getCoverImage();
            user.clearCover();
            imageUploadService.publishReplacedIfOurs(userId, old, null);
        }
        return profileViewAssembler.userResponse(user);
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }
}
