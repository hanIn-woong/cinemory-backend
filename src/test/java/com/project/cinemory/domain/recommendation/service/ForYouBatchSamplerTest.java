package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.recommendation.service.ForYouBatchSampler.Candidate;
import com.project.cinemory.domain.recommendation.service.ForYouBatchSampler.Selection;
import com.project.cinemory.domain.recommendation.service.RecommendationResult.RankedMovie;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;

/** Gumbel-top-k 추출 (M3b R-5-3) - 난수원을 주입하므로 시드를 고정해 결정적으로 검증한다. */
class ForYouBatchSamplerTest {

    private static final double TAU = 0.05;

    /** 점수가 id가 클수록 낮은 후보 n편 - 점수 0.9에서 0.01씩 내려간다. 감독은 영화마다 다르다. */
    private static List<Candidate> candidates(int n) {
        List<Candidate> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new Candidate(new RankedMovie(i + 1, 0.9 - 0.01 * i, null, false, null), new long[]{100 + i}));
        }
        return list;
    }

    private static List<Long> ids(Selection selection) {
        return selection.picks().stream().map(RankedMovie::movieId).toList();
    }

    @Test
    void 같은_시드면_같은_묶음이고_시드가_다르면_달라진다() {
        List<Candidate> pool = candidates(50);

        List<Long> first = ids(ForYouBatchSampler.select(pool, 10, TAU, 2, new Random(7)));
        List<Long> again = ids(ForYouBatchSampler.select(pool, 10, TAU, 2, new Random(7)));
        List<Long> other = ids(ForYouBatchSampler.select(pool, 10, TAU, 2, new Random(8)));

        assertThat(again).isEqualTo(first);
        assertThat(other).isNotEqualTo(first);
    }

    @Test
    void 묶음은_k편이고_점수_내림차순으로_돌려준다() {
        Selection selection = ForYouBatchSampler.select(candidates(50), 10, TAU, 2, new Random(1));

        assertThat(selection.picks()).hasSize(10);
        assertThat(selection.picks()).extracting(RankedMovie::score).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(ids(selection)).doesNotHaveDuplicates();
    }

    @Test
    void 점수가_높은_영화가_더_자주_뽑히지만_하위권도_가끔_뽑힌다() {
        List<Candidate> pool = candidates(50);
        Map<Long, Integer> picked = new HashMap<>();
        Random random = new Random(2026);
        for (int trial = 0; trial < 2_000; trial++) {
            ids(ForYouBatchSampler.select(pool, 10, TAU, 2, random)).forEach(id -> picked.merge(id, 1, Integer::sum));
        }

        int top10 = pool.subList(0, 10).stream().mapToInt(c -> picked.getOrDefault(c.movie().movieId(), 0)).sum();
        int bottom10 = pool.subList(40, 50).stream().mapToInt(c -> picked.getOrDefault(c.movie().movieId(), 0)).sum();
        assertThat(top10).isGreaterThan(bottom10 * 3);
        assertThat(picked.getOrDefault(30L, 0)).isPositive();   // 중위권도 가끔 뽑힌다 - 매일 같은 추천이 되지 않는다
    }

    @Test
    void 온도가_아주_낮으면_상위_점수_그대로_고정된다() {
        List<Long> top = ids(ForYouBatchSampler.select(candidates(50), 10, 1e-6, 2, new Random(3)));

        assertThat(top).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
    }

    @Test
    void 묶음_안에서_같은_감독은_최대_2편이다() {
        // 앞 20편이 모두 같은 감독 - 점수가 가장 높은 영화들이 한 감독에 몰린 최악의 경우
        List<Candidate> pool = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            pool.add(new Candidate(new RankedMovie(i + 1, 0.9 - 0.005 * i, null, false, null), new long[]{i < 20 ? 1L : 100L + i}));
        }
        for (long seed = 0; seed < 100; seed++) {
            Selection selection = ForYouBatchSampler.select(pool, 10, TAU, 2, new Random(seed));

            assertThat(selection.picks()).hasSize(10);
            assertThat(selection.picks().stream().filter(m -> m.movieId() <= 20).count()).isLessThanOrEqualTo(2);
        }
    }

    @Test
    void 감독_상한에_걸려_건너뛴_영화를_알려_준다() {
        List<Candidate> pool = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            pool.add(new Candidate(new RankedMovie(i + 1, 0.9 - 0.01 * i, null, false, null), new long[]{1L}));   // 전부 같은 감독
        }

        Selection selection = ForYouBatchSampler.select(pool, 10, 1e-6, 2, new Random(0));

        assertThat(ids(selection)).containsExactly(1L, 2L);
        assertThat(selection.directorCapped()).containsExactlyInAnyOrder(3L, 4L, 5L);
    }

    @Test
    void 공동_연출은_감독_중_한_명이라도_상한이면_건너뛴다() {
        List<Candidate> pool = List.of(
                new Candidate(new RankedMovie(1, 0.9, null, false, null), new long[]{1L}),
                new Candidate(new RankedMovie(2, 0.8, null, false, null), new long[]{1L}),
                new Candidate(new RankedMovie(3, 0.7, null, false, null), new long[]{1L, 2L}),   // 1번 감독이 이미 2편
                new Candidate(new RankedMovie(4, 0.6, null, false, null), new long[]{2L}));

        assertThat(ids(ForYouBatchSampler.select(pool, 10, 1e-6, 2, new Random(0)))).containsExactly(1L, 2L, 4L);
    }

    @Test
    void 후보가_k보다_적으면_가능한_만큼만_돌려준다() {
        assertThat(ForYouBatchSampler.select(candidates(4), 10, TAU, 2, new Random(0)).picks()).hasSize(4);
        assertThat(ForYouBatchSampler.select(List.of(), 10, TAU, 2, new Random(0)).picks()).isEmpty();
    }

    @Test
    void 추출_key는_후보마다_하나씩이다() {
        Selection selection = ForYouBatchSampler.select(candidates(20), 10, TAU, 2, new Random(5));

        assertThat(selection.keys()).hasSize(20).allSatisfy((id, key) -> assertThat(key).isFinite());
    }

    @Test
    void Gumbel_표본은_난수가_0이어도_유한하다() {
        RandomGenerator zeroFirst = new RandomGenerator() {
            private int calls;

            @Override
            public long nextLong() {
                return 0;
            }

            @Override
            public double nextDouble() {
                return calls++ == 0 ? 0.0 : 0.5;   // 첫 값은 로그가 발산하는 0 - 다시 뽑아야 한다
            }
        };

        assertThat(ForYouBatchSampler.gumbel(zeroFirst)).isFinite();
    }
}
