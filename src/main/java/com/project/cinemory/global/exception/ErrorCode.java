package com.project.cinemory.global.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    // Step5 5-1 — 비밀번호 변경. 소셜 계정은 chk_user_auth_method(로컬 XOR 소셜)상 비밀번호가 없다.
    INVALID_AUTH_METHOD(HttpStatus.BAD_REQUEST, "소셜 로그인 계정은 비밀번호를 사용할 수 없습니다."),
    MOVIE_NOT_FOUND(HttpStatus.NOT_FOUND, "영화를 찾을 수 없습니다."),
    WATCH_RECORD_NOT_FOUND(HttpStatus.NOT_FOUND, "시청 기록을 찾을 수 없습니다."),
    WATCH_RECORD_ACCESS_DENIED(HttpStatus.FORBIDDEN, "해당 시청 기록에 접근할 권한이 없습니다."),
    INVALID_WATCH_TYPE_OTT_COMBINATION(HttpStatus.BAD_REQUEST, "관람 방식과 OTT 플랫폼 조합이 올바르지 않습니다."),
    OTT_PLATFORM_NOT_FOUND(HttpStatus.NOT_FOUND, "OTT 플랫폼을 찾을 수 없습니다."),
    REVIEW_NOT_FOUND(HttpStatus.NOT_FOUND, "리뷰를 찾을 수 없습니다."),
    COLLECTION_NOT_FOUND(HttpStatus.NOT_FOUND, "컬렉션을 찾을 수 없습니다."),
    COLLECTION_ACCESS_DENIED(HttpStatus.FORBIDDEN, "해당 컬렉션에 접근할 권한이 없습니다."),
    COLLECTION_MOVIE_NOT_FOUND(HttpStatus.NOT_FOUND, "컬렉션에 담긴 영화를 찾을 수 없습니다."),

    // 4-6 Follow / Comment / 공개범위 접근 제어
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    CANNOT_FOLLOW_SELF(HttpStatus.BAD_REQUEST, "자기 자신을 팔로우할 수 없습니다."),
    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "댓글을 찾을 수 없습니다."),
    NO_COMMENT_PERMISSION(HttpStatus.FORBIDDEN, "댓글에 대한 권한이 없습니다."),
    UNSUPPORTED_COMMENT_TARGET(HttpStatus.BAD_REQUEST, "지원하지 않는 댓글 대상입니다."),
    DUPLICATE_REQUEST(HttpStatus.CONFLICT, "이미 처리된 요청이거나 데이터 제약 조건을 위반했습니다."),

    // 4-7 Theater / BoxOfficeRecord
    THEATER_NOT_FOUND(HttpStatus.NOT_FOUND, "극장을 찾을 수 없습니다."),
    INVALID_SEARCH_RADIUS(HttpStatus.BAD_REQUEST, "검색 반경이 허용 범위를 벗어났습니다."),
    BOX_OFFICE_NOT_FOUND(HttpStatus.NOT_FOUND, "박스오피스 데이터를 찾을 수 없습니다."),
    EXTERNAL_API_ERROR(HttpStatus.BAD_GATEWAY, "외부 API 호출에 실패했습니다."),

    // Step S 인증/인가
    // 이메일 미존재·비밀번호 불일치·소셜 계정의 로컬 로그인 시도를 전부 같은 코드로 응답한다.
    // 구분하면 "이 이메일이 가입돼 있다" 또는 "카카오로 가입돼 있다"는 정보가 유출된다.
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    // TOKEN_EXPIRED를 INVALID_TOKEN과 분리하는 이유: 클라이언트가 "재발급할 상황"과
    // "로그인 화면으로 보낼 상황"을 구분해야 한다. 합치면 재발급 실패 루프에 빠진다.
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
    REFRESH_TOKEN_NOT_FOUND(HttpStatus.UNAUTHORIZED, "리프레시 토큰을 찾을 수 없습니다."),
    REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED, "이미 폐기된 리프레시 토큰입니다. 다시 로그인해 주세요."),
    INVALID_OAUTH_TOKEN(HttpStatus.UNAUTHORIZED, "소셜 로그인 토큰 검증에 실패했습니다."),
    UNSUPPORTED_OAUTH_PROVIDER(HttpStatus.BAD_REQUEST, "지원하지 않는 소셜 로그인 제공자입니다."),
    // 로그인과 달리 여기는 본인이 자기 계정으로 들어오려는 상황이라 명시적으로 알려준다.
    EMAIL_ALREADY_REGISTERED_LOCALLY(HttpStatus.CONFLICT, "해당 이메일은 이미 일반 회원가입으로 등록되어 있습니다."),
    OAUTH_EMAIL_NOT_PROVIDED(HttpStatus.BAD_REQUEST, "소셜 계정에서 이메일 정보를 제공받지 못했습니다."),

    // S-G — 소셜 로그인 nonce (재전송 방지)
    // INVALID_OAUTH_TOKEN과 분리하는 이유: nonce 만료는 "nonce를 다시 받아 재시도할 상황"이고
    // ID 토큰 검증 실패는 "로그인 자체가 실패한 상황"이라 클라이언트 분기가 다르다.
    INVALID_NONCE(HttpStatus.UNAUTHORIZED, "인증 요청이 만료되었습니다. 다시 시도해 주세요."),

    // S-J — 비밀번호 재설정
    // 미존재 / 만료 / 이미 사용됨을 구분하지 않는다. 구분하면 "이 토큰은 존재했다"는 정보가
    // 새어 토큰 탐색에 쓰이고, 사용자 입장에서는 셋 다 "이 링크는 더 못 쓴다"로 같다.
    INVALID_RESET_TOKEN(HttpStatus.BAD_REQUEST, "유효하지 않거나 만료된 재설정 링크입니다."),

    // Step S — 입력값 검증(@Valid)
    // Step5 5-0-C에서 MethodArgumentNotValidException 전용임을 명확히 하기 위해 INVALID_INPUT_VALUE로 개명
    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),

    // Step5 5-0-C — MVC 예외/404/405 포맷 통일
    INVALID_TYPE_VALUE(HttpStatus.BAD_REQUEST, "요청 값의 타입이 올바르지 않습니다."),
    MALFORMED_REQUEST_BODY(HttpStatus.BAD_REQUEST, "요청 본문을 읽을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    ENDPOINT_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."),

    // 5-6-C ① — GlobalExceptionHandler의 ResponseEntityExceptionHandler 상속 전환으로 새로 덮이는 예외
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 Content-Type입니다."),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "지원하지 않는 Accept 헤더입니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }
}
