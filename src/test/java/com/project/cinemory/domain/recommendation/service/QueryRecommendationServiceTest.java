package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.recommendation.dto.RecommendReasonResponse;
import com.project.cinemory.domain.recommendation.index.MovieFeature;
import com.project.cinemory.domain.recommendation.index.MovieFeatureIndex;
import com.project.cinemory.domain.recommendation.index.MovieVectorIndex;
import com.project.cinemory.domain.recommendation.index.MovieVectorIndex.Hit;
import com.project.cinemory.domain.recommendation.service.QueryConstraintExtractor.QueryConstraints;
import com.project.cinemory.domain.recommendation.service.RecommendationResult.RankedMovie;
import com.project.cinemory.domain.recommendation.service.RecommendationScorer.ReasonChoice;
import com.project.cinemory.domain.recommendation.support.RecommendFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 자연어 추천 — 후보 → 하드 필터 → 후보 안 min-max → 재정렬 (S-1-4). 벡터 검색·특징 인덱스는 가짜. */
class QueryRecommendationServiceTest {

    private static final float[] QUERY = new float[768];

    private MovieVectorIndex vectorIndex;
    private MovieFeatureIndex featureIndex;
    private QueryRecommendationService service;
    private final QueryConstraintExtractor extractor = new QueryConstraintExtractor(RecommendFixtures.properties());

    @BeforeEach
    void setUp() {
        vectorIndex = mock(MovieVectorIndex.class);
        featureIndex = mock(MovieFeatureIndex.class);
        ReasonTextResolver resolver = mock(ReasonTextResolver.class);
        when(resolver.resolve(anyList())).thenAnswer(inv -> {
            List<ReasonChoice> choices = inv.getArgument(0);
            List<RecommendReasonResponse> responses = new ArrayList<>();
            for (ReasonChoice c : choices) {
                responses.add(c == null ? null : new RecommendReasonResponse(c.type(), c.targetId(), null, null));
            }
            return responses;
        });
        var properties = RecommendFixtures.properties();
        service = new QueryRecommendationService(vectorIndex, featureIndex, mock(UserPreferenceProfileService.class),
                extractor, new RecommendationScorer(properties), resolver, properties);
    }

    private void catalog(MovieFeature... movies) {
        when(featureIndex.snapshot()).thenReturn(RecommendFixtures.snapshot(List.of(movies), Map.of(), Map.of()));
    }

    private void hits(Hit... hits) {
        when(vectorIndex.search(any(float[].class), anyInt())).thenReturn(List.of(hits));
    }

    private RecommendationResult run(UserPreferenceProfile profile, String query) {
        return service.recommend(profile, extractor.extract(query), QUERY);
    }

    private static List<Long> ids(RecommendationResult r) {
        return r.movies().stream().map(RankedMovie::movieId).toList();
    }

    @Test
    void 후보는_설정한_K로_검색한다() {
        catalog();
        hits();

        run(UserPreferenceProfile.empty(), "아무 질의");

        verify(vectorIndex).search(any(float[].class), eq(200));
    }

    @Test
    void sim은_필터를_통과한_후보_안에서_min_max_정규화된다() {
        catalog(RecommendFixtures.movie(1).build(), RecommendFixtures.movie(2).build(), RecommendFixtures.movie(3).build());
        hits(new Hit(1, 0.80f), new Hit(2, 0.70f), new Hit(3, 0.60f));

        RecommendationResult result = run(UserPreferenceProfile.empty(), "질의");

        assertThat(ids(result)).containsExactly(1L, 2L, 3L);
        assertThat(result.movies().get(0).breakdown().sim()).isEqualTo(1.0);
        assertThat(result.movies().get(1).breakdown().sim()).isCloseTo(0.5, within(1e-6));
        assertThat(result.movies().get(2).breakdown().sim()).isEqualTo(0.0);
    }

    @Test
    void 코사인의_절대_수준이_달라도_상대화되어_같은_점수가_나온다() {
        catalog(RecommendFixtures.movie(1).build(), RecommendFixtures.movie(2).build());
        hits(new Hit(1, 0.30f), new Hit(2, 0.20f));
        double lowLevel = run(UserPreferenceProfile.empty(), "질의").movies().get(0).breakdown().sim();
        hits(new Hit(1, 0.90f), new Hit(2, 0.80f));
        double highLevel = run(UserPreferenceProfile.empty(), "질의").movies().get(0).breakdown().sim();

        assertThat(lowLevel).isEqualTo(highLevel).isEqualTo(1.0);
    }

