package com.project.cinemory.domain.movie.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 영화 한글 제목 도메인 규칙(admin-spec 12-B-1, 테스트 12-B-5) — DB 없이 엔티티만 본다. */
class MovieTitleKoTest {

    private Movie movie() {
        return Movie.builder().tmdbId(1L).title("Avatar: The Way of Water").build();
    }

    @Test
    void 앞뒤_공백은_잘린다() {
        Movie movie = movie();

        movie.changeTitleKo("  아바타: 물의 길  ");

        assertThat(movie.getTitleKo()).isEqualTo("아바타: 물의 길");
    }

    @Test
    void 빈_문자열과_null은_해제다() {
        Movie movie = movie();
        movie.changeTitleKo("아바타");

        movie.changeTitleKo("   ");
        assertThat(movie.getTitleKo()).isNull();

        movie.changeTitleKo("아바타");
        movie.changeTitleKo(null);
        assertThat(movie.getTitleKo()).isNull();
    }

    @Test
    void 이백오십오자까지_허용하고_초과는_거부한다() {
        Movie movie = movie();

        movie.changeTitleKo("가".repeat(255));
        assertThat(movie.getTitleKo()).hasSize(255);

        assertThatThrownBy(() -> movie.changeTitleKo("가".repeat(256))).isInstanceOf(IllegalArgumentException.class);
        assertThat(movie.getTitleKo()).hasSize(255);   // 거부되면 이전 값 그대로
    }

    @Test
    void 표시_제목은_한글_제목이_있으면_그것_없으면_TMDB_제목이다() {
        Movie movie = movie();
        assertThat(movie.displayTitle()).isEqualTo("Avatar: The Way of Water");

        movie.changeTitleKo("아바타: 물의 길");
        assertThat(movie.displayTitle()).isEqualTo("아바타: 물의 길");

        movie.changeTitleKo(null);
        assertThat(movie.displayTitle()).isEqualTo("Avatar: The Way of Water");
    }

    @Test
    void 재동기화_경로_updateMetadata는_한글_제목을_건드리지_않는다() {
        Movie movie = movie();
        movie.changeTitleKo("아바타: 물의 길");

        movie.updateMetadata("Avatar: The Way of Water (2022)", "/p.jpg", "줄거리", 192, LocalDate.of(2022, 12, 14),
                "Avatar: The Way of Water", "/b.jpg", BigDecimal.valueOf(7.6), 9000, null, null);

        assertThat(movie.getTitle()).isEqualTo("Avatar: The Way of Water (2022)");   // TMDB 값은 갱신됐다
        assertThat(movie.getTitleKo()).isEqualTo("아바타: 물의 길");                    // 한글 제목은 그대로
        assertThat(movie.displayTitle()).isEqualTo("아바타: 물의 길");
    }
}
