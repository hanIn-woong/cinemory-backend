package com.project.cinemory.domain.review.service;

import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.review.dto.ReviewResponse;
import com.project.cinemory.domain.review.dto.ReviewWriteRequest;
import com.project.cinemory.domain.user.entity.PrivacySetting;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.domain.watch.dto.WatchRecordCreateRequest;
import com.project.cinemory.domain.watch.service.WatchRecordService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v15 — review.rating 컬럼 제거에 따른 2단계 폴백 규칙 검증.
 * 표시할 별점 = 대표 시청 기록의 rating → null이면 rating IS NOT NULL인 가장 최근(id DESC)
 * 기록의 rating → 그것도 없으면 null.
 *
 * <p>실제 DB 상태(WatchRecord 대표 지정 조율)가 있어야 의미가 있는 검증이라
 * {@code @SpringBootTest} + {@code @Transactional}로 실 리포지토리를 그대로 쓴다
 * ({@code AccessControlTest}/{@code ViewerFlagTest}와 동일한 근거).
 */
@SpringBootTest
@Transactional
class ReviewRatingFallbackTest {

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private WatchRecordService watchRecordService;

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

    private WatchRecordCreateRequest watchRecordRequest(Long movieId, Double rating) {
        return new WatchRecordCreateRequest(movieId, null, null, null, null, rating, null);
    }

    @Test
    void 대표_기록에_별점이_있으면_그_값을_보여준다() {
        User user = createUser("fallback-rep@test.com", "대표기록유저");
        Movie movie = createMovie(90_001L, "대표기록 테스트 영화");

        watchRecordService.addWatchRecord(user.getId(), watchRecordRequest(movie.getId(), 8.0));
        reviewService.writeReview(user.getId(), movie.getId(), new ReviewWriteRequest("좋은 영화였다"));

        Optional<ReviewResponse> myReview = reviewService.getMyReview(user.getId(), movie.getId());

        assertThat(myReview).isPresent();
        assertThat(myReview.get().rating()).isEqualTo(8.0);
    }

    @Test
    void 별점_없이_재관람_기록이_추가돼도_이전_별점이_유지된다() {
        User user = createUser("fallback-rewatch@test.com", "재관람유저");
        Movie movie = createMovie(90_002L, "재관람 테스트 영화");

        // 1회차 — 별점 8.0으로 관람, 이 시점엔 대표 기록
        watchRecordService.addWatchRecord(user.getId(), watchRecordRequest(movie.getId(), 7.0));
        // 2회차 — 별점 없이 재관람, addWatchRecord는 항상 신규 기록을 대표로 승격시킨다
        watchRecordService.addWatchRecord(user.getId(), watchRecordRequest(movie.getId(), null));

        reviewService.writeReview(user.getId(), movie.getId(), new ReviewWriteRequest("두 번째 관람"));

        Optional<ReviewResponse> myReview = reviewService.getMyReview(user.getId(), movie.getId());

        assertThat(myReview).isPresent();
        assertThat(myReview.get().rating()).isEqualTo(7.0);
    }

    @Test
    void 시청_기록_없이_쓴_리뷰는_별점이_null이고_목록에서_사라지지_않는다() {
        User user = createUser("fallback-noreview@test.com", "무기록리뷰어");
        Movie movie = createMovie(90_003L, "무기록 테스트 영화");

        reviewService.writeReview(user.getId(), movie.getId(), new ReviewWriteRequest("보지는 않았지만 궁금해서"));

        Optional<ReviewResponse> myReview = reviewService.getMyReview(user.getId(), movie.getId());
        assertThat(myReview).isPresent();
        assertThat(myReview.get().rating()).isNull();

        var page = reviewService.getMovieReviews(null, movie.getId(), PageRequest.of(0, 20));
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).rating()).isNull();
    }
}