    @Test
    void 후보가_1편이면_sim은_1이다() {
        catalog(RecommendFixtures.movie(1).build());
        hits(new Hit(1, 0.4f));

        assertThat(run(UserPreferenceProfile.empty(), "질의").movies().get(0).breakdown().sim()).isEqualTo(1.0);
    }

    @Test
    void max와_min이_같으면_전부_sim_1이다() {
        catalog(RecommendFixtures.movie(1).build(), RecommendFixtures.movie(2).build());
        hits(new Hit(1, 0.5f), new Hit(2, 0.5f));

        assertThat(run(UserPreferenceProfile.empty(), "질의").movies())
                .allSatisfy(m -> assertThat(m.breakdown().sim()).isEqualTo(1.0));
    }

    @Test
    void 본_영화와_하한_미달은_제외하고_찜한_영화는_wished로_남긴다() {
        catalog(RecommendFixtures.movie(1).build(),                       // 정상
                RecommendFixtures.movie(2).build(),                       // 시청
                RecommendFixtures.movie(3).build(),                       // 찜 — 제외하지 않는다
                RecommendFixtures.movie(4).votes(8.0, 49).build());       // 해외 50표 미만
        hits(new Hit(1, 0.9f), new Hit(2, 0.8f), new Hit(3, 0.7f), new Hit(4, 0.6f));
        UserPreferenceProfile profile = RecommendFixtures.profile(Map.of(), Map.of(), Map.of(), Map.of(), Set.of(2L), Set.of(3L));

        RecommendationResult result = run(profile, "질의");

        assertThat(ids(result)).containsExactlyInAnyOrder(1L, 3L);
        assertThat(result.movies()).filteredOn(m -> m.movieId() == 3L).singleElement().satisfies(m -> assertThat(m.wished()).isTrue());
        assertThat(result.movies()).filteredOn(m -> m.movieId() == 1L).singleElement().satisfies(m -> assertThat(m.wished()).isFalse());
        assertThat(result.exclusions().watched()).isEqualTo(1);
        assertThat(result.exclusions().belowVoteFloor()).isEqualTo(1);
    }

    @Test
    void 개봉_전_영화와_같은_감독_여러_편도_자연어에서는_제외하지_않는다() {
        catalog(RecommendFixtures.movie(1).directors(100).releaseDate(java.time.LocalDate.of(2999, 1, 1)).build(),
                RecommendFixtures.movie(2).directors(100).build(),
                RecommendFixtures.movie(3).directors(100).build());
        hits(new Hit(1, 0.9f), new Hit(2, 0.8f), new Hit(3, 0.7f));

        assertThat(run(UserPreferenceProfile.empty(), "봉준호 영화").movies()).hasSize(3);
    }

    @Test
    void 인덱스에_없는_영화는_결과에서_버린다() {
        catalog(RecommendFixtures.movie(1).build());
        hits(new Hit(1, 0.9f), new Hit(999, 0.8f));

        assertThat(ids(run(UserPreferenceProfile.empty(), "질의"))).containsExactly(1L);
    }

    @Test
    void 명시_런타임_조건은_하드_필터이고_런타임_null은_제외된다() {
        catalog(RecommendFixtures.movie(1).runtime(85).build(),
                RecommendFixtures.movie(2).runtime(120).build(),
                RecommendFixtures.movie(3).runtime(null).build());
        hits(new Hit(1, 0.9f), new Hit(2, 0.95f), new Hit(3, 0.99f));

        RecommendationResult result = run(UserPreferenceProfile.empty(), "90분 이내로 볼 영화");

        assertThat(ids(result)).containsExactly(1L);
        assertThat(result.conditions()).hasSize(1);
        assertThat(result.exclusions().runtimeFilter()).isEqualTo(2);
    }

