package com.project.cinemory.global.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

/**
 * JVM 기본 시간대가 기대값과 다르면 기동을 실패시킨다 (deploy-spec 1-2 / security-spec L-11).
 *
 * <p><b>왜 필요한가</b> — {@link ClockConfig}·JPA Auditing·{@code LocalDate.now()}가 모두 JVM 기본
 * 시간대를 따른다. 시간대는 실행 환경 한 곳({@code -Duser.timezone})에서 맞추기로 했는데, 그 한 곳이
 * 빠지면 <b>에러 없이 데이터만 틀어진다</b> — 예: {@code BoxOfficeScheduler}가 UTC JVM에서 이틀 전
 * 박스오피스를 수집한다. 이 가드는 그 실수를 기동 실패로 바꾼다.
 *
 * <p><b>{@code ApplicationRunner}가 아니라 {@code @PostConstruct}인 이유</b> — 러너는 컨텍스트
 * 갱신이 끝난 뒤(스케줄러·웹 서버가 이미 돈 뒤) 실행된다. 빈 초기화 시점에 막아야 첫 요청·첫 배치보다 앞선다.
 *
 * <p>{@code cinemory.required-time-zone}이 비어 있으면 검사하지 않는다 — prod에만 설정한다.
 * 개발 PC 시간대를 강제할 이유가 없다.
 */
@Component
public class TimeZoneGuard {

    private final String requiredTimeZone;

    public TimeZoneGuard(@Value("${cinemory.required-time-zone:}") String requiredTimeZone) {
        this.requiredTimeZone = requiredTimeZone;
    }

    @PostConstruct
    void verify() {
        if (requiredTimeZone == null || requiredTimeZone.isBlank()) {
            return;
        }
        ZoneId expected = ZoneId.of(requiredTimeZone);
        ZoneId actual = ZoneId.systemDefault();
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "JVM 기본 시간대가 " + actual + "입니다. 기대값은 " + expected + "입니다 "
                            + "(cinemory.required-time-zone). "
                            + "JVM 옵션 -Duser.timezone=" + expected + " 으로 기동하세요.");
        }
    }
}
