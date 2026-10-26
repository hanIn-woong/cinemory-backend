package com.project.cinemory.global.exception;

import lombok.Getter;

/** 호출 제한 초과 (429). {@code GlobalExceptionHandler}가 {@code Retry-After} 헤더(초)를 더해 응답한다. */
@Getter
public class RateLimitedException extends BusinessException {

    private final long retryAfterSeconds;

    public RateLimitedException(ErrorCode errorCode, long retryAfterSeconds) {
        super(errorCode);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }
}
