package com.project.cinemory.domain.recommendation.index;

import com.project.cinemory.domain.movie.entity.KrCertification;
import com.project.cinemory.domain.movie.entity.RecommendOverride;
import com.project.cinemory.domain.recommendation.service.RecommendSafetyPolicy;

import java.time.LocalDate;

/**
 * 영화 한 편의 채점용 특징 (M3b-recommendation-spec S-1-6). {@code MovieFeatureIndex}가 불변 스냅샷으로 들고 있다.
 *
 * <p>배열은 읽기 전용으로 다룬다(같은 인덱스를 여러 요청이 동시에 채점한다).
 *
 * @param genreIds     {@code genre.id}. {@code genreWeights}와 같은 위치끼리 짝이다
 * @param countryIds   {@code country.id}. {@code countryWeights}와 짝
 * @param directorIds  {@code person.id}
 * @param leadActorIds {@code display_order 0~4}의 {@code person.id}
 * @param genreTmdbIds {@code genre.tmdb_genre_id} — {@code genreIds}와 같은 위치끼리 짝. 질의의 "공포 말고" 같은 제외 장르 판정은
 *                     이름이 아니라 이 안정된 TMDB 코드로 한다(Q-2-3 ①).
 * @param animation    {@code tmdb_genre_id = 16} — 배우 항을 보지 않는다(M3-a 11-2)
 * @param korean       대표국(= {@code movie_country.weight} 최댓값)이 KR
 * @param representativeCountryCode 대표국의 ISO 3166-1 alpha-2 코드. 국가 정보가 없으면 null. {@code korean}과 같은 판정 기준이다(Q-2-1)
 * @param voteAverage  null 가능
 * @param voteCount    null 가능
 * @param krCertification 한국 관람등급(V44). 등급 정보가 없으면 null — 가족 의도 질의의 등급 필터(R-5-1 ②)가 쓴다
 * @param autoBlocked  {@code RecommendSafetyPolicy}의 자동 판정이 막았는가(R-5-1 ①). 저장하지 않고 인덱스를 만들 때 계산한다
 * @param recommendOverride 관리자 덮어쓰기. null이면 자동 판정을 따른다
 */
public record MovieFeature(
        long movieId,
        long[] genreIds,
        int[] genreTmdbIds,
        double[] genreWeights,
        long[] countryIds,
        double[] countryWeights,
        long[] directorIds,
        long[] leadActorIds,
        boolean animation,
        boolean korean,
        String representativeCountryCode,
        Integer runtime,
        LocalDate releaseDate,
        Double voteAverage,
        Integer voteCount,
        KrCertification krCertification,
        boolean autoBlocked,
        RecommendOverride recommendOverride) {

    /** 안전 판정 정보 없이 만든다(자동 판정 통과 · 덮어쓰기 없음 · 등급 없음) — 채점 로직 테스트용. */
    public MovieFeature(long movieId, long[] genreIds, int[] genreTmdbIds, double[] genreWeights, long[] countryIds,
                        double[] countryWeights, long[] directorIds, long[] leadActorIds, boolean animation, boolean korean,
                        String representativeCountryCode, Integer runtime, LocalDate releaseDate, Double voteAverage,
                        Integer voteCount) {
        this(movieId, genreIds, genreTmdbIds, genreWeights, countryIds, countryWeights, directorIds, leadActorIds,
                animation, korean, representativeCountryCode, runtime, releaseDate, voteAverage, voteCount,
                null, false, null);
    }

    /** 추천에서 제외되는가 — 덮어쓰기가 있으면 그 값, 없으면 자동 판정(R-5-1 ①). */
    public boolean recommendBlocked() {
        return RecommendSafetyPolicy.isBlocked(autoBlocked, recommendOverride);
    }

    /** 덮어쓰기만 바꾼 사본 — 관리자가 한 영화의 덮어쓰기를 바꿀 때 인덱스 전체를 다시 만들지 않으려고 쓴다. */
    public MovieFeature withRecommendOverride(RecommendOverride override) {
        return new MovieFeature(movieId, genreIds, genreTmdbIds, genreWeights, countryIds, countryWeights, directorIds,
                leadActorIds, animation, korean, representativeCountryCode, runtime, releaseDate, voteAverage, voteCount,
                krCertification, autoBlocked, override);
    }
}
