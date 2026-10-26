package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.recommendation.config.RecommendProperties;
import com.project.cinemory.domain.recommendation.dto.RecommendReasonResponse;
import com.project.cinemory.domain.recommendation.index.MovieFeature;
import com.project.cinemory.domain.recommendation.index.MovieFeatureIndex;
import com.project.cinemory.domain.recommendation.index.MovieVectorIndex;
import com.project.cinemory.domain.recommendation.service.QueryConstraintExtractor.QueryConstraints;
import com.project.cinemory.domain.recommendation.service.QueryConstraintExtractor.RuntimePreference;
import com.project.cinemory.domain.recommendation.service.RecommendationResult.ExclusionCounts;
import com.project.cinemory.domain.recommendation.service.RecommendationResult.RankedMovie;
import com.project.cinemory.domain.recommendation.service.RecommendationResult.ScoreBreakdown;
import com.project.cinemory.domain.recommendation.service.RecommendationScorer.ReasonChoice;
import com.project.cinemory.domain.recommendation.service.RecommendationScorer.Taste;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * "자연어로 찾기" 결과 생성 (M3b-recommendation-spec S-1-4). 질의 벡터를 받아 순위 100편까지만 만든다 —
 * 질의 임베딩 호출·캐시·호출 제한·{@code degraded} 대체는 PR 3(외부 비용이 드는 부분).
 *
 * <pre>
 * 후보   임베딩 코사인 상위 K = 200 (고정)
 * 필터   Q-1 명시 조건 · 시청 기록 · 투표 수 하한.  찜한 영화는 제외하지 않고 wished = true
 * sim    필터를 통과한 후보 안에서 min-max → 0~1 (후보가 1편이거나 max = min이면 1)
 * S      0.60·sim + 0.25·T + 0.15·Q + 0.10·[런타임 선호 충족]
 * </pre>
 *
 * <p>다양성 상한은 적용하지 않는다 — "봉준호 영화"를 물으면 같은 감독이 정답이다.
 * 개봉 전 영화도 제외하지 않는다(질의 의도가 우선 — 제외 규칙은 취향 추천의 것).
 */
@Service
@RequiredArgsConstructor
public class QueryRecommendationService {

    private final MovieVectorIndex vectorIndex;
    private final MovieFeatureIndex featureIndex;
    private final UserPreferenceProfileService profileService;
    private final QueryConstraintExtractor constraintExtractor;
    private final RecommendationScorer scorer;
    private final ReasonTextResolver reasonResolver;
    private final RecommendProperties properties;

    public RecommendationResult recommend(Long userId, String query, float[] queryVector) {
        return recommend(profileService.build(userId), constraintExtractor.extract(query), queryVector);
    }

    public RecommendationResult recommend(UserPreferenceProfile profile, QueryConstraints constraints, float[] queryVector) {
        MovieFeatureIndex.Snapshot snapshot = featureIndex.snapshot();
        List<MovieVectorIndex.Hit> hits = vectorIndex.search(queryVector, properties.query().candidateSize());

        int watched = 0, belowFloor = 0, runtimeFiltered = 0;
        List<Candidate> candidates = new ArrayList<>();
        for (MovieVectorIndex.Hit hit : hits) {
            MovieFeature movie = snapshot.byId().get(hit.movieId());
            if (movie == null) {
                continue;   // 인덱스 재생성 사이에 삭제된 영화 — 결과 조립 단계에서 버린다(E-4-5)
            }
            if (profile.watchedMovieIds().contains(movie.movieId())) {
                watched++;
            } else if (!scorer.passesVoteFloor(movie)) {
                belowFloor++;
            } else if (constraints.runtimeMax() != null
                    && (movie.runtime() == null || movie.runtime() > constraints.runtimeMax())) {
                runtimeFiltered++;
            } else {
                candidates.add(new Candidate(movie, hit.score()));
            }
        }

        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (Candidate c : candidates) {
            min = Math.min(min, c.cosine());
            max = Math.max(max, c.cosine());
        }

        List<Scored> scored = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            // 후보가 1편이거나 max == min이면 sim = 1 (0으로 나누지 않는다).
            double sim = max > min ? (c.cosine() - min) / (max - min) : 1.0;
            Taste taste = scorer.taste(c.movie(), profile);
            double quality = scorer.quality(c.movie());
            double bonus = prefersRuntime(c.movie(), constraints.preference()) ? 1.0 : 0.0;
            scored.add(new Scored(c.movie(), sim, taste, quality, bonus, scorer.queryScore(sim, taste, quality, bonus)));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparingLong(s -> s.movie().movieId()));
        List<Scored> top = scored.size() > properties.maxResults() ? scored.subList(0, properties.maxResults()) : scored;

        RecommendProperties.Query w = properties.query();
        List<ReasonChoice> choices = new ArrayList<>(top.size());
        for (Scored s : top) {
            // sim·런타임 가산은 이유가 될 수 없는 항이다. 이 둘이 가장 크면 영화별 이유를 달지 않는다(질의 자체가 이유).
            double other = Math.max(w.similarity() * s.sim(), w.runtimeBonus() * s.bonus());
            choices.add(scorer.chooseReason(s.taste(), w.taste(), s.quality(), w.quality(), 0.0, other));
        }
        List<RecommendReasonResponse> reasons = reasonResolver.resolve(choices);

        List<RankedMovie> ranked = new ArrayList<>(top.size());
        for (int i = 0; i < top.size(); i++) {
            Scored s = top.get(i);
            boolean wished = profile.wishedMovieIds().contains(s.movie().movieId());
            Taste t = s.taste();
            ranked.add(new RankedMovie(s.movie().movieId(), s.score(), reasons.get(i), wished,
                    new ScoreBreakdown(s.sim(), t.genreTerm(), t.countryTerm(), t.personTerm(),
                            t.personKind() == null ? null : t.personKind().name(), t.personId(),
                            t.t(), s.quality(), 0.0, s.bonus(), s.score())));
        }
        return new RecommendationResult(ranked, profile.personalized(), constraints.conditions(),
                new ExclusionCounts(watched, 0, 0, belowFloor, runtimeFiltered, 0));
    }

    /** Q-1 ② 모호한 조건("짧은"·"긴")의 충족 여부. {@code runtime}이 null이면 충족하지 않은 것으로 본다. */
    private boolean prefersRuntime(MovieFeature movie, RuntimePreference preference) {
        if (movie.runtime() == null) {
            return false;
        }
        return switch (preference) {
            case SHORT -> movie.runtime() <= properties.query().preferShortMax();
            case LONG -> movie.runtime() >= properties.query().preferLongMin();
            case NONE -> false;
        };
    }

    private record Candidate(MovieFeature movie, double cosine) {
    }

    private record Scored(MovieFeature movie, double sim, Taste taste, double quality, double bonus, double score) {
    }
}
