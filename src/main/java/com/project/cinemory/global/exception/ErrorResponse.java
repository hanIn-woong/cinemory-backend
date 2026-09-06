package com.project.cinemory.global.exception;

import org.springframework.http.HttpStatus;

import java.util.List;

public record ErrorResponse(int status, String code, String message, List<FieldError> errors) {

    public record FieldError(String field, String reason) {}

    public static ErrorResponse from(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.getStatus().value(), errorCode.name(), errorCode.getMessage(), List.of());
    }

    public static ErrorResponse of(HttpStatus status, String message) {
        return new ErrorResponse(status.value(), status.name(), message, List.of());
    }

    public static ErrorResponse of(ErrorCode errorCode, List<FieldError> errors) {
        return new ErrorResponse(errorCode.getStatus().value(), errorCode.name(), errorCode.getMessage(), errors);
    }
}