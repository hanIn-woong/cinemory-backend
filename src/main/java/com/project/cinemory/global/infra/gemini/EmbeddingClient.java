package com.project.cinemory.global.infra.gemini;

import java.util.List;

/**
 * 임베딩 모델 격리 인터페이스 (M3b-recommendation-spec M-1).
 *
 * <p>교체 시 구현 하나({@code OnnxEmbeddingClient} 등)와 전량 재임베딩이면 된다 —
 * {@link #modelName()}이 {@code movie_embedding.source_hash}에 섞여 있어 모델이 바뀌면 전 행이 자동으로 다시 대상이 된다.
 *
 * <p>모든 벡터는 {@link VectorCodec#DIMENSIONS}차원이고 L2 정규화돼 있다(코사인 유사도 = 내적).
 */
public interface EmbeddingClient {

    /** 질의 한 건. 접두어 조립은 구현 내부. 실패 시 {@link EmbeddingClientException}, 429면 {@link EmbeddingRateLimitedException}. */
    float[] embedQuery(String query);

    /**
     * 문서 여러 건을 한 번의 요청으로 임베딩한다. 반환 순서 = 입력 순서, 크기 = 입력 크기.
     *
     * <p>⚠️ 입력을 한 {@code Content}에 합치면 벡터가 1개만 나온다 — 구현은 입력마다 별도 요청으로 보낸다.
     */
    List<float[]> embedDocuments(List<EmbeddingDocument> documents);

    /** {@code movie_embedding.model_name} 기록용. */
    String modelName();
}
