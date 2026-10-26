package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.recommendation.config.RecommendProperties;
import com.project.cinemory.domain.recommendation.dto.AppliedConditionResponse;
import com.project.cinemory.domain.recommendation.dto.ConditionType;
import com.project.cinemory.domain.recommendation.dto.RecommendReasonResponse;
import com.project.cinemory.domain.recommendation.index.MovieFeature;
import com.project.cinemory.domain.recommendation.index.MovieFeatureIndex;
import com.project.cinemory.domain.recommendation.index.MovieVectorIndex;
import com.project.cinemory.domain.recommendation.service.QueryConstraintExtractor.QueryConstraints;
import com.project.cinemory.domain.recommendation.service.QueryConstraintExtractor.RuntimePreference;
import com.project.cinemory.domain.recommendation.service.RecommendationResult.RankedMovie;
import com.project.cinemory.domain.recommendation.service.RecommendationScorer.ReasonChoice;
import com.project.cinemory.domain.recommendation.support.RecommendFixtures;
import com.project.cinemory.domain.recommendation.support.VectorFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 자연어 추천 — 필터 선적용 → 후보 K → 후보 안 min-max → 재정렬 → 부정 감점 (S-1-4 · Q-2). 벡터 인덱스는 실제 구현(저장소만 가짜),
 * 질의 벡터는 {@code VectorFixtures.axis(0)}, 부정 개념 벡터는 {@code axis(1)}·{@code axis(2)}라 영화 벡터 {@code vec(a, b, c)}와의 내적이 각각 a, b, c다.
 */
class QueryRecommendationServiceTest {

    private static final float[] MAIN = VectorFixtures.axis(0);
    private static final float[] NEG1 = VectorFixtures.axis(1);
    private static final float[] NEG2 = VectorFixtures.axis(2);

    private final RecommendProperties properties = RecommendFixtures.properties();
    private final RecommendationScorer scorer = new RecommendationScorer(properties);
    private ReasonTextResolver resolver;
    private MovieFeatureIndex featureIndex;

    @BeforeEach
    void setUp() {
        resolver = mock(ReasonTextResolver.class);
        // 이유 문구 조회는 별도 책임 — 선택된 유형·대상만 그대로 돌려받아 검증한다.
        when(resolver.resolve(anyList())).thenAnswer(inv -> {
            List<ReasonChoice> choices = inv.getArgument(0);
            List<RecommendReasonResponse> responses = new ArrayList<>();
            for (ReasonChoice c : choices) {
                responses.add(c == null ? null : new RecommendReasonResponse(c.type(), c.targetId(), null, null));
            }
            return responses;
        });
        featureIndex = mock(MovieFeatureIndex.class);
    }

    /** 영화 특징과 벡터를 한 번에 올린다. */
    private final Map<Long, float[]> vectors = new HashMap<>();
    private final List<MovieFeature> features = new ArrayList<>();

    private void add(MovieFeature movie, float[] vector) {
        features.add(movie);
        vectors.put(movie.movieId(), vector);
    }

    private QueryRecommendationService service() {
        return service(properties);
    }

    private QueryRecommendationService service(RecommendProperties custom) {
        when(featureIndex.snapshot()).thenReturn(RecommendFixtures.snapshot(features, Map.of(), Map.of()));
        MovieVectorIndex vectorIndex = VectorFixtures.indexOf(vectors);
        return new QueryRecommendationService(vectorIndex, featureIndex, new RecommendationScorer(custom), resolver, custom);
    }

    private RecommendationResult run(UserPreferenceProfile profile, QueryConstraints constraints, float[] main, float[]... negs) {
        return service().recommend(profile, constraints, main, Arrays.asList(negs));
    }

    private static List<Long> ids(RecommendationResult r) {
        return r.movies().stream().map(RankedMovie::movieId).toList();
    }

    private static RankedMovie movie(RecommendationResult r, long id) {
        return r.movies().stream().filter(m -> m.movieId() == id).findFirst().orElseThrow();
    }

    private static QueryConstraints plain() {
        return RecommendFixtures.constraints().build();
    }

    // ── sim 정규화 ─────────────────────────────────────────────────

