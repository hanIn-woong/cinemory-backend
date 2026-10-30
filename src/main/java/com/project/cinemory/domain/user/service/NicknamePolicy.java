package com.project.cinemory.domain.user.service;

import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.exception.ErrorResponse;

import java.util.List;
import java.util.Locale;

/**
 * 닉네임 규칙 (profile-spec PR-6) — 로컬 가입과 닉네임 변경이 같은 규칙을 쓴다.
 * 앞뒤 공백을 제거한 뒤 2~20자(코드포인트), 공백만 불가, 제어 문자 불가, 예약어 포함 불가.
 *
 * <p>길이는 Bean Validation이 아니라 여기서만 판정한다 — 어노테이션 한도와 이 규칙이 따로 놀아 가입 50자/변경 30자처럼
 * 어긋나는 것을 막기 위해서다. 중복은 허용한다(PR-7) — 고유 제약도 409도 없다. 운영자 사칭을 막는 장치는 예약어가 유일하다.
 * 위반은 {@code INVALID_INPUT_VALUE} + {@code errors[]}(field {@code nickname})로, {@code @Valid} 실패와 같은 응답 모양이다.
 */
public final class NicknamePolicy {

    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 20;

    private static final String FIELD = "nickname";
    private static final List<String> RESERVED_WORDS = List.of("관리자", "운영자", "cinemory", "씨네모리", "admin");

    private NicknamePolicy() {
    }

    /** 규칙을 통과한 정규화값(앞뒤 공백 제거)을 돌려준다. 위반이면 {@link BusinessException}. */
    public static String validate(String raw) {
        String nickname = raw == null ? "" : raw.strip();
        if (nickname.isEmpty()) {
            throw invalid("닉네임은 공백만으로 이루어질 수 없습니다.");
        }
        int length = nickname.codePointCount(0, nickname.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw invalid("닉네임은 " + MIN_LENGTH + "자 이상 " + MAX_LENGTH + "자 이하여야 합니다.");
        }
        if (nickname.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("닉네임에 사용할 수 없는 문자가 포함되어 있습니다.");
        }
        String lower = nickname.toLowerCase(Locale.ROOT);
        if (RESERVED_WORDS.stream().anyMatch(lower::contains)) {
            throw invalid("사용할 수 없는 단어가 포함되어 있습니다.");
        }
        return nickname;
    }

    private static BusinessException invalid(String reason) {
        return new BusinessException(ErrorCode.INVALID_INPUT_VALUE, List.of(new ErrorResponse.FieldError(FIELD, reason)));
    }
}
