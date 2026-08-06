package com.project.cinemory.global.infra.kakao;

import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 이 테스트가 고정하는 불변식은 {@code docs/security-spec.md} S-9 E-2에 표로 정리돼 있다.
 * 대부분 깨져도 컴파일은 통과하므로 <b>정리 대상으로 오해해 삭제하지 말 것.</b>
 *
 * <p>특히 <b>재조회 쿨다운</b>은 가용성 방어의 핵심이다. 없으면 임의 {@code kid}를 넣은 토큰을
 * 반복 전송해 JWKS 조회를 무한 유발할 수 있고, 카카오가 우리를 차단하면 소셜 로그인 전체가 죽는다.
 *
 * <p>Mock 대신 <b>JDK 내장 {@link HttpServer}로 실제 HTTP를 태운다</b> —
 * 카카오로 나간 요청 횟수를 정확히 세야 쿨다운과 캐시 동작을 증명할 수 있기 때문이다.
 */
class CachingKakaoJwkSourceTest {

    private HttpServer server;
    private String jwksUri;

    /** 카카오로 실제로 나간 요청 수. 캐시·쿨다운 검증의 근거다. */
    private final AtomicInteger requestCount = new AtomicInteger();

    private volatile String responseBody = "{\"keys\":[]}";
    private volatile int responseStatus = 200;

    private MutableClock clock;

