package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.recommendation.config.RecommendProperties;
import com.project.cinemory.domain.recommendation.dto.AppliedConditionResponse;
import com.project.cinemory.domain.recommendation.dto.ConditionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 자연어 질의에서 조건을 뽑는다 (M3b-recommendation-spec Q-1 ② · Q-2). 사전·정규식 기반 — LLM 파싱은 기각(Q-2-5).
 *
 * <p>임베딩은 문장 전체를 벡터 하나로 압축해 조건이 희석되고 부정을 처리하지 못한다("공포 말고"가 오히려 공포 쪽으로 당긴다).
 * <b>사용자가 명시한 조건은 규칙이 지킨다</b> — 하드 필터는 후보를 뽑기 전에 적용된다(Q-2-4).
 *
 * <table>
 *   <caption>추출 대상</caption>
 *   <tr><td>상영시간 (Q-1)</td><td>"90분 이내" 하드 필터 · "짧은"/"긴" 가산점</td></tr>
 *   <tr><td>국가 (Q-2-1)</td><td>"한국 영화" 하드 필터 · "외국 영화" = KR 제외. "일본 영화 <b>느낌</b>"은 추출하지 않는다</td></tr>
 *   <tr><td>개봉 시기 (Q-2-2)</td><td>"90년대" · "최근 영화"(오늘−5년) · "옛날 영화"(오늘−20년 이전). "최근에 본"은 제외</td></tr>
 *   <tr><td>부정 (Q-2-3)</td><td>① 장르·국가 + 말고/빼고/제외/아닌 → 제외 필터 ② 분위기 → 부정 개념(감점)</td></tr>
 * </table>
 *
 * <p>부정 구간(①·②)은 {@code mainQuery}에서 <b>지운다</b> — 남겨 두면 "잔인"이 벡터를 잔인한 쪽으로 당긴다. 국가·시기·런타임 조건은
 * 지우지 않는다. 명시 런타임 조건이 있으면 선호 표현은 무시한다.
 */
@Component
@RequiredArgsConstructor
public class QueryConstraintExtractor {

    // ── 사전 ───────────────────────────────────────────────────────

    /** 국가 표현 → ISO 3166-1 alpha-2 (Q-2-1). */
    private static final Map<String, String> COUNTRY_CODES = new LinkedHashMap<>();
    /** ISO 코드 → 칩에 쓰는 이름. */
    private static final Map<String, String> COUNTRY_NAMES = new LinkedHashMap<>();
    /** 장르 표현 → TMDB 장르 id (Q-2-3 ①). 제외 장르 판정은 이름이 아니라 이 안정된 코드로 한다. */
    private static final Map<String, Integer> GENRE_IDS = new LinkedHashMap<>();
    /** TMDB 장르 id → 칩에 쓰는 이름. */
    private static final Map<Integer, String> GENRE_NAMES = new LinkedHashMap<>();

    static {
        country("KR", "한국", "한국", "국내");
        country("JP", "일본", "일본");
        country("US", "미국", "미국", "할리우드");
        country("HK", "홍콩", "홍콩");
        country("CN", "중국", "중국");
        country("TW", "대만", "대만");
        country("FR", "프랑스", "프랑스");
        country("GB", "영국", "영국");
        country("IN", "인도", "인도");
        country("ES", "스페인", "스페인");
        country("DE", "독일", "독일");
        country("IT", "이탈리아", "이탈리아");

        genre(27, "공포", "공포", "호러");
        genre(10749, "로맨스", "로맨스", "멜로");
        genre(28, "액션", "액션");
        genre(35, "코미디", "코미디");
        genre(16, "애니메이션", "애니메이션", "애니");
        genre(99, "다큐멘터리", "다큐멘터리", "다큐");
        genre(878, "SF", "sf", "에스에프", "공상과학");
        genre(53, "스릴러", "스릴러");
        genre(18, "드라마", "드라마");
        genre(14, "판타지", "판타지");
        genre(80, "범죄", "범죄");
        genre(9648, "미스터리", "미스터리");
        genre(12, "모험", "모험", "어드벤처");
        genre(10751, "가족", "가족");
        genre(10752, "전쟁", "전쟁");
        genre(37, "서부", "서부극");
        genre(36, "역사", "역사", "사극");
    }