    @Test
    void sim은_필터를_통과한_후보_안에서_min_max_정규화된다() {
        add(RecommendFixtures.movie(1).build(), VectorFixtures.cos(0.80));
        add(RecommendFixtures.movie(2).build(), VectorFixtures.cos(0.70));
        add(RecommendFixtures.movie(3).build(), VectorFixtures.cos(0.60));

        RecommendationResult result = run(UserPreferenceProfile.empty(), plain(), MAIN);

        assertThat(ids(result)).containsExactly(1L, 2L, 3L);
        assertThat(movie(result, 1).breakdown().sim()).isEqualTo(1.0);
        assertThat(movie(result, 2).breakdown().sim()).isCloseTo(0.5, within(1e-6));
        assertThat(movie(result, 3).breakdown().sim()).isEqualTo(0.0);
    }

    @Test
    void 코사인의_절대_수준이_달라도_상대화되어_같은_sim이_나온다() {
        add(RecommendFixtures.movie(1).build(), VectorFixtures.cos(0.30));
        add(RecommendFixtures.movie(2).build(), VectorFixtures.cos(0.20));
        double low = run(UserPreferenceProfile.empty(), plain(), MAIN).movies().get(0).breakdown().sim();
        vectors.clear();
        features.clear();
        add(RecommendFixtures.movie(1).build(), VectorFixtures.cos(0.90));
        add(RecommendFixtures.movie(2).build(), VectorFixtures.cos(0.80));
        double high = run(UserPreferenceProfile.empty(), plain(), MAIN).movies().get(0).breakdown().sim();

        assertThat(low).isEqualTo(high).isEqualTo(1.0);
    }

    @Test
    void 후보가_1편이거나_max와_min이_같으면_sim은_1이다() {
        add(RecommendFixtures.movie(1).build(), VectorFixtures.cos(0.5));
        assertThat(run(UserPreferenceProfile.empty(), plain(), MAIN).movies().get(0).breakdown().sim()).isEqualTo(1.0);

        add(RecommendFixtures.movie(2).build(), VectorFixtures.cos(0.5));
        assertThat(run(UserPreferenceProfile.empty(), plain(), MAIN).movies())
                .allSatisfy(m -> assertThat(m.breakdown().sim()).isEqualTo(1.0));
    }

    @Test
    void 후보는_설정한_K까지만_뽑는다() {
        for (long id = 1; id <= 10; id++) {
            add(RecommendFixtures.movie(id).build(), VectorFixtures.cos(1.0 - id * 0.05));
        }
        RecommendProperties k3 = withQuery(properties.query().similarity(), 3, properties.query().negativePenalty());

        RecommendationResult result = service(k3).recommend(UserPreferenceProfile.empty(), plain(), MAIN, List.of());

        assertThat(ids(result)).containsExactly(1L, 2L, 3L);
    }

    // ── 하드 필터: 선적용 (Q-2-4) ───────────────────────────────────

    @Test
    void 필터는_후보를_뽑기_전에_적용된다_상위_K밖의_조건_일치_영화도_나온다() {
        // 코사인 상위 200편은 전부 미국 영화, 한국 영화 30편은 그보다 낮다(201위 밖). 후필터였다면 한국 영화는 0건이다.
        for (long id = 1; id <= 200; id++) {
            add(RecommendFixtures.movie(id).country("US").build(), VectorFixtures.cos(0.9 - id * 0.0001));
        }
        for (long id = 1001; id <= 1030; id++) {
            add(RecommendFixtures.movie(id).korean().build(), VectorFixtures.cos(0.3 - (id - 1000) * 0.001));
        }

        RecommendationResult result = run(UserPreferenceProfile.empty(), RecommendFixtures.constraints().countries("KR").build(), MAIN);

        assertThat(result.movies()).hasSize(30);
        assertThat(ids(result)).allMatch(id -> id > 1000);
        assertThat(result.exclusions().countryFilter()).isEqualTo(200);   // 스캔 중에 집계된 제외 건수
    }

    @Test
    void 국가_하드_필터는_대표국이_다른_영화와_국가_정보가_없는_영화를_거른다() {
        add(RecommendFixtures.movie(1).country("JP").build(), VectorFixtures.cos(0.5));
        add(RecommendFixtures.movie(2).country("US").build(), VectorFixtures.cos(0.9));
        add(RecommendFixtures.movie(3).build(), VectorFixtures.cos(0.8));   // 국가 정보 없음

        RecommendationResult result = run(UserPreferenceProfile.empty(), RecommendFixtures.constraints().countries("JP").build(), MAIN);

        assertThat(ids(result)).containsExactly(1L);
        assertThat(result.exclusions().countryFilter()).isEqualTo(2);
    }

