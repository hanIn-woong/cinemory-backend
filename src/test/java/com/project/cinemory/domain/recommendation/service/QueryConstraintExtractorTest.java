package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.recommendation.dto.ConditionType;
import com.project.cinemory.domain.recommendation.service.QueryConstraintExtractor.QueryConstraints;
import com.project.cinemory.domain.recommendation.service.QueryConstraintExtractor.RuntimePreference;
import com.project.cinemory.domain.recommendation.support.RecommendFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Q-1 ② — 질의에서 상영시간 조건 추출. */
class QueryConstraintExtractorTest {

    private final QueryConstraintExtractor extractor = new QueryConstraintExtractor(RecommendFixtures.properties());

    @Test
    void 분_이내는_하드_필터다() {
        QueryConstraints c = extractor.extract("90분 이내로 볼 수 있는 영화");

        assertThat(c.runtimeMax()).isEqualTo(90);
        assertThat(c.preference()).isEqualTo(RuntimePreference.NONE);
        assertThat(c.conditions()).singleElement().satisfies(condition -> {
            assertThat(condition.type()).isEqualTo(ConditionType.RUNTIME_MAX);
            assertThat(condition.value()).isEqualTo(90);
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
        assertThat(c.preference()).isEqualTo(RuntimePreference.SHORT);
        assertThat(c.conditions()).singleElement().satisfies(condition -> {
            assertThat(condition.type()).isEqualTo(ConditionType.RUNTIME_PREFER_SHORT);
            assertThat(condition.value()).isEqualTo(100);
        });
    }

    @Test
    void 금방_볼_수_있는도_짧은_선호다() {
        assertThat(extractor.extract("가볍게 금방 볼 수 있는 영화").preference()).isEqualTo(RuntimePreference.SHORT);
    }

    @Test
    void 가볍게만_있으면_분위기_표현이라_조건이_없다() {
        assertThat(extractor.extract("가볍게 웃을 수 있는 영화").preference()).isEqualTo(RuntimePreference.NONE);
    }

    @Test
    void 긴_영화와_대작은_긴_선호다() {
        QueryConstraints c = extractor.extract("긴 영화 보고 싶어");

        assertThat(c.preference()).isEqualTo(RuntimePreference.LONG);
        assertThat(c.conditions()).singleElement().satisfies(condition -> {
            assertThat(condition.type()).isEqualTo(ConditionType.RUNTIME_PREFER_LONG);
            assertThat(condition.value()).isEqualTo(140);
        });
        assertThat(extractor.extract("서사가 큰 대작").preference()).isEqualTo(RuntimePreference.LONG);
    }

    @Test
    void 세_시간짜리는_긴_선호다() {
        assertThat(extractor.extract("3시간짜리 영화").preference()).isEqualTo(RuntimePreference.LONG);
        assertThat(extractor.extract("3시간짜리 영화").runtimeMax()).isNull();
    }

    @Test
    void 긴장감이나_긴박한은_긴_선호가_아니다() {
        assertThat(extractor.extract("긴장감 넘치는 스릴러").preference()).isEqualTo(RuntimePreference.NONE);
        assertThat(extractor.extract("긴박한 추격 영화").preference()).isEqualTo(RuntimePreference.NONE);
    }

    @Test
    void 조건이_없으면_빈_조건이다() {
        QueryConstraints c = extractor.extract("비 오는 날 혼자 보기 좋은 잔잔한 영화");

        assertThat(c.runtimeMax()).isNull();
        assertThat(c.preference()).isEqualTo(RuntimePreference.NONE);
        assertThat(c.conditions()).isEmpty();
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
        assertThat(c.preference()).isEqualTo(RuntimePreference.NONE);
        assertThat(c.conditions()).hasSize(1);
    }

    @Test
    void 비거나_null이면_빈_조건이다() {
        assertThat(extractor.extract("").conditions()).isEmpty();
        assertThat(extractor.extract("   ").conditions()).isEmpty();
        assertThat(extractor.extract(null).conditions()).isEmpty();
    }

    @Test
    void 터무니없이_큰_숫자도_예외없이_처리한다() {
        assertThat(extractor.extract("99999999999999분 이내").runtimeMax()).isNull();
    }
}
