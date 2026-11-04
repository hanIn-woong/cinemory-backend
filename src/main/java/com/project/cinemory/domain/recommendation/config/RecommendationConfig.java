package com.project.cinemory.domain.recommendation.config;

import com.project.cinemory.domain.recommendation.service.EmbeddingSyncProperties;
import com.project.cinemory.domain.recommendation.service.RecommendSafetyProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;

/** {@code cinemory.recommend.*} 프로퍼티 바인딩 등록. */
@Configuration
@EnableConfigurationProperties({EmbeddingSyncProperties.class, RecommendProperties.class, RecommendSafetyProperties.class})
public class RecommendationConfig {

    /**
     * 취향 추천 묶음 추출의 난수원 (R-5-3). {@code SecureRandom}은 스레드 안전하고 추출당 호출이 50번 안팎이라 비용이 무시된다.
     * 테스트는 이 빈을 시드가 고정된 {@code Random}으로 바꾸거나 서비스를 직접 만든다.
     */
    @Bean
    public RandomGenerator forYouRandom() {
        return new SecureRandom();
    }
}
