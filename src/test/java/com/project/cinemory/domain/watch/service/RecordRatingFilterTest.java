package com.project.cinemory.domain.watch.service;

import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.user.entity.PrivacySetting;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.domain.watch.dto.RecordRatingFilter;
import com.project.cinemory.domain.watch.dto.RecordSort;
import com.project.cinemory.domain.watch.dto.UserMovieListItemResponse;
import com.project.cinemory.domain.watch.dto.WatchRecordCreateRequest;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 내 영화 목록 별점 필터({@code RecordRatingFilter}) 검증 — service-layer-spec 5B.1 / poster-perf-spec 5-B. */
@SpringBootTest
@Transactional
class RecordRatingFilterTest {

    @Autowired
    private WatchRecordService watchRecordService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MovieRepository movieRepository;

    private User user;
    private long nextTmdbId = 96_000L;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.createLocal("rating-filter@test.com", "{noop}password", "필터유저"));
        user.changePrivacySetting(PrivacySetting.PUBLIC);
    }

    private Movie movie() {
        long tmdbId = nextTmdbId++;
        return movieRepository.save(Movie.builder().tmdbId(tmdbId).title("필터 영화 " + tmdbId).build());
    }

    private Movie record(Integer rating) {
        Movie movie = movie();
        watchRecordService.addWatchRecord(user.getId(), new WatchRecordCreateRequest(movie.getId(), null,
                null, null, null, rating == null ? null : BigDecimal.valueOf(rating), null));
        return movie;
    }

    private List<Long> list(RecordRatingFilter filter, RecordSort sort, int page, int size) {
        return watchRecordService.getUserMovieList(user.getId(), user.getId(), PageRequest.of(page, size), sort, filter)
                .map(UserMovieListItemResponse::movieId).getContent();
    }

    private List<Long> list(RecordRatingFilter filter) {
        return list(filter, RecordSort.RATING_DESC, 0, 20);
    }

    @Test
    void 경계값은_포함하고_바깥은_제외한다() {
        Long m7 = record(7).getId();
        Long m8 = record(8).getId();
        Long m9 = record(9).getId();
        Long m10 = record(10).getId();

        assertThat(list(RecordRatingFilter.of(8, 9, false))).containsExactly(m9, m8);
        assertThat(list(RecordRatingFilter.of(10, 10, false))).containsExactly(m10);
        assertThat(list(RecordRatingFilter.of(null, 7, false))).containsExactly(m7);
        assertThat(list(RecordRatingFilter.of(9, null, false))).containsExactly(m10, m9);
    }

    @Test
    void unrated는_NULL만_반환한다() {
        record(5);
        Long none = record(null).getId();

        assertThat(list(RecordRatingFilter.of(null, null, true), RecordSort.RECENT, 0, 20)).containsExactly(none);
    }

    @Test
    void 필터와_정렬을_함께_쓴_페이징에서_중복_누락이_없다() {
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            expected.add(record(i % 2 == 0 ? 8 : 9).getId());
        }
        record(3);   // 필터 밖

        List<Long> paged = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            paged.addAll(list(RecordRatingFilter.of(8, 9, false), RecordSort.RATING_DESC, page, 10));
        }

        assertThat(paged).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void 잘못된_조합은_INVALID_RATING_FILTER다() {
        assertInvalid(9, 8, false);
        assertInvalid(0, null, false);
        assertInvalid(null, 11, false);
        assertInvalid(5, null, true);
        assertInvalid(null, 5, true);
    }

    private void assertInvalid(Integer min, Integer max, boolean unrated) {
        assertThatThrownBy(() -> RecordRatingFilter.of(min, max, unrated))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_RATING_FILTER));
    }

    @Test
    void 파라미터가_없으면_기존_결과와_같다() {
        Long a = record(3).getId();
        Long b = record(null).getId();
        Long c = record(10).getId();

        RecordRatingFilter none = RecordRatingFilter.of(null, null, false);
        assertThat(none.isEmpty()).isTrue();
        assertThat(list(none, RecordSort.RECENT, 0, 20)).containsExactly(c, b, a);
    }

    @Test
    void 필터는_대표_기록의_별점만_본다() {
        Movie movie = movie();
        // 1회차 별점 9가 대표다(처음 기록이 대표, 2026-10-17) — 2회차 별점 3은 대표가 아니라 필터에 보이지 않는다
        watchRecordService.addWatchRecord(user.getId(), new WatchRecordCreateRequest(movie.getId(), null,
                null, null, null, BigDecimal.valueOf(9), null));
        var second = watchRecordService.addWatchRecord(user.getId(), new WatchRecordCreateRequest(movie.getId(), null,
                null, null, null, BigDecimal.valueOf(3), null));

        assertThat(list(RecordRatingFilter.of(8, 9, false))).containsExactly(movie.getId());
        assertThat(list(RecordRatingFilter.of(1, 3, false))).isEmpty();

        // 대표를 2회차로 바꾸면 필터가 그 별점을 본다
        watchRecordService.setRepresentative(user.getId(), second.id());
        assertThat(list(RecordRatingFilter.of(8, 9, false))).isEmpty();
        assertThat(list(RecordRatingFilter.of(1, 3, false))).containsExactly(movie.getId());
    }
}
