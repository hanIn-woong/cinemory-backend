package com.project.cinemory.domain.movie.dto;

import com.project.cinemory.domain.movie.entity.KrCertification;
import com.project.cinemory.domain.movie.entity.Movie;

import java.time.LocalDate;
import java.util.List;

public record MovieDetailResponse(
        Long id,
        Long tmdbId,
        String koficMovieCd,
        String title,
        // 원제(nullable, service-layer-spec 4-2 · 2026-11-01). `title`은 한글 제목이 있으면 그것이라, 두 값이 다를 때만 화면에 보인다.
        String originalTitle,
        String posterPath,
        LocalDate releaseDate,
        String overview,
        Integer runtime,
        List<GenreResponse> genres,
        List<CountryResponse> countries,
        List<ActorResponse> actors,
        List<DirectorResponse> directors,
        MovieRatingsResponse ratings,
        // 4-2-B 보강 - 한국 관람등급(nullable, 국내 미개봉 외화 등은 null), 제작사 상위 3, 개봉 중일 때만 누적 관객
        KrCertification krCertification,
        List<CompanyResponse> companies,
        BoxOfficeSnapshotResponse boxOffice
) {

    public static MovieDetailResponse from(Movie movie, List<GenreResponse> genres, List<CountryResponse> countries,
                                            List<ActorResponse> actors, List<DirectorResponse> directors,
                                            MovieRatingsResponse ratings, List<CompanyResponse> companies,
                                            BoxOfficeSnapshotResponse boxOffice) {
        return new MovieDetailResponse(
                movie.getId(),
                movie.getTmdbId(),
                movie.getKoficMovieCd(),
                movie.displayTitle(),
                movie.getOriginalTitle(),
                movie.getPosterPath(),
                movie.getReleaseDate(),
                movie.getOverview(),
                movie.getRuntime(),
                genres,
                countries,
                actors,
                directors,
                ratings,
                movie.getKrCertification(),
                companies,
                boxOffice
        );
    }
}
