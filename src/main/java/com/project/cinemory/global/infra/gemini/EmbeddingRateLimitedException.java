package com.project.cinemory.global.infra.gemini;

/**
 * 임베딩 API가 429를 돌려줌. 일반 실패({@link EmbeddingClientException})와 구분한다 —
 * 배치는 이를 만나면 개별 실패로 넘기지 않고 백오프 후 중단한다(E-4-3).
 */
public class EmbeddingRateLimitedException extends EmbeddingClientException {

    private final Long retryAfterMillis;

    public EmbeddingRateLimitedException(Long retryAfterMillis) {
        this(retryAfterMillis, null);
    }

    /** @param quotaDetail 어떤 한도인지 알려 주는 안내 한 줄(분당/일일/토큰). 운영 진단용 — 요청 내용은 담기지 않는다. */
    public EmbeddingRateLimitedException(Long retryAfterMillis, String quotaDetail) {
        super("임베딩 API 호출 한도를 초과했습니다." + (quotaDetail == null ? "" : " " + quotaDetail));
        this.retryAfterMillis = retryAfterMillis;
    }

    /** {@code Retry-After} 헤더 값. 없으면 {@code null}. */
    public Long getRetryAfterMillis() {
        return retryAfterMillis;
    }
}
