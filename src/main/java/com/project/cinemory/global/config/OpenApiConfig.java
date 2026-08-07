package com.project.cinemory.global.config;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
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
}
