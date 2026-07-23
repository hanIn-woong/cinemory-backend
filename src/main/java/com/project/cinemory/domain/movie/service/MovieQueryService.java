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

        List<ActorResponse> actors = movieActorRepository.findByMovieIdOrderByRoleTierAsc(movieId).stream()
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
