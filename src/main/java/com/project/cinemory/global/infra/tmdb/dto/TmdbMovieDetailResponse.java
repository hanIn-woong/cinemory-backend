package com.project.cinemory.global.infra.tmdb.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /movie/{id}?append_to_response=credits,keywords,translations&language=ko-KR} 응답.
 *
 * <p>매핑 규칙 전반은 tmdb-sync-spec 6-3 참고. 이 레코드는 원본 필드만 담고, 정규화가
 * 필요한 값(제목 폴백·개봉일 파싱·runtime 0 정규화)은 헬퍼 메서드로 노출해
 * {@code MovieSyncPersister}가 매핑 시점에 조용히 실수하지 않게 한다.
 *
 * @param id                 TMDB 영화 ID — {@code movie.tmdb_id}
 * @param adult              성인 영화 여부. {@code syncFromTmdb}가 매핑보다 먼저 검사한다 (6-3 ①)
 * @param title              {@code language=ko-KR} 기준 제목 (비어 올 수 있음)
 * @param originalTitle      원어 제목 — {@code title} 폴백용
 * @param posterPath         포스터 경로만 저장, 베이스 URL은 프론트가 조립
 * @param releaseDate        {@code "yyyy-MM-dd"} 또는 빈 문자열 — {@link #parsedReleaseDate()}로 변환해 쓸 것
 * @param overview           줄거리 (nullable, 폴백 없음)
 * @param runtime            TMDB OpenAPI가 {@code default: 0}을 명시 — {@link #normalizedRuntime()}로 변환해 쓸 것
 * @param backdropPath       가로(16:9) 배경 이미지 경로. posterPath와 동일 원칙 (v13)
 * @param voteAverage        TMDB 평점(소수 첫째 자리) — {@link #normalizedVoteAverage()}로 변환해 쓸 것 (v13)
 * @param voteCount          TMDB 투표 수 (v13)
 * @param genres             {@code /genre/movie/list}와 구조가 같아 {@link TmdbGenreListResponse.Item}을 재사용한다
 * @param productionCountries 국가 가중치 계산 대상 (production_countries가 배열)
 * @param originCountry      대표국 판정용 — 배열이며 빈 배열일 수 있다. {@code production_countries}에
 *                           없는 값일 수 있어 그 경우 폴백한다 (D-3)
 * @param credits            {@code append_to_response=credits}로 함께 실려 온다
 * @param tagline            한국어 태그라인 — 빈 문자열로 오는 경우가 있어 {@link #taglineOrNull()}로 변환해 쓸 것 (6-11)
 * @param keywords           {@code append_to_response=keywords} — 영문 키워드만 제공된다 (6-11)
 * @param translations       {@code append_to_response=translations} — 수십 개 언어. 영문 줄거리 추출용 (6-11)
 */
public record TmdbMovieDetailResponse(
        Long id,
        Boolean adult,
        String title,
        @JsonProperty("original_title") String originalTitle,
        @JsonProperty("poster_path") String posterPath,
        @JsonProperty("release_date") String releaseDate,
        String overview,
        Integer runtime,
        @JsonProperty("backdrop_path") String backdropPath,
        @JsonProperty("vote_average") Double voteAverage,
        @JsonProperty("vote_count") Integer voteCount,
        List<TmdbGenreListResponse.Item> genres,
        @JsonProperty("production_countries") List<TmdbProductionCountry> productionCountries,
        @JsonProperty("origin_country") List<String> originCountry,
        TmdbCredits credits,
        String tagline,
        TmdbKeywords keywords,
        TmdbTranslations translations,
        @JsonProperty("release_dates") TmdbReleaseDates releaseDates,
        @JsonProperty("production_companies") List<TmdbProductionCompany> productionCompanies
) {

    /** 관람등급·제작사 보강(6-12) 이전 형태의 생성자 — 기존 호출부(테스트)가 그대로 쓴다. 새 두 필드는 비어 있다. */
    public TmdbMovieDetailResponse(Long id, Boolean adult, String title, String originalTitle, String posterPath,
                                   String releaseDate, String overview, Integer runtime, String backdropPath,
                                   Double voteAverage, Integer voteCount, List<TmdbGenreListResponse.Item> genres,
                                   List<TmdbProductionCountry> productionCountries, List<String> originCountry,
                                   TmdbCredits credits, String tagline, TmdbKeywords keywords,
                                   TmdbTranslations translations) {
        this(id, adult, title, originalTitle, posterPath, releaseDate, overview, runtime, backdropPath,
                voteAverage, voteCount, genres, productionCountries, originCountry, credits, tagline, keywords,
                translations, null, null);
    }

    /**
     * 한국 관람등급 원문(6-12-2). {@code KR} 항목의 {@code release_dates[]}에서 {@code certification}이 비어 있지 않은
     * 첫 값 — 순서는 {@code type} 3(극장 개봉) → 2(제한 개봉) → 그 외 숫자 오름차순(type이 없으면 맨 뒤). 없으면 null.
     */
    public String krCertificationRaw() {
        if (releaseDates == null) {
            return null;
        }
        return releaseDates.resultsOrEmpty().stream()
                .filter(country -> "KR".equals(country.iso31661()))
                .flatMap(country -> country.releasesOrEmpty().stream())
                .filter(release -> release.certification() != null && !release.certification().isBlank())
                .min(java.util.Comparator.comparingInt(release -> certificationTypeRank(release.type())))
                .map(release -> release.certification().trim())
                .orElse(null);
    }

    private static int certificationTypeRank(Integer type) {
        if (type == null) {
            return Integer.MAX_VALUE;
        }
        return switch (type) {
            case 3 -> 0;
            case 2 -> 1;
            default -> 2 + type;
        };
    }

    public List<TmdbProductionCompany> productionCompaniesOrEmpty() {
        return productionCompanies == null ? List.of() : productionCompanies;
    }

    /** {@code title}이 비어 오는 경우 원어 제목으로 폴백한다 (movie.title not null, 6-3 ⑦). */
    public String resolveTitle() {
        return (title == null || title.isBlank()) ? originalTitle : title;
    }

    /** {@link #resolveTitle()}이 폴백을 탔는지 — 발동 빈도 계측용(잔여 #4). */
    public boolean isTitleFallback() {
        return title == null || title.isBlank();
    }

    /** {@code release_date}가 빈 문자열로 오는 경우가 있어 null로 정규화한다. */
    public LocalDate parsedReleaseDate() {
        return (releaseDate == null || releaseDate.isBlank()) ? null : LocalDate.parse(releaseDate);
    }

    /** TMDB가 미개봉작·데이터 미비 시 {@code null}이 아니라 {@code 0}으로 내려준다 (6-3 ②). */
    public Integer normalizedRuntime() {
        return (runtime == null || runtime == 0) ? null : runtime;
    }

    /**
     * {@code movie.vote_average}가 {@code decimal(3,1)}이라 스케일을 맞춘다. TMDB가 대개
     * 소수 첫째 자리까지 주지만 보장은 아니라 {@code HALF_UP}으로 반올림한다 (v13).
     */
    public BigDecimal normalizedVoteAverage() {
        return voteAverage == null ? null : BigDecimal.valueOf(voteAverage).setScale(1, RoundingMode.HALF_UP);
    }

    public boolean isAdult() {
        return Boolean.TRUE.equals(adult);
    }

    public List<TmdbGenreListResponse.Item> genresOrEmpty() {
        return genres == null ? List.of() : genres;
    }

    public List<TmdbProductionCountry> productionCountriesOrEmpty() {
        return productionCountries == null ? List.of() : productionCountries;
    }

    public List<String> originCountryOrEmpty() {
        return originCountry == null ? List.of() : originCountry;
    }

    public TmdbCredits creditsOrEmpty() {
        return credits == null ? new TmdbCredits(List.of(), List.of()) : credits;
    }

    /** 태그라인이 없는 영화는 {@code ""}로 오기도 한다 — null로 정규화한다 (6-11). */
    public String taglineOrNull() {
        return (tagline == null || tagline.isBlank()) ? null : tagline;
    }

    /**
     * 영문 줄거리. {@code iso_639_1 == "en"} 항목 중 {@code iso_3166_1 == "US"}를 우선하고,
     * 없으면 첫 번째 영어 항목을 쓴다. 영어 항목이 없거나 줄거리가 비면 null (6-11).
     */
    public String englishOverview() {
        if (translations == null) {
            return null;
        }
        List<TmdbTranslations.Item> english = translations.translationsOrEmpty().stream()
                .filter(item -> "en".equals(item.iso6391()))
                .filter(item -> item.data() != null && item.data().overview() != null && !item.data().overview().isBlank())
                .toList();
        return english.stream()
                .filter(item -> "US".equals(item.iso31661()))
                .findFirst()
                .or(() -> english.stream().findFirst())
                .map(item -> item.data().overview())
                .orElse(null);
    }

    public List<TmdbKeywords.Item> keywordsOrEmpty() {
        return keywords == null ? List.of() : keywords.keywordsOrEmpty();
    }
}
