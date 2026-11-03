package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.movie.entity.KrCertification;
import com.project.cinemory.domain.movie.entity.RecommendOverride;
import com.project.cinemory.domain.recommendation.service.RecommendSafetyPolicy.Facts;
import com.project.cinemory.domain.recommendation.service.RecommendSafetyPolicy.Reason;
import com.project.cinemory.domain.recommendation.support.RecommendFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 추천 제외 자동 판정 (M3b R-5-1 ①) - 차단 키워드 · 형태 규칙(주 방어) · 제한상영가 · 덮어쓰기 우선순위. */
class RecommendSafetyPolicyTest {

    private static final int ROMANCE = 10749;
    private static final int DRAMA = 18;
    private static final int ACTION = 28;

    private final RecommendSafetyPolicy policy = RecommendFixtures.safetyPolicy();

    private List<Reason> reasons(KrCertification certification, Integer runtime, Integer votes, List<Integer> genres, boolean keyword) {
        return policy.evaluate(new Facts(certification, runtime, votes, genres, keyword));
    }

    @Test
    void 가족의_재구성_형태는_형태_규칙에_걸린다() {
        // 청불 + 드라마 + 75분 + 투표 수 적음 (사용자가 보고한 실제 사례의 형태)
        assertThat(reasons(KrCertification.AGE_18, 75, 8, List.of(ROMANCE, DRAMA), false)).containsExactly(Reason.SHAPE_RULE);
    }

    @Test
    void 로맨스만_있어도_드라마만_있어도_걸린다() {
        assertThat(reasons(KrCertification.AGE_18, 80, 5, List.of(ROMANCE), false)).containsExactly(Reason.SHAPE_RULE);
        assertThat(reasons(KrCertification.AGE_18, 80, 5, List.of(DRAMA), false)).containsExactly(Reason.SHAPE_RULE);
    }

    @Test
    void 아가씨_올드보이처럼_길거나_투표가_많으면_걸리지_않는다() {
        // 145분 · 투표 수 많음 - 상영시간과 투표 수로 빠진다
        assertThat(reasons(KrCertification.AGE_18, 145, 5_000, List.of(ROMANCE, DRAMA), false)).isEmpty();
        assertThat(reasons(KrCertification.AGE_18, 120, 3, List.of(DRAMA), false)).isEmpty();    // 길다
        assertThat(reasons(KrCertification.AGE_18, 80, 5_000, List.of(DRAMA), false)).isEmpty(); // 알려진 영화
    }

    @Test
    void 경계값_90분은_걸리고_91분은_안_걸리며_20표는_안_걸리고_19표는_걸린다() {
        assertThat(reasons(KrCertification.AGE_18, 90, 19, List.of(DRAMA), false)).containsExactly(Reason.SHAPE_RULE);
        assertThat(reasons(KrCertification.AGE_18, 91, 19, List.of(DRAMA), false)).isEmpty();
        assertThat(reasons(KrCertification.AGE_18, 90, 20, List.of(DRAMA), false)).isEmpty();
    }

    @Test
    void 청불이_아니거나_장르가_맞지_않으면_걸리지_않는다() {
        assertThat(reasons(KrCertification.AGE_15, 75, 5, List.of(DRAMA), false)).isEmpty();
        assertThat(reasons(null, 75, 5, List.of(DRAMA), false)).isEmpty();                       // 등급 없음은 형태 규칙 대상이 아니다
        assertThat(reasons(KrCertification.AGE_18, 75, 5, List.of(ACTION), false)).isEmpty();    // 액션 단편
        assertThat(reasons(KrCertification.AGE_18, 75, 5, List.of(), false)).isEmpty();
    }

    @Test
    void 상영시간이_없으면_형태_규칙이_걸리지_않고_투표_수가_없으면_0표로_본다() {
        assertThat(reasons(KrCertification.AGE_18, null, 5, List.of(DRAMA), false)).isEmpty();
        assertThat(reasons(KrCertification.AGE_18, 80, null, List.of(DRAMA), false)).containsExactly(Reason.SHAPE_RULE);
    }

    @Test
    void 제한상영가는_항상_걸린다() {
        assertThat(reasons(KrCertification.RESTRICTED, 150, 9_000, List.of(ACTION), false)).containsExactly(Reason.RESTRICTED);
    }

    @Test
    void 차단_키워드가_붙어_있으면_걸린다() {
        assertThat(reasons(KrCertification.AGE_15, 150, 9_000, List.of(ACTION), true)).containsExactly(Reason.KEYWORD);
    }

    @Test
    void 사유가_여럿이면_모두_담는다() {
        assertThat(reasons(KrCertification.AGE_18, 75, 5, List.of(DRAMA), true)).containsExactly(Reason.KEYWORD, Reason.SHAPE_RULE);
    }

    @Test
    void 키워드_이름은_대소문자와_공백을_무시하고_비교한다() {
        assertThat(policy.matchesBlockedKeyword(List.of("Erotic Movie"))).isTrue();
        assertThat(policy.matchesBlockedKeyword(List.of("  softcore "))).isTrue();
        assertThat(policy.matchesBlockedKeyword(List.of("family", "autumn"))).isFalse();
        assertThat(policy.matchesBlockedKeyword(List.of())).isFalse();
    }

    @Test
    void 덮어쓰기가_있으면_자동_판정보다_우선한다() {
        assertThat(RecommendSafetyPolicy.isBlocked(true, null)).isTrue();
        assertThat(RecommendSafetyPolicy.isBlocked(false, null)).isFalse();
        assertThat(RecommendSafetyPolicy.isBlocked(true, RecommendOverride.ALLOW)).isFalse();    // 허용
        assertThat(RecommendSafetyPolicy.isBlocked(false, RecommendOverride.BLOCK)).isTrue();    // 규칙 밖이어도 제외
    }
}
