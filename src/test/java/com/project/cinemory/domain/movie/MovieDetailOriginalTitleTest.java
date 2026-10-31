package com.project.cinemory.domain.movie;

import com.project.cinemory.domain.movie.dto.MovieDetailResponse;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.service.MovieQueryService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 영화 상세 응답의 {@code originalTitle} (service-layer-spec 4-2, 2026-11-01). 실 DB({@code cinemory_test})를 쓰고 테스트마다 롤백한다.
 * {@code title}은 {@code displayTitle()}(한글 제목 우선)이라 원제와 갈린다 — 원제는 {@code Movie.originalTitle}을 그대로 노출만 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MovieDetailOriginalTitleTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MovieQueryService movieQueryService;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private EntityManager em;

    private Movie movie(String title, String originalTitle) {
        Movie movie = Movie.builder().tmdbId(600_000_000L + SEQ.incrementAndGet() % 90_000_000L)
                .title(title).originalTitle(originalTitle).build();
        em.persist(movie);
        em.flush();
        return movie;
    }

    @Test
    void 원제가_있으면_title과_별개로_그대로_내려온다() {
        Movie movie = movie("기생충", "Parasite");

        MovieDetailResponse response = movieQueryService.getMovieDetail(movie.getId());

        assertThat(response.title()).isEqualTo("기생충");
        assertThat(response.originalTitle()).isEqualTo("Parasite");
    }

    @Test
    void 한글_제목이_있으면_title은_한글_원제는_원래_값이다() {
        Movie movie = movie("Parasite", "Parasite");
        movie.changeTitleKo("기생충");
        em.flush();

        MovieDetailResponse response = movieQueryService.getMovieDetail(movie.getId());

        assertThat(response.title()).isEqualTo("기생충");
        assertThat(response.originalTitle()).isEqualTo("Parasite");
    }

    @Test
    void 원제가_없으면_null이다() {
        Movie movie = movie("원제 없는 영화", null);

        assertThat(movieQueryService.getMovieDetail(movie.getId()).originalTitle()).isNull();
    }

    @Test
    void JSON에는_title_바로_뒤에_originalTitle_키가_있고_null이어도_키가_빠지지_않는다() throws Exception {
        Movie withOriginal = movie("기생충", "Parasite");
        Movie withoutOriginal = movie("원제 없는 영화", null);

        JsonNode present = JSON.readTree(mockMvc.perform(get("/api/movies/" + withOriginal.getId()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode absent = JSON.readTree(mockMvc.perform(get("/api/movies/" + withoutOriginal.getId()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(present.get("originalTitle").asString()).isEqualTo("Parasite");
        // 앱의 생성 타입(gen:api)이 필드를 nullable로 만들려면 응답에 키가 있어야 한다
        assertThat(absent.has("originalTitle")).isTrue();
        assertThat(absent.get("originalTitle").isNull()).isTrue();
        assertThat(new java.util.ArrayList<>(present.propertyNames())).containsSequence("title", "originalTitle", "posterPath");
    }
}
