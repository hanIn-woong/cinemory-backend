package com.project.cinemory.domain.watch.dto;

import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;

/**
 * "내 영화" 목록의 별점 필터 — 대표 기록의 {@code rating}(1~10 정수, 반 별 ×2) 기준.
 * 5-0-B의 {@code @RequestParam} Bean Validation 미도입 원칙에 따라 검증은 여기서 한다.
 * <p>
 * 하나만 오면 나머지는 끝값(1·10)으로 채운다. 아무것도 없으면 {@link #isEmpty()} — 기존과 동일(필터 없음).
 * {@code unrated}는 {@code rating IS NULL}만이라 {@code min}/{@code max}와 같이 쓸 수 없다.
 */
public record RecordRatingFilter(Integer min, Integer max, boolean unrated) {

    private static final int RATING_MIN = 1;
    private static final int RATING_MAX = 10;

    public static final RecordRatingFilter NONE = new RecordRatingFilter(null, null, false);

    public static RecordRatingFilter of(Integer min, Integer max, boolean unrated) {
        if (unrated) {
            if (min != null || max != null) {
                throw new BusinessException(ErrorCode.INVALID_RATING_FILTER);
            }
            return new RecordRatingFilter(null, null, true);
        }
        if (min == null && max == null) {
            return NONE;
        }
        int lo = min != null ? min : RATING_MIN;
        int hi = max != null ? max : RATING_MAX;
        if (lo < RATING_MIN || hi > RATING_MAX || lo > hi) {
            throw new BusinessException(ErrorCode.INVALID_RATING_FILTER);
        }
        return new RecordRatingFilter(lo, hi, false);
    }

    public boolean isEmpty() {
        return !unrated && min == null;
    }
}
