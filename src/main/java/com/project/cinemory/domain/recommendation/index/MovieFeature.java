package com.project.cinemory.domain.recommendation.index;

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
        Integer voteCount) {
}
