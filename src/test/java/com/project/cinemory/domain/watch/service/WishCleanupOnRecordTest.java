package com.project.cinemory.domain.watch.service;

import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.domain.watch.dto.WatchRecordCreateRequest;
import com.project.cinemory.domain.watch.dto.WatchRecordResponse;
import com.project.cinemory.domain.watch.dto.WatchRecordUpdateRequest;
import com.project.cinemory.domain.wish.service.WishMovieService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시청 기록 생성 시 찜 정리(service-layer-spec 4-3, 2026-10-03).
 * 찜한 뒤에 새 기록이 생기면 찜에서 빠지고, 기록이 있는 영화를 다시 찜하는 것은 허용된다.
 */
@SpringBootTest
@Transactional
class WishCleanupOnRecordTest {

    @Autowired
    private WatchRecordService watchRecordService;
    @Autowired
    private WishMovieService wishMovieService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MovieRepository movieRepository;

    private User user;
    private Movie movie;
    private Movie otherMovie;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.createLocal("wish-cleanup@test.com", "{noop}password", "찜정리유저"));
        movie = movieRepository.save(Movie.builder().tmdbId(97_501L).title("본 영화").build());
        otherMovie = movieRepository.save(Movie.builder().tmdbId(97_502L).title("다른 영화").build());
    }

    private WatchRecordResponse record(Movie target) {
        return watchRecordService.addWatchRecord(user.getId(), new WatchRecordCreateRequest(target.getId(),
                LocalDate.of(2026, 10, 3), null, null, null, BigDecimal.valueOf(8.0), null));
    }

    @Test
    void 찜한_영화에_시청_기록을_추가하면_찜에서_빠진다() {
        wishMovieService.toggleWish(user.getId(), movie.getId());

        record(movie);

        assertThat(wishMovieService.isWished(user.getId(), movie.getId())).isFalse();
    }

    @Test
    void 다른_영화의_찜은_건드리지_않고_찜이_없어도_기록은_정상_생성된다() {
        wishMovieService.toggleWish(user.getId(), otherMovie.getId());

        WatchRecordResponse response = record(movie);

        assertThat(response.id()).isNotNull();
        assertThat(wishMovieService.isWished(user.getId(), otherMovie.getId())).isTrue();
    }

    @Test
    void 기록이_있는_영화도_다시_찜할_수_있고_수정_대표변경은_찜을_유지하며_새_기록에서_빠진다() {
        WatchRecordResponse first = record(movie);
        WatchRecordResponse second = record(movie);
        wishMovieService.toggleWish(user.getId(), movie.getId());
        assertThat(wishMovieService.isWished(user.getId(), movie.getId())).isTrue();

        // 수정·대표 변경은 "새로 봤다"가 아니다
        watchRecordService.updateWatchRecord(user.getId(), second.id(), new WatchRecordUpdateRequest(
                LocalDate.of(2026, 10, 1), null, null, null, BigDecimal.valueOf(9.0), null));
        watchRecordService.setRepresentative(user.getId(), first.id());
        assertThat(wishMovieService.isWished(user.getId(), movie.getId())).isTrue();

        record(movie);
        assertThat(wishMovieService.isWished(user.getId(), movie.getId())).isFalse();
    }
}
