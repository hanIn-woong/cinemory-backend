package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.recommendation.config.RecommendProperties;
import com.project.cinemory.domain.recommendation.index.MovieFeature;
import com.project.cinemory.domain.recommendation.service.ProfileCalculator.EvaluationScores;
import com.project.cinemory.domain.recommendation.support.RecommendFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 선호 프로필 계산(S-1-1 · W-1)의 순수 로직 — DB 없이 입력만으로 검증한다. */
class ProfileCalculatorTest {

    private static final RecommendProperties.Wish WISH = RecommendFixtures.properties().wish();   // λ 0.3, α 5, 감독 2편

    private static final long DRAMA = 1, HORROR = 2, THRILLER = 3;
    private static final long KR = 10, US = 11;

    private static UserPreferenceProfile calc(EvaluationScores eval, List<MovieFeature> wished,
                                              Map<Long, Double> genreShare, Map<Long, Double> countryShare) {
        return ProfileCalculator.calculate(eval, wished, genreShare, countryShare, Set.of(), Set.of(), WISH);
    }

    private static EvaluationScores eval(Map<Long, Double> genre, Map<Long, Double> country,
                                         Map<Long, Double> director, Map<Long, Double> actor) {
        return new EvaluationScores(7.0, genre, country, director, actor);
    }

    // ── 평가 (S-1-1) ───────────────────────────────────────────────

    @Test
    void 평균_이하인_축은_0이라_프로필에서_빠진다() {
        // 쿼리는 양수 합만 돌려주지만 계산부도 0 이하를 버린다 — 감점하지 않는다.
        Map<Long, Double> normalized = ProfileCalculator.normalize(Map.of(1L, 4.0, 2L, 0.0, 3L, -2.5));

        assertThat(normalized).containsOnlyKeys(1L);
    }

    @Test
    void 정규화하면_축의_최댓값이_1이다() {
        Map<Long, Double> normalized = ProfileCalculator.normalize(Map.of(1L, 4.0, 2L, 2.0, 3L, 1.0));

        assertThat(java.util.Collections.max(normalized.values())).isEqualTo(1.0);
        assertThat(normalized.get(2L)).isEqualTo(0.5);
        assertThat(normalized.get(3L)).isEqualTo(0.25);
    }

    @Test
    void 기록이_10편이든_500편이든_내_안의_1등은_1_0이다() {
        // 스케일이 달라도 같은 모양이면 같은 정규화 값
        assertThat(ProfileCalculator.normalize(Map.of(1L, 3.0, 2L, 1.5)))
                .isEqualTo(ProfileCalculator.normalize(Map.of(1L, 300.0, 2L, 150.0)));
    }

    @Test
    void 최댓값이_0이면_축_전체가_비어_있다() {
        assertThat(ProfileCalculator.normalize(Map.of(1L, 0.0, 2L, -1.0))).isEmpty();
        assertThat(ProfileCalculator.normalize(Map.of())).isEmpty();
    }

    @Test
    void 평가_기록만_있으면_평가_값이_그대로_결합값이다_배우는_평가_전용() {
        UserPreferenceProfile profile = calc(
                eval(Map.of(DRAMA, 4.0, HORROR, 2.0), Map.of(KR, 3.0), Map.of(100L, 5.0), Map.of(200L, 2.0, 201L, 1.0)),
                List.of(), Map.of(), Map.of());

        assertThat(profile.genre().value(DRAMA)).isEqualTo(1.0 * 0.7);       // (1−λ)·E, 관심 0
        assertThat(profile.genre().lambda()).isEqualTo(0.3);
        assertThat(profile.actor()).containsEntry(200L, 1.0).containsEntry(201L, 0.5);
        assertThat(profile.personalized()).isTrue();
        assertThat(profile.meanRating()).isEqualTo(7.0);
    }

    // ── 찜 (W-1) ───────────────────────────────────────────────────

