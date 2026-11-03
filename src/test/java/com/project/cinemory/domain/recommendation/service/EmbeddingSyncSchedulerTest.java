package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.global.infra.gemini.GeminiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 스케줄 진입점(OP-1-2) — 상한을 서비스에 넘기고, 키가 없으면 건너뛴다. 운영 cron은 설정 파일을 직접 읽어 고정한다. */
class EmbeddingSyncSchedulerTest {

    private static EmbeddingSyncProperties properties(int scheduledLimit) {
        return new EmbeddingSyncProperties("0 0 6 * * *", 100, 5, Duration.ofSeconds(1), Duration.ofSeconds(90),
                Duration.ZERO, 500, scheduledLimit);
    }

    @Test
    void 스케줄은_처음부터_훑되_임베딩_상한을_넘긴다() {
        EmbeddingSyncService service = mock(EmbeddingSyncService.class);

        new EmbeddingSyncScheduler(service, new GeminiProperties("key", "http://x", "m", Duration.ofSeconds(30)), properties(800))
                .syncEmbeddings();

        verify(service).sync(null, null, 800);
    }

    @Test
    void 상한이_0_이하면_상한_없이_실행한다() {
        EmbeddingSyncService service = mock(EmbeddingSyncService.class);

        new EmbeddingSyncScheduler(service, new GeminiProperties("key", "http://x", "m", Duration.ofSeconds(30)), properties(0))
                .syncEmbeddings();

        verify(service).sync(null, null, null);
    }

    @Test
    void 키가_없으면_건너뛴다() {
        EmbeddingSyncService service = mock(EmbeddingSyncService.class);

        new EmbeddingSyncScheduler(service, new GeminiProperties("", "http://x", "m", Duration.ofSeconds(30)), properties(800))
                .syncEmbeddings();

        verify(service, never()).sync(null, null, 800);
    }

    @Test
    void cron은_06시이고_운영도_덮지_않으며_1회_상한은_2000이다() {
        assertThat(load("application.yml").getProperty("cinemory.recommend.embedding-sync.cron")).isEqualTo("0 0 6 * * *");
        assertThat(load("application.yml").getProperty("cinemory.recommend.embedding-sync.scheduled-limit")).isEqualTo("2000");
        // 유료 전환 후 무료 tier 적재용 17:10 오버라이드를 제거했다(M3b R-5-2) - 운영(prod 프로파일)도 기본 06:00을 쓴다
        assertThat(load("application-prod.yml").getProperty("cinemory.recommend.embedding-sync.cron")).isNull();
    }

    private static Properties load(String resource) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource(resource));
        return yaml.getObject();
    }
}
