package com.project.cinemory.domain.recommendation.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.project.cinemory.domain.recommendation.entity.OverviewSource;
import com.project.cinemory.domain.recommendation.index.MovieFeatureIndex;
import com.project.cinemory.domain.recommendation.index.MovieVectorIndex;
import com.project.cinemory.domain.recommendation.repository.MovieEmbeddingRepository;
import com.project.cinemory.domain.recommendation.support.FakeEmbeddingClient;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.gemini.EmbeddingClient;
import com.project.cinemory.global.infra.gemini.EmbeddingDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 임베딩 배치의 흐름 검증 — DB·Gemini 없이 협력 객체를 가짜로 둔다(M3b-recommendation-spec E-4-3).
 * 영화 id 1~5, 묶음 크기 3이면 묶음은 [1,2,3] [4,5]다.
 */
class EmbeddingSyncServiceTest {

    private EmbeddingInputLoader loader;
    private MovieEmbeddingPersister persister;
    private MovieEmbeddingRepository embeddingRepository;
    private MovieVectorIndex vectorIndex;
    private MovieFeatureIndex featureIndex;

    private final Map<Long, String> storedHashes = new HashMap<>();

    @BeforeEach
    void setUp() {
        loader = mock(EmbeddingInputLoader.class);
        persister = mock(MovieEmbeddingPersister.class);
        embeddingRepository = mock(MovieEmbeddingRepository.class);
        vectorIndex = mock(MovieVectorIndex.class);
        featureIndex = mock(MovieFeatureIndex.class);

        when(loader.load(anyLong(), anyInt())).thenAnswer(inv -> {
            long after = inv.getArgument(0);
            int size = inv.getArgument(1);
            return movies(5).stream().filter(p -> p.movieId() > after).limit(size).toList();
        });
        when(embeddingRepository.findHashesByMovieIdIn(anyCollection())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            List<MovieEmbeddingRepository.HashProjection> rows = new ArrayList<>();
            for (Long id : ids) {
                if (storedHashes.containsKey(id)) {
                    String hash = storedHashes.get(id);
                    rows.add(new MovieEmbeddingRepository.HashProjection() {
                        public Long getMovieId() {
                            return id;
                        }

                        public String getSourceHash() {
                            return hash;
                        }
                    });
                }
            }
            return rows;
        });
    }

    private static List<EmbeddingInputLoader.Prepared> movies(int count) {
        List<EmbeddingInputLoader.Prepared> list = new ArrayList<>();
        for (long id = 1; id <= count; id++) {
            list.add(new EmbeddingInputLoader.Prepared(id, new EmbeddingText("t" + id, "body" + id, OverviewSource.KO)));
        }
        return list;
    }

    private EmbeddingSyncService service(EmbeddingClient client, int threshold) {
        EmbeddingSyncProperties properties = new EmbeddingSyncProperties("0 0 6 * * *", 3, 1, Duration.ZERO, Duration.ofSeconds(90), Duration.ZERO, threshold, 800);
        return new EmbeddingSyncService(loader, persister, embeddingRepository, client, vectorIndex, featureIndex, properties);
    }

    private void storeSameHash(long movieId, String modelName) {
        storedHashes.put(movieId, new EmbeddingText("t" + movieId, "body" + movieId, OverviewSource.KO).hash(modelName));
    }

    private static boolean mentions(List<EmbeddingDocument> batch, String title) {
        return batch.stream().anyMatch(d -> d.title().equals(title));
    }

    @Test
    void 해시가_같은_영화는_건너뛰고_나머지만_Gemini로_보낸다() {
        FakeEmbeddingClient client = new FakeEmbeddingClient();
        storeSameHash(1, client.modelName());
        storeSameHash(2, client.modelName());
        storeSameHash(4, client.modelName());

        EmbeddingSyncResult result = service(client, 500).sync(null, null);

        assertThat(result).isEqualTo(new EmbeddingSyncResult(2, 3, 0, false, 5L));
        assertThat(client.requestedDocumentCount()).isEqualTo(2);
        assertThat(client.batches().stream().flatMap(List::stream).map(EmbeddingDocument::title)).containsExactly("t3", "t5");
    }

    @Test
    void 모든_영화가_최신이면_Gemini를_호출하지_않는다() {
        FakeEmbeddingClient client = new FakeEmbeddingClient();
        for (long id = 1; id <= 5; id++) {
            storeSameHash(id, client.modelName());
        }

        EmbeddingSyncResult result = service(client, 500).sync(null, null);

        assertThat(result).isEqualTo(new EmbeddingSyncResult(0, 5, 0, false, 5L));
        assertThat(client.batches()).isEmpty();
    }

    @Test
    void 모델명이_바뀌면_해시가_달라져_전량_재임베딩된다() {
        for (long id = 1; id <= 5; id++) {
            storeSameHash(id, "old-model");
        }
        FakeEmbeddingClient client = new FakeEmbeddingClient("new-model");

        EmbeddingSyncResult result = service(client, 500).sync(null, null);

        assertThat(result.embedded()).isEqualTo(5);
        assertThat(result.unchanged()).isZero();
    }

    @Test
    void 저장은_모델명과_새_해시로_한다() {
        FakeEmbeddingClient client = new FakeEmbeddingClient();

        service(client, 500).sync(null, 3);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<MovieEmbeddingPersister.Item>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(persister).upsert(eq(client.modelName()), captor.capture());
        assertThat(captor.getValue()).extracting(MovieEmbeddingPersister.Item::movieId).containsExactly(1L, 2L, 3L);
        assertThat(captor.getValue()).allSatisfy(item ->
                assertThat(item.hash()).isEqualTo(item.text().hash(client.modelName())));
    }

    @Test
    void 레이트리밋_429면_백오프_재시도_후_중단하고_커서는_처리하지_못한_영화_앞에서_멈춘다() {
        FakeEmbeddingClient client = new FakeEmbeddingClient().rateLimitWhen(batch -> mentions(batch, "t4"));

        EmbeddingSyncResult result = service(client, 500).sync(null, null);

        // 첫 묶음 [1,2,3]은 저장, 둘째 묶음 [4,5]는 maxRetries=1이라 2번 시도 뒤 중단.
        assertThat(result).isEqualTo(new EmbeddingSyncResult(3, 0, 0, true, 3L));
        assertThat(client.batches()).hasSize(3);
    }

    @Test
    void 서버가_안내한_대기가_상한보다_길면_재시도하지_않고_바로_중단한다() {
        // 일일 한도 소진처럼 몇 분~몇 시간을 기다려야 하는 경우: sleep하지 않고 lastProcessedId를 돌려준다.
        FakeEmbeddingClient client = new FakeEmbeddingClient().rateLimitWhen(batch -> true).retryAfterMillis(600_000L);

        EmbeddingSyncResult result = service(client, 500).sync(null, null);

        assertThat(result).isEqualTo(new EmbeddingSyncResult(0, 0, 0, true, null));
        assertThat(client.batches()).hasSize(1);
    }

    // ── 스케줄 상한 (OP-1-2) — 훑는 수가 아니라 실제 임베딩 요청 수의 상한 ──────────────

    @Test
    void 스케줄_상한은_벡터가_없는_영화만_상한까지_처리하고_멈추며_다음_실행이_이어받는다() {
        FakeEmbeddingClient client = new FakeEmbeddingClient();
        storeSameHash(1, client.modelName());
        storeSameHash(2, client.modelName());   // 앞쪽 두 편은 이미 적재됨 — 훑되 요청은 0

        EmbeddingSyncResult first = service(client, 500).sync(null, null, 2);

        assertThat(first).isEqualTo(new EmbeddingSyncResult(2, 2, 0, false, 4L));
        assertThat(client.batches().stream().flatMap(List::stream).map(EmbeddingDocument::title)).containsExactly("t3", "t4");

        // 다음 날: 3·4가 저장돼 있다 → 처음부터 훑어도 남은 5번만 요청한다.
        storeSameHash(3, client.modelName());
        storeSameHash(4, client.modelName());
        FakeEmbeddingClient secondClient = new FakeEmbeddingClient();
        EmbeddingSyncResult second = service(secondClient, 500).sync(null, null, 2);

        assertThat(second).isEqualTo(new EmbeddingSyncResult(1, 4, 0, false, 5L));
        assertThat(secondClient.batches().stream().flatMap(List::stream).map(EmbeddingDocument::title)).containsExactly("t5");
    }

    @Test
    void 스케줄_상한이_묶음_중간에서_걸려도_초과분은_보내지_않는다() {
        FakeEmbeddingClient client = new FakeEmbeddingClient();

        EmbeddingSyncResult result = service(client, 500).sync(null, null, 4);   // 묶음 크기 3 → 둘째 묶음 [4,5] 중 4번까지만

        assertThat(result).isEqualTo(new EmbeddingSyncResult(4, 0, 0, false, 4L));
        assertThat(client.requestedDocumentCount()).isEqualTo(4);
    }

    @Test
    void 스케줄_상한이_변경분보다_크면_전부_처리한다() {
        FakeEmbeddingClient client = new FakeEmbeddingClient();

        EmbeddingSyncResult result = service(client, 500).sync(null, null, 800);

        assertThat(result).isEqualTo(new EmbeddingSyncResult(5, 0, 0, false, 5L));
    }

    @Test
    void 수동_실행의_limit은_여전히_훑는_수다_상한과_함께_쓰면_둘_중_먼저_걸리는_쪽이다() {
        FakeEmbeddingClient client = new FakeEmbeddingClient();
        storeSameHash(1, client.modelName());
        storeSameHash(2, client.modelName());

        EmbeddingSyncResult scanLimited = service(client, 500).sync(null, 2, null);

        assertThat(scanLimited).isEqualTo(new EmbeddingSyncResult(0, 2, 0, false, 2L));   // 앞쪽 두 편만 훑고 끝
    }

    @Test
    void 이어받기는_lastProcessedId_다음_영화부터_시작한다() {
        FakeEmbeddingClient client = new FakeEmbeddingClient();

        EmbeddingSyncResult result = service(client, 500).sync(3L, null);

        assertThat(result).isEqualTo(new EmbeddingSyncResult(2, 0, 0, false, 5L));
        assertThat(client.batches().stream().flatMap(List::stream).map(EmbeddingDocument::title)).containsExactly("t4", "t5");
    }

    @Test
    void 묶음이_일반_실패하면_한_건씩_다시_시도해_실패한_영화만_건너뛴다() {
        // 2개 이상 묶음은 실패, 단건은 t2만 실패
        FakeEmbeddingClient client = new FakeEmbeddingClient()
                .failWhen(batch -> batch.size() > 1 || mentions(batch, "t2"));

        EmbeddingSyncResult result = service(client, 500).sync(null, null);

        assertThat(result).isEqualTo(new EmbeddingSyncResult(4, 0, 1, false, 5L));
    }

    @Test
    void 한_건씩_재시도_중_429면_그_영화_앞에서_멈춘다() {
        FakeEmbeddingClient client = new FakeEmbeddingClient()
                .failWhen(batch -> batch.size() > 1)
                .rateLimitWhen(batch -> batch.size() == 1 && mentions(batch, "t2"));

        EmbeddingSyncResult result = service(client, 500).sync(null, null);

        assertThat(result).isEqualTo(new EmbeddingSyncResult(1, 0, 0, true, 1L));
    }

    @Test
    void limit은_훑는_영화_수를_제한한다() {
        FakeEmbeddingClient client = new FakeEmbeddingClient();

        EmbeddingSyncResult result = service(client, 500).sync(null, 4);

        assertThat(result).isEqualTo(new EmbeddingSyncResult(4, 0, 0, false, 4L));
    }

    @Test
    void 끝나면_두_인덱스를_재생성한다_429_중단이어도_마찬가지다() {
        service(new FakeEmbeddingClient(), 500).sync(null, null);
        service(new FakeEmbeddingClient().rateLimitWhen(b -> true), 500).sync(null, null);

        verify(vectorIndex, times(2)).reload();
        verify(featureIndex, times(2)).reload();
    }

    @Test
    void 인덱스_재생성이_실패해도_배치_결과는_돌려준다() {
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(vectorIndex).reload();

        EmbeddingSyncResult result = service(new FakeEmbeddingClient(), 500).sync(null, null);

        assertThat(result.embedded()).isEqualTo(5);
    }

    @Test
    void 실행_중에_다시_호출하면_409로_거부한다() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        EmbeddingClient blocking = new FakeEmbeddingClient() {
            @Override
            public List<float[]> embedDocuments(List<EmbeddingDocument> documents) {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.embedDocuments(documents);
            }
        };
        EmbeddingSyncService service = service(blocking, 500);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<EmbeddingSyncResult> first = executor.submit(() -> service.sync(null, null));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(service.isRunning()).isTrue();
            assertThatThrownBy(() -> service.sync(null, null))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EMBEDDING_SYNC_ALREADY_RUNNING));

            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).embedded()).isEqualTo(5);
            assertThat(service.isRunning()).isFalse();
            // 끝난 뒤에는 다시 실행할 수 있다.
            assertThat(service.sync(null, null).unchanged()).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void 실패해도_실행_플래그가_풀린다() {
        EmbeddingSyncService service = service(new FakeEmbeddingClient(), 500);
        when(loader.load(anyLong(), anyInt())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.sync(null, null)).isInstanceOf(IllegalStateException.class);

        assertThat(service.isRunning()).isFalse();
    }

    @Test
    void embedded가_임계를_넘으면_WARN을_남긴다() {
        Logger logger = (Logger) LoggerFactory.getLogger(EmbeddingSyncService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service(new FakeEmbeddingClient(), 4).sync(null, null);

            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("임계");
            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void embedded가_임계_이하면_WARN이_없다() {
        Logger logger = (Logger) LoggerFactory.getLogger(EmbeddingSyncService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service(new FakeEmbeddingClient(), 5).sync(null, null);

            assertThat(appender.list).noneMatch(event -> event.getLevel() == Level.WARN);
        } finally {
            logger.detachAppender(appender);
        }
    }
}
