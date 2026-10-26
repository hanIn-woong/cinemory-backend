package com.project.cinemory.global.infra.gemini;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 요청 형식(접두어·차원·요청별 Content)과 오류 매핑 — 실제 Gemini를 호출하지 않는다. */
class GeminiEmbeddingClientTest {

    private static final String BASE = "http://gemini.test/v1beta";
    private static final String MODEL = "gemini-embedding-2";

    private MockRestServiceServer server;
    private GeminiEmbeddingClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        client = new GeminiEmbeddingClient(restClient, restClient,
                new GeminiProperties("k", BASE, MODEL, Duration.ofSeconds(30)));
    }

    private static String values(int dimensions) {
        return IntStream.range(0, dimensions).mapToObj(i -> i == 0 ? "1.0" : "0.0").collect(Collectors.joining(","));
    }

    private static String batchBody(int vectors, int dimensions) {
        String one = "{\"values\":[" + values(dimensions) + "]}";
        return "{\"embeddings\":[" + IntStream.range(0, vectors).mapToObj(i -> one).collect(Collectors.joining(",")) + "]}";
    }

    @Test
    void 질의는_접두어와_768차원으로_embedContent에_보낸다() {
        server.expect(requestTo(BASE + "/models/" + MODEL + ":embedContent"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.content.parts[0].text").value("task: search result | query: 비 오는 날 보기 좋은 영화"))
                .andExpect(jsonPath("$.outputDimensionality").value(768))
                .andRespond(withSuccess("{\"embedding\":{\"values\":[" + values(768) + "]},\"usageMetadata\":{}}", MediaType.APPLICATION_JSON));

        float[] vector = client.embedQuery("비 오는 날 보기 좋은 영화");

        assertThat(vector).hasSize(768);
        assertThat(vector[0]).isEqualTo(1.0f);
        server.verify();
    }

    @Test
    void 문서는_입력마다_별도_요청으로_batchEmbedContents에_보낸다() {
        server.expect(requestTo(BASE + "/models/" + MODEL + ":batchEmbedContents"))
                .andExpect(jsonPath("$.requests.length()").value(2))
                .andExpect(jsonPath("$.requests[0].content.parts[0].text").value("title: 기생충 | text: 본문1"))
                .andExpect(jsonPath("$.requests[1].content.parts[0].text").value("title: none | text: 본문2"))
                .andExpect(jsonPath("$.requests[0].model").value("models/" + MODEL))
                .andExpect(jsonPath("$.requests[1].outputDimensionality").value(768))
                .andRespond(withSuccess(batchBody(2, 768), MediaType.APPLICATION_JSON));

        List<float[]> vectors = client.embedDocuments(List.of(
                new EmbeddingDocument("기생충", "본문1"), new EmbeddingDocument(null, "본문2")));

        assertThat(vectors).hasSize(2);
        server.verify();
    }

    @Test
    void 빈_입력은_호출하지_않는다() {
        assertThat(client.embedDocuments(List.of())).isEmpty();
        server.verify();
    }

    @Test
    void 응답_벡터_개수가_요청과_다르면_실패한다() {
        server.expect(requestTo(BASE + "/models/" + MODEL + ":batchEmbedContents"))
                .andRespond(withSuccess(batchBody(1, 768), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.embedDocuments(List.of(new EmbeddingDocument("a", "b"), new EmbeddingDocument("c", "d"))))
                .isInstanceOf(EmbeddingClientException.class)
                .isNotInstanceOf(EmbeddingRateLimitedException.class);
    }

    @Test
    void 차원이_768이_아니면_실패한다() {
        server.expect(requestTo(BASE + "/models/" + MODEL + ":embedContent"))
                .andRespond(withSuccess("{\"embedding\":{\"values\":[" + values(3072) + "]}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.embedQuery("q")).isInstanceOf(EmbeddingClientException.class);
    }

    @Test
    void 레이트리밋_429는_Retry_After와_함께_전용_예외가_된다() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", "7");
        server.expect(requestTo(BASE + "/models/" + MODEL + ":embedContent"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers));

        assertThatThrownBy(() -> client.embedQuery("q"))
                .isInstanceOfSatisfying(EmbeddingRateLimitedException.class,
                        e -> assertThat(e.getRetryAfterMillis()).isEqualTo(7000L));
    }

    @Test
    void 헤더가_없으면_429_본문의_retry_in_안내를_대기시간으로_쓴다() {
        String body = "{\"error\":{\"code\":429,\"message\":\"Quota exceeded ... limit: 100 ... Please retry in 28.751928642s.\"}}";
        server.expect(requestTo(BASE + "/models/" + MODEL + ":batchEmbedContents"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body(body).contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.embedDocuments(List.of(new EmbeddingDocument("a", "b"))))
                .isInstanceOfSatisfying(EmbeddingRateLimitedException.class,
                        e -> assertThat(e.getRetryAfterMillis()).isEqualTo(28_752L));
    }

    @Test
    void 일일_한도의_시간_분_초_안내도_대기시간으로_읽는다() {
        String body = "{\"error\":{\"message\":\"Quota exceeded for metric: x, limit: 1000 per day limit exceeded on Free Tier.\\nPlease retry in 1h7m33.5s.\"}}";
        server.expect(requestTo(BASE + "/models/" + MODEL + ":embedContent"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body(body).contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.embedQuery("q"))
                .isInstanceOfSatisfying(EmbeddingRateLimitedException.class, e -> {
                    assertThat(e.getRetryAfterMillis()).isEqualTo(4_053_500L);
                    assertThat(e.getMessage()).contains("Quota exceeded for metric: x");
                });
    }

    @Test
    void 헤더도_안내도_없으면_대기시간은_null이다() {
        server.expect(requestTo(BASE + "/models/" + MODEL + ":embedContent"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.embedQuery("q"))
                .isInstanceOfSatisfying(EmbeddingRateLimitedException.class,
                        e -> assertThat(e.getRetryAfterMillis()).isNull());
    }

    @Test
    void 그_밖의_4xx_5xx는_일반_실패이고_메시지에_질의가_없다() {
        server.expect(requestTo(BASE + "/models/" + MODEL + ":embedContent"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("secret-echo").contentType(MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> client.embedQuery("비밀 질의"))
                .isInstanceOf(EmbeddingClientException.class)
                .hasMessageContaining("400")
                .hasMessageNotContaining("비밀 질의")
                .hasMessageNotContaining("secret-echo");
    }

    @Test
    void 접두어_조립_형식() {
        assertThat(GeminiEmbeddingClient.queryText("질의")).isEqualTo("task: search result | query: 질의");
        assertThat(GeminiEmbeddingClient.documentText(new EmbeddingDocument("제목", "본문"))).isEqualTo("title: 제목 | text: 본문");
        assertThat(GeminiEmbeddingClient.documentText(new EmbeddingDocument(" ", "본문"))).isEqualTo("title: none | text: 본문");
        assertThat(client.modelName()).isEqualTo(MODEL);
    }
}
