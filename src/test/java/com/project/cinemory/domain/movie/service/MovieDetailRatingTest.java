package com.project.cinemory.domain.movie.service;

import com.project.cinemory.domain.movie.dto.RatingSummary;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.review.dto.ReviewResponse;
import com.project.cinemory.domain.review.dto.ReviewWriteRequest;
import com.project.cinemory.domain.review.service.ReviewService;
import com.project.cinemory.domain.user.entity.PrivacySetting;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.domain.watch.dto.WatchRecordCreateRequest;
import com.project.cinemory.domain.watch.service.WatchRecordService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 영화 상세 평점 — TMDB · CineMory 병기 (service-layer-spec 4-2-A, 테스트 T1~T8).
 * CineMory 평점은 사용자당 1값을 2단계 폴백(대표 → 별점 있는 최신 기록)으로 고른 뒤 평균한다.
 *
 * <p>대표 지정 조율이 실제 DB에 반영돼야 의미가 있어 {@code ReviewRatingFallbackTest}와 같은 방식으로
 * {@code @SpringBootTest} + {@code @Transactional}로 실 리포지토리를 쓴다.
 */
@SpringBootTest
@Transactional
class MovieDetailRatingTest {

    @Autowired
    private MovieQueryService movieQueryService;

    @Autowired
    private WatchRecordService watchRecordService;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MovieRepository movieRepository;

    private User createUser(String email, String nickname) {
        User user = User.createLocal(email, "{noop}password", nickname);
        user.changePrivacySetting(PrivacySetting.PUBLIC);
        return userRepository.save(user);
    }

    private Movie createMovie(long tmdbId, String title) {
        return movieRepository.save(Movie.builder().tmdbId(tmdbId).title(title).build());
    }

    private void record(User user, Movie movie, String rating) {
        BigDecimal value = (rating == null) ? null : new BigDecimal(rating);
        watchRecordService.addWatchRecord(user.getId(),
                new WatchRecordCreateRequest(movie.getId(), null, null, null, null, value, null));
    }

    private RatingSummary cinemoryOf(Movie movie) {
        return movieQueryService.getMovieDetail(movie.getId()).ratings().cinemory();
    }

    @Test
    void T1_기록이_없으면_평균은_null_count는_0() {
        Movie movie = createMovie(90_201L, "평점 T1");

        RatingSummary cinemory = cinemoryOf(movie);

        assertThat(cinemory.average()).isNull();
        assertThat(cinemory.count()).isZero();
    }

    @Test
    void T2_사용자별_대표_별점의_평균() {
        Movie movie = createMovie(90_202L, "평점 T2");
        record(createUser("rating-t2-a@test.com", "평점T2A"), movie, "8.0");
        record(createUser("rating-t2-b@test.com", "평점T2B"), movie, "6.0");

        RatingSummary cinemory = cinemoryOf(movie);

        assertThat(cinemory.average()).isEqualByComparingTo("7.00");
        assertThat(cinemory.count()).isEqualTo(2);
    }

    @Test
    void T3_별점_없는_재관람이_대표가_돼도_이전_별점이_집계된다() {
        Movie movie = createMovie(90_203L, "평점 T3");
        User a = createUser("rating-t3-a@test.com", "평점T3A");
        record(a, movie, "8.0");
        record(a, movie, null); // 새 대표, rating null

        RatingSummary cinemory = cinemoryOf(movie);

        assertThat(cinemory.average()).isEqualByComparingTo("8.00");
        assertThat(cinemory.count()).isEqualTo(1);
    }

    @Test
    void T4_사용자당_1값만_집계되고_과거_기록은_섞이지_않는다() {
        Movie movie = createMovie(90_204L, "평점 T4");
        User a = createUser("rating-t4-a@test.com", "평점T4A");
        record(a, movie, "10.0"); // 과거 기록
        record(a, movie, "6.0");  // 대표

        RatingSummary cinemory = cinemoryOf(movie);

        assertThat(cinemory.average()).isEqualByComparingTo("6.00");
        assertThat(cinemory.count()).isEqualTo(1);
    }

    @Test
    void T5_별점_기록이_전혀_없는_사용자는_제외된다() {
        Movie movie = createMovie(90_205L, "평점 T5");
        User a = createUser("rating-t5-a@test.com", "평점T5A");
        record(a, movie, null);
        record(a, movie, null);
        record(createUser("rating-t5-b@test.com", "평점T5B"), movie, "4.0");

        RatingSummary cinemory = cinemoryOf(movie);

        assertThat(cinemory.average()).isEqualByComparingTo("4.00");
        assertThat(cinemory.count()).isEqualTo(1);
    }

    @Test
    void T6_평균은_소수_2자리로_반올림한다() {
        Movie movie = createMovie(90_206L, "평점 T6");
        record(createUser("rating-t6-a@test.com", "평점T6A"), movie, "9.0");
        record(createUser("rating-t6-b@test.com", "평점T6B"), movie, "8.0");
        record(createUser("rating-t6-c@test.com", "평점T6C"), movie, "8.0");

        RatingSummary cinemory = cinemoryOf(movie);

        assertThat(cinemory.average()).isEqualByComparingTo("8.33");
        assertThat(cinemory.count()).isEqualTo(3);
    }

    @Test
    void T7_TMDB_평가_0건이면_평균은_null() {
        Movie movie = movieRepository.save(Movie.builder()
                .tmdbId(90_207L).title("평점 T7")
                .voteAverage(new BigDecimal("0.0")).voteCount(0)
                .build());

        RatingSummary tmdb = movieQueryService.getMovieDetail(movie.getId()).ratings().tmdb();

        assertThat(tmdb.average()).isNull();
        assertThat(tmdb.count()).isZero();
    }

    @Test
    void T8_집계에_들어간_값과_리뷰_목록의_별점이_같다() {
        Movie movie = createMovie(90_208L, "평점 T8");
        User a = createUser("rating-t8-a@test.com", "평점T8A");
        record(a, movie, "8.0");
        record(a, movie, null);
        reviewService.writeReview(a.getId(), movie.getId(), new ReviewWriteRequest("규칙 일치 확인"));

        RatingSummary cinemory = cinemoryOf(movie);
        Optional<ReviewResponse> review = reviewService.getMyReview(a.getId(), movie.getId());

        assertThat(cinemory.count()).isEqualTo(1);
        assertThat(review).isPresent();
        assertThat(cinemory.average()).isEqualByComparingTo(BigDecimal.valueOf(review.get().rating()));
    }
}