    @Test
    void 제외_국가는_해당_대표국만_거르고_국가_정보가_없는_영화는_남긴다() {
        add(RecommendFixtures.movie(1).korean().build(), VectorFixtures.cos(0.9));
        add(RecommendFixtures.movie(2).country("US").build(), VectorFixtures.cos(0.8));
        add(RecommendFixtures.movie(3).build(), VectorFixtures.cos(0.7));

        RecommendationResult result = run(UserPreferenceProfile.empty(), RecommendFixtures.constraints().excludeCountries("KR").build(), MAIN);

        assertThat(ids(result)).containsExactlyInAnyOrder(2L, 3L);
    }

    @Test
    void 개봉_시기_필터는_범위_밖과_개봉일_없는_영화를_거른다() {
        add(RecommendFixtures.movie(1).releaseDate(LocalDate.of(1995, 5, 1)).build(), VectorFixtures.cos(0.5));
        add(RecommendFixtures.movie(2).releaseDate(LocalDate.of(2005, 5, 1)).build(), VectorFixtures.cos(0.9));
        add(RecommendFixtures.movie(3).releaseDate(null).build(), VectorFixtures.cos(0.8));
        add(RecommendFixtures.movie(4).releaseDate(LocalDate.of(1999, 12, 31)).build(), VectorFixtures.cos(0.4));

        RecommendationResult result = run(UserPreferenceProfile.empty(),
                RecommendFixtures.constraints().period(LocalDate.of(1990, 1, 1), LocalDate.of(1999, 12, 31)).build(), MAIN);

        assertThat(ids(result)).containsExactly(1L, 4L);
        assertThat(result.exclusions().releasePeriodFilter()).isEqualTo(2);
    }

    @Test
    void 제외_장르는_TMDB_장르_id로_거른다() {
        add(RecommendFixtures.movie(1).genres(1, 500, 2, 500).genreTmdbIds(27, 53).build(), VectorFixtures.cos(0.9));   // 공포+스릴러
        add(RecommendFixtures.movie(2).genres(2, 1000).genreTmdbIds(53).build(), VectorFixtures.cos(0.8));               // 스릴러만
        add(RecommendFixtures.movie(3).build(), VectorFixtures.cos(0.7));                                                  // 장르 없음

        RecommendationResult result = run(UserPreferenceProfile.empty(), RecommendFixtures.constraints().excludeGenres(27).build(), MAIN);

        assertThat(ids(result)).containsExactly(2L, 3L);
        assertThat(result.exclusions().excludedGenre()).isEqualTo(1);
    }

    @Test
    void 런타임_하드_필터는_상한을_넘는_영화와_런타임_null을_거른다() {
        add(RecommendFixtures.movie(1).runtime(85).build(), VectorFixtures.cos(0.9));
        add(RecommendFixtures.movie(2).runtime(120).build(), VectorFixtures.cos(0.95));
        add(RecommendFixtures.movie(3).runtime(null).build(), VectorFixtures.cos(0.99));

        RecommendationResult result = run(UserPreferenceProfile.empty(), RecommendFixtures.constraints().runtimeMax(90).build(), MAIN);

        assertThat(ids(result)).containsExactly(1L);
        assertThat(result.exclusions().runtimeFilter()).isEqualTo(2);
    }

    @Test
    void 여러_조건은_모두_만족해야_한다() {
        add(RecommendFixtures.movie(1).korean().releaseDate(LocalDate.of(1995, 1, 1)).runtime(100).genreTmdbIds(18).build(), VectorFixtures.cos(0.2));
        add(RecommendFixtures.movie(2).korean().releaseDate(LocalDate.of(2015, 1, 1)).runtime(100).build(), VectorFixtures.cos(0.9));
        add(RecommendFixtures.movie(3).country("JP").releaseDate(LocalDate.of(1995, 1, 1)).runtime(100).build(), VectorFixtures.cos(0.9));
        add(RecommendFixtures.movie(4).korean().releaseDate(LocalDate.of(1995, 1, 1)).runtime(100).genreTmdbIds(27).build(), VectorFixtures.cos(0.9));

        RecommendationResult result = run(UserPreferenceProfile.empty(), RecommendFixtures.constraints().countries("KR")
                .period(LocalDate.of(1990, 1, 1), LocalDate.of(1999, 12, 31)).excludeGenres(27).build(), MAIN);

        assertThat(ids(result)).containsExactly(1L);
        assertThat(result.exclusions().countryFilter()).isEqualTo(1);
        assertThat(result.exclusions().releasePeriodFilter()).isEqualTo(1);
        assertThat(result.exclusions().excludedGenre()).isEqualTo(1);
    }

