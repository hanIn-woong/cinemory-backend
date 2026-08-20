package com.project.cinemory.global.infra.tmdb;

import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.tmdb.dto.TmdbCountryListItem;
import com.project.cinemory.global.infra.tmdb.dto.TmdbDiscoverResponse;
import com.project.cinemory.global.infra.tmdb.dto.TmdbGenreListResponse;
import com.project.cinemory.global.infra.tmdb.dto.TmdbMovieDetailResponse;
import com.project.cinemory.global.infra.tmdb.dto.TmdbSearchResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * TMDB API 호출 클라이언트. {@code KoficClient}와 같은 골격이다.
 *
 * <p>호출 실패는 전부 {@link ErrorCode#EXTERNAL_API_ERROR}로 감싸 던진다.
 * 배치·시드 진입점에서 잡아 로깅만 하고 삼키는 책임 분리는 4-7 KOFIC과 동일하다.
 *
 * <p>모든 요청에 {@code language=ko-KR}을 붙인다. 한국어 제목·줄거리가 기본이며,
 * 비어 오는 경우의 폴백은 동기화 서비스(6-4)가 맡는다.
 *
 * <p><b>429 처리 (6-5)</b> — 선제 {@code sleep}은 두지 않는다. 순차 호출은 왕복 지연만으로
 * 초당 3~10회이고 TMDB의 약 50 req/s 제한에 자연히 못 미친다. 대신 429를 받으면
 * {@link #executeWithRetry(Supplier)}가 반응형으로 백오프한다. 재시도 대상은 429뿐이다 —
 * 5xx까지 넓히면 TMDB 장애 시 시드가 하염없이 길어진다.
 */
@Slf4j
@Component
public class TmdbClient {

    /** 지역화 기준. 프로퍼티로 빼지 않은 이유는 한국 사용자 대상 서비스라 가변 축이 아니기 때문이다. */
    private static final String LANGUAGE = "ko-KR";

    private static final int MAX_RETRY_COUNT = 3;
    private static final long INITIAL_BACKOFF_MILLIS = 1000L;

    private static final ParameterizedTypeReference<List<TmdbCountryListItem>> COUNTRY_LIST =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public TmdbClient(RestClient tmdbRestClient) {
        this.restClient = tmdbRestClient;
    }

    /**
     * 영화 장르 목록 조회 (참조 테이블 선행 적재용).
     *
     * @return 비어 있을 수 있으나 null은 아니다
     */
    public List<TmdbGenreListResponse.Item> fetchMovieGenres() {
        try {
            TmdbGenreListResponse response = executeWithRetry(() -> restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/genre/movie/list")
                            .queryParam("language", LANGUAGE)
                            .build())
                    .retrieve()
                    .body(TmdbGenreListResponse.class));

            if (response == null) {
                throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR, "TMDB 장르 목록 응답이 비어 있습니다.");
            }
            return response.items();

        } catch (RestClientException e) {
            log.error("TMDB 장르 목록 조회 실패", e);
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR, "TMDB 장르 목록 조회에 실패했습니다.");
        }
    }

    /**
     * 국가 목록 조회 (참조 테이블 선행 적재용).
     *
     * <p>⚠️ 이 엔드포인트만 <b>루트 레벨 배열</b>을 반환한다. 래퍼 record가 없으므로
     * {@code ParameterizedTypeReference}로 받는다.
     *
     * @return 비어 있을 수 있으나 null은 아니다
     */
    public List<TmdbCountryListItem> fetchCountries() {
        try {
            List<TmdbCountryListItem> response = executeWithRetry(() -> restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/configuration/countries")
                            .queryParam("language", LANGUAGE)
                            .build())
                    .retrieve()
                    .body(COUNTRY_LIST));

            return response == null ? List.of() : response;

        } catch (RestClientException e) {
            log.error("TMDB 국가 목록 조회 실패", e);
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR, "TMDB 국가 목록 조회에 실패했습니다.");
        }
    }

    /**
     * 영화 상세 + 크레딧 조회 ({@code MovieSyncService.syncFromTmdb} 진입점).
     *
     * <p>{@code append_to_response=credits}로 상세와 출연진을 1회 호출에 묶는다 (6-2).
     *
     * <p>404는 다른 실패와 구분한다 — {@code POST /api/movies/sync}는 사용자가 임의의
     * {@code tmdbId}를 보내는 경로라 잘못된 값이 4xx로 응답돼야 한다. 그 밖의 실패는
     * {@code EXTERNAL_API_ERROR}(502)로 감싼다.
     *
     * @throws BusinessException {@code TMDB_MOVIE_NOT_FOUND}(404), {@code TMDB_RATE_LIMITED}(429)
     *                            또는 {@code EXTERNAL_API_ERROR}
     */
    public TmdbMovieDetailResponse fetchMovieDetail(Long tmdbId) {
        try {
            TmdbMovieDetailResponse response = executeWithRetry(() -> restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/movie/{tmdbId}")
                            .queryParam("append_to_response", "credits")
                            .queryParam("language", LANGUAGE)
                            .build(tmdbId))
                    .retrieve()
                    .body(TmdbMovieDetailResponse.class));

            if (response == null) {
                throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR, "TMDB 영화 상세 응답이 비어 있습니다.");
            }
            return response;

        } catch (HttpClientErrorException.NotFound e) {
            throw new BusinessException(ErrorCode.TMDB_MOVIE_NOT_FOUND);
        } catch (RestClientException e) {
            log.error("TMDB 영화 상세 조회 실패. tmdbId={}", tmdbId, e);
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR, "TMDB 영화 상세 조회에 실패했습니다.");
        }
    }

    /**
     * 영화 검색 (온디맨드 동기화 / 박스오피스 역방향 시드 6-5).
     *
     * <p>{@code year}는 개봉연도로 동명이인 오탐을 줄인다 — null이면 붙이지 않는다.
     * TMDB 기본값 {@code include_adult=false}라 성인 영화는 결과에서 자연히 빠진다.
     *
     * @return 비어 있을 수 있으나 null은 아니다
     */
    public TmdbSearchResponse searchMovie(String query, Integer year) {
        try {
            TmdbSearchResponse response = executeWithRetry(() -> restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/search/movie")
                            .queryParam("query", query)
                            .queryParam("language", LANGUAGE)
                            .queryParamIfPresent("year", Optional.ofNullable(year))
                            .build())
                    .retrieve()
                    .body(TmdbSearchResponse.class));

            return response == null ? new TmdbSearchResponse(List.of()) : response;

        } catch (RestClientException e) {
            log.error("TMDB 영화 검색 실패. query={}, year={}", query, year, e);
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR, "TMDB 영화 검색에 실패했습니다.");
        }
    }

    /**
     * {@code region=KR} 인기작 discover 조회 (시드 보충 경로 6-5).
     *
     * <p>페이지당 20편이 기본이다. {@code totalPages}로 상한을 알 수 있어 호출부가
     * 존재하지 않는 페이지를 두드리지 않게 한다.
     */
    public TmdbDiscoverResponse discoverMovies(int page) {
        try {
            TmdbDiscoverResponse response = executeWithRetry(() -> restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/discover/movie")
                            .queryParam("region", "KR")
                            .queryParam("sort_by", "popularity.desc")
                            .queryParam("language", LANGUAGE)
                            .queryParam("page", page)
                            .build())
                    .retrieve()
                    .body(TmdbDiscoverResponse.class));

            if (response == null) {
                throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR, "TMDB discover 응답이 비어 있습니다.");
            }
            return response;

        } catch (RestClientException e) {
            log.error("TMDB discover 조회 실패. page={}", page, e);
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR, "TMDB discover 조회에 실패했습니다.");
        }
    }

    /**
     * 429를 반응형으로 흡수하는 공통 재시도 루프. 호출부가 셋(참조 시드·영화 시드·온디맨드)이라
     * 각자 구현하면 중복이므로 여기 한 곳으로 접었다.
     *
     * <p>{@code Retry-After} 헤더가 있으면 그 값만큼, 없으면 1s → 2s → 4s 지수 백오프로 대기한다.
     * 최대 {@value #MAX_RETRY_COUNT}회 재시도 후에도 429면 {@code TMDB_RATE_LIMITED}를 던진다 —
     * 호출부가 이를 {@code EXTERNAL_API_ERROR}와 구분해 시드 루프를 멈춰야 한다(IP 차단 방지).
     *
     * <p>이 메서드는 429만 잡는다. {@code NotFound} 등 다른 {@code HttpClientErrorException}은
     * 그대로 통과시켜 호출부의 기존 catch 블록이 처리한다.
     */
    private <T> T executeWithRetry(Supplier<T> call) {
        int retryCount = 0;
        long backoffMillis = INITIAL_BACKOFF_MILLIS;
        while (true) {
            try {
                return call.get();
            } catch (HttpClientErrorException.TooManyRequests e) {
                retryCount++;
                if (retryCount > MAX_RETRY_COUNT) {
                    log.warn("TMDB 429 재시도 {}회 소진, 중단합니다.", MAX_RETRY_COUNT);
                    throw new BusinessException(ErrorCode.TMDB_RATE_LIMITED);
                }
                long waitMillis = resolveRetryAfterMillis(e, backoffMillis);
                log.warn("TMDB 429 수신. {}ms 대기 후 재시도 ({}/{})", waitMillis, retryCount, MAX_RETRY_COUNT);
                sleep(waitMillis);
                backoffMillis *= 2;
            }
        }
    }

    private long resolveRetryAfterMillis(HttpClientErrorException.TooManyRequests e, long fallbackMillis) {
        String retryAfter = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
        if (retryAfter == null) {
            return fallbackMillis;
        }
        try {
            return Long.parseLong(retryAfter.trim()) * 1000L;
        } catch (NumberFormatException ex) {
            return fallbackMillis;
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR, "TMDB 재시도 대기 중 인터럽트되었습니다.");
        }
    }
}
