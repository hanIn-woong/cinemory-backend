package com.project.cinemory.domain.auth.service.oauth;

/**
 * ID 토큰에서 뽑아낸 사용자 정보. <b>provider별 응답 차이를 여기서 흡수</b>해
 * {@code UserService.signUpOAuth}가 provider를 몰라도 되게 한다.
 *
 * @param providerId  provider 내 고유 식별자 (카카오는 {@code sub} = 회원번호)
 * @param email       {@code user.email}이 NOT NULL이라 <b>없으면 가입 불가</b>
 * @param nickname    없으면 검증기가 기본값을 만들어 채운다 (S-9 E-3)
 * @param profileImage 없으면 {@code null}
 */
public record OAuthUserInfo(String providerId, String email, String nickname, String profileImage) {
}
