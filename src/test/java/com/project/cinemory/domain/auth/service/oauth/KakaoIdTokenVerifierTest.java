package com.project.cinemory.domain.auth.service.oauth;

import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.kakao.KakaoJwkSource;
import com.project.cinemory.global.infra.kakao.KakaoOAuthProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
 * 카카오 ID 토큰 검증기 테스트.
 *
 * <p><b>왜 토큰을 직접 조립하는가</b> — 실제 카카오 토큰으로는 "정상 케이스"밖에 만들 수 없다.
 * 자체 RSA 키쌍으로 서명하면 {@code aud} 불일치 / 서명 위조 / 만료 / nonce 불일치처럼
 * <b>실제로 위험한 분기</b>를 전부 태울 수 있다. 스펙의 {@code aud} 값이 틀려 있던 것도
 * (REST API 키 ↔ 네이티브 앱 키) 이런 테스트가 있으면 구현 시점에 잡힌다.
 *
 * <p>JWT 라이브러리의 빌더 대신 JDK 표준 {@link Signature}로 서명하는 이유는,
 * 라이브러리 버전에 따라 빌더 API가 바뀌어도 테스트가 흔들리지 않게 하기 위해서다.
 * 덤으로 {@code aud}를 배열로 만들거나 클레임을 통째로 빼는 조작이 자유롭다.
 */
class KakaoIdTokenVerifierTest {

    private static final String ISSUER = "https://kauth.kakao.com";
    private static final String ALLOWED_AUD = "kakao-native-app-key";
    private static final String KID = "kakao-key-1";
    private static final String SUBJECT = "3000000001";
    private static final String NONCE = "nonce-abc";

    private static final Instant FIXED_NOW = Instant.parse("2026-08-02T00:00:00Z");
    /** 카카오 ID 토큰 수명은 약 2시간이다. */
    private static final long TOKEN_LIFETIME_SECONDS = 7200L;

