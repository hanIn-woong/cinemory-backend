package com.project.cinemory.domain.recommendation.config;

import com.project.cinemory.domain.recommendation.service.EmbeddingSyncProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** {@code cinemory.recommend.*} 프로퍼티 바인딩 등록. PR 2에서 점수 가중치 프로퍼티가 여기에 추가된다. */
@Configuration
@EnableConfigurationProperties(EmbeddingSyncProperties.class)
public class RecommendationConfig {
}
