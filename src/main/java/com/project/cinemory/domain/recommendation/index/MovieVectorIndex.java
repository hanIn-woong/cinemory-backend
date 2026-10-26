package com.project.cinemory.domain.recommendation.index;

import com.project.cinemory.domain.recommendation.repository.MovieEmbeddingRepository;
import com.project.cinemory.global.infra.gemini.EmbeddingClient;
import com.project.cinemory.global.infra.gemini.VectorCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.IntPredicate;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 영화 벡터의 메모리 인덱스 (M3b-recommendation-spec E-4-5).
 *
 * <pre>
 * long[]  movieIds   // n
 * float[] vectors    // n x 768 연속 배열
 * </pre>
 *
 * <ul>
 *   <li><b>적재</b> — 기동 시 전량. 현재 {@code EmbeddingClient.modelName()}·768차원과 일치하는 행만 올린다. 모델 교체 중 섞인 옛 벡터는 무시된다.</li>
 *   <li><b>교체</b> — 새 인덱스를 다 만든 뒤 {@link AtomicReference}로 한 번에 바꾼다. 검색 중인 요청은 반쯤 만든 인덱스를 보지 않는다.
 *       교체 순간 두 벌이 있어 힙이 일시적으로 약 2배(≈ 80MB) 쓰인다.</li>
 *   <li><b>검색</b> — 전수 내적 + 최소 힙. 벡터가 정규화돼 있어 내적 = 코사인 유사도.</li>
 * </ul>
 */
@Slf4j
@Component
public class MovieVectorIndex {

    // 한 번에 읽는 행 수. 행당 약 3KB라 청크당 3MB 안팎 — 적재 중 힙 순간 사용을 제한한다.
    private static final int LOAD_CHUNK_SIZE = 1000;

    private final MovieEmbeddingRepository embeddingRepository;
    private final EmbeddingClient embeddingClient;

    private final AtomicReference<Snapshot> current = new AtomicReference<>(Snapshot.EMPTY);

    public MovieVectorIndex(MovieEmbeddingRepository embeddingRepository, EmbeddingClient embeddingClient) {
        this.embeddingRepository = embeddingRepository;
        this.embeddingClient = embeddingClient;
    }

    /** @param score 질의 벡터와의 내적(= 코사인 유사도) */
    public record Hit(long movieId, float score) {
    }

    private record Snapshot(long[] movieIds, float[] vectors) {
        static final Snapshot EMPTY = new Snapshot(new long[0], new float[0]);

        int size() {
            return movieIds.length;
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        // 인덱스가 비어도 서비스는 떠야 한다 — 자연어 추천이 규칙 기반으로 대체된다(E-4-5).
        try {
            reload();
        } catch (RuntimeException e) {
            log.error("벡터 인덱스 초기 적재에 실패했습니다. 빈 인덱스로 시작합니다.", e);
        }
    }

    /** 새 인덱스를 만들어 교체한다. 만드는 도중 실패하면 기존 인덱스가 그대로 남는다. */
    public void reload() {
        long startedAt = System.currentTimeMillis();
        String modelName = embeddingClient.modelName();
        Short dimensions = (short) VectorCodec.DIMENSIONS;

        int capacity = (int) Math.min(Integer.MAX_VALUE / VectorCodec.DIMENSIONS,
                embeddingRepository.countByModel(modelName, dimensions) + LOAD_CHUNK_SIZE);
        long[] ids = new long[capacity];
        float[] vectors = new float[capacity * VectorCodec.DIMENSIONS];

        int size = 0;
        long afterMovieId = 0L;
        while (true) {
            List<MovieEmbeddingRepository.VectorProjection> rows = embeddingRepository
                    .findVectorsAfter(modelName, dimensions, afterMovieId, PageRequest.of(0, LOAD_CHUNK_SIZE));
            if (rows.isEmpty()) {
                break;
            }
            if (size + rows.size() > ids.length) {
                // 센 뒤에 행이 늘어난 경우(배치 도중 재적재 등)
                int grown = size + rows.size() + LOAD_CHUNK_SIZE;
                ids = Arrays.copyOf(ids, grown);
                vectors = Arrays.copyOf(vectors, grown * VectorCodec.DIMENSIONS);
            }
            for (MovieEmbeddingRepository.VectorProjection row : rows) {
                ids[size] = row.getMovieId();
                VectorCodec.decodeInto(row.getEmbedding(), vectors, size * VectorCodec.DIMENSIONS);
                size++;
                afterMovieId = row.getMovieId();
            }
        }

        if (size != ids.length) {
            ids = Arrays.copyOf(ids, size);
            vectors = Arrays.copyOf(vectors, size * VectorCodec.DIMENSIONS);
        }
        current.set(new Snapshot(ids, vectors));
        log.info("벡터 인덱스 적재 완료. model={}, size={}, elapsed={}ms", modelName, size, System.currentTimeMillis() - startedAt);
    }

