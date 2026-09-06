package com.project.cinemory.global.config;

import com.project.cinemory.global.security.resolver.AuthUser;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Configuration;

/**
 * Springdoc OpenAPI 배선(5-0-G).
 *
 * <p>Swagger UI에 Authorize 버튼을 노출해 토큰을 한 번만 입력하면 되게 한다.
 * Step S 검증 때 하던 curl 반복이 여기서 사라진다. 각 도메인 Controller에서
 * {@code @SecurityRequirement(name = "bearerAuth")}로 참조한다.
 */
@Configuration
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        in = SecuritySchemeIn.HEADER,
        scheme = "bearer",
        bearerFormat = "JWT"
)
public class OpenApiConfig {

    /**
     * {@code @AuthUser} 파라미터가 일반 {@code @RequestParam}처럼 스캔돼 문서에
     * {@code viewerId}/{@code authorId} 같은 쿼리 파라미터로 새어나가는 문제를 막는다
     * (5-7 D에서 {@code openapi-typescript} 생성 결과를 실제로 열어보다가 발견 — 클라이언트가
     * 절대 채워선 안 되는 인증 주체 값이 필수 쿼리 파라미터로 노출되고 있었다).
     * 정적 등록이라 컨텍스트 초기화 전에 반영돼야 하므로 static 블록에 둔다.
     */
    static {
        SpringDocUtils.getConfig().addAnnotationsToIgnore(AuthUser.class);
    }
}
