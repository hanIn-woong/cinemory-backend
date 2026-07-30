package com.project.cinemory.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 시간 소스를 빈으로 노출한다.
 *
 * <p>도메인 규칙상 시각은 항상 <b>인자로 주입</b>받는다
 * ({@code RefreshToken.isExpired(now)} / {@code revoke(now)}). 그런데 그 인자를 만드는 쪽이
 * {@code LocalDateTime.now()}를 직접 호출해버리면 테스트에서 시간을 고정할 수 없어 규칙이 무의미해진다.
 * 서비스·컴포넌트가 이 빈을 주입받아 시각을 얻도록 해 규칙을 실제로 성립시킨다.
 *
 * <p><b>{@code systemDefaultZone()}을 쓰는 이유</b> — JPA Auditing({@code @CreatedDate})이
 * JVM 기본 시간대를 따르므로, 여기서 별도 시간대를 고정하면 같은 행의 {@code created_at}과
 * 애플리케이션이 계산한 {@code expires_at}이 서로 다른 기준을 갖게 된다.
 * 배포 환경의 시간대는 JVM 옵션({@code -Duser.timezone}) 또는 {@code TZ} 환경변수로
 * <b>한 곳에서</b> 맞추는 편이 낫다.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
