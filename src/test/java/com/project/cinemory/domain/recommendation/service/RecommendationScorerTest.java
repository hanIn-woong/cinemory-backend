package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.recommendation.dto.ReasonType;
import com.project.cinemory.domain.recommendation.index.MovieFeature;
import com.project.cinemory.domain.recommendation.service.RecommendationScorer.ReasonChoice;
import com.project.cinemory.domain.recommendation.service.RecommendationScorer.Taste;
import com.project.cinemory.domain.recommendation.support.RecommendFixtures;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** T · Q · Pop · 하한 · 추천 이유 선택 — 스펙의 수치 예시를 그대로 검증한다. */
class RecommendationScorerTest {

    private final RecommendationScorer scorer = new RecommendationScorer(RecommendFixtures.properties());

    private static final long G_DRAMA = 1, G_THRILLER = 2, C_KR = 10, C_US = 11;

    private static UserPreferenceProfile profile(Map<Long, Double> genre, Map<Long, Double> country,
                                                 Map<Long, Double> director, Map<Long, Double> actor) {
        return RecommendFixtures.profile(genre, country, director, actor, Set.of(), Set.of());
    }

    // ── T ──────────────────────────────────────────────────────────

    @Test
    void 장르항은_영화쪽_가중치로_가중평균이다() {
        MovieFeature movie = RecommendFixtures.movie(1).genres(G_DRAMA, 600, G_THRILLER, 400).build();
        UserPreferenceProfile profile = profile(Map.of(G_DRAMA, 1.0, G_THRILLER, 0.5), Map.of(), Map.of(), Map.of());

        Taste taste = scorer.taste(movie, profile);

        assertThat(taste.genreTerm()).isCloseTo(0.6 * 1.0 + 0.4 * 0.5, within(1e-12));        // 0.8
        assertThat(taste.t()).isCloseTo(0.5 * 0.8, within(1e-12));                              // wG 0.5
        assertThat(taste.genreId()).isEqualTo(G_DRAMA);                                         // 기여가 가장 큰 장르
    }

    @Test
    void 국가항도_가중평균이고_T는_세_항의_가중합이다() {
        MovieFeature movie = RecommendFixtures.movie(1)
                .genres(G_DRAMA, 1000).countries(C_KR, 700, C_US, 300).directors(100).build();
        UserPreferenceProfile profile = profile(Map.of(G_DRAMA, 0.8), Map.of(C_KR, 1.0, C_US, 0.0),
                Map.of(100L, 0.6), Map.of());

        Taste taste = scorer.taste(movie, profile);

        assertThat(taste.countryTerm()).isCloseTo(0.7, within(1e-12));
        assertThat(taste.t()).isCloseTo(0.5 * 0.8 + 0.2 * 0.7 + 0.3 * 0.6, within(1e-12));
    }

    @Test
    void 인물항은_합이_아니라_max다_앙상블이_이기지_않는다() {
        MovieFeature ensemble = RecommendFixtures.movie(1).actors(200, 201, 202, 203).build();
        MovieFeature single = RecommendFixtures.movie(2).actors(200).build();
        UserPreferenceProfile profile = profile(Map.of(), Map.of(), Map.of(),
                Map.of(200L, 0.9, 201L, 0.8, 202L, 0.7, 203L, 0.6));

        assertThat(scorer.taste(ensemble, profile).personTerm()).isEqualTo(0.9);
        assertThat(scorer.taste(single, profile).personTerm()).isEqualTo(0.9);
        assertThat(scorer.taste(ensemble, profile).personId()).isEqualTo(200L);
        assertThat(scorer.taste(ensemble, profile).personKind()).isEqualTo(ReasonType.ACTOR);
    }

    @Test
    void 감독과_배우_중_큰_쪽이_인물항이다() {
        MovieFeature movie = RecommendFixtures.movie(1).directors(100).actors(200).build();

        Taste directorWins = scorer.taste(movie, profile(Map.of(), Map.of(), Map.of(100L, 0.9), Map.of(200L, 0.5)));
        Taste actorWins = scorer.taste(movie, profile(Map.of(), Map.of(), Map.of(100L, 0.4), Map.of(200L, 0.5)));

        assertThat(directorWins.personKind()).isEqualTo(ReasonType.DIRECTOR);
        assertThat(directorWins.personId()).isEqualTo(100L);
        assertThat(actorWins.personKind()).isEqualTo(ReasonType.ACTOR);
        assertThat(actorWins.personId()).isEqualTo(200L);
    }

    @Test
    void 애니메이션_영화는_배우항을_보지_않지만_감독항은_본다() {
        MovieFeature animation = RecommendFixtures.movie(1).animation().directors(100).actors(200).build();
        UserPreferenceProfile onlyActor = profile(Map.of(), Map.of(), Map.of(), Map.of(200L, 1.0));
        UserPreferenceProfile onlyDirector = profile(Map.of(), Map.of(), Map.of(100L, 0.7), Map.of());

        assertThat(scorer.taste(animation, onlyActor).personTerm()).isZero();
        assertThat(scorer.taste(animation, onlyDirector).personTerm()).isEqualTo(0.7);
    }