    @Test
    void 찜만_있는_사용자는_평가_축이_비어_람다가_1이다() {
        // 카탈로그: 드라마 40% / 공포 8%.  찜 4편이 전부 공포 → 공포에 강한 관심
        List<MovieFeature> wished = List.of(
                RecommendFixtures.movie(1).genres(HORROR, 1000).build(),
                RecommendFixtures.movie(2).genres(HORROR, 1000).build(),
                RecommendFixtures.movie(3).genres(HORROR, 1000).build(),
                RecommendFixtures.movie(4).genres(HORROR, 1000).build());

        UserPreferenceProfile profile = calc(EvaluationScores.none(), wished,
                Map.of(DRAMA, 0.40, HORROR, 0.08), Map.of());

        assertThat(profile.genre().lambda()).isEqualTo(1.0);
        assertThat(profile.genre().evaluation()).isEmpty();
        assertThat(profile.genre().value(HORROR)).isEqualTo(1.0);     // 관심 정규화 최댓값 × λ(=1)
        assertThat(profile.genre().value(DRAMA)).isZero();
        assertThat(profile.personalized()).isTrue();
    }

    @Test
    void 평가와_관심이_둘_다_있으면_7대3으로_섞인다() {
        List<MovieFeature> wished = List.of(
                RecommendFixtures.movie(1).genres(HORROR, 1000).build(),
                RecommendFixtures.movie(2).genres(HORROR, 1000).build());

        UserPreferenceProfile profile = calc(
                eval(Map.of(DRAMA, 4.0, HORROR, 1.0), Map.of(), Map.of(), Map.of()),
                wished, Map.of(DRAMA, 0.40, HORROR, 0.08), Map.of());

        // 공포: 평가 0.25(=1/4), 관심 1.0 → 0.7·0.25 + 0.3·1.0
        assertThat(profile.genre().value(HORROR)).isCloseTo(0.7 * 0.25 + 0.3 * 1.0, within(1e-12));
        // 드라마: 평가 1.0, 관심 0 → 0.7
        assertThat(profile.genre().value(DRAMA)).isCloseTo(0.7, within(1e-12));
        assertThat(profile.genre().lambda()).isEqualTo(0.3);
    }

    @Test
    void 평가가_낮은_장르도_찜이_많으면_관심만큼_살아난다() {
        List<MovieFeature> wished = List.of(
                RecommendFixtures.movie(1).genres(HORROR, 1000).build(),
                RecommendFixtures.movie(2).genres(HORROR, 1000).build());

        UserPreferenceProfile profile = calc(
                eval(Map.of(DRAMA, 4.0), Map.of(), Map.of(), Map.of()),    // 공포는 평가에서 평균 이하(키 없음)
                wished, Map.of(DRAMA, 0.40, HORROR, 0.08), Map.of());

        assertThat(profile.genre().value(HORROR)).isCloseTo(0.3, within(1e-12));
    }

    @Test
    void 찜이_카탈로그_비율과_같은_드라마는_lift가_약_0이다() {
        // 카탈로그 드라마 40%. 찜 10편 중 4편만 드라마(= 40%) → share = (4 + 5·0.4) / (10 + 5) = 0.4 → lift 0
        List<MovieFeature> wished = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            wished.add(RecommendFixtures.movie(i).genres(DRAMA, 1000).build());
        }
        for (int i = 4; i < 10; i++) {
            wished.add(RecommendFixtures.movie(i).genres(THRILLER, 1000).build());
        }

        Map<Long, Double> interest = ProfileCalculator.lift(wished, MovieFeature::genreIds, MovieFeature::genreWeights,
                Map.of(DRAMA, 0.40, THRILLER, 0.10), WISH);

