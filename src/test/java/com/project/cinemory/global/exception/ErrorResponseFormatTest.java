package com.project.cinemory.global.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.cinemory.domain.user.entity.RoleType;
import com.project.cinemory.global.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 5-7 C-0 — 404/405/415가 {@code GlobalExceptionHandler}의 {@link ErrorResponse} 포맷으로
 * 나가는지 고정한다(5-0-C 회귀). 상태 코드만 보는 S-4 {@code SecurityErrorDispatchTest}와 달리
 * 여기서는 응답 바디의 {@code status}/{@code code}/{@code errors} 필드까지 확인한다.
 *
 * <p>{@code RANDOM_PORT}를 쓰는 이유는 A와 동일 — 컨테이너의 실제 디스패치 경로를 MockMvc가
 * 재현하지 못한다(S-4에서 확인된 사항).
 *
 * <p>모든 호출에 인증 토큰을 싣는 이유 — 대상 경로들이 화이트리스트 밖이라 미인증으로 부르면
 * 인가 단계에서 먼저 401로 걸려 정작 보려는 404/405/415에 도달하지 못한다
 * ({@code SecurityErrorDispatchTest}가 이미 같은 이유로 인증된 호출을 쓰고 있다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErrorResponseFormatTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String token;

    @BeforeEach
    void setUp() {
        token = jwtTokenProvider.createAccessToken(1L, RoleType.USER);
    }

    private HttpResponse<String> call(String method, String path, String contentType, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token);

        if (body != null) {
            builder.header("Content-Type", contentType)
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertErrorBody(HttpResponse<String> response, int expectedStatus, String expectedCode)
            throws IOException {
        assertThat(response.statusCode()).isEqualTo(expectedStatus);

        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("status").asInt()).isEqualTo(expectedStatus);
        assertThat(json.get("code").asText()).isEqualTo(expectedCode);
        assertThat(json.hasNonNull("message")).isTrue();
        assertThat(json.get("errors").isArray()).isTrue();
        assertThat(json.get("errors")).isEmpty();
    }

    @Test
    void 없는_경로는_404_ErrorResponse_포맷이다() throws Exception {
        HttpResponse<String> response = call("GET", "/api/does-not-exist", null, null);
        assertErrorBody(response, 404, "ENDPOINT_NOT_FOUND");
    }

    /** {@code /api/auth/nonce}는 POST 전용이다. */
    @Test
    void 지원하지_않는_메서드는_405_ErrorResponse_포맷이다() throws Exception {
        HttpResponse<String> response = call("GET", "/api/auth/nonce", null, null);
        assertErrorBody(response, 405, "METHOD_NOT_ALLOWED");
    }

    /** {@code @RequestBody}가 읽을 수 없는 {@code Content-Type}이면 415가 나야 한다. */
    @Test
    void 지원하지_않는_ContentType은_415_ErrorResponse_포맷이다() throws Exception {
        HttpResponse<String> response = call("POST", "/api/collections", "text/plain", "{}");
        assertErrorBody(response, 415, "UNSUPPORTED_MEDIA_TYPE");
    }
}
