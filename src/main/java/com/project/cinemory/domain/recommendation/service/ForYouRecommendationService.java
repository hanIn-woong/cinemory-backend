package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.recommendation.config.RecommendProperties;
import com.project.cinemory.domain.recommendation.dto.RecommendReasonResponse;
import com.project.cinemory.domain.recommendation.index.MovieFeature;
import com.project.cinemory.domain.recommendation.index.MovieFeatureIndex;
import com.project.cinemory.domain.recommendation.service.QueryConstraintExtractor.QueryConstraints;
import com.project.cinemory.domain.recommendation.service.RecommendationResult.RankedMovie;
import com.project.cinemory.domain.recommendation.service.RecommendationResult.ScoreBreakdown;
import com.project.cinemory.domain.recommendation.service.RecommendationScorer.ReasonChoice;
import com.project.cinemory.domain.recommendation.service.RecommendationScorer.Taste;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "내 취향 추천" 결과 생성 (M3b-recommendation-spec S-1-4). 순위 100편 확정까지만 한다 — 캐시·페이징·호출 제한은 PR 3.
 *
 * <pre>
 * S = 0.65·T + 0.20·Q + 0.15·Pop
 * 제외: 시청 기록 · 찜 · 개봉 전 · 개봉일 null · 투표 수 하한 미달 (+ 대체 경로에서는 질의의 하드 필터)
 * 다양성: 같은 감독 최대 2편 (공동 연출은 감독 중 한 명이라도 상한에 걸리면 제외)
 * </pre>
 *
 * <p>게스트({@code userId == null})와 기록·찜이 없는 사용자는 빈 프로필이라 T = 0 — 같은 코드 경로가
 * "평이 좋고 알려진 영화 순"(R-3)을 낸다.
 */
@Service
@RequiredArgsConstructor
public class ForYouRecommendationService {

    private final UserPreferenceProfileService profileService;
    private final MovieFeatureIndex featureIndex;
    private final RecommendationScorer scorer;
    private final ReasonTextResolver reasonResolver;
    private final RecommendProperties properties;
    private final Clock clock;

    public RecommendationResult recommend(Long userId) {
        return recommend(profileService.build(userId), QueryConstraints.none());
    }

    /**
     * @param hardFilters 자연어 추천이 {@code degraded}로 대체될 때 질의의 하드 필터(런타임·국가·개봉 시기·제외 장르)를 그대로 유지하려고 넘긴다(5-14-F).
     *                    일반 취향 추천은 {@link QueryConstraints#none()}(또는 {@code null}). 부정 개념·본 질의는 쓰지 않는다 — 벡터가 없다.
     */
    public RecommendationResult recommend(UserPreferenceProfile profile, QueryConstraints hardFilters) {
        MovieFeatureIndex.Snapshot snapshot = featureIndex.snapshot();
        HardFilter filter = new HardFilter(hardFilters, scorer, profile, true, LocalDate.now(clock));

        List<Candidate> candidates = new ArrayList<>();
        for (MovieFeature movie : snapshot.movies()) {
            if (filter.allows(movie)) {
                Taste taste = scorer.taste(movie, profile);
                double quality = scorer.quality(movie);
                double popularity = scorer.popularity(movie);
                candidates.add(new Candidate(movie, taste, quality, popularity, scorer.forYouScore(taste, quality, popularity)));
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed()
                .thenComparingLong(c -> c.movie().movieId()));

        int directorCapped = 0;
        List<Candidate> selected = new ArrayList<>();
        Map<Long, Integer> directorCounts = new HashMap<>();
        for (Candidate candidate : candidates) {
            if (selected.size() >= properties.maxResults()) {
                break;
            }
            long[] directors = candidate.movie().directorIds();
            if (anyAtLimit(directors, directorCounts)) {
                directorCapped++;
                continue;
            }
            for (long director : directors) {
                directorCounts.merge(director, 1, Integer::sum);
            }
            selected.add(candidate);
        }

        List<ReasonChoice> choices = new ArrayList<>(selected.size());
        for (Candidate c : selected) {
            choices.add(scorer.chooseReason(c.taste(), properties.forYou().taste(), c.quality(),
                    properties.forYou().quality(), properties.forYou().popularity() * c.popularity(), 0.0));
        }
        List<RecommendReasonResponse> reasons = reasonResolver.resolve(choices);

        List<RankedMovie> ranked = new ArrayList<>(selected.size());
        for (int i = 0; i < selected.size(); i++) {
            Candidate c = selected.get(i);
            ranked.add(new RankedMovie(c.movie().movieId(), c.score(), reasons.get(i), false, breakdownOf(c)));
        }
        return new RecommendationResult(ranked, profile.personalized(), List.of(), filter.toCounts(directorCapped));
    }

    private boolean anyAtLimit(long[] directors, Map<Long, Integer> counts) {
        for (long director : directors) {
            if (counts.getOrDefault(director, 0) >= properties.maxPerDirector()) {
                return true;
            }
        }
        return false;
    }

    private static ScoreBreakdown breakdownOf(Candidate c) {
        Taste t = c.taste();
        return new ScoreBreakdown(null, t.genreTerm(), t.countryTerm(), t.personTerm(),
                t.personKind() == null ? null : t.personKind().name(), t.personId(),
                t.t(), c.quality(), c.popularity(), 0.0, null, c.score());
    }

    private record Candidate(MovieFeature movie, Taste taste, double quality, double popularity, double score) {
    }
}
