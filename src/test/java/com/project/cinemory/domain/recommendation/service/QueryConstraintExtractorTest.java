package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.recommendation.dto.AppliedConditionResponse;
import com.project.cinemory.domain.recommendation.dto.ConditionType;
import com.project.cinemory.domain.recommendation.service.QueryConstraintExtractor.QueryConstraints;
import com.project.cinemory.domain.recommendation.service.QueryConstraintExtractor.ReleasePeriod;
import com.project.cinemory.domain.recommendation.service.QueryConstraintExtractor.RuntimePreference;
import com.project.cinemory.domain.recommendation.support.RecommendFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** Q-1 ② · Q-2 — 질의에서 상영시간·국가·개봉 시기·부정 표현을 추출한다. 기준일은 2026-10-26(KST). */
class QueryConstraintExtractorTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

    private final QueryConstraintExtractor extractor = new QueryConstraintExtractor(
            RecommendFixtures.properties(), Clock.fixed(TODAY.atStartOfDay(KST).toInstant(), KST));

    private static AppliedConditionResponse chip(QueryConstraints c, ConditionType type) {
        return c.conditions().stream().filter(x -> x.type() == type).findFirst().orElseThrow();
    }

    // ── Q-1 상영시간 ────────────────────────────────────────────────

    @Test
    void 분_이내는_하드_필터다() {
        QueryConstraints c = extractor.extract("90분 이내로 볼 수 있는 영화");

        assertThat(c.runtimeMax()).isEqualTo(90);
        assertThat(c.runtimePreference()).isEqualTo(RuntimePreference.NONE);
        assertThat(c.conditions()).singleElement().satisfies(condition -> {
            assertThat(condition.type()).isEqualTo(ConditionType.RUNTIME_MAX);
            assertThat(condition.value()).isEqualTo(90);
            assertThat(condition.label()).isEqualTo("90분 이하");
            assertThat(condition.text()).isEqualTo("90분 이하 영화 위주로 골랐어요");
        });
    }

    @Test
    void 한_시간_반_안에는_90분이다() {
        assertThat(extractor.extract("1시간 반 안에 볼 수 있는 영화").runtimeMax()).isEqualTo(90);
        assertThat(extractor.extract("한 시간 반 이내 영화").runtimeMax()).isEqualTo(90);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "2시간 이내 영화|120",
            "두 시간 안에 끝나는 영화|120",
            "2시간 30분 이내|150",
            "100분 이하 로맨스|100",
            "80분 안에 볼 영화|80",
            "한 시간 이내|60",
            "러닝타임 120분을 넘지 않는 영화|120",
            "90분 미만 영화|89",
    })
    void 여러_표현이_분으로_환산된다(String query, int expected) {
        assertThat(extractor.extract(query).runtimeMax()).isEqualTo(expected);
    }

    @Test
    void 짧은_영화는_가산점_선호다_결과를_자르지_않는다() {
        QueryConstraints c = extractor.extract("짧은 영화 추천해줘");

        assertThat(c.runtimeMax()).isNull();
        assertThat(c.runtimePreference()).isEqualTo(RuntimePreference.SHORT);
        assertThat(c.conditions()).singleElement().satisfies(condition -> {
            assertThat(condition.type()).isEqualTo(ConditionType.RUNTIME_PREFER_SHORT);
            assertThat(condition.value()).isNull();   // value는 RUNTIME_MAX만 (5-14-C)
            assertThat(condition.label()).isEqualTo("짧은 영화");
        });
    }

    @Test
    void 금방_볼_수_있는도_짧은_선호다() {
        assertThat(extractor.extract("가볍게 금방 볼 수 있는 영화").runtimePreference()).isEqualTo(RuntimePreference.SHORT);
    }

    @Test
    void 가볍게만_있으면_분위기_표현이라_조건이_없다() {
        assertThat(extractor.extract("가볍게 웃을 수 있는 영화").runtimePreference()).isEqualTo(RuntimePreference.NONE);
    }

    @Test
    void 긴_영화와_대작은_긴_선호다() {
        QueryConstraints c = extractor.extract("긴 영화 보고 싶어");

        assertThat(c.runtimePreference()).isEqualTo(RuntimePreference.LONG);
        assertThat(chip(c, ConditionType.RUNTIME_PREFER_LONG).label()).isEqualTo("긴 영화");
        assertThat(extractor.extract("서사가 큰 대작").runtimePreference()).isEqualTo(RuntimePreference.LONG);
    }

    @Test
    void 세_시간짜리는_긴_선호다() {
        assertThat(extractor.extract("3시간짜리 영화").runtimePreference()).isEqualTo(RuntimePreference.LONG);
        assertThat(extractor.extract("3시간짜리 영화").runtimeMax()).isNull();
    }

    @Test
    void 긴장감이나_긴박한은_긴_선호가_아니다() {
        assertThat(extractor.extract("긴장감 넘치는 스릴러").runtimePreference()).isEqualTo(RuntimePreference.NONE);
        assertThat(extractor.extract("긴박한 추격 영화").runtimePreference()).isEqualTo(RuntimePreference.NONE);
    }

    @Test
    void 조건이_없으면_빈_조건이고_본_질의는_질의_그대로다() {
        QueryConstraints c = extractor.extract("비 오는 날 혼자 보기 좋은 잔잔한 영화");

        assertThat(c.runtimeMax()).isNull();
        assertThat(c.runtimePreference()).isEqualTo(RuntimePreference.NONE);
        assertThat(c.countries()).isEmpty();
        assertThat(c.excludeCountries()).isEmpty();
        assertThat(c.releasePeriod()).isNull();
        assertThat(c.excludeGenres()).isEmpty();
        assertThat(c.negativeConcepts()).isEmpty();
        assertThat(c.conditions()).isEmpty();
        assertThat(c.mainQuery()).isEqualTo("비 오는 날 혼자 보기 좋은 잔잔한 영화");
    }

    @Test
    void 시간_표현_없는_안에는_조건이_아니다() {
        assertThat(extractor.extract("집 안에서 보기 좋은 영화").runtimeMax()).isNull();
        assertThat(extractor.extract("마음 안에 오래 남는 영화").runtimeMax()).isNull();
    }

    @Test
    void 명시_조건이_있으면_선호_표현은_무시한다() {
        QueryConstraints c = extractor.extract("짧은 90분 이내 영화");

        assertThat(c.runtimeMax()).isEqualTo(90);
        assertThat(c.runtimePreference()).isEqualTo(RuntimePreference.NONE);
        assertThat(c.conditions()).hasSize(1);
    }

    @Test
    void 비거나_null이면_빈_조건이다() {
        assertThat(extractor.extract("").conditions()).isEmpty();
        assertThat(extractor.extract("   ").conditions()).isEmpty();
        assertThat(extractor.extract(null).conditions()).isEmpty();
        assertThat(extractor.extract(null).mainQuery()).isNull();
    }

    @Test
    void 터무니없이_큰_숫자도_예외없이_처리한다() {
        assertThat(extractor.extract("99999999999999분 이내").runtimeMax()).isNull();
    }

    // ── Q-2-1 국가 ──────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "한국 영화 추천|KR", "한국영화 보고 싶어|KR", "국내 영화 추천|KR",
            "일본 영화 추천|JP", "할리우드 영화 보고 싶어|US", "미국 영화 추천|US",
            "홍콩 영화 추천|HK", "중국 영화|CN", "대만 영화|TW", "프랑스 영화|FR",
            "영국 영화|GB", "인도 영화|IN", "스페인 영화|ES", "독일 영화|DE", "이탈리아 영화|IT",
    })
    void 국가_사전과_영화_패턴은_COUNTRY_하드_필터다(String query, String code) {
        QueryConstraints c = extractor.extract(query);

        assertThat(c.countries()).containsExactly(code);
        assertThat(c.excludeCountries()).isEmpty();
        assertThat(chip(c, ConditionType.COUNTRY).label()).isNotBlank();
        assertThat(chip(c, ConditionType.COUNTRY).value()).isNull();
    }

    @Test
    void 국가_칩_문구() {
        AppliedConditionResponse chip = chip(extractor.extract("일본 영화 추천"), ConditionType.COUNTRY);

        assertThat(chip.label()).isEqualTo("일본");
        assertThat(chip.text()).isEqualTo("일본 영화만 골랐어요");
    }

    @Test
    void 외국_영화와_해외_영화는_KR_제외다() {
        for (String query : new String[]{"외국 영화 추천", "해외 영화 보고 싶어"}) {
            QueryConstraints c = extractor.extract(query);

            assertThat(c.countries()).isEmpty();
            assertThat(c.excludeCountries()).containsExactly("KR");
            assertThat(chip(c, ConditionType.EXCLUDE_COUNTRY).label()).isEqualTo("한국");
        }
    }

    @Test
    void 일본_영화_느낌은_제작국이_아니라_분위기라_추출하지_않는다() {
        for (String query : new String[]{"일본 영화 느낌의 잔잔한 영화", "홍콩 영화 스타일", "한국 영화풍", "프랑스 영화 감성", "일본 영화 분위기"}) {
            QueryConstraints c = extractor.extract(query);

            assertThat(c.countries()).as(query).isEmpty();
            assertThat(c.conditions()).as(query).isEmpty();
        }
    }

    @Test
    void 영화_없이_나라만_있으면_추출하지_않는다() {
        assertThat(extractor.extract("일본에서 촬영한 이야기").countries()).isEmpty();
        assertThat(extractor.extract("한국 배우가 나오는").countries()).isEmpty();
    }

    // ── Q-2-2 개봉 시기 ─────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "90년대 영화|1990|1990년대", "1990년대 한국 영화|1990|1990년대", "2000년대 영화|2000|2000년대",
            "2010년대 영화|2010|2010년대", "2020년대 영화|2020|2020년대", "50년대 영화|1950|1950년대", "70년대 영화 추천|1970|1970년대",
    })
    void 연대는_해당_10년이다(String query, int startYear, String label) {
        QueryConstraints c = extractor.extract(query);

        assertThat(c.releasePeriod()).isEqualTo(new ReleasePeriod(LocalDate.of(startYear, 1, 1), LocalDate.of(startYear + 9, 12, 31)));
        AppliedConditionResponse chip = chip(c, ConditionType.RELEASE_PERIOD);
        assertThat(chip.label()).isEqualTo(label);
        assertThat(chip.value()).isNull();
        assertThat(chip.text()).isEqualTo(label + " 영화만 골랐어요");
    }

    @ParameterizedTest
    @CsvSource({"최근 영화", "요즘 영화 추천", "최근 개봉작", "최근 개봉한 영화 보고 싶어"})
    void 최근은_오늘에서_5년_전부터다(String query) {
        QueryConstraints c = extractor.extract(query);

        assertThat(c.releasePeriod()).isEqualTo(new ReleasePeriod(LocalDate.of(2021, 10, 26), null));
        assertThat(chip(c, ConditionType.RELEASE_PERIOD).label()).isEqualTo("최근 5년");
    }

    @ParameterizedTest
    @CsvSource({"옛날 영화", "오래된 영화 추천", "고전 영화 보고 싶어", "옛날 명작"})
    void 옛날과_고전은_오늘에서_20년_이전이다(String query) {
        QueryConstraints c = extractor.extract(query);

        assertThat(c.releasePeriod()).isEqualTo(new ReleasePeriod(null, LocalDate.of(2006, 10, 26)));
        assertThat(chip(c, ConditionType.RELEASE_PERIOD).label()).isEqualTo("20년 이전");
    }

    @Test
    void 최근에_본_것_같은은_사용자의_시점이라_시기가_아니다() {
        QueryConstraints c = extractor.extract("최근에 본 것 같은 영화");

        assertThat(c.releasePeriod()).isNull();
        assertThat(c.conditions()).isEmpty();
        assertThat(extractor.extract("최근 들어 우울해서 보고 싶은 영화").releasePeriod()).isNull();
    }

    @Test
    void 시기_조건이_둘_이상이면_연대가_우선이다() {
        QueryConstraints c = extractor.extract("최근 90년대 영화");

        assertThat(c.releasePeriod().from()).isEqualTo(LocalDate.of(1990, 1, 1));
        assertThat(c.conditions().stream().filter(x -> x.type() == ConditionType.RELEASE_PERIOD)).singleElement()
                .satisfies(x -> assertThat(x.label()).isEqualTo("1990년대"));
    }

    @Test
    void 연대_감성이나_옛날_영화_느낌은_시기가_아니다() {
        assertThat(extractor.extract("90년대 감성 영화").releasePeriod()).isNull();
        assertThat(extractor.extract("옛날 영화 느낌의 영화").releasePeriod()).isNull();
        assertThat(extractor.extract("2000년대 분위기 영화").releasePeriod()).isNull();
    }

    @Test
    void 개봉_시기_범위는_양끝을_포함하고_개봉일이_없으면_걸린다() {
        ReleasePeriod nineties = extractor.extract("90년대 영화").releasePeriod();

        assertThat(nineties.contains(LocalDate.of(1990, 1, 1))).isTrue();
        assertThat(nineties.contains(LocalDate.of(1999, 12, 31))).isTrue();
        assertThat(nineties.contains(LocalDate.of(1989, 12, 31))).isFalse();
        assertThat(nineties.contains(LocalDate.of(2000, 1, 1))).isFalse();
        assertThat(nineties.contains(null)).isFalse();
    }

    @Test
    void 기준일은_Clock이다_다른_날이면_범위가_움직인다() {
        QueryConstraintExtractor later = new QueryConstraintExtractor(RecommendFixtures.properties(),
                Clock.fixed(LocalDate.of(2030, 1, 1).atStartOfDay(KST).toInstant(), KST));

        assertThat(later.extract("최근 영화").releasePeriod().from()).isEqualTo(LocalDate.of(2025, 1, 1));
    }

    // ── Q-2-3 ① 분류 부정 → 제외 필터 ───────────────────────────────

    @Test
    void 공포_말고_스릴러는_공포_제외_본_질의는_스릴러다() {
        QueryConstraints c = extractor.extract("공포 말고 스릴러");

        assertThat(c.excludeGenres()).containsExactly(27);
        assertThat(c.mainQuery()).isEqualTo("스릴러");
        assertThat(c.negativeConcepts()).isEmpty();
        AppliedConditionResponse chip = chip(c, ConditionType.EXCLUDE_GENRE);
        assertThat(chip.label()).isEqualTo("공포");
        assertThat(chip.text()).isEqualTo("공포 제외");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "호러는 빼고 로맨스|27|로맨스", "로맨스 제외하고 액션|10749|액션", "코미디 아닌 영화 추천해줘|35|",
            "애니메이션 말고 실사 영화|16|실사 영화", "다큐 말고 극영화|99|극영화",
    })
    void 여러_표현의_장르_제외(String query, int tmdbId, String mainQuery) {
        QueryConstraints c = extractor.extract(query);

        assertThat(c.excludeGenres()).containsExactly(tmdbId);
        if (mainQuery == null || mainQuery.isEmpty()) {
            assertThat(c.mainQuery()).isNull();
        } else {
            assertThat(c.mainQuery()).isEqualTo(mainQuery);
        }
    }

    @Test
    void 장르_제외만_있으면_본_질의가_비어_있다() {
        QueryConstraints c = extractor.extract("공포 영화 말고");

        assertThat(c.excludeGenres()).containsExactly(27);
        assertThat(c.mainQuery()).isNull();
    }

    @Test
    void 한국_영화_말고는_KR_제외이고_긍정_국가로는_잡히지_않는다() {
        QueryConstraints c = extractor.extract("한국 영화 말고");

        assertThat(c.excludeCountries()).containsExactly("KR");
        assertThat(c.countries()).isEmpty();
        assertThat(c.mainQuery()).isNull();
        assertThat(chip(c, ConditionType.EXCLUDE_COUNTRY).text()).isEqualTo("한국 영화는 제외했어요");
    }

    @Test
    void 한국_영화_말고_일본_영화는_한국을_빼고_일본만_고른다() {
        QueryConstraints c = extractor.extract("한국 영화 말고 일본 영화");

        assertThat(c.excludeCountries()).containsExactly("KR");
        assertThat(c.countries()).containsExactly("JP");
        assertThat(c.mainQuery()).isEqualTo("일본 영화");
    }

    // ── Q-2-3 ② 분위기 부정 → 부정 개념(감점) ───────────────────────

    @Test
    void 잔인하지_않은_스릴러는_부정_개념_잔인하다_본_질의_스릴러다() {
        QueryConstraints c = extractor.extract("잔인하지 않은 스릴러");

        assertThat(c.negativeConcepts()).containsExactly("잔인하다");
        assertThat(c.mainQuery()).isEqualTo("스릴러");
        assertThat(c.excludeGenres()).isEmpty();
        AppliedConditionResponse chip = chip(c, ConditionType.AVOID_MOOD);
        assertThat(chip.label()).isEqualTo("잔인하다");
        assertThat(chip.text()).isEqualTo("잔인한 영화는 뒤로 미뤘어요");
    }

    @Test
    void 무겁지_않은은_불규칙_활용이라도_기본형_무겁다로_복원된다() {
        QueryConstraints c = extractor.extract("무겁지 않은 영화");

        assertThat(c.negativeConcepts()).containsExactly("무겁다");
        assertThat(chip(c, ConditionType.AVOID_MOOD).text()).isEqualTo("무거운 영화는 뒤로 미뤘어요");
    }

    @Test
    void 본_질의에_영화만_남으면_본_질의는_비어_있다() {
        assertThat(extractor.extract("잔인하지 않은 영화").mainQuery()).isNull();
        assertThat(extractor.extract("무겁지 않은 영화 추천해줘").mainQuery()).isNull();
    }

    @Test
    void 슬프지_않은_로맨스() {
        QueryConstraints c = extractor.extract("슬프지 않은 로맨스");

        assertThat(c.negativeConcepts()).containsExactly("슬프다");
        assertThat(c.mainQuery()).isEqualTo("로맨스");
        assertThat(chip(c, ConditionType.AVOID_MOOD).text()).isEqualTo("슬픈 영화는 뒤로 미뤘어요");
    }

    @Test
    void 잔인한_장면_없는은_명사구가_부정_개념이다() {
        QueryConstraints c = extractor.extract("잔인한 장면 없는 스릴러");

        assertThat(c.negativeConcepts()).containsExactly("잔인한 장면");
        assertThat(c.mainQuery()).isEqualTo("스릴러");
        assertThat(chip(c, ConditionType.AVOID_MOOD).text()).isEqualTo("'잔인한 장면' 쪽은 뒤로 미뤘어요");
    }

    @Test
    void 명사_하나와_없는() {
        QueryConstraints c = extractor.extract("욕설 없는 코미디");

        assertThat(c.negativeConcepts()).containsExactly("욕설");
        assertThat(c.mainQuery()).isEqualTo("코미디");
    }

    @Test
    void 재미없는처럼_합성어_없는은_부정이_아니다() {
        QueryConstraints c = extractor.extract("재미없는 영화");

        assertThat(c.negativeConcepts()).isEmpty();
        assertThat(c.mainQuery()).isEqualTo("재미없는 영화");
    }

    @Test
    void 사전_밖_명사_말고는_부정_개념이다() {
        QueryConstraints c = extractor.extract("폭력 말고 가족 영화");

        assertThat(c.negativeConcepts()).containsExactly("폭력");
        assertThat(c.excludeGenres()).isEmpty();
        assertThat(c.mainQuery()).isEqualTo("가족 영화");
    }

    @Test
    void 부정_개념이_셋이면_둘만_쓰고_셋째도_본_질의에서는_지운다() {
        QueryConstraints c = extractor.extract("잔인하지 않은 무겁지 않은 슬프지 않은 스릴러");

        assertThat(c.negativeConcepts()).containsExactly("잔인하다", "무겁다");
        assertThat(c.conditions().stream().filter(x -> x.type() == ConditionType.AVOID_MOOD)).hasSize(2);
        assertThat(c.mainQuery()).isEqualTo("스릴러");   // 셋째("슬프지 않은")가 남아 벡터를 슬픈 쪽으로 당기지 않는다
    }

    @Test
    void 같은_개념이_두_번_나오면_한_번만_센다() {
        QueryConstraints c = extractor.extract("잔인하지 않은 잔인하지 않은 스릴러");

        assertThat(c.negativeConcepts()).containsExactly("잔인하다");
    }

    // ── 부정 + 다른 조건 ────────────────────────────────────────────

    @Test
    void 국가_시기_런타임_조건은_본_질의에서_지우지_않는다() {
        QueryConstraints c = extractor.extract("90년대 한국 영화인데 잔인하지 않은");

        assertThat(c.countries()).containsExactly("KR");
        assertThat(c.releasePeriod().from()).isEqualTo(LocalDate.of(1990, 1, 1));
        assertThat(c.negativeConcepts()).containsExactly("잔인하다");
        assertThat(c.mainQuery()).isEqualTo("90년대 한국 영화인데");
    }

    @Test
    void 칩은_런타임_국가_시기_제외장르_분위기_순이다() {
        QueryConstraints c = extractor.extract("90분 이내 90년대 한국 영화 공포 말고 잔인하지 않은");

        assertThat(c.conditions()).extracting(AppliedConditionResponse::type).containsExactly(
                ConditionType.RUNTIME_MAX, ConditionType.COUNTRY, ConditionType.RELEASE_PERIOD,
                ConditionType.EXCLUDE_GENRE, ConditionType.AVOID_MOOD);
    }

    @Test
    void 필터_선적용_예시_90년대_한국_영화() {
        QueryConstraints c = extractor.extract("90년대 한국 영화");

        assertThat(c.countries()).containsExactly("KR");
        assertThat(c.releasePeriod()).isEqualTo(new ReleasePeriod(LocalDate.of(1990, 1, 1), LocalDate.of(1999, 12, 31)));
        assertThat(c.mainQuery()).isEqualTo("90년대 한국 영화");
    }

    @Test
    void conditionsWithoutMood는_분위기_칩만_뺀다() {
        QueryConstraints c = extractor.extract("한국 영화 잔인하지 않은");

        assertThat(c.conditions()).extracting(AppliedConditionResponse::type)
                .containsExactly(ConditionType.COUNTRY, ConditionType.AVOID_MOOD);
        assertThat(c.conditionsWithoutMood()).extracting(AppliedConditionResponse::type).containsExactly(ConditionType.COUNTRY);
    }
}