    @Test
    void 빈_프로필이면_T는_0이다() {
        Taste taste = scorer.taste(RecommendFixtures.movie(1).genres(G_DRAMA, 1000).directors(100).build(),
                com.project.cinemory.domain.recommendation.service.UserPreferenceProfile.empty());

        assertThat(taste.t()).isZero();
        assertThat(taste.personKind()).isNull();
    }

    // ── Q (스펙 S-1-3 예시) ────────────────────────────────────────

    @Test
    void Q_무명작_7점_10표는_0_664다() {
        assertThat(scorer.quality(RecommendFixtures.movie(1).votes(7.0, 10).build())).isCloseTo(0.664, within(0.001));
    }

    @Test
    void Q_유명작_6점_5000표는_0_608이라_표가_많은_쪽이_평균에서_덜_당겨진다() {
        double famous = scorer.quality(RecommendFixtures.movie(1).votes(6.0, 5000).build());

        assertThat(famous).isCloseTo(0.608, within(0.001));
    }

    @Test
    void Q_길복순_184표_7_5점은_약_6_8로_당겨진다() {
        assertThat(scorer.quality(RecommendFixtures.movie(1).votes(7.5, 184).build()) * 10).isCloseTo(6.8, within(0.05));
    }

    @Test
    void Q_표가_없으면_평균_6_63을_10으로_나눈_값이다() {
        assertThat(scorer.quality(RecommendFixtures.movie(1).votes(null, null).build())).isCloseTo(0.663, within(1e-9));
        assertThat(scorer.quality(RecommendFixtures.movie(1).votes(9.0, 0).build())).isCloseTo(0.663, within(1e-9));
    }

    // ── Pop (스펙 S-1-3-B 예시) ───────────────────────────────────

    @Test
    void Pop_한국은_한국_기준으로_잰다_50표_0_53_500표_0_84() {
        assertThat(scorer.popularity(RecommendFixtures.movie(1).korean().votes(7.0, 50).build())).isCloseTo(0.53, within(0.01));
        assertThat(scorer.popularity(RecommendFixtures.movie(2).korean().votes(7.0, 500).build())).isCloseTo(0.84, within(0.01));
    }

    @Test
    void Pop_해외는_전체_기준이다_3000표_0_82() {
        assertThat(scorer.popularity(RecommendFixtures.movie(1).votes(7.0, 3000).build())).isCloseTo(0.82, within(0.01));
    }

    @Test
    void Pop_같은_50표라도_한국이_해외보다_높다_그룹별_기준의_효과() {
        double korean = scorer.popularity(RecommendFixtures.movie(1).korean().votes(7.0, 50).build());
        double other = scorer.popularity(RecommendFixtures.movie(2).votes(7.0, 50).build());

        assertThat(korean).isGreaterThan(other);
    }

    @Test
    void Pop은_1을_넘지_않고_표가_없으면_0이다() {
        assertThat(scorer.popularity(RecommendFixtures.movie(1).votes(8.0, 1_000_000).build())).isEqualTo(1.0);
        assertThat(scorer.popularity(RecommendFixtures.movie(2).votes(8.0, null).build())).isZero();
        assertThat(scorer.popularity(RecommendFixtures.movie(3).votes(8.0, 0).build())).isZero();
    }

    // ── 하한 ───────────────────────────────────────────────────────

    @Test
    void 하한_해외는_50표_미만을_거르고_표가_null이어도_거른다() {
        assertThat(scorer.passesVoteFloor(RecommendFixtures.movie(1).votes(7.0, 49).build())).isFalse();
        assertThat(scorer.passesVoteFloor(RecommendFixtures.movie(2).votes(7.0, 50).build())).isTrue();
        assertThat(scorer.passesVoteFloor(RecommendFixtures.movie(3).votes(null, null).build())).isFalse();
    }

    @Test
    void 하한_한국은_없다_표가_0이거나_null이어도_통과한다() {
        assertThat(scorer.passesVoteFloor(RecommendFixtures.movie(1).korean().votes(7.0, 10).build())).isTrue();
        assertThat(scorer.passesVoteFloor(RecommendFixtures.movie(2).korean().votes(null, null).build())).isTrue();
    }

    // ── 점수 결합 ───────────────────────────────────────────────────

    @Test
    void 취향_추천_점수는_065T_020Q_015Pop이다() {
        Taste taste = new Taste(0.5, 0, null, false, 0, null, false, 0, null, null, false);

        assertThat(scorer.forYouScore(taste, 0.6, 0.8)).isCloseTo(0.65 * 0.5 + 0.20 * 0.6 + 0.15 * 0.8, within(1e-12));
    }

