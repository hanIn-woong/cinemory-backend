package com.project.cinemory.domain.movie.service;

import com.project.cinemory.domain.collection.repository.CollectionMovieRepository;
import com.project.cinemory.domain.movie.dto.AdminMovieCreditsResponse;
import com.project.cinemory.domain.movie.dto.AdminMovieCreditsResponse.AdminCreditItem;
import com.project.cinemory.domain.movie.dto.AdminMovieDetailResponse;
import com.project.cinemory.domain.movie.dto.AdminMovieSort;
import com.project.cinemory.domain.movie.dto.AdminMovieSummaryResponse;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieActorRepository;
import com.project.cinemory.domain.movie.repository.MovieCountryRepository;
import com.project.cinemory.domain.movie.repository.MovieDirectorRepository;
import com.project.cinemory.domain.movie.repository.MovieGenreRepository;
import com.project.cinemory.domain.movie.repository.MovieKeywordRepository;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.recommendation.service.RecommendSafetyPolicy;
import com.project.cinemory.domain.review.repository.ReviewRepository;
import com.project.cinemory.domain.watch.repository.WatchRecordRepository;
import com.project.cinemory.domain.wish.repository.WishMovieRepository;
import com.project.cinemory.global.dto.LikePattern;
import com.project.cinemory.global.dto.PageResponse;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 관리자용 영화 조회(admin-spec 3-4~3-5). 앱의 검색 API({@code registered}/{@code suggestions}, 1-based)와
 * 계약이 달라 재사용하지 않는다. 단건 resync가 끝난 뒤 응답을 다시 읽을 때도 {@link #getMovie}를 쓴다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminMovieQueryService {

    private final MovieRepository movieRepository;
    private final MovieGenreRepository movieGenreRepository;
    private final MovieCountryRepository movieCountryRepository;
    private final MovieDirectorRepository movieDirectorRepository;
    private final MovieActorRepository movieActorRepository;
    private final WatchRecordRepository watchRecordRepository;
    private final ReviewRepository reviewRepository;
    private final WishMovieRepository wishMovieRepository;
    private final CollectionMovieRepository collectionMovieRepository;
    private final MovieKeywordRepository movieKeywordRepository;
    private final RecommendSafetyPolicy safetyPolicy;

    public PageResponse<AdminMovieSummaryResponse> getMovies(String q, Long tmdbId, AdminMovieSort sort,
                                                             int page, int size) {
        return PageResponse.from(movieRepository
                .searchForAdmin(LikePattern.contains(q), tmdbId, PageRequest.of(page, size, sort.toSort()))
                .map(AdminMovieSummaryResponse::from));
    }

    /**
     * 출연진·감독 편집 동선의 출발점(admin-spec 12-3, BD-8). 출연진은 앱 영화 상세가 보여 주는 범위(display_order ≤ 20)와
     * 같게 자른다. {@code movie_actor}·{@code movie_director}를 person과 함께 각 1쿼리로 읽는다(N+1 없음).
     */
    public AdminMovieCreditsResponse getCredits(Long movieId) {
        if (!movieRepository.existsById(movieId)) {
            throw new BusinessException(ErrorCode.MOVIE_NOT_FOUND);
        }
        List<AdminCreditItem> directors = movieDirectorRepository.findByMovieId(movieId).stream()
                .map(AdminCreditItem::from).toList();
        List<AdminCreditItem> cast = movieActorRepository
                .findByMovieIdAndDisplayOrderLessThanEqualOrderByDisplayOrderAsc(
                        movieId, MovieQueryService.DETAIL_CAST_MAX_DISPLAY_ORDER)
                .stream().map(AdminCreditItem::from).toList();
        return new AdminMovieCreditsResponse(directors, cast);
    }

    public AdminMovieDetailResponse getMovie(Long movieId) {
        Movie movie = movieRepository.findById(movieId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MOVIE_NOT_FOUND));

        List<String> genres = movieGenreRepository.findByMovieId(movieId).stream()
                .map(movieGenre -> movieGenre.getGenre().getName()).toList();
        List<String> countries = movieCountryRepository.findByMovieId(movieId).stream()
                .map(movieCountry -> movieCountry.getCountry().getName()).toList();
        List<String> directors = movieDirectorRepository.findByMovieId(movieId).stream()
                .map(movieDirector -> movieDirector.getPerson().displayName()).toList();

        AdminMovieDetailResponse.Usage usage = new AdminMovieDetailResponse.Usage(
                watchRecordRepository.countByMovieId(movieId),
                reviewRepository.countByMovieId(movieId),
                wishMovieRepository.countByMovieId(movieId),
                collectionMovieRepository.countByMovieId(movieId));

        return AdminMovieDetailResponse.of(movie, genres, countries, directors,
                movieActorRepository.countByMovieId(movieId), usage, recommendSafetyOf(movie));
    }

    /** 추천 제외 판정(M3b R-5-1) - 추천이 쓰는 {@link RecommendSafetyPolicy} 빈으로 계산해 화면과 실제 동작이 어긋나지 않게 한다. */
    private AdminMovieDetailResponse.RecommendSafety recommendSafetyOf(Movie movie) {
        List<Integer> genreTmdbIds = movieGenreRepository.findByMovieId(movie.getId()).stream()
                .map(movieGenre -> movieGenre.getGenre().getTmdbGenreId()).toList();
        List<String> keywordNames = movieKeywordRepository.findByMovieIdIn(List.of(movie.getId())).stream()
                .map(movieKeyword -> movieKeyword.getKeyword().getName()).toList();
        List<String> reasons = safetyPolicy.evaluate(new RecommendSafetyPolicy.Facts(
                        movie.getKrCertification(), movie.getRuntime(), movie.getVoteCount(), genreTmdbIds,
                        safetyPolicy.matchesBlockedKeyword(keywordNames)))
                .stream().map(Enum::name).toList();
        return new AdminMovieDetailResponse.RecommendSafety(!reasons.isEmpty(), reasons, movie.getRecommendOverride());
    }
}