    @BeforeEach
    void startServer() throws IOException {
        requestCount.set(0);
        clock = new MutableClock(Instant.parse("2026-08-02T00:00:00Z"));

        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/jwks", exchange -> {
            requestCount.incrementAndGet();
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        jwksUri = "http://localhost:" + server.getAddress().getPort() + "/jwks";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    // ---------------------------------------------------------------- helpers

    private CachingKakaoJwkSource source(Duration cooldown) {
        KakaoOAuthProperties properties = new KakaoOAuthProperties(
                "https://kauth.kakao.com", jwksUri, List.of("native-app-key"), cooldown);
        return new CachingKakaoJwkSource(RestClient.create(), properties, clock);
    }

    private CachingKakaoJwkSource source() {
        return source(Duration.ofMinutes(1));
    }

    private static KeyPair generateRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /**
     * 실제 JWKS와 같은 형태로 직렬화한다.
     *
     * <p>{@code n}은 <b>선행 0바이트를 제거</b>해 내보낸다 — 실제 JWKS가 그렇게 준다.
     * 2048bit modulus는 최상위 비트가 항상 1이라, 받는 쪽이 부호를 명시하지 않으면
     * ({@code new BigInteger(bytes)}) <b>음수로 해석돼 엉뚱한 키가 만들어진다.</b>
     */
    private static String jwkJson(String kid, RSAPublicKey key, String kty) {
        String n = base64Url(key.getModulus());
        String e = base64Url(key.getPublicExponent());
        return """
                {"kid":"%s","kty":"%s","alg":"RS256","use":"sig","n":"%s","e":"%s"}"""
                .formatted(kid, kty, n, e);
    }

    private static String jwksJson(String... jwks) {
        return "{\"keys\":[" + String.join(",", jwks) + "]}";
    }

    private static String base64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] stripped = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, stripped, 0, stripped.length);
            bytes = stripped;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 쿨다운을 검증하려면 시간을 움직여야 한다. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    void kid로_공개키를_찾는다() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        responseBody = jwksJson(jwkJson("kid-1", publicKey, "RSA"));

        RSAPublicKey found = source().findByKid("kid-1");

        assertThat(found.getModulus()).isEqualTo(publicKey.getModulus());
        assertThat(found.getPublicExponent()).isEqualTo(publicKey.getPublicExponent());
    }

    /**
     * <b>{@code new BigInteger(1, bytes)}의 첫 인자를 고정한다.</b>
     * 빠뜨리면 최상위 비트가 1인 modulus가 음수로 해석돼 엉뚱한 키가 만들어지고,
     * 증상은 "서명 검증 실패"로만 나타나 원인 추적이 매우 어렵다.
     */
    @Test
    void 최상위_비트가_1인_modulus도_양수로_복원된다() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        // 2048bit RSA modulus는 최상위 비트가 항상 1이다
        assertThat(publicKey.getModulus().bitLength()).isEqualTo(2048);
        responseBody = jwksJson(jwkJson("kid-1", publicKey, "RSA"));

        RSAPublicKey found = source().findByKid("kid-1");

        assertThat(found.getModulus()).isPositive();
        assertThat(found.getModulus()).isEqualTo(publicKey.getModulus());
    }

    @Test
    void 캐시에_있으면_카카오로_다시_요청하지_않는다() throws Exception {
        RSAPublicKey publicKey = (RSAPublicKey) generateRsaKeyPair().getPublic();
        responseBody = jwksJson(jwkJson("kid-1", publicKey, "RSA"));
        CachingKakaoJwkSource jwkSource = source();

        jwkSource.findByKid("kid-1");
        jwkSource.findByKid("kid-1");
        jwkSource.findByKid("kid-1");

        assertThat(requestCount).hasValue(1);
    }

    /** 키 롤오버 — 카카오가 새 키로 서명하기 시작하면 캐시 미스로 재조회해 따라잡아야 한다. */
    @Test
    void 캐시에_없는_kid면_재조회해_키_롤오버를_따라간다() throws Exception {
        RSAPublicKey oldKey = (RSAPublicKey) generateRsaKeyPair().getPublic();
        RSAPublicKey newKey = (RSAPublicKey) generateRsaKeyPair().getPublic();
        responseBody = jwksJson(jwkJson("kid-old", oldKey, "RSA"));

        CachingKakaoJwkSource jwkSource = source(Duration.ofMinutes(1));
        jwkSource.findByKid("kid-old");

        // 카카오가 키를 교체하고, 쿨다운이 지난 뒤 새 kid로 서명된 토큰이 들어온다
        responseBody = jwksJson(jwkJson("kid-old", oldKey, "RSA"), jwkJson("kid-new", newKey, "RSA"));
        clock.advance(Duration.ofMinutes(2));

        assertThat(jwkSource.findByKid("kid-new").getModulus()).isEqualTo(newKey.getModulus());
        assertThat(requestCount).hasValue(2);
    }

    /**
     * <b>가용성 방어의 핵심.</b> 임의 {@code kid}를 반복 전송해도 카카오로 나가는 요청은
     * 쿨다운당 한 번뿐이어야 한다. 이게 깨지면 JWKS 조회를 무한 유발당해 차단될 수 있다.
     */
    @Test
    void 쿨다운_중에는_재조회하지_않는다() throws Exception {
        RSAPublicKey publicKey = (RSAPublicKey) generateRsaKeyPair().getPublic();
        responseBody = jwksJson(jwkJson("kid-1", publicKey, "RSA"));
        CachingKakaoJwkSource jwkSource = source(Duration.ofMinutes(1));

        for (int i = 0; i < 50; i++) {
            int attempt = i;
            assertThatThrownBy(() -> jwkSource.findByKid("공격자가-지어낸-kid-" + attempt))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.INVALID_OAUTH_TOKEN);
        }

        assertThat(requestCount).hasValue(1);
    }

    /**
     * <b>위 테스트의 대조군.</b> 쿨다운을 0으로 두면 같은 50회가 그대로 50번의 조회가 된다.
     * 이 테스트가 함께 통과해야 "요청이 1건으로 묶인 건 캐시가 아니라 쿨다운 덕분"임이 증명된다.
     * (쿨다운 로직을 지우면 위 테스트가 이 값으로 무너진다)
     */
    @Test
    void 쿨다운이_0이면_미스마다_조회한다_대조군() throws Exception {
        RSAPublicKey publicKey = (RSAPublicKey) generateRsaKeyPair().getPublic();
        responseBody = jwksJson(jwkJson("kid-1", publicKey, "RSA"));
        CachingKakaoJwkSource jwkSource = source(Duration.ZERO);

        for (int i = 0; i < 50; i++) {
            int attempt = i;
            assertThatThrownBy(() -> jwkSource.findByKid("없는-kid-" + attempt))
                    .isInstanceOf(BusinessException.class);
        }

        assertThat(requestCount).hasValue(50);
    }

    @Test
    void 쿨다운이_지나면_다시_조회한다() throws Exception {
        RSAPublicKey publicKey = (RSAPublicKey) generateRsaKeyPair().getPublic();
        responseBody = jwksJson(jwkJson("kid-1", publicKey, "RSA"));
        CachingKakaoJwkSource jwkSource = source(Duration.ofMinutes(1));

        assertThatThrownBy(() -> jwkSource.findByKid("없는-kid")).isInstanceOf(BusinessException.class);
        assertThat(requestCount).hasValue(1);

        clock.advance(Duration.ofMinutes(2));
        assertThatThrownBy(() -> jwkSource.findByKid("없는-kid")).isInstanceOf(BusinessException.class);

        assertThat(requestCount).hasValue(2);
    }

    /**
     * 일시적 네트워크 장애가 로그인 전체 중단으로 번지면 안 된다.
     * 이미 캐시된 키로는 계속 동작해야 한다.
     */
    @Test
    void 조회에_실패해도_캐시된_키로_계속_동작한다() throws Exception {
        RSAPublicKey publicKey = (RSAPublicKey) generateRsaKeyPair().getPublic();
        responseBody = jwksJson(jwkJson("kid-1", publicKey, "RSA"));
        CachingKakaoJwkSource jwkSource = source(Duration.ofSeconds(1));
        jwkSource.findByKid("kid-1"); // 캐시 적재

        responseStatus = 500;
        responseBody = "boom";
        clock.advance(Duration.ofMinutes(1));

        // 캐시 미스를 유발해 실패하는 조회를 태운다 — 예외가 전파되면 안 된다
        assertThatThrownBy(() -> jwkSource.findByKid("없는-kid"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_OAUTH_TOKEN);

        // 기존 키는 그대로 살아 있다
        assertThat(jwkSource.findByKid("kid-1").getModulus()).isEqualTo(publicKey.getModulus());
    }

    @Test
    void 응답이_비어_있어도_예외를_전파하지_않는다() {
        responseBody = "{\"keys\":[]}";

        assertThatThrownBy(() -> source().findByKid("kid-1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_OAUTH_TOKEN);
    }

    /** RSA 외의 키 타입은 건너뛴다 — 서명 검증에 쓸 수 없다. */
    @Test
    void RSA가_아닌_키는_건너뛴다() throws Exception {
        RSAPublicKey publicKey = (RSAPublicKey) generateRsaKeyPair().getPublic();
        responseBody = jwksJson(jwkJson("kid-ec", publicKey, "EC"));

        assertThatThrownBy(() -> source().findByKid("kid-ec"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_OAUTH_TOKEN);
    }

    /** kid가 없는 토큰은 조회 자체를 시도하지 않는다 — 헛된 JWKS 호출을 막는다. */
    @Test
    void kid가_null이거나_공백이면_조회하지_않고_INVALID_OAUTH_TOKEN이다() {
        CachingKakaoJwkSource jwkSource = source();

        assertThatThrownBy(() -> jwkSource.findByKid(null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_OAUTH_TOKEN);
        assertThatThrownBy(() -> jwkSource.findByKid("  "))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_OAUTH_TOKEN);

        assertThat(requestCount).hasValue(0);
    }

    @Test
    void 여러_키를_한_번에_캐싱한다() throws Exception {
        RSAPublicKey first = (RSAPublicKey) generateRsaKeyPair().getPublic();
        RSAPublicKey second = (RSAPublicKey) generateRsaKeyPair().getPublic();
        responseBody = jwksJson(jwkJson("kid-1", first, "RSA"), jwkJson("kid-2", second, "RSA"));
        CachingKakaoJwkSource jwkSource = source();

        assertThat(jwkSource.findByKid("kid-1").getModulus()).isEqualTo(first.getModulus());
        assertThat(jwkSource.findByKid("kid-2").getModulus()).isEqualTo(second.getModulus());

        assertThat(requestCount).hasValue(1); // 한 번의 조회로 둘 다 적재됐다
    }
}