        assertThat(interest).doesNotContainKey(DRAMA);              // lift 0 → 관심 아님
        assertThat(interest.get(THRILLER)).isEqualTo(1.0);          // 카탈로그 10%인데 찜 60%
    }

    @Test
    void 스무딩이_찜_몇_개로_희귀_항목이_튀는_것을_막는다() {
        // 카탈로그 비율 0.1%인 국가를 1편 찜. 스무딩 없이는 lift = (1/2)/0.001 − 1 = 499, 스무딩(α=5)이면 훨씬 작다.
        List<MovieFeature> wished = List.of(
                RecommendFixtures.movie(1).countries(US, 1000).build(),
                RecommendFixtures.movie(2).countries(KR, 1000).build());
        Map<Long, Double> share = Map.of(KR, 0.5, US, 0.001);

        double unsmoothed = (1.0 / 2) / 0.001 - 1;
        // 스무딩 후 내부 lift(정규화 전)를 직접 계산해 정규화 전 값이 줄었는지 확인
        double smoothedShare = (1.0 + 5 * 0.001) / (2 + 5);
        double smoothed = smoothedShare / 0.001 - 1;

        assertThat(smoothed).isLessThan(unsmoothed);
        assertThat(smoothed).isCloseTo(142.857 - 1 + 0.0, within(1.0));
        // KR(카탈로그 50%)은 찜이 50%라 관심 아님 → US만 남아 정규화 1.0
        assertThat(ProfileCalculator.lift(wished, MovieFeature::countryIds, MovieFeature::countryWeights, share, WISH))
                .containsOnlyKeys(US).containsEntry(US, 1.0);
    }

    @Test
    void 카탈로그_비율을_모르는_항목은_건너뛴다() {
        List<MovieFeature> wished = List.of(RecommendFixtures.movie(1).genres(HORROR, 1000).build());

        assertThat(ProfileCalculator.lift(wished, MovieFeature::genreIds, MovieFeature::genreWeights, Map.of(), WISH)).isEmpty();
    }

    @Test
    void 감독을_1편만_찜하면_무시한다() {
        List<MovieFeature> wished = List.of(RecommendFixtures.movie(1).directors(100L).build());

        assertThat(ProfileCalculator.repeatedDirectors(wished, 2)).isEmpty();
    }

    @Test
    void 같은_감독을_2편_이상_찜하면_공동_연출을_1_N으로_나눠_반영한다() {
        List<MovieFeature> wished = List.of(
                RecommendFixtures.movie(1).directors(100L).build(),
                RecommendFixtures.movie(2).directors(100L, 101L).build(),     // 100은 0.5만, 101은 1편뿐이라 제외
                RecommendFixtures.movie(3).directors(102L).build(),
                RecommendFixtures.movie(4).directors(102L).build(),
                RecommendFixtures.movie(5).directors(102L).build());

        Map<Long, Double> interest = ProfileCalculator.repeatedDirectors(wished, 2);

        // 100: 1 + 0.5 = 1.5 (2편), 102: 3.0 (3편) → 정규화 100 = 0.5, 102 = 1.0
        assertThat(interest).containsOnlyKeys(100L, 102L);
        assertThat(interest.get(102L)).isEqualTo(1.0);
        assertThat(interest.get(100L)).isEqualTo(0.5);
    }

    @Test
    void 전_축이_비어_있으면_personalized는_false다() {
        UserPreferenceProfile profile = calc(EvaluationScores.none(), List.of(), Map.of(), Map.of());

        assertThat(profile.personalized()).isFalse();
        assertThat(profile.genre().isEmpty()).isTrue();
    }

    @Test
    void 모든_별점이_같으면_양수_합이_없어_콜드_스타트와_같다() {
        // 별점이 전부 평균과 같으면 (별점 − m) = 0 → 쿼리가 빈 결과를 돌려준다
        UserPreferenceProfile profile = calc(eval(Map.of(), Map.of(), Map.of(), Map.of()), List.of(), Map.of(), Map.of());

        assertThat(profile.personalized()).isFalse();
    }
}
