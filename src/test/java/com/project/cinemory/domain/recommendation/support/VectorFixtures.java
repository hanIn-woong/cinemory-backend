package com.project.cinemory.domain.recommendation.support;

import com.project.cinemory.domain.recommendation.index.MovieVectorIndex;
import com.project.cinemory.domain.recommendation.repository.MovieEmbeddingRepository;
import com.project.cinemory.global.infra.gemini.VectorCodec;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 코사인을 직접 정해 주는 벡터와, 저장소만 가짜인 실제 {@link MovieVectorIndex}.
 *
 * <p>질의 벡터를 {@link #axis}(0)으로, 부정 개념 벡터를 {@link #axis}(1)로 두면 영화 벡터 {@code vec(a, b)}와의 내적이 각각 {@code a}, {@code b}가 된다.
 */
public final class VectorFixtures {

    private VectorFixtures() {
    }

    /** 단위 벡터 — {@code axis(0)} = 본 질의, {@code axis(1)} = 첫 부정 개념, {@code axis(2)} = 둘째 부정 개념. */
    public static float[] axis(int index) {
        float[] v = new float[VectorCodec.DIMENSIONS];
        v[index] = 1f;
        return v;
    }

    /** 본 질의와의 코사인이 {@code main}인 단위 벡터. */
    public static float[] cos(double main) {
        return vec(main, 0.0, 0.0);
    }

    /** 본 질의 {@code main}, 첫 부정 개념 {@code neg1}, 둘째 부정 개념 {@code neg2}와의 내적이 각각 그 값인 벡터(정규화는 하지 않는다). */
    public static float[] vec(double main, double neg1, double neg2) {
        float[] v = new float[VectorCodec.DIMENSIONS];
        v[0] = (float) main;
        v[1] = (float) neg1;
        v[2] = (float) neg2;
        // 나머지 성분으로 단위 길이를 맞춘다(필요할 때만) — 내적 검증에는 영향이 없다.
        double rest = 1.0 - main * main - neg1 * neg1 - neg2 * neg2;
        v[3] = rest > 0 ? (float) Math.sqrt(rest) : 0f;
        return v;
    }

    /** 영화 id → 벡터를 적재한 실제 인덱스. 저장소만 가짜다. */
    public static MovieVectorIndex indexOf(Map<Long, float[]> rows) {
        FakeEmbeddingClient client = new FakeEmbeddingClient();
        MovieEmbeddingRepository repository = mock(MovieEmbeddingRepository.class);
        when(repository.countByModel(eq(client.modelName()), eq((short) VectorCodec.DIMENSIONS))).thenReturn((long) rows.size());
        when(repository.findVectorsAfter(eq(client.modelName()), eq((short) VectorCodec.DIMENSIONS), anyLong(), any(Pageable.class)))
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
        MovieVectorIndex index = new MovieVectorIndex(repository, client);
        index.reload();
        return index;
    }
}
