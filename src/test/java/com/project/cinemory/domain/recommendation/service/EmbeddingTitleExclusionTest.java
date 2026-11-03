package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.recommendation.service.EmbeddingInputLoader.Prepared;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 임베딩 문서에서 제목 제거 (M3b R-5-2) - DB의 영화에서 실제 로더를 거쳐 조립한 문서에 표시 제목(한글 제목)·TMDB 제목·원제가
 * 접두어({@code title:})에도 본문({@code text:})에도 들어 있지 않아야 한다. "가을에 보기 좋은 영화"에 제목에 '가을'이 든 영화가
 * 최상단에 오르던 원인이 제목 신호였다.
 */
@SpringBootTest
@Transactional
class EmbeddingTitleExclusionTest {

    @Autowired MovieRepository movieRepository;
    @Autowired EmbeddingInputLoader loader;
    @Autowired EntityManager em;

    @Test
    void 생성된_문서에_표시제목_제목_원제가_들어_있지_않다() {
        Movie movie = movieRepository.save(Movie.builder().tmdbId(95_501L).title("가을의 전설")
                .originalTitle("Legends of the Fall").releaseDate(LocalDate.of(1994, 12, 23)).runtime(133)
                .tagline("거친 시대를 지나는 형제들의 이야기").overview("몬태나 목장에서 자란 세 형제와 한 여인의 이야기.")
                .voteAverage(BigDecimal.valueOf(7.4)).voteCount(3_000).build());
        movie.changeTitleKo("가을의 전설 (한글 제목)");
        em.flush();
        em.clear();

        Prepared prepared = loader.load(movie.getId() - 1, 1).get(0);

        String document = prepared.text().sourceText();
        assertThat(prepared.movieId()).isEqualTo(movie.getId());
        assertThat(document).startsWith("title: none | text: ");
        assertThat(document).doesNotContain("가을의 전설").doesNotContain("Legends of the Fall");
        assertThat(prepared.text().title()).isEqualTo("none");
        assertThat(prepared.text().body()).doesNotContain("가을의 전설").doesNotContain("Legends of the Fall");
        // 내용 신호는 그대로 남는다
        assertThat(document).contains("개봉: 1994년").contains("한 줄 소개: 거친 시대를 지나는 형제들의 이야기").contains("줄거리: 몬태나 목장");
    }

    @Test
    void 제목이_다르고_내용이_같은_두_영화는_같은_문서가_된다() {
        Movie first = movieRepository.save(Movie.builder().tmdbId(95_502L).title("겨울 이야기").originalTitle("Winter Tale")
                .releaseDate(LocalDate.of(2020, 1, 1)).overview("같은 줄거리").build());
        Movie second = movieRepository.save(Movie.builder().tmdbId(95_503L).title("여름 이야기").originalTitle("Summer Tale")
                .releaseDate(LocalDate.of(2020, 1, 1)).overview("같은 줄거리").build());
        em.flush();
        em.clear();

        List<Prepared> prepared = loader.load(first.getId() - 1, 2);

        // 제목 신호가 없으므로 문서(→ 해시 → 벡터)가 제목과 무관하다
        assertThat(prepared).extracting(Prepared::movieId).containsExactly(first.getId(), second.getId());
        assertThat(prepared.get(0).text().sourceText()).isEqualTo(prepared.get(1).text().sourceText());
    }
}
