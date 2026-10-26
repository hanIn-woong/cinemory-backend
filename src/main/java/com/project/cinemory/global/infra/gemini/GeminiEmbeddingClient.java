package com.project.cinemory.global.infra.gemini;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gemini {@code gemini-embedding-2} 호출 (M3b-recommendation-spec M-1).
 *
 * <ul>
 *   <li>{@code task_type}을 받지 않는 모델이라 질의/문서 구분은 <b>텍스트 접두어</b>로 한다.</li>
 *   <li>{@code outputDimensionality = 768} — 축소 차원은 자동 정규화된다(2026-10-26 실측: 노름 1.0).</li>
 *   <li>문서는 {@code batchEmbedContents}로, 입력마다 별도 요청을 넣는다(한 {@code Content}에 합치면 벡터가 1개).</li>
 * </ul>
 *
 * <p>예외 메시지·로그에 요청 본문(사용자 질의)과 키를 넣지 않는다.
 */
@Slf4j
@Component
public class GeminiEmbeddingClient implements EmbeddingClient {

    private static final Pattern RETRY_IN_BODY = Pattern.compile("retry in (?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+(?:\\.\\d+)?)s)?");

    private static final Pattern QUOTA_LINE = Pattern.compile("Quota exceeded for metric:[^\n\"]*");

    private final RestClient queryClient;
    private final RestClient batchClient;
    private final String model;

    public GeminiEmbeddingClient(@Qualifier("geminiQueryRestClient") RestClient queryClient,
                                 @Qualifier("geminiBatchRestClient") RestClient batchClient,
                                 GeminiProperties properties) {
        this.queryClient = queryClient;
        this.batchClient = batchClient;
        this.model = properties.model();
    }

    @Override
    public float[] embedQuery(String query) {
        EmbedResponse response = call(() -> queryClient.post()
                .uri("/models/{model}:embedContent", model)
                .body(request(queryText(query)))
                .retrieve()
                .body(EmbedResponse.class));
        if (response == null || response.embedding() == null) {
            throw new EmbeddingClientException("임베딩 응답이 비어 있습니다.");
        }
        return validated(response.embedding().values());
    }

    @Override
    public List<float[]> embedDocuments(List<EmbeddingDocument> documents) {
        if (documents.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> requests = documents.stream()
                .map(d -> request(documentText(d)))
                .toList();

        BatchResponse response = call(() -> batchClient.post()
                .uri("/models/{model}:batchEmbedContents", model)
                .body(Map.of("requests", requests))
                .retrieve()
                .body(BatchResponse.class));

        // 개수가 어긋나면 벡터를 엉뚱한 영화에 붙이게 된다 — 조용히 넘기지 않는다.
        if (response == null || response.embeddings() == null || response.embeddings().size() != documents.size()) {
            throw new EmbeddingClientException("배치 임베딩 응답 개수가 요청과 다릅니다. 요청="
                    + documents.size() + ", 응답=" + (response == null || response.embeddings() == null ? 0 : response.embeddings().size()));
        }
        return response.embeddings().stream().map(e -> validated(e.values())).toList();
    }

    @Override
    public String modelName() {
        return model;
    }

    static String queryText(String query) {
        return "task: search result | query: " + query;
    }

    static String documentText(EmbeddingDocument document) {
        String title = document.title() == null || document.title().isBlank() ? "none" : document.title();
        return "title: " + title + " | text: " + document.text();
    }

    private Map<String, Object> request(String text) {
        return Map.of(
                "model", "models/" + model,
                "content", Map.of("parts", List.of(Map.of("text", text))),
                "outputDimensionality", VectorCodec.DIMENSIONS);
    }

    private float[] validated(List<Float> values) {
        if (values == null || values.size() != VectorCodec.DIMENSIONS) {
            throw new EmbeddingClientException("임베딩 차원이 " + VectorCodec.DIMENSIONS + "이 아닙니다: "
                    + (values == null ? 0 : values.size()));
        }
        float[] vector = new float[values.size()];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = values.get(i);
        }
        return vector;
    }

    private <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (HttpClientErrorException.TooManyRequests e) {
            throw new EmbeddingRateLimitedException(retryAfterMillis(e), quotaDetail(e));
        } catch (HttpClientErrorException e) {
            // 본문은 버린다 — 요청 내용이 에코될 수 있다.
            throw new EmbeddingClientException("임베딩 API가 " + e.getStatusCode().value() + "을 반환했습니다.");
        } catch (RestClientException e) {
            throw new EmbeddingClientException("임베딩 API 호출에 실패했습니다: " + e.getClass().getSimpleName());
        }
    }

    /**
     * 429의 대기 시간. {@code Retry-After} 헤더를 우선하고, 없으면 본문의 {@code Please retry in 28.7s} 안내에서 숫자만 꺼낸다
     * (Gemini는 헤더 없이 본문으로만 알려 준다 — 2026-10-26 실측). 본문은 한도 안내문이라 질의 내용이 없지만,
     * 숫자 외에는 읽지도 남기지도 않는다.
     */
    private Long retryAfterMillis(HttpClientErrorException.TooManyRequests e) {
        String header = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
        if (header != null) {
            try {
                return Long.parseLong(header.trim()) * 1000L;
            } catch (NumberFormatException ignored) {
                // 본문 안내로 넘어간다
            }
        }
        Matcher matcher = RETRY_IN_BODY.matcher(e.getResponseBodyAsString());
        if (matcher.find() && (matcher.group(1) != null || matcher.group(2) != null || matcher.group(3) != null)) {
            // "28.7s" · "1m5s" · "1h7m33.4s" 형식 — 일일 한도는 시간 단위로 온다.
            double seconds = (matcher.group(1) == null ? 0 : Long.parseLong(matcher.group(1)) * 3600)
                    + (matcher.group(2) == null ? 0 : Long.parseLong(matcher.group(2)) * 60)
                    + (matcher.group(3) == null ? 0 : Double.parseDouble(matcher.group(3)));
            return (long) Math.ceil(seconds * 1000L);
        }
        return null;
    }

    /** 본문의 {@code Quota exceeded for metric: …} 한 줄만 꺼낸다(최대 300자). 없으면 null. */
    private String quotaDetail(HttpClientErrorException.TooManyRequests e) {
        Matcher matcher = QUOTA_LINE.matcher(e.getResponseBodyAsString());
        if (!matcher.find()) {
            return null;
        }
        String line = matcher.group();
        return line.length() > 300 ? line.substring(0, 300) : line;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbedResponse(Values embedding) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BatchResponse(List<Values> embeddings) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Values(List<Float> values) {
    }
}
