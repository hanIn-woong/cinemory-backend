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
 * 프로필 사진 업로드 3단계 (account-integrity-spec B-3). 흐름은 {@link ImageUploadService}(커버와 공용)가 갖고,
 * 여기는 {@code user.profile_image}를 바꾸는 부분만 맡는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProfileImageService {

    private final UserRepository userRepository;
    private final ImageUploadService imageUploadService;
    private final ProfileViewAssembler profileViewAssembler;

    public ProfileImageUploadUrlResponse issueUploadUrl(Long userId) {
        return imageUploadService.issueUploadUrl(userId, ImageUploadService.PROFILE);
    }

    @Transactional
    public UserResponse confirm(Long userId, String key) {
        imageUploadService.validate(userId, key, ImageUploadService.PROFILE);
        return replace(userId, key);
    }

    @Transactional
    public UserResponse remove(Long userId) {
        return replace(userId, null);
    }

    private UserResponse replace(Long userId, String newKey) {
        User user = userRepository.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        String old = user.getProfileImage();
        user.changeProfileImage(newKey);
        imageUploadService.publishReplacedIfOurs(userId, old, newKey);
        return profileViewAssembler.userResponse(user);
    }
}
