package com.project.cinemory.global.security;

import com.project.cinemory.domain.user.entity.RoleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 5-7 A — 화이트리스트 대조 회귀 테스트(5-7의 핵심 산출물, {@code controller-layer-spec.md} 참고).
 *
 * <p>{@code RequestMappingHandlerMapping}에 등록된 전체 엔드포인트를 뽑아
 * {@code SecurityConfig}의 공개 경로 상수({@link SecurityConfig#PUBLIC_GET_ENDPOINTS},
 * {@link SecurityConfig#PUBLIC_POST_ENDPOINTS})와 대조한다. {@code permitAll} /
 * {@code authenticated}(기본값) / {@code hasRole('ADMIN')} 세 갈래를 모두 덮는다.
 *
 * <p><b>{@code MockMvc}를 쓰지 않는 이유</b> — 컨테이너의 인가·ERROR 디스패치 동작을 재현하지
 * 못한다(S-4 {@link SecurityErrorDispatchTest}와 동일한 근거). 여기서도 {@code RANDOM_PORT}로
 * 실제 톰캣을 띄운다.
 *
 * <p><b>부수효과 없음 보장</b> — 아래 테스트가 실제로 호출하는 경로는 (1) 인증이 필요한데
 * 토큰이 없는 요청, (2) {@code /api/admin/**}에 ADMIN이 아닌 토큰으로 보낸 요청뿐이다.
 * 둘 다 Spring Security 필터 단계에서 차단되어 컨트롤러 메서드 본문(=DB 쓰기, KOFIC 외부
 * API 호출 등)에 도달하지 않는다. permitAll 쪽 회귀 확인은 GET만 호출해 읽기로 제한한다 —
 * POST 쪽 permitAll(회원가입/로그인/재발급 등)은 실제 부수효과를 만들 수 있어 호출하지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WhitelistRegressionTest {

    private static final PathPatternParser PATTERN_PARSER = new PathPatternParser();
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[^/{}]+}");

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private String userToken;

    /** 필터는 JWT를 파싱해 주체만 세우고 DB를 보지 않으므로, 유저를 만들지 않고 토큰만 발급하면 된다. */
    @BeforeEach
    void setUp() {
        userToken = jwtTokenProvider.createAccessToken(1L, RoleType.USER);
    }

    private record Endpoint(HttpMethod method, String rawPattern) {

        /** 경로 변수를 더미 값으로 치환해 실제 호출 가능한 URL로 만든다. */
        String samplePath() {
            return PATH_VARIABLE.matcher(rawPattern).replaceAll("1");
        }
    }

    /** {@code RequestMappingHandlerMapping}에 등록된 전체 (메서드, 경로) 쌍을 뽑는다. */
    private List<Endpoint> mappedEndpoints() {
        return handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> {
                    var methods = info.getMethodsCondition().getMethods();
                    var patterns = info.getPatternValues();
                    // 메서드 미지정(예: BasicErrorController)은 전체 허용이므로 GET으로 대표 확인한다.
                    var effectiveMethods = methods.isEmpty() ? List.of(RequestMethod.GET) : methods.stream().toList();
                    return patterns.stream()
                            .flatMap(pattern -> effectiveMethods.stream()
                                    .map(method -> new Endpoint(HttpMethod.valueOf(method.name()), pattern)));
                })
                .distinct()
                .toList();
    }

    /**
     * 화이트리스트 매칭은 {@code SecurityConfig}가 실제로 쓰는 것과 같은 엔진(PathPattern)으로,
     * 같은 종류의 값(구체 경로)에 대해 수행한다 — 패턴끼리 비교하면 AntPathMatcher와
     * PathPattern의 미묘한 차이로 오탐/누락이 생길 수 있다.
     */
    private boolean matchesAny(String[] whitelistPatterns, String concretePath) {
        PathContainer target = PathContainer.parsePath(concretePath);
        return Arrays.stream(whitelistPatterns)
                .anyMatch(pattern -> PATTERN_PARSER.parse(pattern).matches(target));
    }

    private boolean isPermitAll(Endpoint endpoint) {
        String path = endpoint.samplePath();
        if (endpoint.method() == HttpMethod.GET) {
            return matchesAny(SecurityConfig.PUBLIC_GET_ENDPOINTS, path);
        }
        if (endpoint.method() == HttpMethod.POST) {
            return matchesAny(SecurityConfig.PUBLIC_POST_ENDPOINTS, path);
        }
        return false;
    }

    private boolean isAdminOnly(Endpoint endpoint) {
        return endpoint.samplePath().startsWith("/api/admin/");
    }

    private HttpResponse<String> call(HttpMethod method, String path, String token) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .method(method.name(), HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 5-7 A의 핵심 산출물 — 화이트리스트(permitAll GET/POST)에 없는 경로는 인증 없이 호출해도
     * 200을 반환하지 않는다. {@code /api/admin/**}도 화이트리스트 밖이므로 이 스윕에 포함되어
     * authenticated·hasRole('ADMIN') 두 갈래를 함께 덮는다.
     *
     * <p>안전성 — 인증이 없는 요청은 인가 단계에서 걸려 컨트롤러 본문(DB 쓰기·외부 API 호출)에
     * 도달하지 않는다. 어떤 경로를 태우든 부수효과가 없다.
     */
    @Test
    void 화이트리스트에_없는_경로는_미인증으로_200을_반환하지_않는다() throws Exception {
        List<Endpoint> targets = mappedEndpoints().stream()
                .filter(endpoint -> !isPermitAll(endpoint))
                .toList();

        assertThat(targets).isNotEmpty();

        List<Executable> checks = targets.stream()
                .<Executable>map(endpoint -> () -> {
                    int status = call(endpoint.method(), endpoint.samplePath(), null).statusCode();
                    assertThat(status)
                            .as("%s %s 는 미인증 호출로 200을 반환하면 안 된다", endpoint.method(), endpoint.samplePath())
                            .isNotEqualTo(200);
                })
                .toList();

        assertAll(checks);
    }

    /**
     * 반대쪽 회귀 — permitAll GET 화이트리스트 경로가 실수로 {@code authenticated()}로
     * 좁혀지면 미인증 호출이 401을 받는다. POST 쪽 permitAll(회원가입·로그인 등)은 실제
     * 부수효과가 있을 수 있어 GET만 확인한다.
     */
    @Test
    void 화이트리스트_GET_경로는_미인증으로도_401이_아니다() throws Exception {
        List<Endpoint> targets = mappedEndpoints().stream()
                .filter(endpoint -> endpoint.method() == HttpMethod.GET)
                .filter(this::isPermitAll)
                .toList();

        assertThat(targets).isNotEmpty();

        List<Executable> checks = targets.stream()
                .<Executable>map(endpoint -> () -> {
                    int status = call(endpoint.method(), endpoint.samplePath(), null).statusCode();
                    assertThat(status)
                            .as("%s %s 는 화이트리스트인데 미인증 401을 받았다", endpoint.method(), endpoint.samplePath())
                            .isNotEqualTo(401);
                })
                .toList();

        assertAll(checks);
    }

    /**
     * {@code hasRole('ADMIN')} 갈래를 명시적으로 고정한다 — 위 두 스윕만으로는 "인증은 됐지만
     * ADMIN이 아닌 사용자"를 구분하지 못한다(미인증 스윕은 401만 확인하므로, {@code hasRole}이
     * 실수로 {@code authenticated()}로 완화돼도 여전히 401만 나오는 경로에서는 잡히지 않는다).
     *
     * <p>안전성 — {@code AccessDeniedHandler}가 인가 단계에서 403으로 직접 응답하므로 컨트롤러
     * 본문(KOFIC 동기화 등)에 도달하지 않는다.
     */
    @Test
    void admin_경로는_일반_유저_토큰으로는_403이다() throws Exception {
        List<Endpoint> adminEndpoints = mappedEndpoints().stream()
                .filter(this::isAdminOnly)
                .toList();

        assertThat(adminEndpoints).isNotEmpty();

        List<Executable> checks = adminEndpoints.stream()
                .<Executable>map(endpoint -> () -> {
                    int status = call(endpoint.method(), endpoint.samplePath(), userToken).statusCode();
                    assertThat(status)
                            .as("%s %s 는 일반 유저 토큰으로 403이어야 한다", endpoint.method(), endpoint.samplePath())
                            .isEqualTo(403);
                })
                .toList();

        assertAll(checks);
    }
}
