package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.recommendation.config.RecommendProperties;
import com.project.cinemory.domain.recommendation.index.MovieFeature;
import com.project.cinemory.domain.recommendation.service.UserPreferenceProfile.Axis;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 선호 프로필 계산 — <b>순수 함수</b> (DB 없음). 입력은 쿼리가 집계해 온 평가 점수와 찜 영화의 특징이다.
 *
 * <ul>
 *   <li><b>평가</b>(S-1-1): {@code max(0, Σ(별점 − m)·w)}를 축 최댓값으로 나눠 0~1. 최댓값이 0이면 그 축은 비어 있다.</li>
 *   <li><b>찜 장르·국가</b>(W-1-1): lift + 스무딩 — {@code share = (Σw + α·p) / (n + α)}, {@code I = max(0, share/p − 1)}, 최댓값 정규화.</li>
 *   <li><b>찜 감독</b>(W-1-2): 같은 감독 작품을 {@code minDirectorWishes}편 이상 찜했을 때만 {@code Σ1/N}, 최댓값 정규화.</li>
 *   <li><b>결합</b>(W-1-3): {@code (1−λ)·평가 + λ·관심}. 평가 축이 비어 있으면 그 축은 λ = 1.</li>
 * </ul>
 */
final class ProfileCalculator {

    private ProfileCalculator() {
    }

    /** 쿼리가 돌려준 평가 기반 원점수(정규화 전). 키는 {@code genre.id}/{@code country.id}/{@code person.id}. */
    record EvaluationScores(Double meanRating,
                            Map<Long, Double> genre,
                            Map<Long, Double> country,
                            Map<Long, Double> director,
                            Map<Long, Double> actor) {
        static EvaluationScores none() {
            return new EvaluationScores(null, Map.of(), Map.of(), Map.of(), Map.of());
        }
    }

    static UserPreferenceProfile calculate(EvaluationScores evaluation,
                                           List<MovieFeature> wishedUnwatched,
                                           Map<Long, Double> genreShare,
                                           Map<Long, Double> countryShare,
                                           Set<Long> watchedMovieIds,
                                           Set<Long> wishedMovieIds,
                                           RecommendProperties.Wish wish) {
        Axis genre = combine(normalize(evaluation.genre()),
                lift(wishedUnwatched, MovieFeature::genreIds, MovieFeature::genreWeights, genreShare, wish), wish.lambda());
        Axis country = combine(normalize(evaluation.country()),
                lift(wishedUnwatched, MovieFeature::countryIds, MovieFeature::countryWeights, countryShare, wish), wish.lambda());
        Axis director = combine(normalize(evaluation.director()),
                repeatedDirectors(wishedUnwatched, wish.minDirectorWishes()), wish.lambda());
        Map<Long, Double> actor = normalize(evaluation.actor());

        boolean personalized = !genre.isEmpty() || !country.isEmpty() || !director.isEmpty() || !actor.isEmpty();
        return new UserPreferenceProfile(evaluation.meanRating(), genre, country, director, actor, personalized,
                Set.copyOf(watchedMovieIds), Set.copyOf(wishedMovieIds));
    }

    /** 0 이하를 버리고 최댓값으로 나눈다. 남는 값이 없으면 빈 맵. */
    static Map<Long, Double> normalize(Map<Long, Double> raw) {
        double max = 0;
        for (double v : raw.values()) {
            max = Math.max(max, v);
        }
        if (max <= 0) {
            return Map.of();
        }
        Map<Long, Double> normalized = new HashMap<>();
        for (Map.Entry<Long, Double> e : raw.entrySet()) {
            if (e.getValue() > 0) {
                normalized.put(e.getKey(), e.getValue() / max);
            }
        }
        return Map.copyOf(normalized);
    }

    /** W-1-1 — 카탈로그 대비 얼마나 많이 찜했나. {@code p_cat}를 모르는 항목(신규 장르 등)은 건너뛴다. */
    static Map<Long, Double> lift(List<MovieFeature> wished,
                                  java.util.function.Function<MovieFeature, long[]> ids,
                                  java.util.function.Function<MovieFeature, double[]> weights,
                                  Map<Long, Double> catalogShare,
                                  RecommendProperties.Wish wish) {
        if (wished.isEmpty()) {
            return Map.of();
        }
        Map<Long, Double> wishedWeightSum = new HashMap<>();
        for (MovieFeature movie : wished) {
            long[] movieIds = ids.apply(movie);
            double[] movieWeights = weights.apply(movie);
            for (int i = 0; i < movieIds.length; i++) {
                wishedWeightSum.merge(movieIds[i], movieWeights[i], Double::sum);
            }
        }
        int n = wished.size();
        Map<Long, Double> raw = new HashMap<>();
        for (Map.Entry<Long, Double> e : wishedWeightSum.entrySet()) {
            Double pCat = catalogShare.get(e.getKey());
            if (pCat == null || pCat <= 0) {
                continue;
            }
            double share = (e.getValue() + wish.smoothing() * pCat) / (n + wish.smoothing());
            raw.put(e.getKey(), Math.max(0, share / pCat - 1));
        }
        return normalize(raw);
    }

    /** W-1-2 — lift를 쓰지 않는다(작품 수가 적은 감독일수록 커져 다작 감독이 불리). 1편 찜은 우연이라 무시. */
    static Map<Long, Double> repeatedDirectors(List<MovieFeature> wished, int minWishes) {
        Map<Long, Double> shareSum = new HashMap<>();
        Map<Long, Integer> wishCount = new HashMap<>();
        for (MovieFeature movie : wished) {
            long[] directors = movie.directorIds();
            for (long director : directors) {
                shareSum.merge(director, 1.0 / directors.length, Double::sum);
                wishCount.merge(director, 1, Integer::sum);
            }
        }
        Map<Long, Double> raw = new HashMap<>();
        for (Map.Entry<Long, Double> e : shareSum.entrySet()) {
            if (wishCount.get(e.getKey()) >= minWishes) {
                raw.put(e.getKey(), e.getValue());
            }
        }
        return normalize(raw);
    }

    /** W-1-3 — 평가 축이 비어 있으면 λ = 1 (그러지 않으면 찜만 한 신규 사용자의 T가 30%로 깎여 Q·Pop에 묻힌다). */
    static Axis combine(Map<Long, Double> evaluation, Map<Long, Double> interest, double lambda) {
        double effectiveLambda = evaluation.isEmpty() ? 1.0 : lambda;
        Set<Long> ids = new HashSet<>(evaluation.keySet());
        ids.addAll(interest.keySet());
        Map<Long, Double> combined = new HashMap<>();
        for (long id : ids) {
            double value = (1 - effectiveLambda) * evaluation.getOrDefault(id, 0.0)
                    + effectiveLambda * interest.getOrDefault(id, 0.0);
            if (value > 0) {
                combined.put(id, value);
            }
        }
        return new Axis(evaluation, interest, effectiveLambda, Map.copyOf(combined));
    }
}
