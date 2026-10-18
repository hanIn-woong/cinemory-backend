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
        return watchRecordService.getUserMovieList(user.getId(), user.getId(), PageRequest.of(0, 20), sort,
                RecordRatingFilter.NONE)
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
        // 오래된 관람일순: 날짜 없는 B는 뒤로
        assertThat(records(RecordSort.WATCH_DATE_ASC)).containsExactly(a, c, b);
        // 별점 낮은순: 방향이 바뀌어도 별점 없는 둘은 뒤로(보조키 id DESC)
        assertThat(records(RecordSort.RATING_ASC)).containsExactly(a, c, b);
        assertThat(records(RecordSort.TITLE_DESC)).containsExactly(c, a, b);
    }

    @Test
    void 최근_관람일순일_때만_카드_날짜가_최근_관람일이고_별점은_항상_대표_기록이다() {
        // 대표는 "처음 기록"이라 재관람은 비대표 행으로 쌓인다
        // A: 2020 첫 관람(6.0) → 2026-05 재관람(8.0) / B: 2025 한 번 / C: 2026-03 한 번 / D: 2024 → 날짜 없는 재관람
        record(movieA, LocalDate.of(2020, 1, 1), 6.0);
        record(movieB, LocalDate.of(2025, 1, 1), 5.0);
        record(movieC, LocalDate.of(2026, 3, 1), null);
        Movie movieD = createMovie(97_004L, "라 영화", null);
        record(movieD, LocalDate.of(2024, 1, 1), 7.0);
        record(movieA, LocalDate.of(2026, 5, 1), 8.0);
        record(movieD, null, 9.0);

        List<UserMovieListItemResponse> list = watchRecordService.getUserMovieList(user.getId(), user.getId(),
                PageRequest.of(0, 20), RecordSort.WATCH_DATE_DESC, RecordRatingFilter.NONE).getContent();

        // 정렬: A(2026-05) → C(2026-03) → B(2025) → D(2024 — 날짜 없는 재관람은 무시)
        assertThat(list).extracting(UserMovieListItemResponse::movieId)
                .containsExactly(movieA.getId(), movieC.getId(), movieB.getId(), movieD.getId());
        // 카드 날짜: A는 재관람 날짜, D는 날짜 있는 쪽(2024)이 최근 기록이다. 별점은 둘 다 대표(처음) 기록이다
        assertThat(list.get(0).watchDate()).isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(list.get(0).rating()).isEqualByComparingTo("6.0"); // 재관람 8.0이 아니라 대표 6.0
        assertThat(list.get(3).watchDate()).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(list.get(3).rating()).isEqualByComparingTo("7.0"); // 날짜 없는 재관람 9.0이 아니라 대표 7.0
        // 다른 정렬에서는 카드 날짜도 대표(처음 기록)의 날짜 그대로다 — 최근 관람일순일 때만 최근 날짜로 바뀐다
        List<UserMovieListItemResponse> recent = watchRecordService.getUserMovieList(user.getId(), user.getId(),
                PageRequest.of(0, 20), RecordSort.RECENT, RecordRatingFilter.NONE).getContent();
        assertThat(recent).filteredOn(item -> item.movieId().equals(movieA.getId()))
                .extracting(UserMovieListItemResponse::watchDate).containsExactly(LocalDate.of(2020, 1, 1));
        assertThat(recent).filteredOn(item -> item.movieId().equals(movieD.getId()))
                .extracting(UserMovieListItemResponse::watchDate).containsExactly(LocalDate.of(2024, 1, 1));
        // 별점순·별점 필터는 여전히 대표(처음 기록) 기준이다: A 6.0 < D 7.0
        assertThat(records(RecordSort.RATING_DESC)).containsExactly(movieD.getId(), movieA.getId(), movieB.getId(), movieC.getId());
    }

    @Test
    void 최근_관람일순도_별점_필터와_함께_쓸_수_있고_페이지_경계가_안정적이다() {
        record(movieA, LocalDate.of(2020, 1, 1), 6.0);
        record(movieA, LocalDate.of(2026, 5, 1), 8.0);
        record(movieB, LocalDate.of(2025, 1, 1), null);
        record(movieC, LocalDate.of(2026, 3, 1), 6.0);

        // 미평가: 대표 별점이 없는 B만
        assertThat(watchRecordService.getUserMovieList(user.getId(), user.getId(), PageRequest.of(0, 20),
                RecordSort.WATCH_DATE_DESC, RecordRatingFilter.of(null, null, true)).map(UserMovieListItemResponse::movieId).getContent())
                .containsExactly(movieB.getId());
        // 대표 별점 6.0 범위: A(재관람 8.0이어도 대표 6.0)와 C — 최근 관람일 순
        assertThat(watchRecordService.getUserMovieList(user.getId(), user.getId(), PageRequest.of(0, 20),
                RecordSort.WATCH_DATE_DESC, RecordRatingFilter.of(6, 6, false)).map(UserMovieListItemResponse::movieId).getContent())
                .containsExactly(movieA.getId(), movieC.getId());
        // 전체 개수는 영화 수(대표 행 수)다 — 재관람 행이 부풀리지 않는다
        assertThat(watchRecordService.getUserMovieList(user.getId(), user.getId(), PageRequest.of(0, 2),
                RecordSort.WATCH_DATE_DESC, RecordRatingFilter.NONE).getTotalElements()).isEqualTo(3);
    }

    @Test
    void 상영시간순은_방향과_무관하게_정보_없음이_뒤로_간다() {
        Movie shortMovie = movieRepository.save(Movie.builder().tmdbId(97_201L).title("짧은 영화").runtime(80).build());
        Movie longMovie = movieRepository.save(Movie.builder().tmdbId(97_202L).title("긴 영화").runtime(180).build());
        Movie unknown = movieRepository.save(Movie.builder().tmdbId(97_203L).title("모르는 영화").build());
        record(shortMovie, null, null);
        record(longMovie, null, null);
        record(unknown, null, null);

        assertThat(records(RecordSort.RUNTIME_ASC)).containsExactly(shortMovie.getId(), longMovie.getId(), unknown.getId());
        assertThat(records(RecordSort.RUNTIME_DESC)).containsExactly(longMovie.getId(), shortMovie.getId(), unknown.getId());
    }

    @Test
    void 오래된_관람일순은_영화별_가장_이른_관람일로_정렬하고_이_정렬에서만_카드_날짜가_바뀐다() {
        // A: 2020 → 2026-05 재관람 / B: 2025 한 번 / C: 날짜 없음 → 2019 재관람(대표는 날짜 없는 첫 기록)
        record(movieA, LocalDate.of(2020, 1, 1), 6.0);
        record(movieB, LocalDate.of(2025, 1, 1), 5.0);
        record(movieC, null, 4.0);
        record(movieA, LocalDate.of(2026, 5, 1), 8.0);
        record(movieC, LocalDate.of(2019, 1, 1), 9.0);

        List<UserMovieListItemResponse> list = watchRecordService.getUserMovieList(user.getId(), user.getId(),
                PageRequest.of(0, 20), RecordSort.WATCH_DATE_ASC, RecordRatingFilter.NONE).getContent();

        // 정렬: C(2019 — 날짜 없는 대표 대신 재관람의 날짜) → A(2020) → B(2025)
        assertThat(list).extracting(UserMovieListItemResponse::movieId)
                .containsExactly(movieC.getId(), movieA.getId(), movieB.getId());
        assertThat(list.get(0).watchDate()).isEqualTo(LocalDate.of(2019, 1, 1));
        assertThat(list.get(1).watchDate()).isEqualTo(LocalDate.of(2020, 1, 1)); // 재관람 2026이 아니라 가장 이른 날짜
        // 별점은 어느 정렬이든 대표(처음 기록) 그대로
        assertThat(list.get(0).rating()).isEqualByComparingTo("4.0");
        assertThat(list.get(1).rating()).isEqualByComparingTo("6.0");
        // 필터를 같이 써도 같은 정렬: 대표 별점 5~6 = A(6.0), B(5.0) — C(4.0)는 빠진다
        assertThat(watchRecordService.getUserMovieList(user.getId(), user.getId(), PageRequest.of(0, 20),
                RecordSort.WATCH_DATE_ASC, RecordRatingFilter.of(5, 6, false)).map(UserMovieListItemResponse::movieId).getContent())
                .containsExactly(movieA.getId(), movieB.getId());
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
        assertThat(wishes(WishSort.TITLE_DESC)).containsExactly(c, a, b);
    }

    @Test
    void 위시리스트_상영시간순도_방향과_무관하게_정보_없음이_뒤로_간다() {
        Movie shortMovie = movieRepository.save(Movie.builder().tmdbId(97_301L).title("짧은 영화").runtime(80).build());
        Movie longMovie = movieRepository.save(Movie.builder().tmdbId(97_302L).title("긴 영화").runtime(180).build());
        Movie unknown = movieRepository.save(Movie.builder().tmdbId(97_303L).title("모르는 영화").build());
        wishMovieService.toggleWish(user.getId(), shortMovie.getId());
        wishMovieService.toggleWish(user.getId(), longMovie.getId());
        wishMovieService.toggleWish(user.getId(), unknown.getId());

        assertThat(wishes(WishSort.RUNTIME_ASC)).containsExactly(shortMovie.getId(), longMovie.getId(), unknown.getId());
        assertThat(wishes(WishSort.RUNTIME_DESC)).containsExactly(longMovie.getId(), shortMovie.getId(), unknown.getId());
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
                    RecordSort.RATING_DESC, RecordRatingFilter.NONE).map(UserMovieListItemResponse::movieId).getContent());
        }

        assertThat(paged).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void 클라이언트가_보낸_자유_sort는_무시된다() {
        record(movieA, null, null);
        record(movieB, null, null);

        List<Long> ids = watchRecordService.getUserMovieList(user.getId(), user.getId(),
                        PageRequest.of(0, 20, Sort.by("id")), RecordSort.RECENT, RecordRatingFilter.NONE)
                .map(UserMovieListItemResponse::movieId).getContent();

        assertThat(ids).containsExactly(movieB.getId(), movieA.getId());
    }
}
