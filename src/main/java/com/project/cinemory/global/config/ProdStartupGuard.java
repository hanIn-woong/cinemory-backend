package com.project.cinemory.global.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 운영 기동 전제 조건이 틀리면 기동을 실패시킨다 (deploy-spec 1-2 B / security-spec L-10·L-11).
 *
 * <p><b>① 시간대 검사</b> — {@link ClockConfig}·JPA Auditing·{@code LocalDate.now()}가 모두 JVM 기본
 * 시간대를 따른다. 시간대는 실행 환경 한 곳({@code -Duser.timezone})에서 맞추기로 했는데, 그 한 곳이
 * 빠지면 <b>에러 없이 데이터만 틀어진다</b> — 예: {@code BoxOfficeScheduler}가 UTC JVM에서 이틀 전
 * 박스오피스를 수집한다.
 *
 * <p><b>② 필수 설정 검사</b> — {@code @ConfigurationProperties} 바인딩은 해석 못 한 플레이스홀더를
 * {@code "${KOFIC_API_KEY}"} 같은 <b>리터럴 문자열로 넣고 넘어간다</b>({@code @Value}와 다르다). 그래서
 * 환경변수가 빠져도 헬스는 UP으로 뜨고 기능만 조용히 깨진다. {@link Environment#getRequiredProperty}는
 * 미해석 플레이스홀더를 예외로 던지므로, 이 조회로 누락을 판정한다. <b>존재 여부만 본다</b> — 값이 틀린
 * 경우(오타·만료)는 잡지 못하며, 기동 시 외부 API를 호출해 확인하지도 않는다(외부 장애가 배포 실패가 된다).
 *
 * <p><b>{@code ApplicationRunner}가 아니라 {@code @PostConstruct}인 이유</b> — 러너는 컨텍스트
 * 갱신이 끝난 뒤(스케줄러·웹 서버가 이미 돈 뒤) 실행된다. 빈 초기화 시점에 막아야 첫 요청·첫 배치보다 앞선다.
 *
 * <p>두 설정은 {@code application-prod.yml}에만 있다 — 비어 있으면 해당 검사를 건너뛴다(로컬·테스트).
 * 문제는 첫 실패에서 멈추지 않고 전부 모아 한 번에 보고하며, <b>메시지에는 키 이름만</b> 넣는다(값은 비밀일 수 있다).
 */
@Component
public class ProdStartupGuard {

    static final String REQUIRED_TIME_ZONE_KEY = "cinemory.required-time-zone";
    static final String REQUIRED_PROPERTIES_KEY = "cinemory.startup-check.required-properties";

    private final Environment environment;

    public ProdStartupGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void verify() {
        Optional<String> timeZoneProblem = checkTimeZone();
        List<String> missing = findMissingProperties();
        if (timeZoneProblem.isEmpty() && missing.isEmpty()) {
            return;
        }

        List<String> problems = new ArrayList<>();
        timeZoneProblem.ifPresent(problems::add);
        if (!missing.isEmpty()) {
            problems.add("[필수 설정 누락] " + String.join(", ", missing)
                    + " — 운영 환경변수(/etc/cinemory/cinemory.env)를 확인하세요.");
        }
        int count = (timeZoneProblem.isPresent() ? 1 : 0) + missing.size();
        throw new IllegalStateException(
                "운영 기동 조건 미충족 " + count + "건: " + String.join(" / ", problems));
    }

    private Optional<String> checkTimeZone() {
        String required = environment.getProperty(REQUIRED_TIME_ZONE_KEY, "");
        if (required.isBlank()) {
            return Optional.empty();
        }
        ZoneId expected = ZoneId.of(required);
        ZoneId actual = ZoneId.systemDefault();
        if (expected.equals(actual)) {
            return Optional.empty();
        }
        return Optional.of("[시간대] JVM 기본 시간대가 " + actual + "입니다. 기대값은 " + expected
                + "입니다(" + REQUIRED_TIME_ZONE_KEY + "). JVM 옵션 -Duser.timezone=" + expected + " 으로 기동하세요.");
    }

    private List<String> findMissingProperties() {
        List<String> keys = Binder.get(environment)
                .bind(REQUIRED_PROPERTIES_KEY, Bindable.listOf(String.class))
                .orElse(List.of());

        List<String> missing = new ArrayList<>();
        for (String key : keys) {
            if (!isPresent(key)) {
                missing.add(key);
            }
        }
        return missing;
    }

    private boolean isPresent(String key) {
        try {
            return !environment.getRequiredProperty(key).isBlank();
        } catch (IllegalArgumentException | IllegalStateException e) {
            // 미해석 플레이스홀더(IllegalArgumentException) 또는 키 자체 없음(IllegalStateException).
            // 예외 메시지는 버린다 — 값이 섞여 들어갈 수 있다.
            return false;
        }
    }
}
