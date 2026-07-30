package com.project.cinemory.global.security.handler;

import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * 필터 단계 인증/인가 실패 응답을 {@code GlobalExceptionHandler}와 동일한 포맷으로 쓴다.
 *
 * <p>{@code AuthenticationEntryPoint}와 {@code AccessDeniedHandler}가 같은 로직을 각자
 * 들고 있으면 응답 포맷이 갈라지므로 한 곳으로 모았다.
 *
 * <p><b>Jackson 주의</b> — Spring Boot 4는 Jackson 3을 기본으로 쓰며
 * {@link JsonMapper}({@code tools.jackson})를 {@code @Primary}로 자동 구성한다.
 * {@code com.fasterxml.jackson.databind.ObjectMapper}를 임포트해 주입하면 해당 타입의 빈이 없어
 * <b>기동이 실패</b>한다. {@code new}로 직접 만드는 것도 금지 — 자동 구성본과 설정이 갈라진다.
 *
 * <p><b>charset을 명시하는 이유</b> — {@code ErrorCode}의 메시지가 한글이라
 * 생략하면 클라이언트에 따라 깨져서 도착한다.
 */
@Component
@RequiredArgsConstructor
public class SecurityErrorResponseWriter {

    private static final String CONTENT_TYPE_JSON_UTF8 = "application/json;charset=UTF-8";

    private final JsonMapper jsonMapper;

    public void write(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getStatus().value());
        response.setContentType(CONTENT_TYPE_JSON_UTF8);
        response.getWriter().write(jsonMapper.writeValueAsString(ErrorResponse.from(errorCode)));
    }
}