    @Test
    void 자연어_점수는_06sim_025T_015Q_01런타임이다() {
        Taste taste = new Taste(0.4, 0, null, false, 0, null, false, 0, null, null, false);

        assertThat(scorer.queryScore(0.9, taste, 0.6, 1.0))
                .isCloseTo(0.6 * 0.9 + 0.25 * 0.4 + 0.15 * 0.6 + 0.10 * 1.0, within(1e-12));
        assertThat(scorer.queryScore(0.9, taste, 0.6, 0.0))
                .isCloseTo(0.6 * 0.9 + 0.25 * 0.4 + 0.15 * 0.6, within(1e-12));
    }

    // ── 추천 이유 (S-1-5 · W-1-4) ───────────────────────────────────

    @Test
    void 이유_가중치를_곱한_기여가_가장_큰_항이_이유다_인물() {
        // 인물 0.65·0.3·1.0 = 0.195 > 장르 0.65·0.5·0.3 = 0.0975 > 품질 0.2·0.6
        Taste taste = new Taste(0, 0.3, G_DRAMA, false, 0.0, null, false, 1.0, ReasonType.DIRECTOR, 100L, false);

        ReasonChoice choice = scorer.chooseReason(taste, 0.65, 0.6, 0.20, 0.15 * 0.1, 0.0);

        assertThat(choice).isEqualTo(new ReasonChoice(ReasonType.DIRECTOR, 100L));
    }

    @Test
    void 이유_원값이_커도_가중치가_작으면_밀린다_국가보다_품질() {
        // 국가 원값 1.0이어도 0.65·0.2·1.0 = 0.13 < 품질 0.20·0.9 = 0.18
        Taste taste = new Taste(0, 0, null, false, 1.0, C_KR, false, 0, null, null, false);

        ReasonChoice choice = scorer.chooseReason(taste, 0.65, 0.9, 0.20, 0.0, 0.0);

        assertThat(choice.type()).isEqualTo(ReasonType.QUALITY);
        assertThat(choice.targetId()).isNull();
    }

    @Test
    void 이유_콜드스타트는_품질_또는_인지도다() {
        Taste empty = new Taste(0, 0, null, false, 0, null, false, 0, null, null, false);

        assertThat(scorer.chooseReason(empty, 0.65, 0.66, 0.20, 0.15 * 0.2, 0.0).type()).isEqualTo(ReasonType.QUALITY);
    }

    @Test
    void 이유_인지도_기여가_품질보다_크면_인지도다() {
        Taste empty = new Taste(0, 0, null, false, 0, null, false, 0, null, null, false);

        // 품질 0.20·0.4 = 0.08 < 인지도 0.15·1.0 = 0.15
        assertThat(scorer.chooseReason(empty, 0.65, 0.4, 0.20, 0.15, 0.0).type()).isEqualTo(ReasonType.POPULARITY);
    }

    @Test
    void 이유_찜_관심이_평가보다_큰_장르는_WISH_GENRE다() {
        Taste taste = new Taste(0, 1.0, G_DRAMA, true, 0, null, false, 0, null, null, false);

        assertThat(scorer.chooseReason(taste, 0.65, 0.1, 0.20, 0.0, 0.0))
                .isEqualTo(new ReasonChoice(ReasonType.WISH_GENRE, G_DRAMA));
    }

    @Test
    void 이유_자연어에서_sim이_가장_크면_null이다() {
        Taste taste = new Taste(0, 0.2, G_DRAMA, false, 0, null, false, 0, null, null, false);

        // sim 기여 0.6·1.0 = 0.6 > 장르 0.25·0.5·0.2 = 0.025, 품질 0.15·0.6 = 0.09
        assertThat(scorer.chooseReason(taste, 0.25, 0.6, 0.15, 0.0, 0.6)).isNull();
    }

    @Test
    void 이유_자연어에서도_취향_기여가_sim보다_크면_이유가_붙는다() {
        Taste taste = new Taste(0, 0, null, false, 0, null, false, 1.0, ReasonType.ACTOR, 200L, false);

        // 인물 0.25·0.3·1.0 = 0.075 vs sim 0.6·0.05 = 0.03
        assertThat(scorer.chooseReason(taste, 0.25, 0.1, 0.15, 0.0, 0.03))
                .isEqualTo(new ReasonChoice(ReasonType.ACTOR, 200L));
    }

    @Test
    void 이유_찜_관심이_지배하는_감독은_WISH_DIRECTOR다() {
        MovieFeature movie = RecommendFixtures.movie(1).directors(100).build();
        // 감독 100: 평가 0, 관심 1.0 → 관심 우세
        UserPreferenceProfile.Axis director = new UserPreferenceProfile.Axis(Map.of(), Map.of(100L, 1.0), 1.0, Map.of(100L, 1.0));
        UserPreferenceProfile profile = new UserPreferenceProfile(null, UserPreferenceProfile.Axis.EMPTY, UserPreferenceProfile.Axis.EMPTY,
                director, Map.of(), true, Set.of(), Set.of());

        Taste taste = scorer.taste(movie, profile);

        assertThat(taste.personKind()).isEqualTo(ReasonType.WISH_DIRECTOR);
        assertThat(taste.personId()).isEqualTo(100L);
    }
}
