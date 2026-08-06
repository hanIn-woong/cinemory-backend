package com.project.cinemory.domain.auth.controller;

import com.project.cinemory.domain.auth.dto.LoginRequest;
import com.project.cinemory.domain.auth.dto.LogoutRequest;
import com.project.cinemory.domain.auth.dto.NonceResponse;
import com.project.cinemory.domain.auth.dto.OAuthLoginRequest;
import com.project.cinemory.domain.auth.dto.PasswordResetConfirmRequest;
import com.project.cinemory.domain.auth.dto.PasswordResetRequest;
import com.project.cinemory.domain.auth.dto.PasswordResetVerifyRequest;
import com.project.cinemory.domain.auth.dto.ReissueRequest;
import com.project.cinemory.domain.auth.dto.TokenResponse;
import com.project.cinemory.domain.auth.service.AuthService;
import com.project.cinemory.domain.auth.service.OAuthNonceService;
import com.project.cinemory.domain.auth.service.PasswordResetService;
import com.project.cinemory.domain.user.dto.SignUpLocalRequest;
import com.project.cinemory.domain.user.dto.UserResponse;
import com.project.cinemory.domain.user.service.UserService;
import com.project.cinemory.global.security.resolver.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 엔드포인트.
 *
 * <p>{@code signup}/{@code login}/{@code reissue}는 {@code permitAll}이며
 * {@code JwtAuthenticationFilter}의 대상에서도 <b>제외</b>된다(S-9 C-1) —
 * 만료된 토큰이 헤더에 남아 있어도 재로그인이 막히지 않게 하기 위함이다.
 *
 * <p>반면 {@code logout}은 <b>인증이 필요하다</b>(S-9 A-5). 그래야 전달받은 리프레시 토큰이
 * 호출자 본인의 것인지 검증할 수 있다.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final AuthService authService;
    private final OAuthNonceService nonceService;
    private final PasswordResetService passwordResetService;

    /** 가입만 하고 토큰은 발급하지 않는다. 클라이언트가 이어서 로그인을 호출한다. */
    @PostMapping("/signup")
    public ResponseEntity<UserResponse> signUp(@Valid @RequestBody SignUpLocalRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.signUpLocal(request));
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    /**
     * 소셜 로그인 시작 전 nonce 발급 (S-9 E-1).
     *
     * <p>상태를 만드는(서버에 보관하는) 동작이라 GET이 아니라 POST다.
     * 클라이언트는 이 값을 카카오 SDK 로그인에 전달하고, 이어서
     * {@code /api/auth/oauth/{provider}}에 ID 토큰과 함께 다시 보낸다.
     */
    @PostMapping("/nonce")
    public ResponseEntity<NonceResponse> issueNonce() {
        return ResponseEntity.ok(
                new NonceResponse(nonceService.issue(), nonceService.ttlSeconds()));
    }

    /**
     * 소셜 로그인. {@code nonce}는 {@code POST /api/auth/nonce}로 미리 발급받은 값이어야 한다.
     * 이 경로도 {@code JwtAuthenticationFilter}의 대상에서 제외된다(S-9 C-1) —
     * 만료된 Access를 헤더에 단 채 재로그인하는 흐름이기 때문이다.
     */
    @PostMapping("/oauth/{provider}")
    public ResponseEntity<TokenResponse> oauthLogin(@PathVariable String provider,
                                                    @Valid @RequestBody OAuthLoginRequest request) {
        return ResponseEntity.ok(authService.oauthLogin(provider, request));
    }

    @PostMapping("/reissue")
    public ResponseEntity<TokenResponse> reissue(@Valid @RequestBody ReissueRequest request) {
        return ResponseEntity.ok(authService.reissue(request.refreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthUser(required = true) Long userId,
                                       @Valid @RequestBody LogoutRequest request) {
        authService.logout(userId, request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    /**
     * 재설정 메일 요청 (S-J). <b>계정 존재 여부·가입 방식과 무관하게 항상 200</b>이다 —
     * 응답을 구분하면 "이 이메일은 가입돼 있다" 또는 "이 주소는 소셜로 가입됐다"가 새어 나간다.
     * 메일은 조건에 맞을 때만 실제로 나간다.
     */
    @PostMapping("/password-reset/request")
    public ResponseEntity<Void> requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
        passwordResetService.requestReset(request.email());
        return ResponseEntity.ok().build();
    }

    /**
     * 딥링크로 받은 토큰의 사전 검증 (S-9 F-3). 토큰을 소비하지 않는다.
     * 없으면 사용자가 새 비밀번호를 다 입력한 뒤에야 만료를 알게 된다.
     */
    @PostMapping("/password-reset/verify")
    public ResponseEntity<Void> verifyPasswordResetToken(@Valid @RequestBody PasswordResetVerifyRequest request) {
        passwordResetService.verifyToken(request.token());
        return ResponseEntity.ok().build();
    }

    /**
     * 재설정 확정. <b>토큰을 발급하지 않는다</b>(S-9 F-4) — 클라이언트는 로그인 화면으로 보낸다.
     * 자동 로그인은 "전체 세션 폐기" 결정과 모순된다.
     */
    @PostMapping("/password-reset/confirm")
    public ResponseEntity<Void> confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        passwordResetService.confirmReset(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