    @Test
    void 본_영화와_하한_미달은_제외하고_찜한_영화는_wished로_남긴다() {
        add(RecommendFixtures.movie(1).build(), VectorFixtures.cos(0.9));
        add(RecommendFixtures.movie(2).build(), VectorFixtures.cos(0.8));                       // 시청
        add(RecommendFixtures.movie(3).build(), VectorFixtures.cos(0.7));                       // 찜 — 제외하지 않는다
        add(RecommendFixtures.movie(4).votes(8.0, 49).build(), VectorFixtures.cos(0.6));       // 해외 50표 미만
        UserPreferenceProfile profile = RecommendFixtures.profile(Map.of(), Map.of(), Map.of(), Map.of(), Set.of(2L), Set.of(3L));

        RecommendationResult result = run(profile, plain(), MAIN);

        assertThat(ids(result)).containsExactlyInAnyOrder(1L, 3L);
        assertThat(movie(result, 3).wished()).isTrue();
        assertThat(movie(result, 1).wished()).isFalse();
        assertThat(result.exclusions().watched()).isEqualTo(1);
        assertThat(result.exclusions().belowVoteFloor()).isEqualTo(1);
    }

    @Test
    void 개봉_전_영화와_같은_감독_여러_편도_자연어에서는_제외하지_않는다() {
        add(RecommendFixtures.movie(1).directors(100).releaseDate(LocalDate.of(2999, 1, 1)).build(), VectorFixtures.cos(0.9));
        add(RecommendFixtures.movie(2).directors(100).build(), VectorFixtures.cos(0.8));
        add(RecommendFixtures.movie(3).directors(100).build(), VectorFixtures.cos(0.7));

        assertThat(run(UserPreferenceProfile.empty(), plain(), MAIN).movies()).hasSize(3);
    }

    @Test
    void 특징_인덱스에_없는_영화는_결과에서_버린다() {
        add(RecommendFixtures.movie(1).build(), VectorFixtures.cos(0.9));
        vectors.put(999L, VectorFixtures.cos(0.95));   // 벡터는 있지만 특징 인덱스에는 없는(삭제된) 영화

        assertThat(ids(run(UserPreferenceProfile.empty(), plain(), MAIN))).containsExactly(1L);
    }

    // ── 런타임 가산점 · 점수 · 이유 ─────────────────────────────────

    @Test
    void 짧은_선호는_조건을_만족하는_영화에_가산점만_주고_자르지_않는다() {
        add(RecommendFixtures.movie(1).runtime(88).build(), VectorFixtures.cos(0.8));
        add(RecommendFixtures.movie(2).runtime(130).build(), VectorFixtures.cos(0.8));

        RecommendationResult result = run(UserPreferenceProfile.empty(),
                RecommendFixtures.constraints().preference(RuntimePreference.SHORT).build(), MAIN);

        assertThat(result.movies()).hasSize(2);
        assertThat(result.movies().get(0).movieId()).isEqualTo(1L);
        assertThat(result.movies().get(0).breakdown().runtimeBonus()).isEqualTo(1.0);
        assertThat(result.movies().get(1).breakdown().runtimeBonus()).isZero();
        assertThat(result.movies().get(0).score() - result.movies().get(1).score()).isCloseTo(0.10, within(1e-9));
    }

    @Test
    void 자연어_점수는_sim_T_Q_런타임의_가중합이다() {
        add(RecommendFixtures.movie(1).genres(1, 1000).votes(8.0, 5000).runtime(88).build(), VectorFixtures.cos(0.8));
        UserPreferenceProfile profile = RecommendFixtures.profile(Map.of(1L, 0.5), Map.of(), Map.of(), Map.of(), Set.of(), Set.of());

        RankedMovie movie = run(profile, RecommendFixtures.constraints().preference(RuntimePreference.SHORT).build(), MAIN).movies().get(0);

        assertThat(movie.score()).isCloseTo(0.60 * 1.0 + 0.25 * movie.breakdown().taste() + 0.15 * movie.breakdown().quality() + 0.10 * 1.0, within(1e-9));
        assertThat(movie.breakdown().negativeSim()).isNull();   // 감점 없음
    }

