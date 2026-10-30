package com.project.cinemory.domain.auth.service.oauth;

/**
 * ID 토큰에서 뽑아낸 사용자 정보. <b>provider별 응답 차이를 여기서 흡수</b>해
 * {@code UserService.signUpOAuth}가 provider를 몰라도 되게 한다.
 *
 * <p>제공자의 닉네임·프로필 사진은 읽지 않는다(profile-spec PR-9) — 구글 {@code name}·카카오 사진은 실명·본인 얼굴이 흔한데
 * 사용자는 로그인했을 뿐 소셜 기능 공개에 동의한 적이 없다. 새 가입자는 서버가 만든 자동 닉네임과 사진 없음으로 시작한다.
 *
 * @param providerId provider 내 고유 식별자 (카카오는 {@code sub} = 회원번호)
 * @param email      {@code user.email}이 NOT NULL이라 <b>없으면 가입 불가</b>
 */
public record OAuthUserInfo(String providerId, String email) {
}
