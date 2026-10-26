package com.project.cinemory.domain.recommendation.service;

import java.util.Map;
import java.util.Set;

/**
 * 한 사용자의 선호 프로필 (M3b-recommendation-spec S-1-1 · W-1). 모든 값은 축별로 0~1로 정규화돼 있다.
 *
 * <p>평가 기여({@link Axis#evaluation})와 찜 관심 기여({@link Axis#interest})를 따로 보존한다 —
 * 점수 분해 디버그(λ 조정 근거)와 추천 이유("찜 목록에 많은 공포 장르")가 둘의 비중을 본다.
 *
 * @param meanRating   사용자 누적 평균 별점 m. 평가 기록이 없으면 {@code null}
 * @param genre        장르 축 {@code genre.id}
 * @param country      국가 축 {@code country.id}
 * @param director     감독 축 {@code person.id}
 * @param actor        배우 축 {@code person.id} — 평가에서만 온다(찜은 반영하지 않는다)
 * @param personalized 전 축이 비어 있으면 {@code false} (R-3 콜드 스타트)
 * @param watchedMovieIds 시청 기록이 있는 영화(전 회차, 대표 여부 무관)
 * @param wishedMovieIds  찜한 영화 전체(이미 본 영화 포함 — 결과 제외·{@code wished} 표시용)
 */
public record UserPreferenceProfile(
        Double meanRating,
        Axis genre,
        Axis country,
        Axis director,
        Map<Long, Double> actor,
        boolean personalized,
        Set<Long> watchedMovieIds,
        Set<Long> wishedMovieIds) {

    /**
     * 한 축의 결합 결과.
     *
     * @param evaluation 평가 기반 정규화 값(0~1)
     * @param interest   찜 관심 정규화 값(0~1)
     * @param lambda     실제로 쓴 관심 비중 — 평가 축이 비어 있으면 1, 아니면 설정값 λ
     * @param combined   {@code (1−λ)·evaluation + λ·interest}
     */
    public record Axis(Map<Long, Double> evaluation, Map<Long, Double> interest, double lambda, Map<Long, Double> combined) {

        static final Axis EMPTY = new Axis(Map.of(), Map.of(), 0.0, Map.of());

        public double value(long id) {
            return combined.getOrDefault(id, 0.0);
        }

        public boolean isEmpty() {
            return combined.isEmpty();
        }
    }

    /** 기록도 찜도 없는 사용자·게스트. */
    public static UserPreferenceProfile empty() {
        return new UserPreferenceProfile(null, Axis.EMPTY, Axis.EMPTY, Axis.EMPTY, Map.of(), false, Set.of(), Set.of());
    }

    /** 기록이나 찜이 하나라도 있는가 — 없으면 전역 공유 캐시를 쓴다(5-14-D, PR 3). */
    public boolean hasActivity() {
        return !watchedMovieIds.isEmpty() || !wishedMovieIds.isEmpty();
    }
}
