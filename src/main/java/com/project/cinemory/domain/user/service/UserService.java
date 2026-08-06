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
    /**
     * 소셜 가입/조회 (멱등). <b>{@code User}를 반환한다</b> — 호출부인 {@code AuthService}가
     * Access Token에 담을 {@code role}이 필요한데 {@code UserResponse}에는 없기 때문이다.
     * {@code login()}과 마찬가지로 Controller까지 나가지 않는 서비스 간 호출이라
     * 엔티티를 그대로 반환해도 DTO 원칙에 어긋나지 않는다.
     */
    public User signUpOAuth(String email, String nickname, String profileImage,
                                     String provider, String providerId) {
        return userRepository.findByProviderAndProviderId(provider, providerId)
                .orElseGet(() -> {
                    // 소셜 최초 로그인인데 같은 이메일이 이미 가입돼 있으면 uk_user_email 위반으로
                    // 500이 나가기 전에 409로 명시 응답한다. 로그인은 계정 존재 여부를 감춰야 하지만
                    // 여기는 본인이 자기 계정으로 들어오려는 상황이라 알려주는 편이 낫다.
                    // provider가 KAKAO 하나뿐이라 이 시점의 이메일 충돌은 로컬 가입 계정을 의미한다.
                    if (userRepository.existsByEmail(email)) {
                        throw new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED_LOCALLY);
                    }
                    User user = User.createOAuth(email, nickname, profileImage, provider, providerId);
                    return userRepository.save(user);
                });
    }

    /**
     * 자격증명 검증. 성공 시 {@code User}를 반환한다 — 토큰 발급·저장 조율은 {@code AuthService} 책임이라
     * 여기서는 "이 이메일/비밀번호가 맞는가"만 판정한다. Controller까지 나가지 않는 서비스 간 호출이므로
     * 엔티티를 그대로 반환해도 DTO 원칙에 어긋나지 않는다.
     *
     * <p><b>실패 사유를 구분하지 않는다.</b> 이메일 미존재 / 비밀번호 불일치 /
     * OAuth 가입 계정({@code passwordHash == null})의 로컬 로그인 시도를 전부
     * {@code INVALID_CREDENTIALS} 하나로 응답한다. 구분하면 "이 이메일이 가입돼 있다",
     * "이 계정은 카카오로 가입했다" 같은 정보가 새어 계정 탐색에 쓰인다.
     */
    public User login(String email, String rawPassword) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        if (user.isOAuthUser() || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        return user;
    }

    /**
     * 비밀번호 갱신 — <b>재설정(S-J)과 변경(Step5)의 공통 지점</b>이다.
     * 인코딩 규칙이 한 곳에만 있어야 두 흐름이 갈라지지 않는다.
     *
     * <p>여기에 <b>자격 검증은 없다.</b> "누가 이 사람임을 어떻게 증명했는가"는 호출부의 책임이며
     * 재설정은 메일로 받은 토큰이, 변경은 현재 비밀번호가 그 역할을 한다.
     * Step5의 변경은 이 메서드 앞에 현재 비밀번호 검증만 덧붙이면 된다.
     *
     * <p><b>세션 폐기는 하지 않는다.</b> 호출부가 순서를 통제해야 하기 때문이다 —
     * {@code revokeAllByUserId}는 {@code REQUIRES_NEW}라 별도 트랜잭션에서 커밋되므로,
     * 여기서 함께 호출하면 이후 단계가 롤백돼도 폐기만 남아
     * "로그아웃됐는데 비밀번호는 그대로"인 상태가 만들어진다.
     *
     * <p>엔티티를 받는 이유는 호출부가 이미 조회를 마친 상태이기 때문이다
     * (재설정은 토큰에서, 변경은 인증 주체에서 사용자를 얻는다). 서비스 간 호출이라
     * DTO 원칙에 어긋나지 않는다 — {@code login()}/{@code signUpOAuth()}와 같은 판단.
     */
    @Transactional
    public void updatePassword(User user, String newRawPassword) {
        user.changePassword(passwordEncoder.encode(newRawPassword));
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
