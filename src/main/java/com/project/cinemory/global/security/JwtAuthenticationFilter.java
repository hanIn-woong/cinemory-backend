package com.project.cinemory.global.security;

import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.security.handler.JwtAuthenticationEntryPoint;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Access Token으로 SecurityContext를 채우는 인증 필터.
 *
 * <p><b>인증만 하고 인가는 하지 않는다.</b> 인가는 체인 뒤쪽의 {@code AuthorizationFilter}가
 * {@code authorizeHttpRequests} 규칙으로 판정한다. 이 분리 덕분에 필터를 건너뛰는 설정이
 * 잘못되더라도 방향이 fail-closed다 — 컨텍스트가 비면 보호된 경로는 통과가 아니라 거부된다.
 *
 * <p><b>세 갈래 분기</b> (security-spec.md S-4)
 * <ol>
 *   <li>토큰 없음 → 컨텍스트를 비운 채 통과. {@code permitAll} 경로는 {@code viewerId == null}로 진입</li>
 *   <li>토큰 유효 → 인증 객체를 채우고 통과</li>
 *   <li>토큰 무효·만료 → <b>401로 응답하고 체인을 끊는다</b> (S-9 A-3)</li>
 * </ol>
 *
 * <p><b>③에서 체인을 끊어야 하는 이유</b> — 오류를 기록만 하고 체인을 계속 태우면
 * {@code permitAll} 경로는 인가를 통과해 200이 나가버려 A-3이 무력화된다.
 * 또한 여기서 예외를 던져도 {@code ExceptionTranslationFilter}는 체인 뒤쪽에 있어 잡지 못하므로,
 * 필터가 {@link JwtAuthenticationEntryPoint}를 직접 호출해 응답을 끝낸다.
 *
 * <p><b>빈으로 등록하지 않는다.</b> Spring Boot는 {@code Filter} 타입 빈을 서블릿 컨테이너
 * 필터 체인에 <b>자동 등록</b>하므로, {@code @Component}를 붙이면 Security 체인 밖에서도
 * 한 번 더 동작한다. {@code SecurityConfig}에서 직접 생성해 배선한다.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;

    /**
     * 필터를 건너뛸 POST 경로 패턴 (S-9 C-1).
     * {@code SecurityConfig}가 공개 POST 경로 상수를 그대로 넘겨준다 — 목록을 두 곳에 적으면
     * 엔드포인트를 추가할 때 갈라지기 때문이다.
     */
    private final List<PathPattern> skipPatterns;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                   JwtAuthenticationEntryPoint authenticationEntryPoint,
                                   String[] skipPostPatterns) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.authenticationEntryPoint = authenticationEntryPoint;

        PathPatternParser parser = new PathPatternParser();
        this.skipPatterns = Arrays.stream(skipPostPatterns).map(parser::parse).toList();
    }

    /**
     * 로그인·회원가입·소셜로그인·재발급만 건너뛴다.
     *
     * <p>만료된 토큰을 헤더에 단 채 로그인을 시도하는 상황이 흔한데, A-3을 그대로 적용하면
     * <b>재로그인 자체가 401로 막혀 복구할 수 없게 된다.</b> 네 경로 모두 인증 주체를 쓰지 않으며,
     * 재발급조차 자격증명이 헤더의 Access가 아니라 body의 리프레시 토큰이다.
     *
     * <p>{@code /api/auth/logout}은 인증이 필요하므로(S-9 A-5) 제외 대상이 아니다 —
     * {@code /api/auth/**} 같은 통짜 패턴을 쓰면 안 되는 이유가 이것이다.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return false;
        }
        PathContainer path = PathContainer.parsePath(pathWithinApplication(request));
        return skipPatterns.stream().anyMatch(pattern -> pattern.matches(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String token = resolveToken(request);

        // ① 토큰 없음 — 오류가 아니다. 비로그인으로 진입시킨다
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            AuthUserPrincipal principal = jwtTokenProvider.parseAccessToken(token);
            // ② 토큰 유효
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(toAuthentication(principal));
            SecurityContextHolder.setContext(context);

        } catch (BusinessException e) {
            // ③ 토큰 무효·만료 — 여기서 응답을 끝낸다 (doFilter를 호출하지 않는다)
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.commence(request, response, new JwtAuthenticationException(e.getErrorCode()));
            return;
        }

        filterChain.doFilter(request, response);
    }

    /** {@code Authorization: Bearer <token>}. 헤더가 없거나 접두사가 다르면 "토큰 없음"으로 본다. */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * authority에 <b>{@code ROLE_} 접두사를 포함</b>시킨다 (S-9 B-1).
     * {@code hasRole()}이 비교 시 접두사를 자동 부착하므로, 빠뜨리면 {@code /api/admin/**}이
     * 전부 403이 되고 원인 추적이 까다롭다. 접두사 부착은 {@code RoleType.authority()}가 전담한다.
     */
    private Authentication toAuthentication(AuthUserPrincipal principal) {
        return UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                List.of(new SimpleGrantedAuthority(principal.role().authority())));
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return (contextPath == null || contextPath.isEmpty()) ? uri : uri.substring(contextPath.length());
    }
}
