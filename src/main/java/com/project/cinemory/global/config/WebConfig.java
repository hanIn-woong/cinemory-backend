package com.project.cinemory.global.config;

import com.project.cinemory.global.security.resolver.AuthUserArgumentResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Spring MVC 설정.
 *
 * <p>인자 리졸버는 MVC 관심사이므로 {@code SecurityConfig}가 아니라 여기서 등록한다.
 * {@code SecurityConfig}는 필터체인과 접근 정책만 책임진다.
 *
 * <p><b>{@code @EnableSpringDataWebSupport}를 여기 직접 선언하지 않는다.</b> Boot 4는 이 지원을
 * 자체 자동구성({@code DataWebAutoConfiguration})으로 옮기고 {@code pageSerializationMode}·
 * {@code max-page-size}를 {@code spring.data.web.pageable.*} 프로퍼티로 노출한다(application.yml 참고).
 * 여기서 어노테이션으로 직접 선언하면 프로퍼티를 읽지 않는 별도 리졸버가 만들어져
 * {@code max-page-size}가 조용히 무시된다 — 5-0-D 구현 중 curl로 확인된 회귀다.
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthUserArgumentResolver authUserArgumentResolver;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(authUserArgumentResolver);
    }
}