    private static void country(String code, String name, String... words) {
        COUNTRY_NAMES.put(code, name);
        for (String word : words) {
            COUNTRY_CODES.put(word, code);
        }
    }

    private static void genre(int tmdbId, String name, String... words) {
        GENRE_NAMES.put(tmdbId, name);
        for (String word : words) {
            GENRE_IDS.put(word, tmdbId);
        }
    }

    /** 긴 표현이 먼저 매칭되도록 길이 내림차순으로 정렬한 정규식 alternation. */
    private static String alternation(Iterable<String> words) {
        List<String> sorted = new ArrayList<>();
        words.forEach(sorted::add);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        return sorted.stream().map(Pattern::quote).collect(Collectors.joining("|"));
    }

    private static final String COUNTRY_WORDS = alternation(COUNTRY_CODES.keySet());
    private static final String GENRE_WORDS = alternation(GENRE_IDS.keySet());

    // ── 정규식 ─────────────────────────────────────────────────────

    // [N시간] [반] [M분] 뒤에 상한 표현. 숫자 대신 한/두/세/네/다섯 시간도 받는다. (?<!\d)는 큰 숫자의 뒷부분만 잘라 매칭하는 것을 막는다.
    private static final Pattern LIMIT = Pattern.compile(
            "(?:(?<!\\d)(\\d{1,2}|한|두|세|네|다섯)\\s*시간\\s*(반)?)?\\s*(?:(?<!\\d)(\\d{1,3})\\s*분)?\\s*[을이]?\\s*(이내|이하|안에|미만|까지|넘지\\s*않는|넘지\\s*않게)");
    // "N분짜리" · "N시간짜리" — 상한 표현 없이 길이만 말한 경우. 선호로만 쓴다.
    private static final Pattern LENGTH_ONLY = Pattern.compile(
            "(?:(?<!\\d)(\\d{1,2}|한|두|세|네|다섯)\\s*시간\\s*(반)?)?\\s*(?:(?<!\\d)(\\d{1,3})\\s*분)?\\s*짜리");
    private static final Pattern SHORT_WORDS = Pattern.compile("짧은|짧게|짧고|금방|간단히 볼");
    // "긴"은 단독 어절일 때만 — "긴장감"·"긴박한"을 잡지 않는다.
    private static final Pattern LONG_WORDS = Pattern.compile("(?<![가-힣])긴(?![가-힣])|길게|대작|러닝타임이 (?:긴|길)");

    // 국가: 사전 + "영화" 패턴 (Q-2-1). 외국/해외는 KR 제외.
    private static final Pattern COUNTRY_MOVIE = Pattern.compile("(" + COUNTRY_WORDS + ")\\s*(?:영화|작품)");
    private static final Pattern FOREIGN_MOVIE = Pattern.compile("(?:외국|해외|외화)\\s*(?:영화|작품)");
    // 분위기 접미 — "일본 영화 느낌"·"90년대 감성"처럼 제작국·시기가 아니라 분위기를 말하는 표현 (Q-2-1 · Q-2-2).
    private static final Pattern MOOD_SUFFIX = Pattern.compile("^\\s*(?:영화|작품)?\\s*(?:느낌|스타일|풍|감성|분위기|같은|처럼)");

    // 개봉 시기 (Q-2-2)
    private static final Pattern DECADE_FULL = Pattern.compile("(?<!\\d)(19[5-9]0|20[0-2]0)\\s*년대");
    private static final Pattern DECADE_SHORT = Pattern.compile("(?<!\\d)([5-9]0)\\s*년대");
    private static final Pattern RECENT = Pattern.compile("(?:최근|요즘)\\s*(?:개봉작|개봉한\\s*영화|개봉\\s*영화|영화|작품)");
    private static final Pattern OLD = Pattern.compile("(?:옛날|오래된|고전)\\s*(?:영화|작품|명작)");

