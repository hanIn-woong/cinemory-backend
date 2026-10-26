package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.recommendation.index.MovieFeatureIndex;
import com.project.cinemory.domain.recommendation.index.MovieVectorIndex;
import com.project.cinemory.domain.recommendation.repository.MovieEmbeddingRepository;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.gemini.EmbeddingClient;
import com.project.cinemory.global.infra.gemini.EmbeddingClientException;
import com.project.cinemory.global.infra.gemini.EmbeddingDocument;
import com.project.cinemory.global.infra.gemini.EmbeddingRateLimitedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 영화 임베딩 생성·갱신 배치 (M3b-recommendation-spec E-4-3 · E-4-4).
 *
 * <p>⚠️ <b>트랜잭션이 없다.</b> Gemini 왕복을 DB 트랜잭션 밖에 둔다 — 읽기는 {@link EmbeddingInputLoader},
 * 저장은 {@link MovieEmbeddingPersister}가 각자 짧게 연다(tmdb-sync-spec 6-4).
 *
 * <p>{@code running} 플래그는 스케줄과 수동 실행이 <b>공유</b>하고 시드·resync 플래그와는 <b>분리</b>한다 — TMDB와 Gemini는 호출 한도가 다르다.
 *
 * <p>전량 해시 비교(E-4-4 ⓒ)라 무엇이 바뀌든 텍스트가 바뀐 영화만 다시 임베딩한다. 비용은 DB 읽기뿐.
 */
@Slf4j
@Service
public class EmbeddingSyncService {

    private final EmbeddingInputLoader inputLoader;
    private final MovieEmbeddingPersister persister;
    private final MovieEmbeddingRepository embeddingRepository;
    private final EmbeddingClient embeddingClient;
    private final MovieVectorIndex vectorIndex;
    private final MovieFeatureIndex featureIndex;
    private final EmbeddingSyncProperties properties;

    // 서버가 알려 준 대기 시간 직후에 다시 보내면 경계에서 또 429가 난다.
    private static final long RETRY_SLACK_MILLIS = 1_000L;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private long lastRequestAtNanos;

    public EmbeddingSyncService(EmbeddingInputLoader inputLoader,
                                MovieEmbeddingPersister persister,
                                MovieEmbeddingRepository embeddingRepository,
                                EmbeddingClient embeddingClient,
                                MovieVectorIndex vectorIndex,
                                MovieFeatureIndex featureIndex,
                                EmbeddingSyncProperties properties) {
        this.inputLoader = inputLoader;
        this.persister = persister;
        this.embeddingRepository = embeddingRepository;
        this.embeddingClient = embeddingClient;
        this.vectorIndex = vectorIndex;
        this.featureIndex = featureIndex;
        this.properties = properties;
    }

