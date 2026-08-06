package com.project.cinemory.global.security;

import com.project.cinemory.domain.user.entity.RoleType;
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
 * 컨테이너의 ERROR 디스패치가 인가에 걸려 원래 상태 코드를 덮지 않는지 고정한다.
 *
 * <p><b>배경</b> — Spring Security 6+는 ERROR 디스패치에도 필터체인을 적용하는데
 * {@link JwtAuthenticationFilter}는 {@code OncePerRequestFilter}라
 * {@code shouldNotFilterErrorDispatch()}가 기본 {@code true}다. 즉 에러 디스패치에서는
 * 인증을 다시 채우지 않으므로, {@code /error}가 {@code anyRequest().authenticated()}에 걸리면
 * <b>404/405/500이 전부 401로 덮인다.</b> 유효한 토큰을 든 클라이언트가 경로를 오타 내도
 * 401을 받아 "세션 만료"로 오진하고 A-3의 재발급 분기를 잘못 타게 된다.
 *
 * <p><b>MockMvc를 쓰지 않는 이유</b> — MockMvc는 서블릿 컨테이너의 ERROR 디스패치를
 * 그대로 재현하지 않아 이 회귀를 잡지 못한다. 실제 포트를 띄워 확인해야 한다.
 *
 * <p>깨져도 컴파일은 통과하므로 <b>정리 대상으로 오해해 삭제하지 말 것.</b>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityErrorDispatchTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private String accessToken;

    /** 필터는 JWT를 파싱해 주체만 세우고 DB를 보지 않으므로, 유저를 만들지 않고 토큰만 발급하면 된다. */
    @BeforeEach
    void setUp() {
        accessToken = jwtTokenProvider.createAccessToken(1L, RoleType.USER);
    }

    private HttpResponse<String> call(String method, String path, boolean authenticated)
            throws IOException, InterruptedException {

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, HttpRequest.BodyPublishers.noBody());

        if (authenticated) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void 인증된_요청이_없는_경로를_치면_401이_아니라_404다() throws Exception {
        assertThat(call("GET", "/api/does-not-exist", true).statusCode()).isEqualTo(404);
    }

    /** {@code /api/auth/nonce}는 POST 전용이다. 인증된 GET은 405여야 하고 401이면 회귀다. */
    @Test
    void 인증된_요청의_메서드가_틀리면_401이_아니라_405다() throws Exception {
        assertThat(call("GET", "/api/auth/nonce", true).statusCode()).isEqualTo(405);
    }

    /**
     * permitAll 경로 패턴({@code /api/movies/**})에 걸리지만 핸들러가 없는 경우.
     * 인가는 통과하므로 미인증이어도 404가 나와야 한다.
     *
     * <p><b>경로 선정 주의</b> — 원래 {@code POST /api/auth/oauth/nonce}를 썼으나, S-G에서
     * {@code @PostMapping("/oauth/{provider}")}가 생기면서 <b>이 경로에 핸들러가 생겨</b>
     * 본문 없는 요청이 404가 아니라 400을 받게 됐다. 이 테스트가 고정하려는 것은
     * "인가 통과 + 핸들러 없음 → 404"이므로, <b>앞으로도 매핑이 생길 여지가 없는</b>
     * 다중 세그먼트 경로로 옮긴다.
     */
    @Test
    void permitAll_경로에_핸들러가_없으면_404다() throws Exception {
        assertThat(call("GET", "/api/movies/no-such-handler/nope/nope", false).statusCode()).isEqualTo(404);
    }

    /**
     * <b>디스패치 타입으로 열고 경로로 열지 않은 이유를 고정한다.</b>
     * {@code /error}를 경로로 permitAll 하면 외부 직접 호출까지 열린다.
     * 컨테이너가 만드는 내부 ERROR 디스패치가 아니므로 계속 막혀 있어야 한다.
     */
    @Test
    void error_경로_직접_호출은_여전히_막힌다() throws Exception {
        assertThat(call("GET", "/error", false).statusCode()).isEqualTo(401);
    }

    /** 보호된 경로의 미인증 접근은 그대로 401이어야 한다 — 이번 수정으로 열린 곳이 없는지 확인한다. */
    @Test
    void 보호된_경로의_미인증_접근은_그대로_401이다() throws Exception {
        HttpResponse<String> response = call("POST", "/api/auth/logout", false);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("UNAUTHORIZED");
    }

    /** 미인증 + 없는 경로는 인가 단계에서 먼저 걸리므로 404가 아니라 401이다(엔드포인트 존재 여부 비노출). */
    @Test
    void 미인증_요청은_없는_경로에서도_401이라_엔드포인트_존재를_노출하지_않는다() throws Exception {
        assertThat(call("GET", "/api/does-not-exist", false).statusCode()).isEqualTo(401);
    }
}
