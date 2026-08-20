package com.project.cinemory.domain.movie.service;

import com.project.cinemory.domain.movie.dto.ActorResponse;
import com.project.cinemory.domain.movie.dto.CountryResponse;
import com.project.cinemory.domain.movie.dto.DirectorResponse;
import com.project.cinemory.domain.movie.dto.GenreResponse;
import com.project.cinemory.domain.movie.dto.MovieDetailResponse;
import com.project.cinemory.domain.movie.dto.MovieListItemResponse;
import com.project.cinemory.domain.movie.dto.MovieSummaryResponse;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieActorRepository;
import com.project.cinemory.domain.movie.repository.MovieCountryRepository;
import com.project.cinemory.domain.movie.repository.MovieDirectorRepository;
import com.project.cinemory.domain.movie.repository.MovieGenreRepository;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MovieQueryService {

    /**
     * 상세 응답에 싣는 출연진 상한(`displayOrder` 기준, 0부터이므로 21명).
     *
     * <p>cast를 자르지 않고 전량 저장하므로 전체를 내려보내면 영화당 수백 행이 된다.
     * MINOR 구간의 끝(20)과 일치시켜 "가중치를 갖는 출연진"까지만 노출한다.
     * 전체 출연진 엔드포인트는 controller-layer-spec 잔여 #10.
     */
    private static final int DETAIL_CAST_MAX_DISPLAY_ORDER = 20;

    private final MovieRepository movieRepository;
    private final MovieGenreRepository movieGenreRepository;
    private final MovieCountryRepository movieCountryRepository;
    private final MovieActorRepository movieActorRepository;
    private final MovieDirectorRepository movieDirectorRepository;

    public MovieDetailResponse getMovieDetail(Long movieId) {
        Movie movie = movieRepository.findById(movieId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MOVIE_NOT_FOUND));

        List<GenreResponse> genres = movieGenreRepository.findByMovieId(movieId).stream()
                .map(movieGenre -> GenreResponse.from(movieGenre.getGenre()))
                .toList();

        List<CountryResponse> countries = movieCountryRepository.findByMovieId(movieId).stream()
                .map(movieCountry -> CountryResponse.from(movieCountry.getCountry()))
                .toList();

        List<ActorResponse> actors = movieActorRepository
                .findByMovieIdAndDisplayOrderLessThanEqualOrderByDisplayOrderAsc(
                        movieId, DETAIL_CAST_MAX_DISPLAY_ORDER)
                .stream()
                .map(ActorResponse::from)
                .toList();

        List<DirectorResponse> directors = movieDirectorRepository.findByMovieId(movieId).stream()
                .map(movieDirector -> DirectorResponse.from(movieDirector.getPerson()))
                .toList();

        return MovieDetailResponse.from(movie, genres, countries, actors, directors);
    }

    public Page<MovieListItemResponse> getMovieList(Pageable pageable) {
        Page<Movie> moviePage = movieRepository.findAll(pageable);
        List<Long> movieIds = moviePage.getContent().stream().map(Movie::getId).toList();

        Map<Long, List<GenreResponse>> genresByMovieId = movieGenreRepository.findByMovieIdIn(movieIds).stream()
                .collect(Collectors.groupingBy(
                        movieGenre -> movieGenre.getMovie().getId(),
                        Collectors.mapping(movieGenre -> GenreResponse.from(movieGenre.getGenre()), Collectors.toList())
                ));

        Map<Long, List<CountryResponse>> countriesByMovieId = movieCountryRepository.findByMovieIdIn(movieIds).stream()
                .collect(Collectors.groupingBy(
                        movieCountry -> movieCountry.getMovie().getId(),
                        Collectors.mapping(movieCountry -> CountryResponse.from(movieCountry.getCountry()), Collectors.toList())
                ));

        return moviePage.map(movie -> MovieListItemResponse.from(
                movie,
                genresByMovieId.getOrDefault(movie.getId(), List.of()),
                countriesByMovieId.getOrDefault(movie.getId(), List.of())
        ));
    }

    public Page<MovieSummaryResponse> searchMovies(Pageable pageable) {
        return movieRepository.findAll(pageable).map(MovieSummaryResponse::from);
    }
}