    @Test
    void sim이_최대_기여면_영화별_이유를_달지_않는다() {
        add(RecommendFixtures.movie(1).build(), VectorFixtures.cos(0.8));

        assertThat(run(UserPreferenceProfile.empty(), plain(), MAIN).movies().get(0).reason()).isNull();
    }

    @Test
    void 취향_기여가_sim보다_크면_이유가_붙는다() {
        // sim 최저(0) → sim 기여 0. 품질을 낮춰(2.0점) 인물 0.25·0.3·1.0 = 0.075가 품질 기여(0.15·Q)보다 크게 한다.
        add(RecommendFixtures.movie(1).build(), VectorFixtures.cos(0.9));
        add(RecommendFixtures.movie(2).directors(100).votes(2.0, 100000).build(), VectorFixtures.cos(0.5));
        UserPreferenceProfile profile = RecommendFixtures.profile(Map.of(), Map.of(), Map.of(100L, 1.0), Map.of(), Set.of(), Set.of());

        RankedMovie second = movie(run(profile, plain(), MAIN), 2);

        assertThat(second.reason()).isNotNull();
        assertThat(second.reason().targetId()).isEqualTo(100L);
    }

    @Test
    void 결과는_최대_100편이다() {
        for (long id = 1; id <= 200; id++) {
            add(RecommendFixtures.movie(id).build(), VectorFixtures.cos(1.0 - id / 1000.0));
        }

        assertThat(run(UserPreferenceProfile.empty(), plain(), MAIN).movies()).hasSize(100);
    }

    @Test
    void 벡터가_없으면_빈_결과다() {
        assertThat(run(UserPreferenceProfile.empty(), plain(), MAIN).movies()).isEmpty();
    }

    // ── 부정 감점 (Q-2-3 ②) ─────────────────────────────────────────

    @Test
    void 부정_개념과_가까운_후보는_뒤로_밀린다() {
        // 1번: 본 질의와 가장 가깝지만 부정 개념("잔인하다")과도 가장 가깝다. 2번: 본 질의는 조금 덜 가깝지만 부정 개념과는 멀다.
        add(RecommendFixtures.movie(1).build(), VectorFixtures.vec(0.80, 0.90, 0));
        add(RecommendFixtures.movie(2).build(), VectorFixtures.vec(0.78, 0.10, 0));
        add(RecommendFixtures.movie(3).build(), VectorFixtures.vec(0.60, 0.50, 0));
        QueryConstraints constraints = RecommendFixtures.constraints().negatives("잔인하다").build();

        RecommendationResult withoutPenalty = run(UserPreferenceProfile.empty(), plain(), MAIN);
        RecommendationResult withPenalty = run(UserPreferenceProfile.empty(), constraints, MAIN, NEG1);

        assertThat(ids(withoutPenalty).get(0)).isEqualTo(1L);
        assertThat(ids(withPenalty).get(0)).isEqualTo(2L);
        // negSim은 후보 안 min-max: 1번 = 1.0(최대), 2번 = 0.0(최소)
        assertThat(movie(withPenalty, 1).breakdown().negativeSim()).isEqualTo(1.0);
        assertThat(movie(withPenalty, 2).breakdown().negativeSim()).isEqualTo(0.0);
        assertThat(movie(withPenalty, 1).score()).isCloseTo(movie(withoutPenalty, 1).score() - 0.3, within(1e-6));
    }

    @Test
    void 부정_개념이_둘이면_후보마다_큰_감점을_쓴다() {
        add(RecommendFixtures.movie(1).build(), VectorFixtures.vec(0.8, 0.9, 0.1));   // 첫째 개념에 가까움
        add(RecommendFixtures.movie(2).build(), VectorFixtures.vec(0.8, 0.1, 0.9));   // 둘째 개념에 가까움
        add(RecommendFixtures.movie(3).build(), VectorFixtures.vec(0.8, 0.1, 0.1));   // 둘 다 멂

        RecommendationResult result = run(UserPreferenceProfile.empty(),
                RecommendFixtures.constraints().negatives("잔인하다", "무겁다").build(), MAIN, NEG1, NEG2);

        assertThat(movie(result, 1).breakdown().negativeSim()).isEqualTo(1.0);
        assertThat(movie(result, 2).breakdown().negativeSim()).isEqualTo(1.0);
        assertThat(movie(result, 3).breakdown().negativeSim()).isEqualTo(0.0);
        assertThat(ids(result).get(0)).isEqualTo(3L);
    }

