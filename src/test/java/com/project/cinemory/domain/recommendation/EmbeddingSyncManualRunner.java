package com.project.cinemory.domain.recommendation;

import com.project.cinemory.domain.recommendation.index.MovieFeatureIndex;
import com.project.cinemory.domain.recommendation.index.MovieVectorIndex;
import com.project.cinemory.domain.recommendation.service.EmbeddingSyncResult;
import com.project.cinemory.domain.recommendation.service.EmbeddingSyncService;
import com.project.cinemory.global.infra.gemini.EmbeddingClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * <b>수동 실행 전용</b> — 로컬 개발 DB({@code cinemory})에 <b>실제 Gemini를 호출해</b> 첫 적재를 한다(M3-b PR 1 확인용).
 * 관리자 엔드포인트(PR 3) 전의 진입점이며, 평소 {@code ./gradlew test}에서는 실행되지 않는다.
 *
 * <pre>
 * MANUAL_EMBEDDING_SYNC=true [MANUAL_EMBEDDING_LIMIT=200] [MANUAL_EMBEDDING_AFTER_ID=0] ./gradlew test --tests '*EmbeddingSyncManualRunner'
 * </pre>
 *
 * <p>요금이 드는 호출이다(전량 약 $1). 429로 멈추면 출력된 {@code lastProcessedId}를 {@code MANUAL_EMBEDDING_AFTER_ID}로 넘겨 이어 간다.
 * 키는 {@code config/application-secret.yml}에서만 읽고 출력하지 않는다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/cinemory?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul",
        "gemini.base-url=https://generativelanguage.googleapis.com/v1beta"
})
@EnabledIfEnvironmentVariable(named = "MANUAL_EMBEDDING_SYNC", matches = "true")
class EmbeddingSyncManualRunner {

    @Autowired EmbeddingSyncService syncService;
    @Autowired EmbeddingClient embeddingClient;
    @Autowired MovieVectorIndex vectorIndex;
    @Autowired MovieFeatureIndex featureIndex;

    @Test
    void 로컬_개발_DB에_임베딩을_적재한다() {
        String limitEnv = System.getenv("MANUAL_EMBEDDING_LIMIT");
        String afterEnv = System.getenv("MANUAL_EMBEDDING_AFTER_ID");
        Integer limit = limitEnv == null || limitEnv.isBlank() ? null : Integer.valueOf(limitEnv);
        Long afterId = afterEnv == null || afterEnv.isBlank() ? null : Long.valueOf(afterEnv);

        System.out.println("[manual] model=" + embeddingClient.modelName() + " afterId=" + afterId + " limit=" + limit);
        long startedAt = System.nanoTime();
        EmbeddingSyncResult result = syncService.sync(afterId, limit);
        long elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000L;

        System.out.println("[manual] result=" + result);
        System.out.println("[manual] elapsed=" + elapsedSeconds + "s");
        System.out.println("[manual] vectorIndex.size=" + vectorIndex.size()
                + " featureIndex.movies=" + featureIndex.snapshot().movies().size());
        System.out.println("[manual] heap=" + heapUsedMb() + "MB (gc 후)");
    }

    private static long heapUsedMb() {
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
    }
}
