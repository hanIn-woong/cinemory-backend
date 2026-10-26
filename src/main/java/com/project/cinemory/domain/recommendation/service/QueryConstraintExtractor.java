package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.recommendation.config.RecommendProperties;
import com.project.cinemory.domain.recommendation.dto.AppliedConditionResponse;
import com.project.cinemory.domain.recommendation.dto.ConditionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 자연어 질의에서 상영시간 조건을 뽑는다 (M3b-recommendation-spec Q-1 ②). 사전·정규식 기반 — LLM 파싱은 기각.
 * 임베딩은 "짧은"과 {@code 88분}을 연결하지 못하므로(오히려 줄거리의 "짧은 인생"이 걸린다) 규칙 점수 단계에서 푼다.
 *
 * <table>
 *   <tr><td>"90분 이내" · "1시간 반 안에"</td><td>{@code RUNTIME_MAX} — <b>하드 필터</b>(명시 조건)</td></tr>
 *   <tr><td>"짧은" · "금방"</td><td>{@code RUNTIME_PREFER_SHORT} — 가산점(모호하므로 0건으로 만들지 않는다)</td></tr>
 *   <tr><td>"긴" · "대작" · "3시간짜리"</td><td>{@code RUNTIME_PREFER_LONG} — 가산점</td></tr>
 * </table>
 *
 * <p>명시 조건이 있으면 선호 표현은 무시한다(하드 필터가 이미 모든 결과에 적용된다).
 * "가볍게"는 분위기 표현("가볍게 웃을 수 있는")으로 더 많이 쓰여 넣지 않았다 — 스펙의 "가볍게 금방"은 "금방"이 신호다.
 */
@Component
@RequiredArgsConstructor
public class QueryConstraintExtractor {

    // [N시간] [반] [M분] 뒤에 상한 표현. 숫자 대신 한/두/세/네/다섯 시간도 받는다.
    private static final Pattern LIMIT = Pattern.compile(
            "(?:(?<!\\d)(\\d{1,2}|한|두|세|네|다섯)\\s*시간\\s*(반)?)?\\s*(?:(?<!\\d)(\\d{1,3})\\s*분)?\\s*[을이]?\\s*(이내|이하|안에|미만|까지|넘지\\s*않는|넘지\\s*않게)");

    // "N분짜리" · "N시간짜리" — 상한 표현 없이 길이만 말한 경우. 선호로만 쓴다.
    private static final Pattern LENGTH_ONLY = Pattern.compile(
            "(?:(?<!\\d)(\\d{1,2}|한|두|세|네|다섯)\\s*시간\\s*(반)?)?\\s*(?:(?<!\\d)(\\d{1,3})\\s*분)?\\s*짜리");

    private static final Pattern SHORT_WORDS = Pattern.compile("짧은|짧게|짧고|금방|간단히 볼");
    // "긴"은 단독 어절일 때만 — "긴장감"·"긴박한"을 잡지 않는다.
    private static final Pattern LONG_WORDS = Pattern.compile("(?<![가-힣])긴(?![가-힣])|길게|대작|러닝타임이 (?:긴|길)");

    private final RecommendProperties properties;

    /**
     * @param runtimeMax    하드 필터 상한(분). 없으면 {@code null}
     * @param preference    가산점 대상. 명시 조건이 있으면 항상 {@code NONE}
     * @param conditions    응답의 {@code conditions}
     */
    public record QueryConstraints(Integer runtimeMax, RuntimePreference preference, List<AppliedConditionResponse> conditions) {

        public static QueryConstraints none() {
            return new QueryConstraints(null, RuntimePreference.NONE, List.of());
        }
    }

    public enum RuntimePreference { NONE, SHORT, LONG }

    public QueryConstraints extract(String query) {
        if (query == null || query.isBlank()) {
            return QueryConstraints.none();
        }

        Integer limit = limitMinutes(query);
        if (limit != null) {
            return new QueryConstraints(limit, RuntimePreference.NONE, List.of(
                    new AppliedConditionResponse(ConditionType.RUNTIME_MAX, limit, limit + "분 이하 영화 위주로 골랐어요")));
        }

        RuntimePreference preference = preferenceOf(query);
        return switch (preference) {
            case SHORT -> new QueryConstraints(null, preference, List.of(new AppliedConditionResponse(
                    ConditionType.RUNTIME_PREFER_SHORT, properties.query().preferShortMax(), "짧은 영화 위주로 골랐어요")));
            case LONG -> new QueryConstraints(null, preference, List.of(new AppliedConditionResponse(
                    ConditionType.RUNTIME_PREFER_LONG, properties.query().preferLongMin(), "긴 영화 위주로 골랐어요")));
            case NONE -> QueryConstraints.none();
        };
    }

    private Integer limitMinutes(String query) {
        Matcher matcher = LIMIT.matcher(query);
        while (matcher.find()) {
            Integer minutes = minutesOf(matcher.group(1), matcher.group(2), matcher.group(3));
            if (minutes == null) {
                continue;   // "안에"만 있고 시간 표현이 없는 경우("집 안에서")
            }
            // "미만"은 그 값을 포함하지 않는다.
            return "미만".equals(matcher.group(4)) ? minutes - 1 : minutes;
        }
        return null;
    }

    private RuntimePreference preferenceOf(String query) {
        if (SHORT_WORDS.matcher(query).find()) {
            return RuntimePreference.SHORT;
        }
        if (LONG_WORDS.matcher(query).find()) {
            return RuntimePreference.LONG;
        }
        Matcher matcher = LENGTH_ONLY.matcher(query);
        while (matcher.find()) {
            Integer minutes = minutesOf(matcher.group(1), matcher.group(2), matcher.group(3));
            if (minutes == null) {
                continue;
            }
            if (minutes >= properties.query().preferLongMin()) {
                return RuntimePreference.LONG;
            }
            if (minutes <= properties.query().preferShortMax()) {
                return RuntimePreference.SHORT;
            }
        }
        return RuntimePreference.NONE;
    }

    /** 시간/반/분 조각 → 분. 아무 조각도 없으면 null. "1시간 반" = 90, "2시간 30분" = 150, "반"만 있는 경우는 시간 조각이 없어 매칭되지 않는다. */
    private static Integer minutesOf(String hours, String half, String minutes) {
        if (hours == null && minutes == null) {
            return null;
        }
        int total = 0;
        if (hours != null) {
            total += hoursOf(hours) * 60;
            if (half != null) {
                total += 30;
            }
        }
        if (minutes != null) {
            total += Integer.parseInt(minutes);
        }
        return total;
    }

    private static int hoursOf(String token) {
        return switch (token) {
            case "한" -> 1;
            case "두" -> 2;
            case "세" -> 3;
            case "네" -> 4;
            case "다섯" -> 5;
            default -> Integer.parseInt(token);
        };
    }
}
