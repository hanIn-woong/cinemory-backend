package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.recommendation.service.RecommendationCache.Entry;
import com.project.cinemory.domain.recommendation.support.RecommendFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationCacheTest {

    private final RecommendationCache cache = new RecommendationCache(RecommendFixtures.properties());

    private static Entry entry(boolean personalized) {
        return new Entry(List.of(), personalized, List.of());
    }

    @Test
    void 게스트_결과는_같은_세대에서_한_번만_계산한다() {
        Object generation = new Object();
        AtomicInteger computed = new AtomicInteger();

        Entry first = cache.guest(generation, () -> {
            computed.incrementAndGet();
            return entry(false);
        });
        Entry second = cache.guest(generation, () -> {
            computed.incrementAndGet();
            return entry(false);
        });

        assertThat(computed).hasValue(1);
        assertThat(second).isSameAs(first);
    }

    @Test
    void 세대가_바뀌면_다시_계산한다_인덱스_재생성_직후() {
        AtomicInteger computed = new AtomicInteger();

        cache.guest(new Object(), () -> {
            computed.incrementAndGet();
            return entry(false);
        });
        cache.guest(new Object(), () -> {
            computed.incrementAndGet();
            return entry(false);
        });

        assertThat(computed).hasValue(2);
    }

    @Test
    void 동시에_첫_요청이_몰려도_한_번만_계산한다() throws Exception {
        Object generation = new Object();
        AtomicInteger computed = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Entry>>();
            for (int i = 0; i < 16; i++) {
                futures.add(executor.submit(() -> cache.guest(generation, () -> {
                    computed.incrementAndGet();
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return entry(false);
                })));
            }
            for (var future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(computed).hasValue(1);
    }

    @Test
    void 결과_캐시는_키별로_저장하고_교체한다() {
        cache.putResult(RecommendationCache.forYouKey(1), entry(true));
        cache.putResult(RecommendationCache.forYouKey(1), entry(false));

        assertThat(cache.getResult(RecommendationCache.forYouKey(1)).personalized()).isFalse();
        assertThat(cache.getResult(RecommendationCache.forYouKey(2))).isNull();
        assertThat(cache.getResult(RecommendationCache.queryKey(1, "q"))).isNull();
    }

    @Test
    void 질의_임베딩_캐시는_사용자와_무관하게_공유된다() {
        float[] vector = new float[768];
        cache.putQueryEmbedding("비 오는 날", vector);

        assertThat(cache.getQueryEmbedding("비 오는 날")).isSameAs(vector);
        assertThat(cache.getQueryEmbedding("다른 질의")).isNull();
    }

    @Test
    void 키_형식() {
        assertThat(RecommendationCache.forYouKey(7)).isEqualTo("FY:7");
        assertThat(RecommendationCache.queryKey(7, "abc")).isEqualTo("Q:7:abc");
    }
}
