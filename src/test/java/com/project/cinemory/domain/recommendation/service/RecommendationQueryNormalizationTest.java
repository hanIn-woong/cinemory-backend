package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.text.Normalizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 5-14-B — 앞뒤 공백 제거 · 연속 공백 1개 · 영문 소문자 · NFC, 정규화 후 2~100자. */
class RecommendationQueryNormalizationTest {

    @Test
    void 공백과_대소문자와_유니코드가_정규화된다() {
        assertThat(RecommendationPageService.normalizeQuery("   Rainy   DAY  영화 ")).isEqualTo("rainy day 영화");
    }

    @Test
    void NFD로_들어온_한글은_NFC로_합쳐진다() {
        String nfd = Normalizer.normalize("잔잔한 영화", Normalizer.Form.NFD);

        assertThat(RecommendationPageService.normalizeQuery(nfd)).isEqualTo("잔잔한 영화");
    }

    @Test
    void 정규화_후_1자면_거부한다_공백을_걷어낸_뒤_길이로_본다() {
        assertThatThrownBy(() -> RecommendationPageService.normalizeQuery("  a  "))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_RECOMMENDATION_QUERY));
        assertThatThrownBy(() -> RecommendationPageService.normalizeQuery("     "))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> RecommendationPageService.normalizeQuery(""))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 경계_2자와_100자는_통과하고_101자는_거부한다() {
        assertThat(RecommendationPageService.normalizeQuery("ab")).isEqualTo("ab");
        assertThat(RecommendationPageService.normalizeQuery("가".repeat(100))).hasSize(100);
        assertThatThrownBy(() -> RecommendationPageService.normalizeQuery("가".repeat(101)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_RECOMMENDATION_QUERY));
    }

    @Test
    void 이모지도_코드포인트_하나로_센다() {
        assertThat(RecommendationPageService.normalizeQuery("😀".repeat(50))).isNotEmpty();
        assertThatThrownBy(() -> RecommendationPageService.normalizeQuery("😀".repeat(101))).isInstanceOf(BusinessException.class);
    }
}
