package com.project.cinemory.global.infra.kakao;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 카카오 로컬(주소 검색) REST 설정(cinemap-spec C-4-4). {@code restApiKey}는 서버에만 둔다.
 * 로그인용 {@code oauth.kakao.*}와는 별개다(운영 앱 키 허용 목록에 REST 키를 넣지 않는 원칙은 그대로).
 */
@ConfigurationProperties(prefix = "kakao")
public record KakaoLocalProperties(String baseUrl, String restApiKey) {

    public boolean isConfigured() {
        return restApiKey != null && !restApiKey.isBlank();
    }
}
