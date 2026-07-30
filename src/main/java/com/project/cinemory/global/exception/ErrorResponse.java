package com.project.cinemory.global.exception;

import org.springframework.http.HttpStatus;

public record ErrorResponse(String code, String message) {

    public static ErrorResponse from(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage());
    }

    public static ErrorResponse of(HttpStatus status, String message) {
        return new ErrorResponse(status.name(), message);
    }
}
