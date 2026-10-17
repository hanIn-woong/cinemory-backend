package com.project.cinemory.domain.watch.service;

import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.domain.watch.dto.WatchRecordCreateRequest;
import com.project.cinemory.domain.watch.dto.WatchRecordResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대표 기록은 "처음 기록"이다(service-layer-spec 4-3, 2026-10-17).
 * 새 기록은 영화에 대표가 없을 때만 대표가 되고, 대표를 지우면 가장 오래된 기록이 승격된다.
 * 예전 정책("가장 최근 기록이 대표")과 반대라, 두 정책을 가르는 경우만 골라 고정한다.
 */
@SpringBootTest
@Transactional
class RepresentativeFirstRecordTest {

    @Autowired
    private WatchRecordService watchRecordService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MovieRepository movieRepository;

    private User user;
    private Movie movie;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.createLocal("rep-first@test.com", "{noop}password", "처음기록유저"));
        movie = movieRepository.save(Movie.builder().tmdbId(97_601L).title("대표 정책 영화").build());
    }

    private WatchRecordResponse record(BigDecimal rating) {
        return watchRecordService.addWatchRecord(user.getId(), new WatchRecordCreateRequest(movie.getId(),
                LocalDate.of(2026, 10, 17), null, null, null, rating, null));
    }

    /** 대표인 기록 id들 — 단일성이 지켜지면 항상 0개 또는 1개다. */
    private List<Long> representativeIds() {
        return watchRecordService.getWatchLog(user.getId(), user.getId(), movie.getId()).stream()
                .filter(WatchRecordResponse::representative)
                .map(WatchRecordResponse::id)
                .toList();
    }

    @Test
    void 첫_기록은_대표가_된다() {
        WatchRecordResponse first = record(BigDecimal.valueOf(8.0));

        assertThat(first.representative()).isTrue();
        assertThat(representativeIds()).containsExactly(first.id());
    }

    @Test
    void 두_번째_기록은_대표가_아니고_기존_대표가_유지된다() {
        WatchRecordResponse first = record(BigDecimal.valueOf(8.0));

        WatchRecordResponse second = record(BigDecimal.valueOf(4.0));

        assertThat(second.representative()).isFalse();
        assertThat(representativeIds()).containsExactly(first.id());
    }

    @Test
    void 수동으로_지정한_대표는_새_기록이_생겨도_유지된다() {
        record(BigDecimal.valueOf(8.0));
        WatchRecordResponse second = record(BigDecimal.valueOf(4.0));
        watchRecordService.setRepresentative(user.getId(), second.id());

        WatchRecordResponse third = record(BigDecimal.valueOf(6.0));

        assertThat(third.representative()).isFalse();
        assertThat(representativeIds()).containsExactly(second.id());
    }

    @Test
    void 대표를_지우면_남은_기록_중_가장_오래된_기록이_대표가_된다() {
        WatchRecordResponse first = record(BigDecimal.valueOf(8.0));
        record(BigDecimal.valueOf(4.0));
        WatchRecordResponse third = record(BigDecimal.valueOf(6.0));
        // 가장 최근 기록을 대표로 올린 뒤 지운다 — "최신 승격"이면 두 번째, "가장 오래된 승격"이면 첫 번째
        watchRecordService.setRepresentative(user.getId(), third.id());

        watchRecordService.deleteWatchRecord(user.getId(), third.id());

        assertThat(representativeIds()).containsExactly(first.id());
    }

    @Test
    void 대표가_아닌_기록을_지워도_대표는_그대로다() {
        WatchRecordResponse first = record(BigDecimal.valueOf(8.0));
        WatchRecordResponse second = record(BigDecimal.valueOf(4.0));

        watchRecordService.deleteWatchRecord(user.getId(), second.id());

        assertThat(representativeIds()).containsExactly(first.id());
    }

    @Test
    void 마지막_기록을_지운_뒤_다시_기록하면_새_기록이_대표가_된다() {
        WatchRecordResponse first = record(BigDecimal.valueOf(8.0));
        watchRecordService.deleteWatchRecord(user.getId(), first.id());
        assertThat(representativeIds()).isEmpty();

        WatchRecordResponse again = record(BigDecimal.valueOf(5.0));

        assertThat(again.representative()).isTrue();
        assertThat(representativeIds()).containsExactly(again.id());
    }
}
