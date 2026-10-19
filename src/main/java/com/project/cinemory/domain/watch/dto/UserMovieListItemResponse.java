package com.project.cinemory.domain.watch.dto;

import com.project.cinemory.domain.movie.dto.CountryResponse;
import com.project.cinemory.domain.movie.dto.GenreResponse;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.watch.entity.WatchRecord;
import com.project.cinemory.domain.watch.entity.WatchType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record UserMovieListItemResponse(
        Long movieId,
        String title,
        String posterPath,
        LocalDate releaseDate,
        List<GenreResponse> genres,
        List<CountryResponse> countries,
        LocalDate watchDate,
        BigDecimal rating,
        WatchType watchType
) {

    public static UserMovieListItemResponse from(WatchRecord watchRecord, List<GenreResponse> genres, List<CountryResponse> countries) {
        return from(watchRecord, genres, countries, watchRecord);
    }

    /**
     * 최근 관람일순일 때만 카드에 보일 날짜를 {@code latest}(그 영화의 최근 기록)에서 가져온다(2026-10-18) — 정렬 기준과 보이는 날짜를
     * 맞추려는 것이다. 다른 정렬에서는 호출하는 쪽이 대표 기록을 넘긴다. ⚠️ 별점·관람 방식은 대표 기록 그대로다 — 별점순 정렬·별점 필터가
     * 대표 기준이라, 카드 별점도 같은 기준이어야 필터를 쓸 때 헷갈리지 않는다(사용자 결정).
     */
    public static UserMovieListItemResponse from(WatchRecord watchRecord, List<GenreResponse> genres, List<CountryResponse> countries,
                                                 WatchRecord latest) {
        Movie movie = watchRecord.getMovie();
        return new UserMovieListItemResponse(
                movie.getId(),
                movie.displayTitle(),
                movie.getPosterPath(),
                movie.getReleaseDate(),
                genres,
                countries,
                latest.getWatchDate(),
                watchRecord.getRating(),
                watchRecord.getWatchType()
        );
    }
}
