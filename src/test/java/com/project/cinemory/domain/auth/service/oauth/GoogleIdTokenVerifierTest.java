package com.project.cinemory.domain.auth.service.oauth;

import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.google.GoogleOAuthProperties;
import com.project.cinemory.global.infra.oidc.JwkSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 구글 ID 토큰 검증기 테스트 (account-integrity D-5-D).
 *
 * <p>토큰을 자체 RSA 키로 직접 조립하는 이유는 {@code KakaoIdTokenVerifierTest}와 같다 — 실제 구글 토큰으로는
 * 정상 케이스밖에 만들 수 없다. 서명·만료 같은 공통 검증은 카카오 테스트가 이미 고정하므로, 여기서는
 * <b>구글에만 있는 것</b>(두 형식의 {@code iss}, {@code azp} 무시, {@code email_verified}, {@code name})에 집중한다.
 */
class GoogleIdTokenVerifierTest {

    private static final String ISSUER_HTTPS = "https://accounts.google.com";
    private static final String ISSUER_BARE = "accounts.google.com";
    private static final String WEB_CLIENT_ID = "web-client-id.apps.googleusercontent.com";
    private static final String ANDROID_CLIENT_ID = "android-client-id.apps.googleusercontent.com";
    private static final String KID = "google-key-1";
    private static final String SUBJECT = "109876543210987654321";
    private static final String NONCE = "nonce-abc";

    private static final Instant FIXED_NOW = Instant.parse("2026-10-11T00:00:00Z");
    /** 구글 ID 토큰 수명은 1시간이다. */
    private static final long TOKEN_LIFETIME_SECONDS = 3600L;

