package com.project.cinemory.global.exception;

import lombok.Getter;

import java.util.List;

@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    /** 필드 단위 사유. 비어 있으면 일반 응답과 같다 — {@code @Valid} 실패 응답({@code errors[]})과 같은 모양을 서비스 검증도 낼 수 있게 한다. */
    private final List<ErrorResponse.FieldError> fieldErrors;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.fieldErrors = List.of();
    }

    public BusinessException(ErrorCode errorCode, String customMessage) {
        super(customMessage);
        this.errorCode = errorCode;
        this.fieldErrors = List.of();
    }

    public BusinessException(ErrorCode errorCode, List<ErrorResponse.FieldError> fieldErrors) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.fieldErrors = List.copyOf(fieldErrors);
    }
}