    private static KeyPair kakaoKeyPair;
    private static KeyPair attackerKeyPair;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        kakaoKeyPair = generator.generateKeyPair();
        attackerKeyPair = generator.generateKeyPair();
    }

    // ---------------------------------------------------------------- 정상

    @Test
    void 유효한_토큰이면_사용자_정보를_반환한다() throws Exception {
        OAuthUserInfo info = verifier().verify(signedToken(defaultClaims()), NONCE);

        assertThat(info.providerId()).isEqualTo(SUBJECT);
        assertThat(info.email()).isEqualTo("hong@example.com");
        assertThat(info.nickname()).isEqualTo("인웅");
        assertThat(info.profileImage()).isEqualTo("https://img.example/p.jpg");
    }

    // ---------------------------------------------------------------- 서명

    @Test
    void 다른_키로_서명된_토큰은_거부된다() throws Exception {
        // 서명 검증이 실제로 수행되는지 확인한다. 이게 뚫리면 페이로드를 마음대로 써서
        // 아무 계정으로나 로그인할 수 있다.
        String forged = signedToken(defaultClaims(), attackerKeyPair.getPrivate(), KID);

        assertThatThrownBy(() -> verifier().verify(forged, NONCE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_OAUTH_TOKEN);
    }

    @Test
    void 알_수_없는_kid는_거부된다() throws Exception {
        String token = signedToken(defaultClaims(), kakaoKeyPair.getPrivate(), "unknown-kid");

        assertThatThrownBy(() -> verifier().verify(token, NONCE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_OAUTH_TOKEN);
    }

    // ---------------------------------------------------------------- aud

    @Test
    void 허용되지_않은_aud는_거부된다() throws Exception {
        // 다른 서비스용으로 발급된 카카오 토큰을 막는 유일한 장치다.
        // 같은 카카오가 발급했으므로 서명·iss·exp는 전부 통과한다.
        Map<String, String> claims = defaultClaims();
        claims.put("aud", quoted("someone-elses-app-key"));

        assertThatThrownBy(() -> verifier().verify(signedToken(claims), NONCE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_OAUTH_TOKEN);
    }

    @Test
    void aud가_배열이어도_허용_목록과_교집합이_있으면_통과한다() throws Exception {
        // OIDC 표준상 aud는 문자열 또는 배열이다. 카카오는 단일이지만 방어해 둔다.
        Map<String, String> claims = defaultClaims();
        claims.put("aud", "[" + quoted("other-app") + "," + quoted(ALLOWED_AUD) + "]");

        assertThat(verifier().verify(signedToken(claims), NONCE).providerId()).isEqualTo(SUBJECT);
    }

    // ---------------------------------------------------------------- iss

    @Test
    void 발급자가_다르면_거부된다() throws Exception {
        Map<String, String> claims = defaultClaims();
        claims.put("iss", quoted("https://accounts.google.com"));

        assertThatThrownBy(() -> verifier().verify(signedToken(claims), NONCE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_OAUTH_TOKEN);
    }

    // ---------------------------------------------------------------- 만료

    @Test
    void 만료된_토큰은_INVALID_OAUTH_TOKEN이다() throws Exception {
        // 우리 Access Token의 TOKEN_EXPIRED와 구분한다 — 카카오 ID 토큰은 재발급 대상이 아니라
        // 로그인을 처음부터 다시 해야 하므로 클라이언트 분기가 같다.
        Map<String, String> claims = defaultClaims();
        claims.put("exp", String.valueOf(FIXED_NOW.minusSeconds(60).getEpochSecond()));

        assertThatThrownBy(() -> verifier().verify(signedToken(claims), NONCE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_OAUTH_TOKEN);
    }

    @Test
    void 시계_오차_허용치_안에서_막_만료된_토큰은_통과한다() throws Exception {
        // 카카오 서버와 우리 서버의 시계가 몇 초 어긋나도 방금 발급된 토큰이 튕기지 않아야 한다.
        Map<String, String> claims = defaultClaims();
        claims.put("exp", String.valueOf(FIXED_NOW.minusSeconds(10).getEpochSecond()));

        assertThat(verifier().verify(signedToken(claims), NONCE).providerId()).isEqualTo(SUBJECT);
    }

    // ---------------------------------------------------------------- nonce

    @Test
    void nonce가_다르면_INVALID_NONCE다() throws Exception {
        // INVALID_OAUTH_TOKEN과 분리한다 — 클라이언트는 nonce를 다시 받아 재시도해야 한다.
        assertThatThrownBy(() -> verifier().verify(signedToken(defaultClaims()), "different-nonce"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_NONCE);
    }

    @Test
    void nonce_클레임이_없으면_INVALID_NONCE다() throws Exception {
        Map<String, String> claims = defaultClaims();
        claims.remove("nonce");

        assertThatThrownBy(() -> verifier().verify(signedToken(claims), NONCE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_NONCE);
    }

    // ---------------------------------------------------------------- 클레임 매핑

    @Test
    void 이메일이_없으면_가입을_막는다() throws Exception {
        // user.email이 NOT NULL이고, 플레이스홀더를 만들면 uk_user_email에 가짜 데이터가 쌓인다.
        Map<String, String> claims = defaultClaims();
        claims.remove("email");

        assertThatThrownBy(() -> verifier().verify(signedToken(claims), NONCE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.OAUTH_EMAIL_NOT_PROVIDED);
    }

    @Test
    void 닉네임이_없으면_기본값을_만들어_가입을_진행한다() throws Exception {
        // 이메일과 달리 UNIQUE가 아니고 나중에 변경할 수 있어 가입을 막지 않는다 (S-9 E-3).
        Map<String, String> claims = defaultClaims();
        claims.remove("nickname");

        OAuthUserInfo info = verifier().verify(signedToken(claims), NONCE);

        assertThat(info.nickname()).isEqualTo("카카오사용자" + SUBJECT.substring(SUBJECT.length() - 6));
        assertThat(info.email()).isEqualTo("hong@example.com");
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

    private KakaoIdTokenVerifier verifier() {
        KakaoJwkSource jwkSource = requestedKid -> {
            if (KID.equals(requestedKid)) {
                return (RSAPublicKey) kakaoKeyPair.getPublic();
            }
            throw new BusinessException(ErrorCode.INVALID_OAUTH_TOKEN);
        };

        KakaoOAuthProperties properties = new KakaoOAuthProperties(
                ISSUER, "https://unused.example/jwks.json", List.of(ALLOWED_AUD), Duration.ofMinutes(1));

        return new KakaoIdTokenVerifier(jwkSource, properties, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    /** 키는 JSON 리터럴 그대로 담는다 — 문자열은 {@link #quoted}, 숫자·배열은 있는 그대로. */
    private Map<String, String> defaultClaims() {
        Map<String, String> claims = new LinkedHashMap<>();
        claims.put("iss", quoted(ISSUER));
        claims.put("aud", quoted(ALLOWED_AUD));
        claims.put("sub", quoted(SUBJECT));
        claims.put("iat", String.valueOf(FIXED_NOW.getEpochSecond()));
        claims.put("exp", String.valueOf(FIXED_NOW.plusSeconds(TOKEN_LIFETIME_SECONDS).getEpochSecond()));
        claims.put("nonce", quoted(NONCE));
        claims.put("nickname", quoted("인웅"));
        claims.put("picture", quoted("https://img.example/p.jpg"));
        claims.put("email", quoted("hong@example.com"));
        return claims;
    }

    private String signedToken(Map<String, String> claims) throws Exception {
        return signedToken(claims, kakaoKeyPair.getPrivate(), KID);
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
