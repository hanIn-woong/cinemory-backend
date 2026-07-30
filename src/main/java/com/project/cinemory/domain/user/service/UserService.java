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
                    // 소셜 최초 로그인인데 같은 이메일이 이미 가입돼 있으면 uk_user_email 위반으로
                    // 500이 나가기 전에 409로 명시 응답한다. 로그인은 계정 존재 여부를 감춰야 하지만
                    // 여기는 본인이 자기 계정으로 들어오려는 상황이라 알려주는 편이 낫다.
                    // provider가 KAKAO 하나뿐이라 이 시점의 이메일 충돌은 로컬 가입 계정을 의미한다.
                    if (userRepository.existsByEmail(email)) {
                        throw new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED_LOCALLY);
                    }
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
