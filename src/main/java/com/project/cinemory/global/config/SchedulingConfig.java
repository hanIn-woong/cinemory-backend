package com.project.cinemory.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 배치 스케줄러 활성화.
 *
 * <p><b>단일 인스턴스 전제</b>: 애플리케이션을 여러 인스턴스로 띄우면 같은 시각에 배치가
 * 중복 실행된다. 수집 배치 자체는 멱등하게 설계돼 있어 데이터가 깨지진 않지만, 외부 API를
 * 불필요하게 중복 호출한다. 다중 인스턴스로 확장할 때는 ShedLock 등 분산 락을 도입해야 하며
 * 캡스톤 범위 밖으로 둔다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
