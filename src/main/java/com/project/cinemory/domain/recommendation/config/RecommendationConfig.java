package com.project.cinemory.domain.recommendation.config;

import com.project.cinemory.domain.recommendation.service.EmbeddingSyncProperties;
import com.project.cinemory.domain.recommendation.service.RecommendSafetyProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** {@code cinemory.recommend.*} 프로퍼티 바인딩 등록. */
@Configuration
@EnableConfigurationProperties({EmbeddingSyncProperties.class, RecommendProperties.class, RecommendSafetyProperties.class})
public class RecommendationConfig {
}
