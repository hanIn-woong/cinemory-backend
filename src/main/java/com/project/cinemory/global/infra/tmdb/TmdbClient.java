package com.project.cinemory.global.infra.tmdb;

import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.tmdb.dto.TmdbCountryListItem;
import com.project.cinemory.global.infra.tmdb.dto.TmdbGenreListResponse;
import com.project.cinemory.global.infra.tmdb.dto.TmdbMovieDetailResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * TMDB API 호출 클라이언트. {@code KoficClient}와 같은 골격이다.
 *
 * <p>호출 실패는 전부 {@link ErrorCode#EXTERNAL_API_ERROR}로 감싸 던진다.
 * 배치·시드 진입점에서 잡아 로깅만 하고 삼키는 책임 분리는 4-7 KOFIC과 동일하다.
 *
 * <p>모든 요청에 {@code language=ko-KR}을 붙인다. 한국어 제목·줄거리가 기본이며,
 * 비어 오는 경우의 폴백은 동기화 서비스(6-4)가 맡는다.
 */
@Slf4j
@Component
public class TmdbClient {

    /** 지역화 기준. 프로퍼티로 빼지 않은 이유는 한국 사용자 대상 서비스라 가변 축이 아니기 때문이다. */
    private static final String LANGUAGE = "ko-KR";

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
            TmdbGenreListResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/genre/movie/list")
                            .queryParam("language", LANGUAGE)
                            .build())
                    .retrieve()
                    .body(TmdbGenreListResponse.class);

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
            List<TmdbCountryListItem> response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/configuration/countries")
                            .queryParam("language", LANGUAGE)
                            .build())
                    .retrieve()
                    .body(COUNTRY_LIST);

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
     * @throws BusinessException {@code TMDB_MOVIE_NOT_FOUND}(404) 또는 {@code EXTERNAL_API_ERROR}
     */
    public TmdbMovieDetailResponse fetchMovieDetail(Long tmdbId) {
        try {
            TmdbMovieDetailResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/movie/{tmdbId}")
                            .queryParam("append_to_response", "credits")
                            .queryParam("language", LANGUAGE)
                            .build(tmdbId))
                    .retrieve()
                    .body(TmdbMovieDetailResponse.class);

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
}
