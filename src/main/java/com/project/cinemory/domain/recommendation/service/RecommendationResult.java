package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.recommendation.dto.AppliedConditionResponse;
import com.project.cinemory.domain.recommendation.dto.RecommendReasonResponse;

import java.util.List;

/**
 * 추천 계산 결과 — 순위가 확정된 최대 100편 (캐시·페이징·응답 DTO 변환은 PR 3).
 *
 * @param movies       점수 내림차순(동점이면 movieId 오름차순)
 * @param personalized 프로필 전 축이 0이 아니면 {@code true}
 * @param conditions   자연어에서 질의로부터 뽑은 조건. 취향 추천은 빈 리스트
 * @param exclusions   제외로 빠진 건수(디버그)
 */
public record RecommendationResult(List<RankedMovie> movies,
                                   boolean personalized,
                                   List<AppliedConditionResponse> conditions,
                                   ExclusionCounts exclusions) {

    /**
     * @param reason    {@code null} 가능 — 자연어에서 {@code sim}이 최대 기여일 때
     * @param wished    자연어 전용(취향 추천은 항상 false)
     * @param breakdown 점수 분해(관리자 디버그, S-1-7)
     */
    public record RankedMovie(long movieId, double score, RecommendReasonResponse reason,
                              boolean wished, ScoreBreakdown breakdown) {
    }

    /**
     * 항별 원값(가중치 곱하기 전). {@code sim}은 자연어에서만 값이 있다.
     *
     * @param personKind 인물 항 {@code P}가 어느 쪽에서 왔는가 — {@code DIRECTOR}/{@code ACTOR}, 인물 항이 0이면 {@code null}
     */
    public record ScoreBreakdown(Double sim, double genreTerm, double countryTerm, double personTerm,
                                 String personKind, Long personId, double taste, double quality,
                                 double popularity, double runtimeBonus, Double negativeSim, double score) {
    }

    /**
     * 제외 사유별 건수. 해당 없는 사유는 0. 자연어는 후보를 뽑는 전수 스캔 중에 집계한다(Q-2-4) — 영화 한 편은 처음 걸린 사유 하나로만 센다.
     *
     * @param countryFilter       국가·제외 국가 조건에 걸린 영화
     * @param releasePeriodFilter 개봉 시기 조건에 걸린 영화 (개봉일 없음 포함)
     * @param excludedGenre       제외 장르("공포 말고")에 걸린 영화
     */
    public record ExclusionCounts(int watched, int wished, int unreleased, int belowVoteFloor,
                                  int runtimeFilter, int directorCap,
                                  int countryFilter, int releasePeriodFilter, int excludedGenre) {

        public static ExclusionCounts none() {
            return new ExclusionCounts(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }
}
