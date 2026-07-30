package com.project.cinemory.global.security.handler;

import com.project.cinemory.global.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 인증은 됐으나 권한이 모자란 요청에 대한 403 응답.
 *
 * <p>실제로 타는 경로는 <b>일반 유저의 {@code /api/admin/**} 호출이 사실상 유일</b>하다.
 * 익명 요청은 여기가 아니라 {@code JwtAuthenticationEntryPoint}(401)로 간다.
 */
@Component
@RequiredArgsConstructor
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityErrorResponseWriter responseWriter;

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {

        responseWriter.write(response, ErrorCode.ACCESS_DENIED);
    }
}