    private static KeyPair googleKeyPair;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        googleKeyPair = generator.generateKeyPair();
    }

    // ---------------------------------------------------------------- 정상

    @Test
    void 유효한_토큰이면_사용자_정보를_반환한다() throws Exception {
        OAuthUserInfo info = verifier().verify(signedToken(defaultClaims()), NONCE);

        assertThat(info.providerId()).isEqualTo(SUBJECT);
        assertThat(info.email()).isEqualTo("hong@gmail.com");
        assertThat(info.nickname()).isEqualTo("홍길동");
        assertThat(info.profileImage()).isEqualTo("https://lh3.googleusercontent.com/a/p.jpg");
    }

    // ---------------------------------------------------------------- iss

    /** 구글은 두 형식을 모두 발급한다 — 하나만 받으면 일부 토큰이 이유 없이 거부된다. */
    @ParameterizedTest
    @ValueSource(strings = {ISSUER_HTTPS, ISSUER_BARE})
    void iss는_두_형식_모두_통과한다(String issuer) throws Exception {
        Map<String, String> claims = defaultClaims();
        claims.put("iss", quoted(issuer));

        assertThat(verifier().verify(signedToken(claims), NONCE).providerId()).isEqualTo(SUBJECT);
    }

    @Test
    void 다른_발급자의_토큰은_거부된다() throws Exception {
        Map<String, String> claims = defaultClaims();
        claims.put("iss", quoted("https://kauth.kakao.com"));

        assertThatThrownBy(() -> verifier().verify(signedToken(claims), NONCE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_OAUTH_TOKEN);
    }

    // ---------------------------------------------------------------- aud / azp

    @Test
    void aud가_웹_클라이언트_ID가_아니면_거부된다() throws Exception {
        // Android 클라이언트 ID를 aud로 받아 주면 안 된다 — 구글 기준 aud는 웹 클라이언트 ID다
        Map<String, String> claims = defaultClaims();
        claims.put("aud", quoted(ANDROID_CLIENT_ID));

        assertThatThrownBy(() -> verifier().verify(signedToken(claims), NONCE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_OAUTH_TOKEN);
    }

    /** Android 토큰은 azp = Android 클라이언트 ID로 온다. azp를 검증하면 정상 로그인이 전부 막힌다. */
    @Test
    void azp가_aud와_달라도_통과한다() throws Exception {
        Map<String, String> claims = defaultClaims();
        claims.put("azp", quoted("someone-elses-client-id"));

        assertThat(verifier().verify(signedToken(claims), NONCE).providerId()).isEqualTo(SUBJECT);
    }

    // ---------------------------------------------------------------- nonce / 만료

    @Test
    void nonce가_다르면_INVALID_NONCE다() throws Exception {
        assertThatThrownBy(() -> verifier().verify(signedToken(defaultClaims()), "different-nonce"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_NONCE);
    }

    @Test
    void 만료된_토큰은_INVALID_OAUTH_TOKEN이다() throws Exception {
        Map<String, String> claims = defaultClaims();
        claims.put("exp", String.valueOf(FIXED_NOW.minusSeconds(60).getEpochSecond()));

        assertThatThrownBy(() -> verifier().verify(signedToken(claims), NONCE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_OAUTH_TOKEN);
    }

    // ---------------------------------------------------------------- email / email_verified

    @Test
    void 이메일이_없으면_OAUTH_EMAIL_NOT_PROVIDED다() throws Exception {
        Map<String, String> claims = defaultClaims();
        claims.remove("email");

        assertThatThrownBy(() -> verifier().verify(signedToken(claims), NONCE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.OAUTH_EMAIL_NOT_PROVIDED);
    }

    /** 판정 순서: email 존재 → email_verified. 둘 다 없으면 "제공 안 됨"이 사실이다. */
    @Test
    void 이메일과_인증_여부가_모두_없으면_OAUTH_EMAIL_NOT_PROVIDED다() throws Exception {
        Map<String, String> claims = defaultClaims();
        claims.remove("email");
        claims.remove("email_verified");

        assertThatThrownBy(() -> verifier().verify(signedToken(claims), NONCE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.OAUTH_EMAIL_NOT_PROVIDED);
    }

    @Test
    void email_verified가_false면_OAUTH_EMAIL_NOT_VERIFIED다() throws Exception {
        Map<String, String> claims = defaultClaims();
        claims.put("email_verified", "false");

        assertThatThrownBy(() -> verifier().verify(signedToken(claims), NONCE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.OAUTH_EMAIL_NOT_VERIFIED);
    }

    /** 누락은 "확인되지 않음"으로 본다 — 모르는 상태를 통과시키는 쪽이 위험하다. */
    @Test
    void email_verified가_없으면_OAUTH_EMAIL_NOT_VERIFIED다() throws Exception {
        Map<String, String> claims = defaultClaims();
        claims.remove("email_verified");

        assertThatThrownBy(() -> verifier().verify(signedToken(claims), NONCE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.OAUTH_EMAIL_NOT_VERIFIED);
    }

    /** 일부 OIDC 제공자는 불리언을 문자열로 보낸다. */
    @Test
    void email_verified가_문자열_true여도_통과한다() throws Exception {
        Map<String, String> claims = defaultClaims();
        claims.put("email_verified", quoted("true"));

        assertThat(verifier().verify(signedToken(claims), NONCE).email()).isEqualTo("hong@gmail.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"false\"", "\"TRUE\"", "1", "null"})
    void email_verified가_true_또는_문자열_true가_아니면_거부된다(String rawJsonValue) throws Exception {
        Map<String, String> claims = defaultClaims();
        claims.put("email_verified", rawJsonValue);

        assertThatThrownBy(() -> verifier().verify(signedToken(claims), NONCE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.OAUTH_EMAIL_NOT_VERIFIED);
    }

    // ---------------------------------------------------------------- 매핑

    @Test
    void 이름이_없으면_기본값을_만들어_가입을_진행한다() throws Exception {
        Map<String, String> claims = defaultClaims();
        claims.remove("name");

        OAuthUserInfo info = verifier().verify(signedToken(claims), NONCE);

        assertThat(info.nickname()).isEqualTo("구글사용자" + SUBJECT.substring(SUBJECT.length() - 6));
    }

    @Test
    void 프로필_이미지가_없어도_통과한다() throws Exception {
        Map<String, String> claims = defaultClaims();
        claims.remove("picture");

        assertThat(verifier().verify(signedToken(claims), NONCE).profileImage()).isNull();
    }

    @Test
    void sub가_없으면_거부된다() throws Exception {
        Map<String, String> claims = defaultClaims();
        claims.remove("sub");

        assertThatThrownBy(() -> verifier().verify(signedToken(claims), NONCE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_OAUTH_TOKEN);
    }

    // ================================================================ 헬퍼

    private GoogleIdTokenVerifier verifier() {
        JwkSource jwkSource = requestedKid -> {
            if (KID.equals(requestedKid)) {
                return (RSAPublicKey) googleKeyPair.getPublic();
            }
            throw new BusinessException(ErrorCode.INVALID_OAUTH_TOKEN);
        };

        GoogleOAuthProperties properties = new GoogleOAuthProperties(
                List.of(ISSUER_HTTPS, ISSUER_BARE), "https://unused.example/certs",
                List.of(WEB_CLIENT_ID), Duration.ofMinutes(1));

        return new GoogleIdTokenVerifier(jwkSource, properties, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    /** Android 앱이 받는 실제 토큰 모양 — aud는 웹, azp는 Android 클라이언트 ID. */
    private Map<String, String> defaultClaims() {
        Map<String, String> claims = new LinkedHashMap<>();
        claims.put("iss", quoted(ISSUER_HTTPS));
        claims.put("azp", quoted(ANDROID_CLIENT_ID));
        claims.put("aud", quoted(WEB_CLIENT_ID));
        claims.put("sub", quoted(SUBJECT));
        claims.put("email", quoted("hong@gmail.com"));
        claims.put("email_verified", "true");
        claims.put("nonce", quoted(NONCE));
        claims.put("name", quoted("홍길동"));
        claims.put("picture", quoted("https://lh3.googleusercontent.com/a/p.jpg"));
        claims.put("iat", String.valueOf(FIXED_NOW.getEpochSecond()));
        claims.put("exp", String.valueOf(FIXED_NOW.plusSeconds(TOKEN_LIFETIME_SECONDS).getEpochSecond()));
        return claims;
    }

    private String signedToken(Map<String, String> claims) throws Exception {
        return signedToken(claims, googleKeyPair.getPrivate(), KID);
    }

    private String signedToken(Map<String, String> claims, PrivateKey key, String kid) throws Exception {
        Map<String, String> header = new LinkedHashMap<>();
        header.put("kid", quoted(kid));
        header.put("typ", quoted("JWT"));
        header.put("alg", quoted("RS256"));

        String signingInput = encode(toJson(header)) + "." + encode(toJson(claims));

        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key);
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));

        return signingInput + "." + base64Url(signature.sign());
    }

    private String toJson(Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(entry -> quoted(entry.getKey()) + ":" + entry.getValue())
                .collect(Collectors.joining(",", "{", "}"));
    }

    private String encode(String json) {
        return base64Url(json.getBytes(StandardCharsets.UTF_8));
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String quoted(String value) {
        return "\"" + value + "\"";
    }
}