    public int size() {
        return current.get().size();
    }

    public boolean isEmpty() {
        return current.get().size() == 0;
    }

    /**
     * 질의 벡터와 내적이 큰 상위 {@code k}편. 점수 내림차순, 같으면 movieId 오름차순(결정적).
     * 인덱스가 비었거나 {@code k <= 0}이면 빈 리스트다.
     */
    public List<Hit> search(float[] queryVector, int k) {
        return view().search(queryVector, k, position -> true);
    }

    /**
     * 조건을 통과한 영화만 대상으로 한 상위 {@code k}편 (Q-2-4 필터 선적용). {@code allowed}는 <b>인덱스 위치</b>를 받는다.
     *
     * <p>⚠️ 위치는 이 호출이 쓰는 스냅샷 기준이라, 판정 함수 안에서 위치 → movieId 변환이 필요하면 반드시 같은 {@link View}를 써야 한다
     * (그 사이 {@link #reload()}가 인덱스를 교체해도 어긋나지 않도록). 그런 경우엔 {@link #view()}로 한 번 받아 끝까지 쓴다.
     */
    public List<Hit> search(float[] queryVector, int k, IntPredicate allowed) {
        return view().search(queryVector, k, allowed);
    }

    /** 요청 하나가 끝까지 같은 인덱스를 보게 하는 불변 뷰 — 중간에 {@code reload()}로 교체돼도 이 뷰는 바뀌지 않는다. */
    public View view() {
        return new View(current.get());
    }

    /** 한 시점의 인덱스 스냅샷. 위치(position)는 이 뷰 안에서만 의미가 있다. */
    public static final class View {

        private final Snapshot snapshot;

        private View(Snapshot snapshot) {
            this.snapshot = snapshot;
        }

        public int size() {
            return snapshot.size();
        }

        public long movieIdAt(int position) {
            return snapshot.movieIds()[position];
        }

        /** 영화 id의 위치. 인덱스는 movie id 오름차순으로 적재되므로 이분 탐색이다. 없으면 -1. */
        public int positionOf(long movieId) {
            int position = Arrays.binarySearch(snapshot.movieIds(), movieId);
            return position >= 0 ? position : -1;
        }

        /** 위치의 벡터와 {@code queryVector}의 내적. */
        public float dot(int position, float[] queryVector) {
            float[] vectors = snapshot.vectors();
            int base = position * VectorCodec.DIMENSIONS;
            float score = 0f;
            for (int d = 0; d < VectorCodec.DIMENSIONS; d++) {
                score += queryVector[d] * vectors[base + d];
            }
            return score;
        }

        /**
         * {@code allowed}가 true인 위치만 후보로 삼아 상위 {@code k}편을 찾는다. 전수 스캔 중에 검사하므로 필터 비용이 따로 들지 않는다.
         * {@code allowed}는 위치마다 정확히 한 번 호출된다(스캔 중 제외 건수를 집계하는 데 쓸 수 있다).
         */
        public List<Hit> search(float[] queryVector, int k, IntPredicate allowed) {
            if (queryVector.length != VectorCodec.DIMENSIONS) {
                throw new IllegalArgumentException("질의 벡터 차원이 " + VectorCodec.DIMENSIONS + "이 아닙니다: " + queryVector.length);
            }
            if (k <= 0 || snapshot.size() == 0) {
                return List.of();
            }
            // 최소 힙: 루트가 현재 상위 k 중 가장 낮은 점수(같으면 movieId가 큰 쪽이 먼저 밀려난다).
            PriorityQueue<Hit> heap = new PriorityQueue<>(k + 1, HEAP_ORDER);
            long[] ids = snapshot.movieIds();
            for (int i = 0; i < ids.length; i++) {
                if (!allowed.test(i)) {
                    continue;
                }
                float score = dot(i, queryVector);
                if (heap.size() < k) {
                    heap.add(new Hit(ids[i], score));
                } else {
                    Hit worst = heap.peek();
                    if (score > worst.score() || (score == worst.score() && ids[i] < worst.movieId())) {
                        heap.poll();
                        heap.add(new Hit(ids[i], score));
                    }
                }
            }
            List<Hit> result = new ArrayList<>(heap);
            result.sort(RESULT_ORDER);
            return result;
        }
    }

    private static final Comparator<Hit> HEAP_ORDER = Comparator
            .comparingDouble(Hit::score)
            .thenComparing(Comparator.comparingLong(Hit::movieId).reversed());

    private static final Comparator<Hit> RESULT_ORDER = Comparator
            .comparingDouble(Hit::score).reversed()
            .thenComparingLong(Hit::movieId);
}
