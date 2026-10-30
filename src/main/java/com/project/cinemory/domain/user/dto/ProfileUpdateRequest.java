package com.project.cinemory.domain.user.dto;

import com.project.cinemory.domain.user.entity.PrivacySetting;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 프로필 통합 저장 — 전체 치환 (profile-spec PR-1 · 3-1). 부분 수정의 "null = 지움/안 바꿈" 모호성이 없도록 모든 필드를 보낸다.
 *
 * <p>길이·문자·예약어(닉네임), 길이·줄 수(자기소개), 개수·중복(인생 영화)은 어노테이션이 아니라 서비스가 판정한다 —
 * 닉네임은 "바뀌었을 때만" 규칙을 적용해야 하는데 Bean Validation은 현재 값을 모른다(PT-5).
 *
 * @param bio           없거나 비면 자기소개 없음
 * @param coverMovieId  {@code null}이면 <b>영화 커버만</b> 해제한다 — 업로드 커버는 이 API로 건드리지 않는다(cover-image DELETE로)
 */
public record ProfileUpdateRequest(

        @NotBlank(message = "닉네임은 필수입니다.")
        String nickname,

        String bio,

        @NotNull(message = "공개 범위는 필수입니다.")
        PrivacySetting privacySetting,

        @NotNull(message = "인생 영화 목록은 필수입니다. 없으면 빈 배열을 보내세요.")
        List<Long> favoriteMovieIds,

        Long coverMovieId
) {
}
