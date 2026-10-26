package com.project.cinemory.domain.recommendation.dto;

import java.util.List;

/**
 * 점수 분해 디버그 (M3b-recommendation-spec S-1-7, controller-layer-spec 5-14-G). 관리자 전용 — 튜닝을 감이 아니라 숫자로 하기 위한 응답이다.
 *
 * @param query      자연어 디버그면 정규화된 질의, 취향 디버그면 null
 * @param conditions 자연어에서 뽑은 조건
 * @param profile    사용자 프로필 요약
 * @param movies     점수 분해된 결과(최대 100편)
 * @param exclusions 제외 사유별 건수
 */
public record RecommendationDebugResponse(
        Long userId,
        String query,
        List<AppliedConditionResponse> conditions,
        Profile profile,
        List<Movie> movies,
        Exclusions exclusions) {

    /**
     * @param meanRating 사용자 누적 평균 별점 m. 평가 기록이 없으면 null
     * @param actors     평가 전용(찜은 배우를 반영하지 않는다)
     */
    public record Profile(Double meanRating, boolean personalized, int watchedCount, int wishedCount,
                          Axis genres, Axis countries, Axis directors, Axis actors) {
    }

    /**
     * 한 축의 상위 항목. 값은 0~1로 정규화돼 있고 {@code combined = (1−λ)·evaluation + λ·interest}다.
     *
     * @param lambda 실제 쓴 관심 비중 — 평가 축이 비어 있으면 1
     * @param size   축 전체 항목 수(top은 상위 일부만)
     */
    public record Axis(double lambda, int size, List<Entry> top) {
    }

    public record Entry(Long id, String name, double evaluation, double interest, double combined) {
    }

    /**
     * 항별 원값(가중치 곱하기 전). {@code sim}은 자연어에서만, {@code popularity}는 취향 추천에서만 의미가 있다.
     *
     * @param personKind 인물 항이 어디서 왔는가 — DIRECTOR / WISH_DIRECTOR / ACTOR, 인물 항이 0이면 null
     */
    public record Movie(Long id, String title, Double sim, double genreTerm, double countryTerm, double personTerm,
                        String personKind, Long personId, String personName, double taste, double quality,
                        double popularity, double runtimeBonus, double score,
                        RecommendReasonResponse reason, boolean wished) {
    }

    public record Exclusions(int watched, int wished, int unreleased, int belowVoteFloor,
                             int runtimeFilter, int directorCap) {
    }
}
