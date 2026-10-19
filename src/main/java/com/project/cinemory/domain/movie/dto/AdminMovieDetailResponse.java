package com.project.cinemory.domain.movie.dto;

import com.project.cinemory.domain.movie.entity.Movie;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 관리자 영화 상세(admin-spec 3-5) — 목록 필드 + 줄거리·분류·사용 지표. 출연진은 개수만 싣는다. */
public record AdminMovieDetailResponse(
        Long movieId,
        Long tmdbId,
        String koficMovieCd,
        String title,
        String titleKo,
        String originalTitle,
        LocalDate releaseDate,
        String posterPath,
        BigDecimal voteAverage,
        Integer voteCount,
        LocalDateTime createdAt,
        LocalDateTime syncedAt,
        String overview,
        Integer runtime,
        String backdropPath,
        List<String> genres,
        List<String> countries,
        List<String> directors,
        long castCount,
        Usage usage
) {

    /** 이 영화를 참조하는 사용자 데이터 수 — 재동기화·정리 판단 근거. */
    public record Usage(long watchRecordCount, long reviewCount, long wishCount, long collectionCount) {
    }

    public static AdminMovieDetailResponse of(Movie movie, List<String> genres, List<String> countries,
                                              List<String> directors, long castCount, Usage usage) {
        return new AdminMovieDetailResponse(movie.getId(), movie.getTmdbId(), movie.getKoficMovieCd(),
                movie.getTitle(), movie.getTitleKo(), movie.getOriginalTitle(), movie.getReleaseDate(), movie.getPosterPath(),
                movie.getVoteAverage(), movie.getVoteCount(), movie.getCreatedAt(), movie.getSyncedAt(),
                movie.getOverview(), movie.getRuntime(), movie.getBackdropPath(),
                genres, countries, directors, castCount, usage);
    }
}
