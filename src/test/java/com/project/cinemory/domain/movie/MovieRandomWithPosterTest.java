package com.project.cinemory.domain.movie;

import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 게스트 홈 배경용 랜덤 표본({@code findRandomWithPoster}) — 네이티브 쿼리라 {@code @Formula} 필드({@code Movie.sortTitle}, V29)가
 * 결과 컬럼에 없으면 엔티티 매핑이 "Column 'sorttitle' not found"로 실패해 홈 배경 API가 500이 된다.
 * 다른 영화 쿼리는 JPQL이라 이 쿼리만 걸렸고, 기존 테스트에는 이 쿼리를 부르는 곳이 없어 놓쳤다.
 */
@SpringBootTest
@Transactional
class MovieRandomWithPosterTest {

    @Autowired
    private MovieRepository movieRepository;
    @Autowired
    private EntityManager em;

    @Test
    void 포스터가_있는_영화만_엔티티로_매핑해_돌려준다() {
        movieRepository.save(Movie.builder().tmdbId(97_701L).title("zqt Poster Movie").posterPath("/poster.jpg").build());
        movieRepository.save(Movie.builder().tmdbId(97_702L).title("zqt No Poster Movie").build());
        em.flush();
        em.clear();

        List<Movie> result = movieRepository.findRandomWithPoster(1000);

        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(m -> assertThat(m.getPosterPath()).isNotNull());
        assertThat(result).extracting(Movie::getTmdbId).contains(97_701L).doesNotContain(97_702L);
    }

    @Test
    void 한글_제목이_있으면_정렬_제목에_반영된다() {
        Movie movie = movieRepository.save(Movie.builder().tmdbId(97_703L).title("zqt Foreign").posterPath("/p.jpg").build());
        movie.changeTitleKo("제크 한글");
        em.flush();
        em.clear();

        Movie loaded = movieRepository.findRandomWithPoster(1000).stream()
                .filter(m -> m.getTmdbId().equals(97_703L)).findFirst().orElseThrow();

        assertThat(loaded.getSortTitle()).isEqualTo("제크 한글");   // @Formula와 같은 COALESCE(title_ko, title)
    }
}
