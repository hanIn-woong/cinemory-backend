package com.project.cinemory.global.security;

import com.project.cinemory.domain.user.entity.RoleType;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * Access Token 발급·검증과 Refresh Token 생성을 전담한다.
 *
 * <p><b>HS256(대칭키)</b>를 쓴다. 발급자와 검증자가 같은 단일 서버이므로 RS256의 키쌍 관리
 * 비용이 이득 없이 늘어난다. 인증 서버를 분리하게 되면 그때 전환한다.
 *
 * <p><b>Refresh Token은 JWT가 아니다.</b> 어차피 DB를 조회해 유효성을 확인하므로 자체 검증
 * 능력이 필요 없고, JWT로 만들면 페이로드에 불필요한 정보가 들어가 유출 시 노출면만 넓어진다.
 * 불투명(opaque) 고엔트로피 랜덤값이 더 안전하고 짧다.
 */
@Component
public class JwtTokenProvider {

    private static final String CLAIM_ROLE = "role";

    /** HS256에 대응하는 JCA 알고리즘 이름. jjwt는 이 값을 공개 API로 노출하지 않아 상수로 둔다. */
    private static final String HS256_JCA_NAME = "HmacSHA256";

    /** Refresh Token 엔트로피(256bit). Base64URL 인코딩 시 패딩 없이 43자가 된다. */
    private static final int REFRESH_TOKEN_BYTES = 32;

    private final SecretKey secretKey;
    private final Duration accessTokenTtl;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public JwtTokenProvider(JwtProperties properties, Clock clock) {
        // Keys.hmacShaKeyFor()는 키 길이에 따라 HmacSHA384/512로 알고리즘을 바꿔버린다.
        // 설정 값 길이와 무관하게 HS256으로 고정하기 위해 JCA 이름을 직접 지정한다.
        this.secretKey = new SecretKeySpec(
                properties.secret().getBytes(StandardCharsets.UTF_8), HS256_JCA_NAME);
        this.accessTokenTtl = properties.accessTokenTtl();
        this.clock = clock;
    }

    /** claims: {@code sub}=userId, {@code role}, {@code iat}, {@code exp} */
    public String createAccessToken(Long userId, RoleType role) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_ROLE, role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtl)))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 서명·만료를 검증하고 인증 주체를 복원한다.
     *
     * <p>만료를 {@code INVALID_TOKEN}과 분리해 던지는 이유는, 클라이언트가 "재발급을 시도할
     * 상황"과 "로그인 화면으로 보낼 상황"을 구분해야 하기 때문이다. 하나로 합치면 앱이 매번
     * 재발급을 시도하다 실패하는 루프에 빠진다.
     *
     * @throws BusinessException {@code TOKEN_EXPIRED}(만료) / {@code INVALID_TOKEN}(서명·형식 오류)
     */
    public AuthUserPrincipal parseAccessToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    // 만료 판정도 주입된 시간 소스를 따르게 한다. 지정하지 않으면 jjwt가
                    // 시스템 시계를 쓰므로 테스트에서 시간을 고정할 수 없다.
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String role = claims.get(CLAIM_ROLE, String.class);
            if (role == null) {
                throw new JwtException("role 클레임이 없는 토큰입니다.");
            }
            // sub 파싱 실패(NumberFormatException)와 미지원 role 값은 모두
            // IllegalArgumentException이라 아래 catch에서 INVALID_TOKEN으로 수렴한다.
            return new AuthUserPrincipal(Long.valueOf(claims.getSubject()), RoleType.valueOf(role));

        } catch (ExpiredJwtException e) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }
    }

    /** JWT가 아닌 256bit 랜덤값(Base64URL, 패딩 없음). DB에는 이 값의 SHA-256 해시만 저장한다. */
    public String createRefreshToken() {
        byte[] entropy = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(entropy);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }
}
