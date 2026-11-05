package com.project.cinemory.global.infra.tmdb.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * {@code GET /movie/{id}/images} 응답(admin-spec 18-2) - 포스터만 읽는다. {@code iso_639_1}이 null이면 글자 없는 포스터다.
 */
public record TmdbMovieImagesResponse(List<Poster> posters) {

    public List<Poster> postersOrEmpty() {
        return posters == null ? List.of() : posters;
    }

    public record Poster(
            @JsonProperty("file_path") String filePath,
            @JsonProperty("iso_639_1") String language,
            Integer width,
            Integer height,
            @JsonProperty("vote_average") Double voteAverage
    ) {
    }
}
