package com.project.cinemory.global.security;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import com.project.cinemory.global.security.handler.JwtAccessDeniedHandler;
import com.project.cinemory.global.security.handler.JwtAuthenticationEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security 필터체인 설정.
 *
 * <p><b>책임 경계</b> — Spring Security는 "인증 여부"(당신이 누구인가)만 판정한다.
 * "그 사람의 데이터를 볼 자격이 있는가"(가시성)는 {@code UserAccessPolicy}의 책임이다.
 * 따라서 아래 {@code permitAll}은 "누구나 데이터를 본다"는 뜻이 <b>아니다.</b>
 * 필터는 통과시키되 실제 가시성은 서비스 계층에서 {@code viewerId}(비로그인이면 null) 기준으로 판정된다.
 * 이 분리를 지키면 Security 설정을 건드리지 않고도 공개범위 정책을 바꿀 수 있다.
 *
 * <p><b>화이트리스트 방식</b> — 기본을 {@code authenticated()}로 두고 공개 경로만 열거한다.
 * 반대로 하면 엔드포인트를 추가할 때마다 보호를 빠뜨릴 위험이 생긴다.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    /**
     * 인증 없이 호출 가능한 POST 엔드포인트.
     * {@code /api/auth/logout}은 <b>의도적으로 제외</b>했다 — 로그아웃은 인증된 상태가 정상 흐름이고,
     * 인증을 요구해야 전달받은 리프레시 토큰이 호출자 본인의 것인지 검증할 수 있다.
     *
     * <p>이 배열은 {@link JwtAuthenticationFilter}의 {@code shouldNotFilter} 대상으로도
     * <b>그대로 전달된다</b>(S-9 C-1). 목록을 두 곳에 적으면 엔드포인트 추가 시 갈라지기 때문이다.
     */
    static final String[] PUBLIC_POST_ENDPOINTS = {
            "/api/auth/signup",
            "/api/auth/login",
            // 소셜 로그인 nonce 발급 (S-9 E-1).
            // 경로를 /api/auth/oauth/nonce로 두지 않는 이유는 아래 {provider} 패턴과 겹쳐
            // nonce가 provider 이름처럼 해석될 여지가 생기기 때문이다.
            "/api/auth/nonce",
            "/api/auth/oauth/*",
            "/api/auth/reissue",
            // 비밀번호 재설정 3종(S-J). 비로그인 흐름이므로 permitAll이고,
            // 여기에 넣음으로써 C-1 필터 제외까지 자동으로 따라온다 — 이것이 필수다.
            // 만료된 Access가 헤더에 남은 채 재설정을 시도하는 것이 흔한 상황이기 때문이다.
            "/api/auth/password-reset/request",
            "/api/auth/password-reset/verify",
            "/api/auth/password-reset/confirm"
    };

    /**
     * 인증 없이 호출 가능한 GET 엔드포인트.
     *
     * <p>중괄호 치환({@code /api/users/*}/{@code {records,collections}})은 경로 매칭 문법이 아니라
     * 경로 변수 캡처로 해석되므로 <b>축약하지 말고 하나씩 열거</b>한다.
     *
     * <p>패키지 접근으로 열어둔 이유 — 5-7 A(화이트리스트 대조 회귀 테스트)가 같은 패키지에서
     * 이 배열을 직접 참조한다. 테스트가 별도로 화이트리스트를 다시 적으면 출처가 갈린다(5-0-F).
     */
    static final String[] PUBLIC_GET_ENDPOINTS = {
            // 공용 데이터
            "/api/movies/**",          // 영화 상세·목록 및 /api/movies/{id}/reviews 포함
            "/api/theaters/**",
            "/api/box-office/**",
            "/api/comments",
            // 프로필 헤더 — 비공개 계정이어도 헤더 자체는 노출한다
            "/api/users/*/profile",
            // 아래는 필터를 통과할 뿐, 실제 노출 여부는 UserAccessPolicy가 판정한다.
            // records는 /records/movies/{movieId}(회차 조회)까지 매칭해야 하므로 /** 이다(5-0-F) —
            // 세그먼트 1개만 잡는 * 였다면 회차 조회가 걸리지 않아 비로그인 공개 조회가 401로 막힌다.
            "/api/users/*/records/**",
            "/api/users/*/collections",
            "/api/users/*/reviews",
            "/api/users/*/wishes",
            "/api/users/*/followers",
            "/api/users/*/followings",
            // 4-5에서 공개 조회로 확정됐으나 누락돼 있었다(5-0-F) — 없으면 기본값 authenticated에 걸린다
            "/api/collections/*/movies",
            // Springdoc — Swagger UI + OpenAPI 3 문서(5-0-G). 운영 프로파일에서는 자체를 비활성화한다.
            "/swagger-ui/**",
            "/v3/api-docs/**"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   CorsConfigurationSource corsConfigurationSource,
                                                   JwtTokenProvider jwtTokenProvider,
                                                   JwtAuthenticationEntryPoint authenticationEntryPoint,
                                                   JwtAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                // JWT 기반 무상태 인증이라 CSRF 토큰이 무의미하다
                .csrf(AbstractHttpConfigurer::disable)
                // 스타터가 자동 활성화하는 기본 로그인 수단 해제
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)   // 401
                        .accessDeniedHandler(accessDeniedHandler))            // 403
                .authorizeHttpRequests(auth -> auth
                        // 404/405 등은 서블릿이 /error로 다시 디스패치하는데, 그 디스패치도 인가 대상이라
                        // 열어두지 않으면 원래 상태 코드가 401로 덮인다.
                        // 결과적으로 "경로 오타"가 클라이언트에는 "인증 실패"로 보여
                        // A-3의 401 분기(재발급/재로그인)를 잘못 태우게 된다.
                        //
                        // 덮이는 직접적인 원인은 JwtAuthenticationFilter가 OncePerRequestFilter라
                        // shouldNotFilterErrorDispatch()가 기본 true라는 점이다 — 에러 디스패치에서는
                        // 인증을 다시 채우지 않으므로 컨텍스트가 빈 채로 인가에 걸린다.
                        //
                        // 인가 판정 자체는 원래 요청에서 이미 끝났고, 거부된 요청은 EntryPoint가
                        // 직접 응답하므로 이 허용으로 보호된 로직에 도달할 수 있게 되지는 않는다.
                        //
                        // 경로(/error)가 아니라 디스패치 타입으로 여는 이유 — 경로로 열면
                        // 외부에서 GET /error를 직접 호출하는 것까지 함께 열린다. 그건 컨테이너가
                        // 만드는 내부 디스패치가 아니므로 계속 막아둔다.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET_ENDPOINTS).permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                // 필터를 빈으로 만들지 않고 여기서 생성한다 — Filter 타입 빈은 Spring Boot가
                // 서블릿 컨테이너 필터 체인에도 자동 등록해 Security 체인 밖에서 한 번 더 돌기 때문이다.
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtTokenProvider, authenticationEntryPoint, PUBLIC_POST_ENDPOINTS),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${cinemory.cors.allowed-origins}") List<String> allowedOrigins) {

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        // 쿠키를 쓰지 않고 Authorization 헤더로만 인증하므로 credentials는 허용하지 않는다
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
