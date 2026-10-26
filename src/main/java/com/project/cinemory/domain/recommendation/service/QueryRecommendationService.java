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
import com.project.cinemory.domain.recommendation.service.RecommendationResult.ScoreBreakdown;
import com.project.cinemory.domain.recommendation.service.RecommendationScorer.ReasonChoice;
import com.project.cinemory.domain.recommendation.service.RecommendationScorer.Taste;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * "자연어로 찾기" 결과 생성 (M3b-recommendation-spec S-1-4 · Q-2). 질의 벡터를 받아 순위 100편까지만 만든다 —
 * 질의 임베딩 호출·캐시·호출 제한·{@code degraded} 대체는 {@code RecommendationPageService}(외부 비용이 드는 부분).
 *
 * <pre>
 * 필터   하드 필터를 <b>먼저</b> 적용한다(Q-2-4): 런타임 · 국가/제외 국가 · 개봉 시기 · 제외 장르 · 시청 기록 · 투표 수 하한.
 *        전수 스캔 중에 검사하므로 비용이 따로 들지 않고, 조건에 맞는 영화가 적어도 "DB에 있는데 결과 없음"이 생기지 않는다.
 *        찜한 영화는 제외하지 않고 wished = true
 * 후보   필터를 통과한 영화 안에서 본 질의 코사인 상위 K = 200 (고정)
 * sim    후보 안 min-max → 0~1 (후보가 1편이거나 max = min이면 1)
 * S      0.60·sim + 0.25·T + 0.15·Q + 0.10·[런타임 선호 충족] − γ·negSim
 * </pre>
 *
 * <p>{@code negSim}은 부정 개념("잔인하다")별 코사인을 후보 안에서 min-max한 값이고, 개념이 둘이면 큰 값을 쓴다(Q-2-3 ②).
 * 본 질의가 비면({@code mainVec == null}, "잔인하지 않은 영화") sim 항은 0이고 후보는 <b>필터를 통과한 영화 전체</b>다 — T·Q와 감점으로 순위를 매긴다.
 *
 * <p>다양성 상한은 적용하지 않는다 — "봉준호 영화"를 물으면 같은 감독이 정답이다. 개봉 전 영화도 거르지 않는다(질의 의도가 우선).
 */
@Service
@RequiredArgsConstructor
public class QueryRecommendationService {

    private final MovieVectorIndex vectorIndex;
    private final MovieFeatureIndex featureIndex;
    private final RecommendationScorer scorer;
    private final ReasonTextResolver reasonResolver;
    private final RecommendProperties properties;

