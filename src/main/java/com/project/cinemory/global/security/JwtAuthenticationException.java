package com.project.cinemory.global.security;

import com.project.cinemory.global.exception.ErrorCode;
import lombok.Getter;
import org.springframework.security.core.AuthenticationException;

/**
 * 필터 단계에서 판별한 인증 실패 사유({@link ErrorCode})를 {@code AuthenticationEntryPoint}까지
 * 전달하기 위한 예외.
 *
 * <p>필터가 던진 예외는 {@code ExceptionTranslationFilter}가 잡지 못한다 — 그 필터는 체인
 * <b>뒤쪽</b>에 있어 자기보다 하류에서 발생한 예외만 처리하기 때문이다. 따라서 이 예외는
 * "던져서 전파되는" 용도가 아니라, 필터가 {@code EntryPoint.commence()}를 <b>직접 호출할 때
 * 사유를 실어 보내는 전달자</b>로만 쓰인다.
 *
 * <p>{@code BusinessException}을 그대로 쓰지 않는 이유는 {@code commence()}의 시그니처가
 * {@code AuthenticationException}을 요구하기 때문이다.
 */
@Getter
public class JwtAuthenticationException extends AuthenticationException {

    private final ErrorCode errorCode;

    public JwtAuthenticationException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