    @Test
    void 부정_유사도에_편차가_없으면_감점은_0이다() {
        add(RecommendFixtures.movie(1).build(), VectorFixtures.vec(0.9, 0.5, 0));
        add(RecommendFixtures.movie(2).build(), VectorFixtures.vec(0.8, 0.5, 0));

        RecommendationResult result = run(UserPreferenceProfile.empty(), RecommendFixtures.constraints().negatives("잔인하다").build(), MAIN, NEG1);

        assertThat(result.movies()).allSatisfy(m -> assertThat(m.breakdown().negativeSim()).isEqualTo(0.0));
    }

    @Test
    void 감점_가중치가_0이면_감점을_끈다_칩도_내리지_않는다() {
        add(RecommendFixtures.movie(1).build(), VectorFixtures.vec(0.80, 0.90, 0));
        add(RecommendFixtures.movie(2).build(), VectorFixtures.vec(0.78, 0.10, 0));
        RecommendProperties off = withQuery(properties.query().similarity(), properties.query().candidateSize(), 0.0);

        RecommendationResult result = service(off).recommend(UserPreferenceProfile.empty(),
                RecommendFixtures.constraints().negatives("잔인하다").build(), MAIN, List.of(NEG1));

        assertThat(ids(result).get(0)).isEqualTo(1L);
        assertThat(result.conditions()).extracting(AppliedConditionResponse::type).doesNotContain(ConditionType.AVOID_MOOD);
        assertThat(result.movies().get(0).breakdown().negativeSim()).isNull();
    }

    @Test
    void AVOID_MOOD_칩은_감점이_실제로_적용된_개념만_남는다() {
        add(RecommendFixtures.movie(1).build(), VectorFixtures.vec(0.8, 0.9, 0.1));
        add(RecommendFixtures.movie(2).build(), VectorFixtures.vec(0.7, 0.1, 0.9));
        QueryConstraints constraints = RecommendFixtures.constraints().negatives("잔인하다", "무겁다").build();

        // 둘째 개념의 임베딩이 실패했다(null) → 첫째 개념의 감점만 적용되고 칩도 그것만 내린다.
        RecommendationResult partial = service().recommend(UserPreferenceProfile.empty(), constraints, MAIN, Arrays.asList(NEG1, null));
        // 둘 다 적용
        RecommendationResult both = service().recommend(UserPreferenceProfile.empty(), constraints, MAIN, List.of(NEG1, NEG2));
        // 하나도 적용되지 않음
        RecommendationResult none = service().recommend(UserPreferenceProfile.empty(), constraints, MAIN, Arrays.asList(null, null));

        assertThat(partial.conditions()).extracting(AppliedConditionResponse::label).containsExactly("잔인하다");
        assertThat(both.conditions()).extracting(AppliedConditionResponse::label).containsExactly("잔인하다", "무겁다");
        assertThat(none.conditions()).isEmpty();
    }

    @Test
    void 다른_조건_칩은_감점_여부와_무관하게_유지된다() {
        add(RecommendFixtures.movie(1).korean().build(), VectorFixtures.cos(0.9));
        QueryConstraints constraints = new QueryConstraints(null, RuntimePreference.NONE, Set.of("KR"), Set.of(), null, Set.of(), List.of("잔인하다"), "q",
                List.of(new AppliedConditionResponse(ConditionType.COUNTRY, null, "한국", "한국 영화만 골랐어요"),
                        new AppliedConditionResponse(ConditionType.AVOID_MOOD, null, "잔인하다", "잔인한 영화는 뒤로 미뤘어요")));

        RecommendationResult result = service().recommend(UserPreferenceProfile.empty(), constraints, MAIN, Arrays.asList((float[]) null));

        assertThat(result.conditions()).extracting(AppliedConditionResponse::type).containsExactly(ConditionType.COUNTRY);
    }

