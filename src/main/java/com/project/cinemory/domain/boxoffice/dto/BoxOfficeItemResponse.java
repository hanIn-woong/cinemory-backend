package com.project.cinemory.domain.boxoffice.dto;

import com.project.cinemory.domain.boxoffice.entity.BoxOfficeRecord;
import com.project.cinemory.domain.movie.entity.Movie;

/**
 * 박스오피스 순위 1건.
 *
 * <p>TMDB 매칭에 실패한 레코드도 목록에 노출한다. 포스터가 없는 항목이 섞이는 것을 감수하더라도
 * 순위 목록에 구멍이 나는 편이 더 나쁘기 때문이다. 클라이언트는 {@code linked}로 상세 화면
 * 링크의 활성 여부를 판단한다.
 */
public record BoxOfficeItemResponse(
        int rank,
        Integer rankChange,
        boolean isNew,
        String movieTitle,
        Long movieId,
        String posterPath,
        int audienceCount,
        long audienceAcc,
        long salesAmount,
        boolean linked
) {

    public static BoxOfficeItemResponse from(BoxOfficeRecord boxOffice) {
        Movie movie = boxOffice.getMovie();

        return new BoxOfficeItemResponse(
                boxOffice.getBoxOfficeRank(),
                boxOffice.getRankChange(),
                boxOffice.isNew(),
                boxOffice.getMovieTitleSnapshot(),
                movie != null ? movie.getId() : null,
                movie != null ? movie.getPosterPath() : null,
                boxOffice.getAudienceCount(),
                boxOffice.getAudienceAcc(),
                boxOffice.getSalesAmount(),
                movie != null
        );
    }
}
