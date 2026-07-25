package com.project.cinemory.global.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    MOVIE_NOT_FOUND(HttpStatus.NOT_FOUND, "영화를 찾을 수 없습니다."),
    WATCH_RECORD_NOT_FOUND(HttpStatus.NOT_FOUND, "시청 기록을 찾을 수 없습니다."),
    WATCH_RECORD_ACCESS_DENIED(HttpStatus.FORBIDDEN, "해당 시청 기록에 접근할 권한이 없습니다."),
    INVALID_WATCH_TYPE_OTT_COMBINATION(HttpStatus.BAD_REQUEST, "관람 방식과 OTT 플랫폼 조합이 올바르지 않습니다."),
    OTT_PLATFORM_NOT_FOUND(HttpStatus.NOT_FOUND, "OTT 플랫폼을 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }
}