    // ── 본 질의가 비었을 때 ("잔인하지 않은 영화") ─────────────────────

    @Test
    void 본_질의가_없으면_sim은_0이고_후보는_필터를_통과한_전체다() {
        // 코사인과 무관하게 모든 영화가 후보다 — K(200)보다 많아도 전부 대상이다.
        for (long id = 1; id <= 250; id++) {
            add(RecommendFixtures.movie(id).votes(7.0, 1000).build(), VectorFixtures.vec(0.5, 0.5, 0));
        }
        add(RecommendFixtures.movie(900).votes(9.5, 90000).build(), VectorFixtures.vec(0.1, 0.5, 0));   // 품질이 가장 높다

        RecommendationResult result = run(UserPreferenceProfile.empty(), RecommendFixtures.constraints().noMainQuery().build(), null);

        assertThat(result.movies()).hasSize(100);
        assertThat(ids(result).get(0)).isEqualTo(900L);   // T·Q로 순위: sim 항이 없으므로 품질 1위가 앞선다
        assertThat(result.movies()).allSatisfy(m -> assertThat(m.breakdown().sim()).isNull());
    }

    @Test
    void 본_질의가_없어도_부정_개념_감점과_하드_필터는_적용된다() {
        add(RecommendFixtures.movie(1).korean().votes(8.0, 9000).build(), VectorFixtures.vec(0, 0.9, 0));   // 잔인함에 가깝다
        add(RecommendFixtures.movie(2).korean().votes(8.0, 9000).build(), VectorFixtures.vec(0, 0.1, 0));   // 멀다
        add(RecommendFixtures.movie(3).country("US").votes(8.0, 9000).build(), VectorFixtures.vec(0, 0.1, 0));   // 국가 필터에 걸림

        RecommendationResult result = service().recommend(UserPreferenceProfile.empty(),
                RecommendFixtures.constraints().noMainQuery().countries("KR").negatives("잔인하다").build(), null, List.of(NEG1));

        assertThat(ids(result)).containsExactly(2L, 1L);
        assertThat(movie(result, 1).breakdown().negativeSim()).isEqualTo(1.0);
        assertThat(result.exclusions().countryFilter()).isEqualTo(1);
        assertThat(result.conditions()).extracting(AppliedConditionResponse::label).contains("잔인하다");
    }

    @Test
    void 본_질의도_부정_개념도_없으면_T_Q_순위의_필터_결과다() {
        add(RecommendFixtures.movie(1).votes(6.0, 200).build(), VectorFixtures.cos(0.1));
        add(RecommendFixtures.movie(2).votes(8.5, 20000).build(), VectorFixtures.cos(0.1));

        RecommendationResult result = run(UserPreferenceProfile.empty(), RecommendFixtures.constraints().noMainQuery().build(), null);

        assertThat(ids(result)).containsExactly(2L, 1L);
    }

    @Test
    void 조건_객체의_칩_순서와_내용은_그대로_전달된다() {
        add(RecommendFixtures.movie(1).korean().build(), VectorFixtures.cos(0.9));
        QueryConstraints constraints = new QueryConstraints(null, RuntimePreference.NONE, Set.of("KR"), Set.of(), null, Set.of(), List.of(), "q",
                List.of(new AppliedConditionResponse(ConditionType.COUNTRY, null, "한국", "한국 영화만 골랐어요")));

        RecommendationResult result = service().recommend(UserPreferenceProfile.empty(), constraints, MAIN, List.of());

        assertThat(result.conditions()).singleElement().satisfies(c -> assertThat(c.label()).isEqualTo("한국"));
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────

    private RecommendProperties withQuery(double similarity, int candidateSize, double negativePenalty) {
        RecommendProperties.Query q = properties.query();
        return new RecommendProperties(properties.taste(), properties.forYou(),
                new RecommendProperties.Query(similarity, q.taste(), q.quality(), q.runtimeBonus(), candidateSize,
                        q.preferShortMax(), q.preferLongMin(), negativePenalty, q.maxNegatives(), q.recentYears(), q.oldYears()),
                properties.quality(), properties.popularity(), properties.minVoteCount(), properties.wish(),
                properties.maxPerDirector(), properties.maxResults(), properties.pageSize(), properties.cacheTtl(),
                properties.cache(), properties.rateLimit());
    }
}
