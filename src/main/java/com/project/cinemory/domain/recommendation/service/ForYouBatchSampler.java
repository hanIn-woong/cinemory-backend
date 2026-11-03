package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.recommendation.service.RecommendationResult.RankedMovie;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * 점수 비례 무작위 추출 - Gumbel-top-k (M3b-recommendation-spec R-5-3).
 *
 * <pre>
 * key = S / τ + Gumbel 잡음 (Gumbel = −ln(−ln U), U ~ (0, 1))     key 상위 k편을 고른다
 * </pre>
 *
 * <p>S에 비례하는 확률로 중복 없이 뽑는 표준 기법이다. τ를 낮추면 상위 점수에 고정되고 높이면 섞인다.
 * <b>무작위는 "무엇이 뽑히나"에만</b> 쓴다 - 뽑힌 묶음은 S 내림차순으로 돌려준다.
 *
 * <p>순수 함수이고 난수원({@link RandomGenerator})을 주입받으므로 테스트가 시드를 고정할 수 있다. 후보는 호출부가 정한 순서대로
 * 난수를 소비하므로(풀은 S 내림차순·같으면 id 오름차순) 같은 시드·같은 입력이면 같은 결과다.
 */
final class ForYouBatchSampler {

    private ForYouBatchSampler() {
    }

    /** 추출 후보 - {@code directorIds}는 묶음 안 감독 상한(S-1-4)에 쓴다. */
    record Candidate(RankedMovie movie, long[] directorIds) {
    }

    /**
     * @param picks          뽑힌 영화 - S 내림차순(같으면 id 오름차순)
     * @param keys           후보별 추출 key(디버그용). 후보로 올라오지 못한(제외된) 영화는 없다
     * @param directorCapped key 순위로는 뽑힐 차례였으나 같은 감독 상한에 걸려 건너뛴 영화 id
     */
    record Selection(List<RankedMovie> picks, Map<Long, Double> keys, Set<Long> directorCapped) {
    }

    static Selection select(List<Candidate> available, int k, double tau, int maxPerDirector, RandomGenerator random) {
        Map<Long, Double> keys = new HashMap<>(available.size() * 2);
        for (Candidate candidate : available) {   // 난수 소비 순서 = 호출부가 넘긴 순서(결정적)
            keys.put(candidate.movie().movieId(), candidate.movie().score() / tau + gumbel(random));
        }
        List<Candidate> byKey = new ArrayList<>(available);
        byKey.sort(Comparator.<Candidate>comparingDouble(c -> keys.get(c.movie().movieId())).reversed()
                .thenComparingLong(c -> c.movie().movieId()));

        List<RankedMovie> picks = new ArrayList<>(k);
        Set<Long> capped = new HashSet<>();
        Map<Long, Integer> directorCounts = new HashMap<>();
        for (Candidate candidate : byKey) {
            if (picks.size() >= k) {
                break;
            }
            if (anyAtLimit(candidate.directorIds(), directorCounts, maxPerDirector)) {
                capped.add(candidate.movie().movieId());
                continue;
            }
            for (long director : candidate.directorIds()) {
                directorCounts.merge(director, 1, Integer::sum);
            }
            picks.add(candidate.movie());
        }
        picks.sort(Comparator.comparingDouble(RankedMovie::score).reversed().thenComparingLong(RankedMovie::movieId));
        return new Selection(picks, keys, capped);
    }

    /** 표준 Gumbel(0, 1) 표본. U는 (0, 1) 열린 구간이어야 로그가 유한하다. */
    static double gumbel(RandomGenerator random) {
        double u;
        do {
            u = random.nextDouble();   // [0, 1)
        } while (u <= 0.0);
        return -Math.log(-Math.log(u));
    }

    private static boolean anyAtLimit(long[] directors, Map<Long, Integer> counts, int limit) {
        for (long director : directors) {
            if (counts.getOrDefault(director, 0) >= limit) {
                return true;
            }
        }
        return false;
    }
}
