package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.recommendation.entity.OverviewSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 영화 한 편의 임베딩 입력 텍스트 조립 (M3b-recommendation-spec E-1 · E-2 · Q-1 ①). <b>순수 함수</b> — DB·시간·난수에 의존하지 않는다.
 *
 * <p>⚠️ <b>결정성</b>(E-4-3): 모든 목록의 정렬 기준을 이 클래스 안에서 명시한다. 호출부가 넘긴 순서에 기대면
 * 순서가 흔들리는 날 해시가 바뀌어 <b>매일 전량 재임베딩</b>(요금 사고)이 된다. 입력 순서를 섞어도 같은 텍스트가 나와야 한다.
 *
 * <p>값이 없는 줄은 줄째 생략한다 — 모든 문서에 같은 빈 라벨이 들어가면 잡음이 된다.
 */
public final class EmbeddingTextBuilder {

    // Q-1 ① 상영시간 구간 라벨 — 임베딩 입력의 일부라 바꾸면 전량 재임베딩이 필요하다(튜닝 가중치가 아님).
    static final int SHORT_RUNTIME_MAX = 95;
    static final int LONG_RUNTIME_MIN = 140;

    static final int MAX_LEAD_ACTORS = 3;
    static final int MAX_KEYWORDS = 20;

    // source_text VARCHAR(4000). 필드 상한 합은 약 3,000자라 정상 경로에선 닿지 않는 안전장치다.
    static final int SOURCE_TEXT_MAX = 4000;
    private static final String SOURCE_PREFIX_OVERHEAD = "title:  | text: ";

    private EmbeddingTextBuilder() {
    }

    /** 순서 키({@code order})는 정렬 전용이다 — 국가는 {@code weight DESC, id}, 나머지는 각 문서의 정렬 기준. */
    public record Named(long order, String name) {
    }

    public record Country(long id, BigDecimal weight, String name) {
    }

    public record Actor(int displayOrder, long personId, String name) {
    }

    /**
     * @param displayTitle  {@code COALESCE(title_ko, title)}
     * @param originalTitle 표시제목과 같으면 생략된다
     * @param genres        {@code order} = genre.id
     * @param directors     {@code order} = movie_director.id, {@code name} = {@code COALESCE(name_ko, name)}
     * @param keywords      {@code order} = movie_keyword.id
     */
    public record Input(
            String displayTitle,
            String originalTitle,
            LocalDate releaseDate,
            List<Country> countries,
            List<Named> genres,
            Integer runtime,
            List<Named> directors,
            List<Actor> actors,
            String tagline,
            List<Named> keywords,
            String overview,
            String overviewEn) {
    }

    public static EmbeddingText build(Input in) {
        List<String> lines = new ArrayList<>();

        addIfPresent(lines, originLine(in));
        addIfPresent(lines, listLine("장르", sortedNames(in.genres())));
        addIfPresent(lines, runtimeLine(in.runtime()));
        addIfPresent(lines, listLine("감독", sortedNames(in.directors())));
        addIfPresent(lines, listLine("주연", leadActorNames(in.actors())));
        addIfPresent(lines, valueLine("한 줄 소개", in.tagline()));
        addIfPresent(lines, listLine("키워드", sortedNames(in.keywords()).stream().limit(MAX_KEYWORDS).toList()));

        OverviewSource overviewSource = OverviewSource.NONE;
        String overview = clean(in.overview());
        if (overview != null) {
            overviewSource = OverviewSource.KO;
        } else {
            overview = clean(in.overviewEn());
            if (overview != null) {
                overviewSource = OverviewSource.EN;
            }
        }
        if (overviewSource != OverviewSource.NONE) {
            lines.add("줄거리: " + overview);
        }

        String title = titleOf(in);
        String body = String.join("\n", lines);
        int bodyMax = SOURCE_TEXT_MAX - SOURCE_PREFIX_OVERHEAD.length() - title.length();
        if (body.length() > bodyMax) {
            body = body.substring(0, Math.max(0, bodyMax));
        }
        return new EmbeddingText(title, body, overviewSource);
    }

    private static String titleOf(Input in) {
        String display = clean(in.displayTitle());
        String original = clean(in.originalTitle());
        if (display == null) {
            return original != null ? original : "none";
        }
        return original != null && !original.equals(display) ? display + " (" + original + ")" : display;
    }

    private static String originLine(Input in) {
        List<String> parts = new ArrayList<>();
        if (in.releaseDate() != null) {
            parts.add("개봉: " + in.releaseDate().getYear() + "년");
        }
        List<String> countries = in.countries() == null ? List.of() : in.countries().stream()
                .sorted(Comparator.comparing(Country::weight, Comparator.reverseOrder())
                        .thenComparingLong(Country::id))
                .map(Country::name)
                .toList();
        if (!countries.isEmpty()) {
            parts.add("국가: " + String.join(", ", countries));
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    private static String runtimeLine(Integer runtime) {
        if (runtime == null) {
            return null;
        }
        if (runtime <= SHORT_RUNTIME_MAX) {
            return "상영시간: 짧은 편 (" + runtime + "분)";
        }
        if (runtime >= LONG_RUNTIME_MIN) {
            return "상영시간: 긴 편 (" + runtime + "분)";
        }
        return null;
    }

    private static List<String> leadActorNames(List<Actor> actors) {
        if (actors == null) {
            return List.of();
        }
        return actors.stream()
                .sorted(Comparator.comparingInt(Actor::displayOrder).thenComparingLong(Actor::personId))
                .limit(MAX_LEAD_ACTORS)
                .map(Actor::name)
                .toList();
    }

    private static List<String> sortedNames(List<Named> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream()
                .sorted(Comparator.comparingLong(Named::order))
                .map(Named::name)
                .toList();
    }

    private static String listLine(String label, List<String> values) {
        return values.isEmpty() ? null : label + ": " + String.join(", ", values);
    }

    private static String valueLine(String label, String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : label + ": " + cleaned;
    }

    private static void addIfPresent(List<String> lines, String line) {
        if (line != null) {
            lines.add(line);
        }
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.replace("\r\n", "\n").trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
