package com.project.cinemory.domain.recommendation.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 테스트에서 실제 Gemini 대신 {@link FakeEmbeddingClient}를 쓰게 한다 — {@code @Import(FakeEmbeddingConfig.class)}.
 * {@code @Primary}라 인덱스·배치·추천 서비스가 모두 이 빈을 주입받는다. (실제 클라이언트는 {@code application-test.yml}이 base-url도 막아 둔다.)
 */
@TestConfiguration
public class FakeEmbeddingConfig {

    @Bean
    @Primary
    public FakeEmbeddingClient fakeEmbeddingClient() {
        return new FakeEmbeddingClient();
    }
}