    /**
     * @param afterId 이 id 다음 영화부터. {@code null}이면 처음부터. 429로 중단된 결과의 {@code lastProcessedId}를 넣어 이어받는다.
     * @param limit   이번 실행에서 훑을 영화 수 상한. {@code null}이면 끝까지(스케줄).
     * @throws BusinessException {@code EMBEDDING_SYNC_ALREADY_RUNNING}(409)
     */
    public EmbeddingSyncResult sync(Long afterId, Integer limit) {
        if (!running.compareAndSet(false, true)) {
            throw new BusinessException(ErrorCode.EMBEDDING_SYNC_ALREADY_RUNNING);
        }
        try {
            EmbeddingSyncResult result = run(afterId, limit);
            reloadIndexes();
            log.info("임베딩 배치 완료. {}", result);
            if (result.embedded() > properties.warnEmbeddedThreshold()) {
                // 첫 적재·모델 교체·템플릿 변경 같은 수동 실행은 예외로 본다. 변경이 없는 날 이 값이 크면 텍스트 조립 순서가 흔들린 것이다(E-4-3).
                log.warn("임베딩 배치의 embedded가 {}건으로 임계({})를 넘었습니다. 첫 적재·모델 교체가 아니라면 텍스트 조립의 결정성을 확인하세요.",
                        result.embedded(), properties.warnEmbeddedThreshold());
            }
            return result;
        } finally {
            running.set(false);
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    private EmbeddingSyncResult run(Long afterId, Integer limit) {
        String modelName = embeddingClient.modelName();
        long cursor = afterId != null ? afterId : 0L;
        Long lastProcessedId = afterId;
        int remaining = limit != null ? limit : Integer.MAX_VALUE;
        int embedded = 0;
        int unchanged = 0;
        int failed = 0;
        boolean stoppedByRateLimit = false;

        while (remaining > 0) {
            List<EmbeddingInputLoader.Prepared> chunk = inputLoader.load(cursor, Math.min(properties.batchSize(), remaining));
            if (chunk.isEmpty()) {
                break;
            }
            Map<Long, String> storedHashes = embeddingRepository
                    .findHashesByMovieIdIn(chunk.stream().map(EmbeddingInputLoader.Prepared::movieId).toList()).stream()
                    .collect(Collectors.toMap(h -> h.getMovieId(), h -> h.getSourceHash()));

            List<EmbeddingInputLoader.Prepared> targets = new ArrayList<>();
            List<String> targetHashes = new ArrayList<>();
            for (EmbeddingInputLoader.Prepared p : chunk) {
                String hash = p.text().hash(modelName);
                if (hash.equals(storedHashes.get(p.movieId()))) {
                    unchanged++;
                } else {
                    targets.add(p);
                    targetHashes.add(hash);
                }
            }

            ChunkOutcome outcome = embedChunk(modelName, targets, targetHashes);
            embedded += outcome.embedded();
            failed += outcome.failed();

            if (outcome.firstUnprocessedMovieId() != null) {
                stoppedByRateLimit = true;
                // 처리하지 못한 첫 영화 바로 앞까지만 전진한다. 그 앞은 저장했거나 unchanged·failed로 지나간 영화다.
                lastProcessedId = lastIdBefore(chunk, outcome.firstUnprocessedMovieId(), lastProcessedId);
                log.warn("임베딩 배치: rate limit 도달, 중단합니다. embedded={}, unchanged={}, failed={}, lastProcessedId={}",
                        embedded, unchanged, failed, lastProcessedId);
                break;
            }

            long chunkLastId = chunk.get(chunk.size() - 1).movieId();
            lastProcessedId = chunkLastId;
            cursor = chunkLastId;
            remaining -= chunk.size();
        }
        return new EmbeddingSyncResult(embedded, unchanged, failed, stoppedByRateLimit, lastProcessedId);
    }

    private Long lastIdBefore(List<EmbeddingInputLoader.Prepared> chunk, long firstUnprocessedId, Long fallback) {
        Long last = fallback;
        for (EmbeddingInputLoader.Prepared p : chunk) {
            if (p.movieId() >= firstUnprocessedId) {
                break;
            }
            last = p.movieId();
        }
        return last;
    }

    /**
     * 대상 영화들을 임베딩해 저장한다. 묶음 요청이 (429가 아닌 이유로) 실패하면 한 건씩 다시 시도해 실패한 영화만 건너뛴다.
     */
    private ChunkOutcome embedChunk(String modelName, List<EmbeddingInputLoader.Prepared> targets, List<String> hashes) {
        if (targets.isEmpty()) {
            return new ChunkOutcome(0, 0, null);
        }
        List<EmbeddingDocument> documents = targets.stream()
                .map(p -> new EmbeddingDocument(p.text().title(), p.text().body()))
                .toList();
        try {
            List<float[]> vectors = withBackoff(() -> embeddingClient.embedDocuments(documents));
            persister.upsert(modelName, items(targets, hashes, vectors));
            return new ChunkOutcome(targets.size(), 0, null);
        } catch (EmbeddingRateLimitedException e) {
            return new ChunkOutcome(0, 0, targets.get(0).movieId());
        } catch (EmbeddingClientException e) {
            log.warn("임베딩 묶음 요청 실패, 한 건씩 재시도합니다. size={}, reason={}", targets.size(), e.getMessage());
            return embedOneByOne(modelName, targets, hashes, documents);
        }
    }

    private ChunkOutcome embedOneByOne(String modelName, List<EmbeddingInputLoader.Prepared> targets,
                                       List<String> hashes, List<EmbeddingDocument> documents) {
        int embedded = 0;
        int failed = 0;
        for (int i = 0; i < targets.size(); i++) {
            EmbeddingInputLoader.Prepared target = targets.get(i);
            List<EmbeddingDocument> single = List.of(documents.get(i));
            try {
                List<float[]> vector = withBackoff(() -> embeddingClient.embedDocuments(single));
                persister.upsert(modelName, items(List.of(target), List.of(hashes.get(i)), vector));
                embedded++;
            } catch (EmbeddingRateLimitedException e) {
                return new ChunkOutcome(embedded, failed, target.movieId());
            } catch (EmbeddingClientException e) {
                log.warn("임베딩 실패. movieId={}, reason={}", target.movieId(), e.getMessage());
                failed++;
            }
        }
        return new ChunkOutcome(embedded, failed, null);
    }

    private List<MovieEmbeddingPersister.Item> items(List<EmbeddingInputLoader.Prepared> targets,
                                                      List<String> hashes, List<float[]> vectors) {
        List<MovieEmbeddingPersister.Item> items = new ArrayList<>(targets.size());
        for (int i = 0; i < targets.size(); i++) {
            items.add(new MovieEmbeddingPersister.Item(targets.get(i).movieId(), targets.get(i).text(), hashes.get(i), vectors.get(i)));
        }
        return items;
    }

    /** 429만 백오프 후 재시도한다. 소진되면 마지막 {@link EmbeddingRateLimitedException}을 그대로 던진다. */
    private <T> T withBackoff(Supplier<T> call) {
        long backoffMillis = properties.backoff().toMillis();
        for (int attempt = 0; ; attempt++) {
            try {
                paceRequests();
                return call.get();
            } catch (EmbeddingRateLimitedException e) {
                if (attempt >= properties.maxRetries()) {
                    throw e;
                }
                long wait = (e.getRetryAfterMillis() != null ? e.getRetryAfterMillis() + RETRY_SLACK_MILLIS : backoffMillis << attempt);
                if (wait > properties.maxWait().toMillis()) {
                    // 일일 한도 소진 같은 긴 대기 — 잠들지 않고 중단해 lastProcessedId를 돌려준다.
                    log.warn("임베딩 429: 대기 {}ms가 상한({}ms)을 넘어 재시도하지 않습니다.", wait, properties.maxWait().toMillis());
                    throw e;
                }
                log.warn("임베딩 429 수신. {}ms 대기 후 재시도 ({}/{}) {}", wait, attempt + 1, properties.maxRetries(), e.getMessage());
                sleep(wait);
            }
        }
    }

    /** 직전 요청과의 간격이 {@code min-interval}보다 짧으면 그만큼 기다린다. 배치는 단일 실행이라 필드 접근은 직렬이다. */
    private void paceRequests() {
        long interval = properties.minInterval().toNanos();
        if (interval > 0 && lastRequestAtNanos != 0) {
            long waitNanos = interval - (System.nanoTime() - lastRequestAtNanos);
            if (waitNanos > 0) {
                sleep(waitNanos / 1_000_000L);
            }
        }
        lastRequestAtNanos = System.nanoTime();
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingClientException("임베딩 재시도 대기 중 인터럽트되었습니다.");
        }
    }

    private void reloadIndexes() {
        // 인덱스 교체 실패가 배치 결과를 가리지 않게 한다 — 옛 인덱스가 그대로 남아 서비스는 계속된다.
        try {
            vectorIndex.reload();
            featureIndex.reload();
        } catch (RuntimeException e) {
            log.error("추천 인덱스 재생성에 실패했습니다. 이전 인덱스를 유지합니다.", e);
        }
    }

    /** @param firstUnprocessedMovieId 429로 막혀 처리하지 못한 첫 영화. {@code null}이면 묶음을 끝까지 처리했다. */
    private record ChunkOutcome(int embedded, int failed, Long firstUnprocessedMovieId) {
    }
}
