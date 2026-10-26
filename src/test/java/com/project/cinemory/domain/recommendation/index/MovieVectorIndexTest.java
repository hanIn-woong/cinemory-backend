package com.project.cinemory.domain.recommendation.index;

import com.project.cinemory.domain.recommendation.index.MovieVectorIndex.Hit;
import com.project.cinemory.domain.recommendation.repository.MovieEmbeddingRepository;
import com.project.cinemory.domain.recommendation.support.FakeEmbeddingClient;
import com.project.cinemory.global.infra.gemini.VectorCodec;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 메모리 벡터 인덱스 — 적재 후 top-K가 전수 정렬 결과와 같은지(DB 없이 저장소만 가짜). */
class MovieVectorIndexTest {

    private static final FakeEmbeddingClient CLIENT = new FakeEmbeddingClient();

    /** 저장소 가짜: id 오름차순으로 청크를 돌려준다(afterMovieId, Pageable 크기 존중). */
    private MovieEmbeddingRepository repositoryWith(Map<Long, float[]> rows) {
        MovieEmbeddingRepository repository = mock(MovieEmbeddingRepository.class);
        when(repository.countByModel(eq(CLIENT.modelName()), eq((short) VectorCodec.DIMENSIONS))).thenReturn((long) rows.size());
        when(repository.findVectorsAfter(eq(CLIENT.modelName()), eq((short) VectorCodec.DIMENSIONS), anyLong(), any(Pageable.class)))
                .thenAnswer(inv -> {
                    long after = inv.getArgument(2);
                    int size = ((Pageable) inv.getArgument(3)).getPageSize();
                    List<MovieEmbeddingRepository.VectorProjection> page = new ArrayList<>();
                    for (Map.Entry<Long, float[]> e : new TreeMap<>(rows).entrySet()) {
                        if (e.getKey() > after && page.size() < size) {
                            long id = e.getKey();
                            byte[] bytes = VectorCodec.encode(e.getValue());
                            page.add(new MovieEmbeddingRepository.VectorProjection() {
                                public Long getMovieId() {
                                    return id;
                                }

                                public byte[] getEmbedding() {
                                    return bytes;
                                }
                            });
                        }
                    }
                    return page;
                });
        return repository;
    }

    private static float dot(float[] a, float[] b) {
        float s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    @Test
    void top_K는_전수_정렬_결과와_같다() {
        // 청크 크기(1000)를 넘겨 여러 청크에 걸치게 한다.
        Map<Long, float[]> rows = new TreeMap<>();
        for (long id = 1; id <= 2500; id++) {
            rows.put(id * 3, FakeEmbeddingClient.vectorOf("movie-" + id));
        }
        MovieVectorIndex index = new MovieVectorIndex(repositoryWith(rows), CLIENT);
        index.reload();
        float[] query = FakeEmbeddingClient.vectorOf("some-query");

        List<Hit> hits = index.search(query, 200);

        List<Hit> expected = rows.entrySet().stream()
                .map(e -> new Hit(e.getKey(), dot(query, e.getValue())))
                .sorted(Comparator.comparingDouble(Hit::score).reversed().thenComparingLong(Hit::movieId))
                .limit(200)
                .toList();
        assertThat(index.size()).isEqualTo(2500);
        assertThat(hits).isEqualTo(expected);
    }

    @Test
    void 질의와_같은_벡터가_1위이고_점수는_약_1이다() {
        Map<Long, float[]> rows = new TreeMap<>();
        for (long id = 1; id <= 50; id++) {
            rows.put(id, FakeEmbeddingClient.vectorOf("m" + id));
        }
        MovieVectorIndex index = new MovieVectorIndex(repositoryWith(rows), CLIENT);
        index.reload();

        List<Hit> hits = index.search(rows.get(17L), 3);

        assertThat(hits.get(0).movieId()).isEqualTo(17L);
        assertThat(hits.get(0).score()).isCloseTo(1.0f, org.assertj.core.data.Offset.offset(1e-4f));
        assertThat(hits).hasSize(3).isSortedAccordingTo(Comparator.comparingDouble(Hit::score).reversed());
    }

    @Test
    void k가_전체보다_크면_전부_돌려주고_빈_인덱스는_빈_결과다() {
        Map<Long, float[]> rows = new TreeMap<>();
        rows.put(1L, FakeEmbeddingClient.vectorOf("a"));
        rows.put(2L, FakeEmbeddingClient.vectorOf("b"));
        MovieVectorIndex index = new MovieVectorIndex(repositoryWith(rows), CLIENT);
        MovieVectorIndex empty = new MovieVectorIndex(repositoryWith(Map.of()), CLIENT);
        index.reload();
        empty.reload();

        assertThat(index.search(FakeEmbeddingClient.vectorOf("q"), 200)).hasSize(2);
        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.search(FakeEmbeddingClient.vectorOf("q"), 10)).isEmpty();
    }

    @Test
    void 동점이면_movieId_오름차순이다() {
        float[] same = FakeEmbeddingClient.vectorOf("same");
        Map<Long, float[]> rows = new TreeMap<>();
        for (long id : new long[]{9, 3, 7}) {
            rows.put(id, same);
        }
        MovieVectorIndex index = new MovieVectorIndex(repositoryWith(rows), CLIENT);
        index.reload();

        assertThat(index.search(same, 2)).extracting(Hit::movieId).containsExactly(3L, 7L);
    }

    @Test
    void reload는_새_인덱스를_다_만든_뒤_교체한다_실패하면_기존_인덱스가_남는다() {
        Map<Long, float[]> rows = new TreeMap<>();
        rows.put(1L, FakeEmbeddingClient.vectorOf("a"));
        MovieEmbeddingRepository repository = repositoryWith(rows);
        MovieVectorIndex index = new MovieVectorIndex(repository, CLIENT);
        index.reload();
        assertThat(index.size()).isEqualTo(1);

        when(repository.countByModel(any(), any())).thenThrow(new IllegalStateException("db down"));
        try {
            index.reload();
        } catch (IllegalStateException expected) {
            // 교체 전에 실패
        }

        assertThat(index.size()).isEqualTo(1);
        assertThat(index.search(FakeEmbeddingClient.vectorOf("a"), 1)).extracting(Hit::movieId).containsExactly(1L);
    }

    @Test
    void 질의_벡터_차원이_다르면_거부한다() {
        MovieVectorIndex index = new MovieVectorIndex(repositoryWith(Map.of()), CLIENT);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> index.search(new float[10], 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 대량_비교에서도_결정적이다() {
        Random random = new Random(7);
        Map<Long, float[]> rows = new TreeMap<>();
        for (long id = 1; id <= 300; id++) {
            rows.put(id, FakeEmbeddingClient.vectorOf("r" + random.nextInt()));
        }
        MovieVectorIndex index = new MovieVectorIndex(repositoryWith(rows), CLIENT);
        index.reload();
        float[] query = FakeEmbeddingClient.vectorOf("q");

        assertThat(index.search(query, 20)).isEqualTo(index.search(query, 20));
    }
}
