package com.project.cinemory.global.security.handler;

import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.security.JwtAuthenticationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 인증되지 않은 요청에 대한 401 응답. 두 경로로 진입한다.
 *
 * <ul>
 *   <li>{@code JwtAuthenticationFilter}가 <b>직접 호출</b> — 토큰이 있으나 무효·만료인 경우.
 *       사유({@code TOKEN_EXPIRED} / {@code INVALID_TOKEN})가 담겨 온다</li>
 *   <li>Spring Security가 호출 — {@code authenticated()} 경로에 토큰 없이 접근한 경우.
 *       이때는 사유가 없으므로 {@code UNAUTHORIZED}로 응답한다</li>
 * </ul>
 *
 * <p>익명 요청은 {@code AccessDeniedException}이 아니라 이쪽으로 온다.
 * Spring Security가 "권한이 없다"가 아니라 "로그인부터 하라"로 해석하기 때문이다.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final SecurityErrorResponseWriter responseWriter;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        ErrorCode errorCode = (authException instanceof JwtAuthenticationException jwtException)
                ? jwtException.getErrorCode()
                : ErrorCode.UNAUTHORIZED;

        responseWriter.write(response, errorCode);
    }
}
