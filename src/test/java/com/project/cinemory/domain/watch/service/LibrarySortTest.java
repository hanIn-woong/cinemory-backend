package com.project.cinemory.domain.watch.service;

import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.user.entity.PrivacySetting;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.domain.watch.dto.RecordSort;
import com.project.cinemory.domain.watch.dto.UserMovieListItemResponse;
import com.project.cinemory.domain.watch.dto.WatchRecordCreateRequest;
import com.project.cinemory.domain.wish.dto.WishListItemResponse;
import com.project.cinemory.domain.wish.dto.WishSort;
import com.project.cinemory.domain.wish.service.WishMovieService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 내 영화·위시리스트 정렬 화이트리스트({@code RecordSort}·{@code WishSort}) 검증.
 * NULL 위치(nullsLast)가 MySQL에서 실제로 뒤로 가는지는 SQL 방언에 달려 있어 실 DB로 고정한다.
 */
@SpringBootTest
@Transactional
class LibrarySortTest {

    @Autowired
    private WatchRecordService watchRecordService;
    @Autowired
    private WishMovieService wishMovieService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MovieRepository movieRepository;

    private User user;
    // 등록 순서: A → B → C (id 오름차순)
    private Movie movieA;
    private Movie movieB;
    private Movie movieC;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.createLocal("sort-user@test.com", "{noop}password", "정렬유저"));
        user.changePrivacySetting(PrivacySetting.PUBLIC);

        movieA = createMovie(97_001L, "나 영화", LocalDate.of(2010, 1, 1));
        movieB = createMovie(97_002L, "가 영화", null);
        movieC = createMovie(97_003L, "다 영화", LocalDate.of(2020, 1, 1));
    }

    private Movie createMovie(long tmdbId, String title, LocalDate releaseDate) {
        return movieRepository.save(Movie.builder().tmdbId(tmdbId).title(title).releaseDate(releaseDate).build());
    }

    private void record(Movie movie, LocalDate watchDate, Double rating) {
        watchRecordService.addWatchRecord(user.getId(), new WatchRecordCreateRequest(movie.getId(), watchDate,
                null, null, null, rating == null ? null : BigDecimal.valueOf(rating), null));
    }

    private List<Long> records(RecordSort sort) {
        return watchRecordService.getUserMovieList(user.getId(), user.getId(), PageRequest.of(0, 20), sort)
                .map(UserMovieListItemResponse::movieId).getContent();
    }

    private List<Long> wishes(WishSort sort) {
        return wishMovieService.getUserWishList(user.getId(), user.getId(), PageRequest.of(0, 20), sort)
                .map(WishListItemResponse::movieId).getContent();
    }

    @Test
    void 내_영화_정렬_옵션마다_순서가_바뀌고_NULL은_항상_뒤로_간다() {
        // A: 날짜·별점 있음 / B: 날짜·별점 없음 / C: 날짜만 있음
        record(movieA, LocalDate.of(2026, 1, 1), 6.0);
        record(movieB, null, null);
        record(movieC, LocalDate.of(2026, 3, 1), null);

        Long a = movieA.getId(), b = movieB.getId(), c = movieC.getId();
        assertThat(records(RecordSort.RECENT)).containsExactly(c, b, a);
        assertThat(records(RecordSort.OLDEST)).containsExactly(a, b, c);
        assertThat(records(RecordSort.TITLE)).containsExactly(b, a, c);
        assertThat(records(RecordSort.RELEASE_DESC)).containsExactly(c, a, b);
        assertThat(records(RecordSort.RELEASE_ASC)).containsExactly(a, c, b);
        assertThat(records(RecordSort.WATCH_DATE_DESC)).containsExactly(c, a, b);
        // 별점이 없는 둘(B·C)은 뒤로, 그 안에서는 보조키 id DESC
        assertThat(records(RecordSort.RATING_DESC)).containsExactly(a, c, b);
    }

    @Test
    void 위시리스트_정렬_옵션마다_순서가_바뀌고_NULL은_항상_뒤로_간다() {
        wishMovieService.toggleWish(user.getId(), movieA.getId());
        wishMovieService.toggleWish(user.getId(), movieB.getId());
        wishMovieService.toggleWish(user.getId(), movieC.getId());

        Long a = movieA.getId(), b = movieB.getId(), c = movieC.getId();
        assertThat(wishes(WishSort.RECENT)).containsExactly(c, b, a);
        assertThat(wishes(WishSort.OLDEST)).containsExactly(a, b, c);
        assertThat(wishes(WishSort.TITLE)).containsExactly(b, a, c);
        assertThat(wishes(WishSort.RELEASE_DESC)).containsExactly(c, a, b);
        assertThat(wishes(WishSort.RELEASE_ASC)).containsExactly(a, c, b);
    }

    @Test
    void 값이_겹치는_정렬도_페이지_경계에서_중복_누락이_없다() {
        // 25편 전부 같은 별점 — 보조키가 없으면 페이지 사이 순서가 보장되지 않는다
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            Movie movie = createMovie(97_100L + i, "페이지 영화 " + i, null);
            record(movie, null, 8.0);
            expected.add(movie.getId());
        }

        List<Long> paged = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            paged.addAll(watchRecordService.getUserMovieList(user.getId(), user.getId(), PageRequest.of(page, 10),
                    RecordSort.RATING_DESC).map(UserMovieListItemResponse::movieId).getContent());
        }

        assertThat(paged).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void 클라이언트가_보낸_자유_sort는_무시된다() {
        record(movieA, null, null);
        record(movieB, null, null);

        List<Long> ids = watchRecordService.getUserMovieList(user.getId(), user.getId(),
                        PageRequest.of(0, 20, Sort.by("id")), RecordSort.RECENT)
                .map(UserMovieListItemResponse::movieId).getContent();

        assertThat(ids).containsExactly(movieB.getId(), movieA.getId());
    }
}