    /**
     * @param mainVec 본 질의 벡터. {@code null}이면 본 질의가 비어 있는 것이다(sim 항 0, 후보는 필터 통과 전체)
     * @param negVecs 부정 개념 벡터 — {@code constraints.negativeConcepts()}와 같은 순서·같은 길이. 임베딩에 실패한 개념은 {@code null}
     *                (그 개념의 감점만 생략한다). {@code null} 또는 빈 리스트면 감점 없음
     */
    public RecommendationResult recommend(UserPreferenceProfile profile, QueryConstraints constraints,
                                          float[] mainVec, List<float[]> negVecs) {
        MovieVectorIndex.View view = vectorIndex.view();   // 요청 하나가 끝까지 같은 인덱스를 본다
        MovieFeatureIndex.Snapshot snapshot = featureIndex.snapshot();
        HardFilter filter = new HardFilter(constraints, scorer, profile, false, null);

        IntPredicate allowed = position -> {
            MovieFeature movie = snapshot.byId().get(view.movieIdAt(position));
            // 인덱스 재생성 사이에 삭제된 영화는 건너뛴다(E-4-5)
            return movie != null && filter.allows(movie);
        };

        List<Candidate> candidates = new ArrayList<>();
        if (mainVec != null) {
            for (MovieVectorIndex.Hit hit : view.search(mainVec, properties.query().candidateSize(), allowed)) {
                candidates.add(new Candidate(snapshot.byId().get(hit.movieId()), view.positionOf(hit.movieId()), hit.score()));
            }
        } else {
            for (int position = 0; position < view.size(); position++) {
                if (allowed.test(position)) {
                    candidates.add(new Candidate(snapshot.byId().get(view.movieIdAt(position)), position, 0f));
                }
            }
        }

        double[] simNormalized = mainVec == null ? new double[candidates.size()] : minMax(candidates, null, null);
        double[] negNormalized = negativeSimilarity(view, candidates, constraints, negVecs);
        boolean penaltyApplied = penaltyApplied(constraints, negVecs);

        List<Scored> scored = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            double sim = simNormalized[i];
            double negSim = negNormalized[i];
            Taste taste = scorer.taste(c.movie(), profile);
            double quality = scorer.quality(c.movie());
            double bonus = prefersRuntime(c.movie(), constraints.runtimePreference()) ? 1.0 : 0.0;
            scored.add(new Scored(c.movie(), sim, taste, quality, bonus, negSim,
                    scorer.queryScore(sim, taste, quality, bonus, negSim)));
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
                    new ScoreBreakdown(mainVec == null ? null : s.sim(), t.genreTerm(), t.countryTerm(), t.personTerm(),
                            t.personKind() == null ? null : t.personKind().name(), t.personId(),
                            t.t(), s.quality(), 0.0, s.bonus(), penaltyApplied ? s.negSim() : null, s.score())));
        }
        return new RecommendationResult(ranked, profile.personalized(), appliedConditions(constraints, negVecs),
                filter.toCounts(0));
    }

    /**
     * 부정 개념별 코사인을 후보 안에서 min-max → 0~1, 개념이 둘이면 후보마다 큰 값을 쓴다. 값이 모두 같으면(편차 없음) 0 —
     * 모두 같은 감점이면 순위를 바꾸지 못하므로 점수를 한꺼번에 내리지 않는다. 감점이 꺼져 있으면(γ ≤ 0) 전부 0.
     */
    private double[] negativeSimilarity(MovieVectorIndex.View view, List<Candidate> candidates,
                                        QueryConstraints constraints, List<float[]> negVecs) {
        double[] combined = new double[candidates.size()];
        if (negVecs == null || candidates.isEmpty() || properties.query().negativePenalty() <= 0) {
            return combined;
        }
        for (float[] negVec : negVecs) {
            if (negVec == null) {
                continue;
            }
            double[] normalized = minMax(candidates, view, negVec);
            for (int i = 0; i < combined.length; i++) {
                combined[i] = Math.max(combined[i], normalized[i]);
            }
        }
        return combined;
    }

    /**
     * 후보 안 min-max 정규화. {@code view == null}이면 본 질의 코사인({@code Candidate.cosine}), 아니면 {@code vector}와의 내적.
     * 후보가 1편이거나 max == min이면: 본 질의 sim은 1(S-1-4), 부정 개념은 0 — 호출부가 {@code view}로 구분한다.
     */
    private static double[] minMax(List<Candidate> candidates, MovieVectorIndex.View view, float[] vector) {
        boolean main = view == null;
        double[] raw = new double[candidates.size()];
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (int i = 0; i < raw.length; i++) {
            raw[i] = main ? candidates.get(i).cosine() : view.dot(candidates.get(i).position(), vector);
            min = Math.min(min, raw[i]);
            max = Math.max(max, raw[i]);
        }
        double[] normalized = new double[raw.length];
        for (int i = 0; i < raw.length; i++) {
            normalized[i] = max > min ? (raw[i] - min) / (max - min) : (main ? 1.0 : 0.0);
        }
        return normalized;
    }

    private boolean penaltyApplied(QueryConstraints constraints, List<float[]> negVecs) {
        return properties.query().negativePenalty() > 0 && negVecs != null && negVecs.stream().anyMatch(v -> v != null);
    }

    /** 응답 칩 — AVOID_MOOD는 감점이 <b>실제로 적용된</b> 개념(벡터가 있고 γ &gt; 0)만 남긴다(Q-2-3 칩). */
    private List<AppliedConditionResponse> appliedConditions(QueryConstraints constraints, List<float[]> negVecs) {
        Set<String> applied = new HashSet<>();
        if (properties.query().negativePenalty() > 0 && negVecs != null) {
            for (int i = 0; i < constraints.negativeConcepts().size() && i < negVecs.size(); i++) {
                if (negVecs.get(i) != null) {
                    applied.add(constraints.negativeConcepts().get(i));
                }
            }
        }
        return constraints.conditions().stream()
                .filter(c -> c.type() != ConditionType.AVOID_MOOD || applied.contains(c.label()))
                .toList();
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

    private record Candidate(MovieFeature movie, int position, double cosine) {
    }

    private record Scored(MovieFeature movie, double sim, Taste taste, double quality, double bonus, double negSim, double score) {
    }
}
