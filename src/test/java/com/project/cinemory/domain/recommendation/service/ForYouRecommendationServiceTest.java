package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.recommendation.dto.ReasonType;
import com.project.cinemory.domain.recommendation.dto.RecommendReasonResponse;
import com.project.cinemory.domain.recommendation.index.MovieFeature;
import com.project.cinemory.domain.recommendation.index.MovieFeatureIndex;
import com.project.cinemory.domain.recommendation.service.RecommendationResult.RankedMovie;
import com.project.cinemory.domain.recommendation.service.RecommendationScorer.ReasonChoice;
import com.project.cinemory.domain.recommendation.support.RecommendFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 내 취향 추천 — 제외 · 하한 · 다양성 상한 · 100편 상한 · 이유 (S-1-4). 특징 인덱스는 가짜 스냅샷. */
class ForYouRecommendationServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

    private MovieFeatureIndex featureIndex;
    private ReasonTextResolver reasonResolver;
    private ForYouRecommendationService service;

    @BeforeEach
    void setUp() {
        featureIndex = mock(MovieFeatureIndex.class);
        reasonResolver = mock(ReasonTextResolver.class);
        // 이유 문구 조회는 별도 책임 — 여기서는 선택된 유형·대상만 그대로 돌려받아 검증한다.
        when(reasonResolver.resolve(anyList())).thenAnswer(inv -> {
            List<ReasonChoice> choices = inv.getArgument(0);
            List<RecommendReasonResponse> responses = new ArrayList<>();
            for (ReasonChoice c : choices) {
                responses.add(c == null ? null : new RecommendReasonResponse(c.type(), c.targetId(), null, null));
            }
            return responses;
        });
        Clock clock = Clock.fixed(TODAY.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul"));
        var properties = RecommendFixtures.properties();
        service = new ForYouRecommendationService(mock(UserPreferenceProfileService.class), featureIndex,
                new RecommendationScorer(properties), reasonResolver, properties, clock);
    }

    private void catalog(MovieFeature... movies) {
        when(featureIndex.snapshot()).thenReturn(RecommendFixtures.snapshot(List.of(movies), Map.of(), Map.of()));
    }

    private static UserPreferenceProfile emptyProfile() {
        return UserPreferenceProfile.empty();
    }

    private static List<Long> ids(RecommendationResult result) {
        return result.movies().stream().map(RankedMovie::movieId).toList();
    }

    // ── 제외 ───────────────────────────────────────────────────────

    @Test
    void 본_영화_찜한_영화_개봉전_개봉일없음_하한미달은_제외된다() {
        catalog(
                RecommendFixtures.movie(1).build(),                                                       // 정상
                RecommendFixtures.movie(2).build(),                                                       // 시청
                RecommendFixtures.movie(3).build(),                                                       // 찜
                RecommendFixtures.movie(4).releaseDate(TODAY.plusDays(1)).build(),                        // 개봉 전
                RecommendFixtures.movie(5).releaseDate(null).build(),                                     // 개봉일 null
                RecommendFixtures.movie(6).votes(8.0, 49).build(),                                        // 해외 50표 미만
                RecommendFixtures.movie(7).releaseDate(TODAY).build());                                   // 오늘 개봉은 포함
        UserPreferenceProfile profile = RecommendFixtures.profile(Map.of(), Map.of(), Map.of(), Map.of(), Set.of(2L), Set.of(3L));

        RecommendationResult result = service.recommend(profile, null);

        assertThat(ids(result)).containsExactlyInAnyOrder(1L, 7L);
        assertThat(result.exclusions().watched()).isEqualTo(1);
        assertThat(result.exclusions().wished()).isEqualTo(1);
        assertThat(result.exclusions().unreleased()).isEqualTo(2);
        assertThat(result.exclusions().belowVoteFloor()).isEqualTo(1);
    }

    @Test
    void 한국_영화는_표가_적어도_하한에_걸리지_않는다() {
        catalog(RecommendFixtures.movie(1).korean().votes(7.0, 12).build(),
                RecommendFixtures.movie(2).votes(7.0, 12).build());

        assertThat(ids(service.recommend(emptyProfile(), null))).containsExactly(1L);
    }

    @Test
    void 런타임_상한이_있으면_넘는_영화와_런타임_null을_제외한다() {
        catalog(RecommendFixtures.movie(1).runtime(85).build(),
                RecommendFixtures.movie(2).runtime(120).build(),
                RecommendFixtures.movie(3).runtime(null).build());

        RecommendationResult result = service.recommend(emptyProfile(), 90);

        assertThat(ids(result)).containsExactly(1L);
        assertThat(result.exclusions().runtimeFilter()).isEqualTo(2);
    }

    // ── 순위 ───────────────────────────────────────────────────────

    @Test
    void 점수_내림차순이고_동점이면_movieId_오름차순이다() {
        catalog(RecommendFixtures.movie(30).votes(7.0, 1000).build(),
                RecommendFixtures.movie(10).votes(7.0, 1000).build(),
                RecommendFixtures.movie(20).votes(9.0, 100000).build());      // 점수가 가장 높음

        assertThat(ids(service.recommend(emptyProfile(), null))).containsExactly(20L, 10L, 30L);
    }

    @Test
    void 콜드_스타트는_T가_0이라_품질과_인지도_순이다() {
        catalog(RecommendFixtures.movie(1).votes(6.0, 200).build(),
                RecommendFixtures.movie(2).votes(8.5, 20000).build(),
                RecommendFixtures.movie(3).votes(7.2, 3000).build());

        RecommendationResult result = service.recommend(emptyProfile(), null);

        assertThat(ids(result)).containsExactly(2L, 3L, 1L);
        assertThat(result.personalized()).isFalse();
        assertThat(result.movies()).allSatisfy(m -> assertThat(m.breakdown().taste()).isZero());
        assertThat(result.movies().get(0).reason().type()).isIn(ReasonType.QUALITY, ReasonType.POPULARITY);
    }

    @Test
    void 취향이_있으면_취향에_맞는_영화가_같은_품질의_다른_영화보다_앞선다() {
        catalog(RecommendFixtures.movie(1).genres(1, 1000).build(),
                RecommendFixtures.movie(2).genres(2, 1000).build());
        UserPreferenceProfile profile = RecommendFixtures.profile(Map.of(2L, 1.0), Map.of(), Map.of(), Map.of(), Set.of(), Set.of());

        RecommendationResult result = service.recommend(profile, null);

        assertThat(ids(result)).containsExactly(2L, 1L);
        assertThat(result.personalized()).isTrue();
        assertThat(result.movies().get(0).reason()).extracting(RecommendReasonResponse::type, RecommendReasonResponse::targetId)
                .containsExactly(ReasonType.GENRE, 2L);
    }

    // ── 다양성 ─────────────────────────────────────────────────────

    @Test
    void 같은_감독은_최대_2편이고_넘치면_다음_순위가_채운다() {
        catalog(RecommendFixtures.movie(1).directors(100).votes(9.0, 90000).build(),
                RecommendFixtures.movie(2).directors(100).votes(8.9, 90000).build(),
                RecommendFixtures.movie(3).directors(100).votes(8.8, 90000).build(),   // 3번째 — 제외
                RecommendFixtures.movie(4).directors(200).votes(7.0, 1000).build());

        RecommendationResult result = service.recommend(emptyProfile(), null);

        assertThat(ids(result)).containsExactly(1L, 2L, 4L);
        assertThat(result.exclusions().directorCap()).isEqualTo(1);
    }

    @Test
    void 공동_연출은_감독_중_한_명이라도_상한이면_제외한다() {
        catalog(RecommendFixtures.movie(1).directors(100).votes(9.0, 90000).build(),
                RecommendFixtures.movie(2).directors(100).votes(8.9, 90000).build(),
                RecommendFixtures.movie(3).directors(100, 300).votes(8.8, 90000).build(),   // 100이 이미 2편 → 제외
                RecommendFixtures.movie(4).directors(300).votes(7.0, 1000).build());

        assertThat(ids(service.recommend(emptyProfile(), null))).containsExactly(1L, 2L, 4L);
    }

    @Test
    void 제외된_공동연출작은_다른_감독의_횟수를_올리지_않는다() {
        // 3번(100+300)이 막혀도 300의 카운트는 0이어야 한다 — 4·5번(300)이 둘 다 들어간다.
        catalog(RecommendFixtures.movie(1).directors(100).votes(9.0, 90000).build(),
                RecommendFixtures.movie(2).directors(100).votes(8.9, 90000).build(),
                RecommendFixtures.movie(3).directors(100, 300).votes(8.8, 90000).build(),
                RecommendFixtures.movie(4).directors(300).votes(7.0, 1000).build(),
                RecommendFixtures.movie(5).directors(300).votes(6.9, 1000).build());

        assertThat(ids(service.recommend(emptyProfile(), null))).containsExactly(1L, 2L, 4L, 5L);
    }

    @Test
    void 감독_정보가_없는_영화는_상한이_없다() {
        List<MovieFeature> movies = new ArrayList<>();
        for (long id = 1; id <= 5; id++) {
            movies.add(RecommendFixtures.movie(id).build());
        }
        when(featureIndex.snapshot()).thenReturn(RecommendFixtures.snapshot(movies, Map.of(), Map.of()));

        assertThat(service.recommend(emptyProfile(), null).movies()).hasSize(5);
    }

    // ── 100편 상한 ─────────────────────────────────────────────────

    @Test
    void 결과는_최대_100편이다() {
        List<MovieFeature> movies = new ArrayList<>();
        for (long id = 1; id <= 250; id++) {
            movies.add(RecommendFixtures.movie(id).directors(1000 + id).build());
        }
        when(featureIndex.snapshot()).thenReturn(RecommendFixtures.snapshot(movies, Map.of(), Map.of()));

        assertThat(service.recommend(emptyProfile(), null).movies()).hasSize(100);
    }

    @Test
    void 점수_분해에_항별_원값이_담긴다() {
        catalog(RecommendFixtures.movie(1).genres(1, 1000).directors(100).votes(8.0, 5000).build());
        UserPreferenceProfile profile = RecommendFixtures.profile(Map.of(1L, 0.5), Map.of(), Map.of(100L, 0.8), Map.of(), Set.of(), Set.of());

        RecommendationResult.ScoreBreakdown b = service.recommend(profile, null).movies().get(0).breakdown();

        assertThat(b.sim()).isNull();
        assertThat(b.genreTerm()).isEqualTo(0.5);
        assertThat(b.personTerm()).isEqualTo(0.8);
        assertThat(b.personKind()).isEqualTo("DIRECTOR");
        assertThat(b.personId()).isEqualTo(100L);
        assertThat(b.taste()).isEqualTo(0.5 * 0.5 + 0.3 * 0.8);
        assertThat(b.score()).isEqualTo(0.65 * b.taste() + 0.20 * b.quality() + 0.15 * b.popularity());
    }
}
