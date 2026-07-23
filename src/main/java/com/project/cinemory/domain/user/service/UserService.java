package com.project.cinemory.domain.user.service;

import com.project.cinemory.domain.user.dto.SignUpLocalRequest;
import com.project.cinemory.domain.user.dto.UserResponse;
import com.project.cinemory.domain.user.entity.PrivacySetting;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public UserResponse signUpLocal(SignUpLocalRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }
        String encodedPassword = passwordEncoder.encode(request.rawPassword());
        User user = User.createLocal(request.email(), encodedPassword, request.nickname());
        return UserResponse.from(userRepository.save(user));
    }

    @Transactional
    public UserResponse signUpOAuth(String email, String nickname, String profileImage,
                                     String provider, String providerId) {
        return userRepository.findByProviderAndProviderId(provider, providerId)
                .map(UserResponse::from)
                .orElseGet(() -> {
                    User user = User.createOAuth(email, nickname, profileImage, provider, providerId);
                    return UserResponse.from(userRepository.save(user));
                });
    }

    public UserResponse getUser(Long userId) {
        return UserResponse.from(findUserOrThrow(userId));
    }

    @Transactional
    public void changeNickname(Long userId, String nickname) {
        User user = findUserOrThrow(userId);
        user.changeNickname(nickname);
    }

    @Transactional
    public void changePrivacySetting(Long userId, PrivacySetting privacySetting) {
        User user = findUserOrThrow(userId);
        user.changePrivacySetting(privacySetting);
    }

    private User findUserOrThrow(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }
}
