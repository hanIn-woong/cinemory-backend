package com.project.cinemory.domain.movie.service;

import com.project.cinemory.domain.movie.dto.AdminMovieDetailResponse;
import com.project.cinemory.domain.movie.dto.AdminMoviePostersResponse;
import com.project.cinemory.domain.movie.dto.AdminMoviePosterUnpinResponse;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.tmdb.TmdbClient;
import com.project.cinemory.global.infra.tmdb.dto.TmdbMovieImagesResponse;
import com.project.cinemory.global.security.resolver.AuditActor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 포스터 후보 조회·고정·해제(admin-spec 18). TMDB 호출이 끼므로 <b>이 클래스는 트랜잭션을 열지 않는다</b> -
 * 외부 호출 동안 DB 커넥션을 쥐지 않도록 DB 쓰기와 감사 로그는 {@link AdminMovieCommandService}의 짧은 트랜잭션에 맡긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminMoviePosterService {

    static final int MAX_CANDIDATES = 50;

    private final MovieRepository movieRepository;
    private final TmdbClient tmdbClient;
    private final AdminMovieCommandService adminMovieCommandService;
    private final AdminMovieQueryService adminMovieQueryService;
    private final MovieSeedService movieSeedService;

    public AdminMoviePostersResponse getPosters(Long movieId) {
        Movie movie = findMovie(movieId);
        List<AdminMoviePostersResponse.Candidate> candidates = sortedPosters(movie.getTmdbId()).stream()
                .limit(MAX_CANDIDATES)
                .map(AdminMoviePostersResponse.Candidate::from)
                .toList();
        return new AdminMoviePostersResponse(movie.getPosterPath(), movie.getPosterPathPinned(), candidates);
    }

    public AdminMovieDetailResponse pin(Long movieId, String filePath, AuditActor actor) {
        Movie movie = findMovie(movieId);
        // 같은 값 재고정은 멱등 - TMDB를 부르지 않는다
        if (!Objects.equals(movie.getPosterPathPinned(), filePath)) {
            // DD-6: 후보를 다시 조회해 대조한다. 후보 노출(getPosters)과 같은 50장 범위가 아니라 TMDB가 준 전체 목록과 비교한다
            boolean known = sortedPosters(movie.getTmdbId()).stream().anyMatch(p -> filePath.equals(p.filePath()));
            if (!known) {
                throw new BusinessException(ErrorCode.INVALID_POSTER_PATH);
            }
        }
        return adminMovieCommandService.pinPoster(movieId, filePath, actor);
    }

    public AdminMoviePosterUnpinResponse unpin(Long movieId, AuditActor actor) {
        findMovie(movieId);
        adminMovieCommandService.unpinPoster(movieId, actor);

        // 고정을 푼 뒤 TMDB 기본 포스터를 즉시 되돌린다. 일괄 작업 중(409)이거나 TMDB 쪽 사정으로 실패해도
        // 해제는 이미 끝났으므로 실패로 돌려주지 않고 resynced=false로 알린다(다음 동기화 때 돌아온다).
        boolean resynced;
        try {
            movieSeedService.resyncOne(movieId);
            resynced = true;
        } catch (BusinessException e) {
            log.warn("포스터 고정 해제 뒤 재동기화를 건너뜁니다. movieId={}, code={}", movieId, e.getErrorCode());
            resynced = false;
        }
        return new AdminMoviePosterUnpinResponse(adminMovieQueryService.getMovie(movieId), resynced);
    }

    /** 한국어 → 언어 없음 → 영어 순, 같은 언어 안에서는 평점 내림차순. */
    private List<TmdbMovieImagesResponse.Poster> sortedPosters(Long tmdbId) {
        return tmdbClient.fetchMovieImages(tmdbId).postersOrEmpty().stream()
                .filter(p -> p.filePath() != null)
                .sorted(Comparator.<TmdbMovieImagesResponse.Poster>comparingInt(p -> languageRank(p.language()))
                        .thenComparing(p -> p.voteAverage() == null ? 0.0 : p.voteAverage(), Comparator.reverseOrder()))
                .toList();
    }

    private static int languageRank(String language) {
        if ("ko".equals(language)) {
            return 0;
        }
        return language == null ? 1 : 2;
    }

    private Movie findMovie(Long movieId) {
        return movieRepository.findById(movieId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MOVIE_NOT_FOUND));
    }
}
