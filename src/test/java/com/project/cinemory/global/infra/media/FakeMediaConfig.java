package com.project.cinemory.global.infra.media;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 모든 {@code @SpringBootTest}가 S3 대신 인메모리 저장소를 쓰게 한다. {@code @TestConfiguration}이 아니라 일반
 * {@code @Configuration}이라 컴포넌트 스캔으로 자동 적용된다 — 탈퇴 같은 기존 테스트가 실제 S3 호출(자격 증명 조회 대기)을 하지 않게 하려는 것이다.
 */
@Configuration
public class FakeMediaConfig {

    @Bean
    @Primary
    public InMemoryMediaStorage inMemoryMediaStorage() {
        return new InMemoryMediaStorage();
    }
}
