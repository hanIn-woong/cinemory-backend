package com.project.cinemory.domain.movie.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.project.cinemory.domain.movie.dto.WatchProvidersResponse;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.tmdb.TmdbClient;
import com.project.cinemory.global.infra.tmdb.dto.TmdbWatchProviders;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 어디서 볼 수 있나(service-layer-spec 4-2-B ③). 저장하지 않는다 - 제공처가 수시로 바뀌어 저장하면 틀려진다.
 * 대신 영화별로 24시간 캐시한다(Caffeine, 최대 5,000개). 다른 인메모리 캐시({@code OAuthNonceService} 등)와 같이
 * Caffeine을 직접 쓴다 - 스프링 캐시 추상화를 켜지 않는다.
 *
 * <p>정책: ① <b>빈 결과도 캐시한다</b>(KR 제공처가 없는 영화를 계속 묻지 않게). ② TMDB 오류·타임아웃은
 * <b>200 + 빈 응답 + WARN</b>이고 <b>캐시하지 않는다</b> - 장애가 풀리면 다음 호출이 바로 다시 TMDB로 간다.
 * 그래서 {@code Cache.get(key, loader)}가 아니라 성공했을 때만 {@code put}한다.
 * ③ 트랜잭션을 열지 않는다 - 영화 조회는 짧은 자체 트랜잭션이고 TMDB 호출 동안 DB 커넥션을 쥐지 않는다.
 */
@Slf4j
@Service
public class MovieWatchProviderService {

    private static final String REGION = "KR";

    private final MovieRepository movieRepository;
    private final TmdbClient tmdbClient;
    private final Cache<Long, WatchProvidersResponse> cache;

    public MovieWatchProviderService(MovieRepository movieRepository, TmdbClient tmdbClient) {
        this.movieRepository = movieRepository;
        this.tmdbClient = tmdbClient;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofHours(24))
                .maximumSize(5_000)
                .build();
    }

    public WatchProvidersResponse getWatchProviders(Long movieId) {
        WatchProvidersResponse cached = cache.getIfPresent(movieId);
        if (cached != null) {
            return cached;
        }

        Movie movie = movieRepository.findById(movieId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MOVIE_NOT_FOUND));
        if (movie.getTmdbId() == null) {
            // TMDB에 묻을 수 없는 영화(KOFIC만 있는 영화) - 제공처 정보가 없는 것이 정상이다
            return remember(movieId, WatchProvidersResponse.empty());
        }

        TmdbWatchProviders fetched;
        try {
            fetched = tmdbClient.fetchWatchProviders(movie.getTmdbId());
        } catch (RuntimeException e) {
            // 상세의 다른 부분과 무관하다 - 검색의 TMDB 폴백(6-8)과 같은 원칙. 실패는 캐시하지 않는다.
            log.warn("TMDB 시청 제공처 조회 실패로 빈 응답을 돌려줍니다. movieId={}, tmdbId={}", movieId, movie.getTmdbId(), e);
            return WatchProvidersResponse.empty();
        }
        return remember(movieId, WatchProvidersResponse.from(fetched.regionOrNull(REGION)));
    }

    private WatchProvidersResponse remember(Long movieId, WatchProvidersResponse response) {
        cache.put(movieId, response);
        return response;
    }
}
