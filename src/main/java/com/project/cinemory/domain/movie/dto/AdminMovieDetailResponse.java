package com.project.cinemory.domain.movie.dto;

import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.entity.RecommendOverride;

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
        String posterPathPinned,
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
        Usage usage,
        RecommendSafety recommendSafety
) {

    /** 이 영화를 참조하는 사용자 데이터 수 — 재동기화·정리 판단 근거. */
    public record Usage(long watchRecordCount, long reviewCount, long wishCount, long collectionCount) {
    }

    /**
     * 추천 제외 판정(M3b R-5-1) - 자동 판정 결과와 덮어쓰기. 최종 제외 = 덮어쓰기가 있으면 그 값, 없으면 자동 판정.
     *
     * @param autoBlocked 자동 판정이 막았는가
     * @param reasons     막은 사유 - {@code KEYWORD} · {@code SHAPE_RULE} · {@code RESTRICTED}. 통과면 빈 배열
     * @param override    관리자 덮어쓰기. 없으면 null
     */
    public record RecommendSafety(boolean autoBlocked, List<String> reasons, RecommendOverride override) {
    }

    public static AdminMovieDetailResponse of(Movie movie, List<String> genres, List<String> countries,
                                              List<String> directors, long castCount, Usage usage,
                                              RecommendSafety recommendSafety) {
        return new AdminMovieDetailResponse(movie.getId(), movie.getTmdbId(), movie.getKoficMovieCd(),
                movie.getTitle(), movie.getTitleKo(), movie.getOriginalTitle(), movie.getReleaseDate(), movie.getPosterPath(),
                movie.getPosterPathPinned(), movie.getVoteAverage(), movie.getVoteCount(), movie.getCreatedAt(), movie.getSyncedAt(),
                movie.getOverview(), movie.getRuntime(), movie.getBackdropPath(),
                genres, countries, directors, castCount, usage, recommendSafety);
    }
}
