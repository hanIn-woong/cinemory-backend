package com.project.cinemory.domain.watch.dto;

import com.project.cinemory.domain.movie.dto.CountryResponse;
import com.project.cinemory.domain.movie.dto.GenreResponse;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.watch.entity.WatchRecord;
import com.project.cinemory.domain.watch.entity.WatchType;

import java.time.LocalDate;
import java.util.List;

public record MyMovieListItemResponse(
        Long movieId,
        String title,
        String posterPath,
        LocalDate releaseDate,
        List<GenreResponse> genres,
        List<CountryResponse> countries,
        LocalDate watchDate,
        Double rating,
        WatchType watchType
) {

    public static MyMovieListItemResponse from(WatchRecord watchRecord, List<GenreResponse> genres, List<CountryResponse> countries) {
        Movie movie = watchRecord.getMovie();
        return new MyMovieListItemResponse(
                movie.getId(),
                movie.getTitle(),
                movie.getPosterPath(),
                movie.getReleaseDate(),
                genres,
                countries,
                watchRecord.getWatchDate(),
                watchRecord.getRating(),
                watchRecord.getWatchType()
        );
    }
}
