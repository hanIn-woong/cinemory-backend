package com.project.cinemory.domain.recommendation.support;

import com.project.cinemory.global.infra.gemini.EmbeddingClient;
import com.project.cinemory.global.infra.gemini.EmbeddingClientException;
import com.project.cinemory.global.infra.gemini.EmbeddingDocument;
import com.project.cinemory.global.infra.gemini.EmbeddingRateLimitedException;
import com.project.cinemory.global.infra.gemini.VectorCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

/**
 * 테스트용 가짜 임베딩 클라이언트 — 실제 Gemini를 호출하지 않는다.
 *
 * <p>벡터는 텍스트의 해시를 시드로 만든 L2 정규화 벡터라 같은 텍스트는 같은 벡터다.
 * 호출 기록(요청된 문서)과 실패 주입({@link #failWhen}, {@link #rateLimitWhen})을 지원한다.
 */
public class FakeEmbeddingClient implements EmbeddingClient {

    public static final String MODEL = "fake-embedding-test";

    private final List<List<EmbeddingDocument>> batches = new ArrayList<>();
    private Predicate<List<EmbeddingDocument>> rateLimit = batch -> false;
    private Predicate<List<EmbeddingDocument>> failure = batch -> false;
    private Long retryAfterMillis;
    private final String modelName;

    public FakeEmbeddingClient() {
        this(MODEL);
    }

    public FakeEmbeddingClient(String modelName) {
        this.modelName = modelName;
    }

    /** 이 조건을 만족하는 요청에 429를 던진다. */
    public FakeEmbeddingClient rateLimitWhen(Predicate<List<EmbeddingDocument>> condition) {
        this.rateLimit = condition;
        return this;
    }

    /** 429에 실어 보낼 서버 안내 대기 시간. */
    public FakeEmbeddingClient retryAfterMillis(Long millis) {
        this.retryAfterMillis = millis;
        return this;
    }

    /** 이 조건을 만족하는 요청에 일반 실패를 던진다. */
    public FakeEmbeddingClient failWhen(Predicate<List<EmbeddingDocument>> condition) {
        this.failure = condition;
        return this;
    }

    public List<List<EmbeddingDocument>> batches() {
        return batches;
    }

    public int requestedDocumentCount() {
        return batches.stream().mapToInt(List::size).sum();
    }

    @Override
    public float[] embedQuery(String query) {
        return vectorOf("query:" + query);
    }

    @Override
    public List<float[]> embedDocuments(List<EmbeddingDocument> documents) {
        batches.add(List.copyOf(documents));
        if (rateLimit.test(documents)) {
            throw new EmbeddingRateLimitedException(retryAfterMillis);
        }
        if (failure.test(documents)) {
            throw new EmbeddingClientException("가짜 실패");
        }
        return documents.stream().map(d -> vectorOf(d.title() + "|" + d.text())).toList();
    }

    @Override
    public String modelName() {
        return modelName;
    }

    public static float[] vectorOf(String seedText) {
        Random random = new Random(seedText.hashCode());
        float[] v = new float[VectorCodec.DIMENSIONS];
        double sumSquares = 0;
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) random.nextGaussian();
            sumSquares += v[i] * v[i];
        }
        float norm = (float) Math.sqrt(sumSquares);
        for (int i = 0; i < v.length; i++) {
            v[i] /= norm;
        }
        return v;
    }
}