    // 부정 (Q-2-3). ① 장르·국가 사전 + 말고/빼고/제외/아닌, ② 어간 + 지 않은/않는, 명사 + 말고…(사전 밖), 명사구 + 없는.
    private static final String NEGATOR = "(?:제외하고|말고|빼고|제외|아닌|아니고)";
    private static final String NOUN_TAIL = "\\s*(?:영화|작품|장르)?\\s*(?:은|는|만|을|를|이|가)?\\s*";
    private static final Pattern NEGATED_GENRE = Pattern.compile("(" + GENRE_WORDS + ")" + NOUN_TAIL + NEGATOR);
    private static final Pattern NEGATED_COUNTRY = Pattern.compile("(" + COUNTRY_WORDS + ")" + NOUN_TAIL + NEGATOR);
    private static final Pattern NEGATED_NOUN = Pattern.compile("([가-힣a-z0-9]{2,12})\\s*(?:은|는|만|을|를)?\\s*" + NEGATOR);
    private static final Pattern NEGATED_ADJECTIVE = Pattern.compile("([가-힣]{1,10})지\\s*않(?:은|는)");

    /** 명사 + 말고에서 개념으로 삼지 않는 말(지시어·일반어). */
    private static final Set<String> NOT_A_CONCEPT = Set.of("영화", "작품", "그거", "이거", "저거", "그것", "이것", "것", "거", "다른", "그런", "이런");
    /** "~지 않는"이지만 분위기가 아닌 어간 — 상영시간 상한 표현("넘지 않는")과 겹친다. */
    private static final Set<String> NOT_A_MOOD_STEM = Set.of("넘");
    /** 부정 구간을 지운 뒤 남은 말이 전부 여기 속하면 본 질의는 비어 있다(sim 항 0). */
    private static final Set<String> FILLER = Set.of("영화", "영화들", "영화를", "영화로", "영화가", "작품", "추천", "추천해줘", "추천해주세요",
            "추천해", "보여줘", "찾아줘", "좀", "것", "거", "하나", "만", "같은", "인데", "이고", "이면서", "면서", "그리고", "하고", "도", "는", "은", "를", "을");

    private final RecommendProperties properties;
    private final Clock clock;

    // ── 결과 ───────────────────────────────────────────────────────

    /** 개봉 시기 범위 — 양끝 포함, 한쪽이 null이면 열려 있다. */
    public record ReleasePeriod(LocalDate from, LocalDate to) {

