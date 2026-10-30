package com.project.cinemory.domain.user.service;

import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.exception.ErrorResponse;

import java.util.List;

/**
 * 자기소개 규칙 (profile-spec PR-2) — 앞뒤 공백 제거, 빈 문자열은 {@code null}, 최대 150자(코드포인트), 줄바꿈 2개까지(헤더에 2~3줄).
 * 위반은 {@code NicknamePolicy}와 같은 모양: 400 {@code INVALID_INPUT_VALUE} + {@code errors[]}(field {@code bio}).
 */
public final class BioPolicy {

    public static final int MAX_LENGTH = 150;
    public static final int MAX_NEWLINES = 2;

    private static final String FIELD = "bio";

    private BioPolicy() {
    }

    /** 정규화한 자기소개(없으면 {@code null}). 위반이면 {@link BusinessException}. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String bio = raw.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (bio.isEmpty()) {
            return null;
        }
        if (bio.codePointCount(0, bio.length()) > MAX_LENGTH) {
            throw invalid("자기소개는 " + MAX_LENGTH + "자 이하여야 합니다.");
        }
        if (bio.chars().filter(c -> c == '\n').count() > MAX_NEWLINES) {
            throw invalid("자기소개의 줄바꿈은 " + MAX_NEWLINES + "번까지 쓸 수 있습니다.");
        }
        return bio;
    }

    private static BusinessException invalid(String reason) {
        return new BusinessException(ErrorCode.INVALID_INPUT_VALUE, List.of(new ErrorResponse.FieldError(FIELD, reason)));
    }
}
