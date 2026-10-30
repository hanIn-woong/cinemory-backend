package com.project.cinemory.domain.user.profilefields;

import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.user.entity.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 커버는 영화 또는 업로드 중 하나만 — DB CHECK를 만들 수 없어(MySQL ERROR 3823, v38-delta) 엔티티 메서드가 지키는 불변식을 고정한다.
 */
class UserCoverInvariantTest {

    private static Movie movie() {
        return Movie.builder().tmdbId(1L).title("영화").backdropPath("/b.jpg").build();
    }

    private static User user() {
        return User.createOAuth("cover@test.com", "커버", null);
    }

    @Test
    void 처음에는_커버가_없다() {
        User user = user();
        assertThat(user.hasMovieCover()).isFalse();
        assertThat(user.hasImageCover()).isFalse();
    }

    @Test
    void 영화로_바꾸면_업로드가_해제된다() {
        User user = user();
        user.changeCoverToImage("cover/1/a.jpg");

        user.changeCoverToMovie(movie());

        assertThat(user.hasMovieCover()).isTrue();
        assertThat(user.getCoverImage()).isNull();
    }

    @Test
    void 업로드로_바꾸면_영화가_해제된다() {
        User user = user();
        user.changeCoverToMovie(movie());

        user.changeCoverToImage("cover/1/a.jpg");

        assertThat(user.getCoverMovie()).isNull();
        assertThat(user.getCoverImage()).isEqualTo("cover/1/a.jpg");
    }

    @Test
    void 어떤_순서로_바꿔도_둘이_동시에_채워지지_않는다() {
        User user = user();
        Movie movie = movie();
        String[] steps = {"m", "i", "i", "m", "m", "i", "c", "m", "c", "i"};
        for (String step : steps) {
            switch (step) {
                case "m" -> user.changeCoverToMovie(movie);
                case "i" -> user.changeCoverToImage("cover/1/a.jpg");
                default -> user.clearCover();
            }
            assertThat(user.hasMovieCover() && user.hasImageCover()).as("after " + step).isFalse();
        }
    }

    @Test
    void clearCover는_둘_다_비운다() {
        User user = user();
        user.changeCoverToMovie(movie());
        user.clearCover();
        assertThat(user.hasMovieCover()).isFalse();
        assertThat(user.hasImageCover()).isFalse();
    }

    @Test
    void 빈_값은_거부한다() {
        User user = user();
        assertThatThrownBy(() -> user.changeCoverToMovie(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> user.changeCoverToImage(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> user.changeCoverToImage("  ")).isInstanceOf(IllegalArgumentException.class);
    }
}