        public boolean contains(LocalDate date) {
            return date != null && (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to));
        }
    }

    /**
     * 질의에서 뽑은 조건 전체.
     *
     * @param runtimeMax        하드 필터 상한(분). 없으면 null
     * @param runtimePreference 가산점 대상. 명시 조건이 있으면 항상 NONE
     * @param countries         국가 하드 필터(ISO 코드). 비면 제한 없음
     * @param excludeCountries  제외 국가
     * @param releasePeriod     개봉 시기 하드 필터. 없으면 null
     * @param excludeGenres     제외 장르(TMDB 장르 id)
     * @param negativeConcepts  부정 개념(기본형 "잔인하다" 또는 명사구) — 최대 {@code max-negatives}개, 감점 대상
     * @param mainQuery         부정 구간을 지운 질의. 비어 있으면 null (sim 항 0)
     * @param conditions        응답의 {@code conditions}(칩). AVOID_MOOD는 감점이 실제 적용될 때만 서비스가 남긴다
     */
    public record QueryConstraints(Integer runtimeMax,
                                   RuntimePreference runtimePreference,
                                   Set<String> countries,
                                   Set<String> excludeCountries,
                                   ReleasePeriod releasePeriod,
                                   Set<Integer> excludeGenres,
                                   List<String> negativeConcepts,
                                   String mainQuery,
                                   List<AppliedConditionResponse> conditions) {

        public static QueryConstraints none() {
            return new QueryConstraints(null, RuntimePreference.NONE, Set.of(), Set.of(), null, Set.of(), List.of(), null, List.of());
        }

        /** 일반(비-AVOID_MOOD) 조건 칩만. 대체(degraded) 결과나 감점이 적용되지 않은 경우의 칩 목록이다. */
        public List<AppliedConditionResponse> conditionsWithoutMood() {
            return conditions.stream().filter(c -> c.type() != ConditionType.AVOID_MOOD).toList();
        }
    }

    public enum RuntimePreference { NONE, SHORT, LONG }

    // ── 추출 ───────────────────────────────────────────────────────

    public QueryConstraints extract(String query) {
        if (query == null || query.isBlank()) {
            return QueryConstraints.none();
        }
        String text = query.trim();

        // 1) 부정 구간 — 지울 구간을 먼저 모은다. 이후 긍정 조건은 지운 텍스트에서 뽑는다("한국 영화 말고 일본 영화"의 한국을 잡지 않도록).
        Negation negation = findNegations(text);
        String rest = negation.erase(text);

        List<AppliedConditionResponse> conditions = new ArrayList<>();

        // 2) 상영시간 (Q-1)
        Integer runtimeMax = limitMinutes(rest);
        RuntimePreference preference = runtimeMax != null ? RuntimePreference.NONE : preferenceOf(rest);
        if (runtimeMax != null) {
            conditions.add(new AppliedConditionResponse(ConditionType.RUNTIME_MAX, runtimeMax, runtimeMax + "분 이하",
                    runtimeMax + "분 이하 영화 위주로 골랐어요"));
        } else if (preference == RuntimePreference.SHORT) {
            conditions.add(new AppliedConditionResponse(ConditionType.RUNTIME_PREFER_SHORT, null, "짧은 영화", "짧은 영화 위주로 골랐어요"));
        } else if (preference == RuntimePreference.LONG) {
            conditions.add(new AppliedConditionResponse(ConditionType.RUNTIME_PREFER_LONG, null, "긴 영화", "긴 영화 위주로 골랐어요"));
        }

        // 3) 국가 (Q-2-1)
        Set<String> countries = new LinkedHashSet<>();
        Set<String> excludeCountries = new LinkedHashSet<>(negation.excludeCountries);
        Matcher countryMatcher = COUNTRY_MOVIE.matcher(rest);
        while (countryMatcher.find()) {
            if (!moodSuffixFollows(rest, countryMatcher.end())) {
                countries.add(COUNTRY_CODES.get(countryMatcher.group(1)));
            }
        }
        Matcher foreignMatcher = FOREIGN_MOVIE.matcher(rest);
        while (foreignMatcher.find()) {
            if (!moodSuffixFollows(rest, foreignMatcher.end())) {
                excludeCountries.add("KR");
            }
        }
        countries.removeAll(excludeCountries);
        for (String code : countries) {
            String name = COUNTRY_NAMES.get(code);
            conditions.add(new AppliedConditionResponse(ConditionType.COUNTRY, null, name, name + " 영화만 골랐어요"));
        }
        for (String code : excludeCountries) {
            String name = COUNTRY_NAMES.get(code);
            conditions.add(new AppliedConditionResponse(ConditionType.EXCLUDE_COUNTRY, null, name, name + " 영화는 제외했어요"));
        }

        // 4) 개봉 시기 (Q-2-2)
        ReleasePeriod period = releasePeriodOf(rest, conditions);

        // 5) 제외 장르 (Q-2-3 ①)
        for (int tmdbId : negation.excludeGenres) {
            String name = GENRE_NAMES.get(tmdbId);
            conditions.add(new AppliedConditionResponse(ConditionType.EXCLUDE_GENRE, null, name, name + " 제외"));
        }

        // 6) 부정 개념 (Q-2-3 ②) — 최대 max-negatives개만 쓴다. 나머지도 본 질의에서는 이미 지워졌다.
        List<String> concepts = negation.concepts.stream().distinct().limit(properties.query().maxNegatives()).toList();
        for (String concept : concepts) {
            conditions.add(new AppliedConditionResponse(ConditionType.AVOID_MOOD, null, concept, moodText(concept)));
        }

        String mainQuery = negation.hasErasure() ? blankToNull(rest) : text;
        return new QueryConstraints(runtimeMax, preference, Set.copyOf(countries), Set.copyOf(excludeCountries), period,
                Set.copyOf(negation.excludeGenres), concepts, mainQuery, List.copyOf(conditions));
    }

    // ── 부정 ───────────────────────────────────────────────────────

    private static final class Negation {
        final Set<Integer> excludeGenres = new LinkedHashSet<>();
        final Set<String> excludeCountries = new LinkedHashSet<>();
        final List<String> concepts = new ArrayList<>();
        final List<int[]> spans = new ArrayList<>();   // [start, end)

        boolean hasErasure() {
            return !spans.isEmpty();
        }

        /** 겹치는 구간을 합쳐 텍스트에서 지우고 공백을 정리한다. */
        String erase(String text) {
            if (spans.isEmpty()) {
                return text;
            }
            List<int[]> sorted = new ArrayList<>(spans);
            sorted.sort(Comparator.comparingInt(s -> s[0]));
            StringBuilder out = new StringBuilder();
            int cursor = 0;
            for (int[] span : sorted) {
                if (span[1] <= cursor) {
                    continue;
                }
                if (span[0] > cursor) {
                    out.append(text, cursor, span[0]);
                }
                out.append(' ');
                cursor = Math.max(cursor, span[1]);
            }
            out.append(text.substring(cursor));
            return out.toString().replaceAll("\\s+", " ").trim();
        }
    }

    private Negation findNegations(String text) {
        Negation n = new Negation();

        Matcher genre = NEGATED_GENRE.matcher(text);
        while (genre.find()) {
            n.excludeGenres.add(GENRE_IDS.get(genre.group(1)));
            n.spans.add(new int[]{genre.start(), genre.end()});
        }
        Matcher country = NEGATED_COUNTRY.matcher(text);
        while (country.find()) {
            n.excludeCountries.add(COUNTRY_CODES.get(country.group(1)));
            n.spans.add(new int[]{country.start(), country.end()});
        }
        // ② 어간 + 지 않은/않는 → 어간 + "다"
        Matcher adjective = NEGATED_ADJECTIVE.matcher(text);
        while (adjective.find()) {
            // "120분을 넘지 않는"은 분위기 부정이 아니라 상영시간 상한(Q-1)이다.
            if (NOT_A_MOOD_STEM.contains(adjective.group(1))) {
                continue;
            }
            n.concepts.add(adjective.group(1) + "다");
            n.spans.add(new int[]{adjective.start(), adjective.end()});
        }
        // ② 사전 밖 명사 + 말고/빼고/제외/아닌 — 이미 장르·국가로 잡힌 구간과 겹치면 건너뛴다.
        Matcher noun = NEGATED_NOUN.matcher(text);
        while (noun.find()) {
            String word = noun.group(1);
            if (NOT_A_CONCEPT.contains(word) || GENRE_IDS.containsKey(word) || COUNTRY_CODES.containsKey(word)
                    || overlaps(n.spans, noun.start(), noun.end())) {
                continue;
            }
            n.concepts.add(word);
            n.spans.add(new int[]{noun.start(), noun.end()});
        }
        // ② 명사구 + 없는 — "없는"이 어절로 떨어져 있을 때만("재미없는"·"쓸데없는" 같은 합성어는 부정이 아니다).
        int from = 0;
        while (true) {
            int idx = text.indexOf(" 없는", from);
            if (idx < 0) {
                break;
            }
            from = idx + 3;
            String before = text.substring(0, idx);
            String[] tokens = before.split("\\s+");
            if (tokens.length == 0 || tokens[tokens.length - 1].isEmpty()) {
                continue;
            }
            String last = tokens[tokens.length - 1];
            // 관형형 수식어(잔인한/무서운/…)가 바로 앞에 있으면 두 어절을 한 명사구로 본다("잔인한 장면 없는").
            boolean twoTokens = tokens.length >= 2 && endsWithAdnominal(tokens[tokens.length - 2]);
            String phrase = twoTokens ? tokens[tokens.length - 2] + " " + last : last;
            int phraseStart = before.length() - phrase.length();
            if (NOT_A_CONCEPT.contains(last) || overlaps(n.spans, phraseStart, idx + 3)) {
                continue;
            }
            n.concepts.add(phrase);
            n.spans.add(new int[]{phraseStart, idx + 3});
        }
        return n;
    }

    private static boolean overlaps(List<int[]> spans, int start, int end) {
        for (int[] span : spans) {
            if (start < span[1] && span[0] < end) {
                return true;
            }
        }
        return false;
    }

    private static boolean endsWithAdnominal(String token) {
        if (token.isEmpty()) {
            return false;
        }
        char c = token.charAt(token.length() - 1);
        return "한운은인는런른".indexOf(c) >= 0;
    }

    /** 칩 문구 — 형용사 기본형이면 관형형으로("잔인한 영화는 뒤로 미뤘어요"), 명사구면 그대로 인용. */
    private static String moodText(String concept) {
        if (concept.endsWith("다") && !concept.contains(" ")) {
            return KoreanAdjective.toAdnominal(concept) + " 영화는 뒤로 미뤘어요";
        }
        return "'" + concept + "' 쪽은 뒤로 미뤘어요";
    }

    private static String blankToNull(String text) {
        String cleaned = text == null ? "" : text.trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        for (String token : cleaned.split("\\s+")) {
            if (!FILLER.contains(token)) {
                return cleaned;
            }
        }
        return null;   // "잔인하지 않은 영화" → 본 질의에 남는 것이 "영화"뿐이면 비어 있는 것으로 본다
    }

    // ── 개봉 시기 ──────────────────────────────────────────────────

    /** 연대 > 최근/옛날 (교집합 0건 방지 — Q-2-2). 최근과 옛날이 둘 다 있으면 먼저 나온 것. */
    private ReleasePeriod releasePeriodOf(String text, List<AppliedConditionResponse> conditions) {
        LocalDate today = LocalDate.now(clock);

        Integer decade = decadeOf(text);
        if (decade != null) {
            String label = decade + "년대";
            conditions.add(new AppliedConditionResponse(ConditionType.RELEASE_PERIOD, null, label, label + " 영화만 골랐어요"));
            return new ReleasePeriod(LocalDate.of(decade, 1, 1), LocalDate.of(decade + 9, 12, 31));
        }

        int recentAt = firstUnmoody(RECENT, text);
        int oldAt = firstUnmoody(OLD, text);
        if (recentAt >= 0 && (oldAt < 0 || recentAt < oldAt)) {
            int years = properties.query().recentYears();
            String label = "최근 " + years + "년";
            conditions.add(new AppliedConditionResponse(ConditionType.RELEASE_PERIOD, null, label, label + " 영화만 골랐어요"));
            return new ReleasePeriod(today.minusYears(years), null);
        }
        if (oldAt >= 0) {
            int years = properties.query().oldYears();
            String label = years + "년 이전";
            conditions.add(new AppliedConditionResponse(ConditionType.RELEASE_PERIOD, null, label, label + " 영화만 골랐어요"));
            return new ReleasePeriod(null, today.minusYears(years));
        }
        return null;
    }

    private Integer decadeOf(String text) {
        Matcher full = DECADE_FULL.matcher(text);
        while (full.find()) {
            if (!moodSuffixFollows(text, full.end())) {
                return Integer.parseInt(full.group(1));
            }
        }
        Matcher shorter = DECADE_SHORT.matcher(text);
        while (shorter.find()) {
            if (!moodSuffixFollows(text, shorter.end())) {
                return 1900 + Integer.parseInt(shorter.group(1));
            }
        }
        return null;
    }

    private static int firstUnmoody(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            if (!moodSuffixFollows(text, matcher.end())) {
                return matcher.start();
            }
        }
        return -1;
    }

    /** "일본 영화 느낌"·"90년대 감성"처럼 매칭 뒤에 분위기 표현이 이어지면 제작국/시기가 아니다. */
    private static boolean moodSuffixFollows(String text, int end) {
        return MOOD_SUFFIX.matcher(text.substring(end)).find();
    }

    // ── 상영시간 (Q-1) ─────────────────────────────────────────────

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

    /** 시간/반/분 조각 → 분. 아무 조각도 없으면 null. "1시간 반" = 90, "2시간 30분" = 150. */
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