    @Test
    void 짧은_선호는_조건을_만족하는_영화에_가산점만_주고_자르지_않는다() {
        catalog(RecommendFixtures.movie(1).runtime(88).build(),
                RecommendFixtures.movie(2).runtime(130).build());
        hits(new Hit(1, 0.80f), new Hit(2, 0.80f));

        RecommendationResult result = run(UserPreferenceProfile.empty(), "짧은 영화");

        assertThat(result.movies()).hasSize(2);
        assertThat(result.movies().get(0).movieId()).isEqualTo(1L);
        assertThat(result.movies().get(0).breakdown().runtimeBonus()).isEqualTo(1.0);
        assertThat(result.movies().get(1).breakdown().runtimeBonus()).isZero();
        assertThat(result.movies().get(0).score() - result.movies().get(1).score()).isCloseTo(0.10, within(1e-9));
    }

    @Test
    void 조건이_없으면_런타임_항이_0이다() {
        catalog(RecommendFixtures.movie(1).runtime(88).build());
        hits(new Hit(1, 0.8f));

        RecommendationResult result = run(UserPreferenceProfile.empty(), "잔잔한 영화");

        assertThat(result.movies().get(0).breakdown().runtimeBonus()).isZero();
        assertThat(result.conditions()).isEmpty();
    }

    @Test
    void 자연어_점수는_sim_T_Q_런타임의_가중합이다() {
        catalog(RecommendFixtures.movie(1).genres(1, 1000).votes(8.0, 5000).runtime(88).build());
        hits(new Hit(1, 0.8f));
        UserPreferenceProfile profile = RecommendFixtures.profile(Map.of(1L, 0.5), Map.of(), Map.of(), Map.of(), Set.of(), Set.of());

        RankedMovie movie = run(profile, "짧은 영화").movies().get(0);

        assertThat(movie.score()).isCloseTo(0.60 * 1.0 + 0.25 * movie.breakdown().taste() + 0.15 * movie.breakdown().quality() + 0.10 * 1.0, within(1e-9));
    }

    @Test
    void sim이_최대_기여면_영화별_이유를_달지_않는다() {
        catalog(RecommendFixtures.movie(1).build());
        hits(new Hit(1, 0.8f));

        assertThat(run(UserPreferenceProfile.empty(), "질의").movies().get(0).reason()).isNull();
    }

    @Test
    void 취향_기여가_sim보다_크면_이유가_붙는다() {
        // sim 최저(0) → sim 기여 0. 품질을 낮춰(2.0점) 인물 0.25·0.3·1.0 = 0.075가 품질 기여(0.15·Q)보다 크게 한다.
        catalog(RecommendFixtures.movie(1).build(), RecommendFixtures.movie(2).directors(100).votes(2.0, 100000).build());
        hits(new Hit(1, 0.9f), new Hit(2, 0.5f));
        UserPreferenceProfile profile = RecommendFixtures.profile(Map.of(), Map.of(), Map.of(100L, 1.0), Map.of(), Set.of(), Set.of());

        RankedMovie second = run(profile, "질의").movies().stream().filter(m -> m.movieId() == 2L).findFirst().orElseThrow();

        assertThat(second.reason()).isNotNull();
        assertThat(second.reason().targetId()).isEqualTo(100L);
    }

    @Test
    void 결과는_최대_100편이다() {
        List<MovieFeature> movies = new ArrayList<>();
        List<Hit> hits = new ArrayList<>();
        for (long id = 1; id <= 200; id++) {
            movies.add(RecommendFixtures.movie(id).build());
            hits.add(new Hit(id, 1.0f - id / 1000f));
        }
        when(featureIndex.snapshot()).thenReturn(RecommendFixtures.snapshot(movies, Map.of(), Map.of()));
        when(vectorIndex.search(any(float[].class), anyInt())).thenReturn(hits);

        assertThat(run(UserPreferenceProfile.empty(), "질의").movies()).hasSize(100);
    }

    @Test
    void 벡터_인덱스가_비면_빈_결과다() {
        catalog(RecommendFixtures.movie(1).build());
        hits();

        assertThat(run(UserPreferenceProfile.empty(), "질의").movies()).isEmpty();
    }

    @Test
    void 조건_객체를_직접_넘길_수도_있다_대체_경로용() {
        catalog(RecommendFixtures.movie(1).build());
        hits(new Hit(1, 0.8f));

        assertThat(service.recommend(UserPreferenceProfile.empty(), QueryConstraints.none(), QUERY).movies()).hasSize(1);
    }
}
