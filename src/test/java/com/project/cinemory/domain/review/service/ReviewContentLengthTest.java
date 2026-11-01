package com.project.cinemory.domain.review.service;

import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.review.dto.ReviewResponse;
import com.project.cinemory.domain.review.dto.ReviewWriteRequest;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/** service-layer-spec 4-4-A — 리뷰 본문 10,000자: 한도 저장 성공(V42 컬럼) · 한도 초과 검증 실패. */
@SpringBootTest
@Transactional
class ReviewContentLengthTest {

    @Autowired
    private ReviewService reviewService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MovieRepository movieRepository;

    @Test
    void 만자_리뷰가_저장되고_그대로_조회된다() {
        User user = userRepository.save(User.createLocal("len10000@test.com", "{noop}pw", "len유저"));
        Movie movie = movieRepository.save(Movie.builder().tmdbId(990042L).title("긴 리뷰").build());
        String content = "가".repeat(10_000);

        reviewService.writeReview(user.getId(), movie.getId(), new ReviewWriteRequest(content));
        userRepository.flush();

        ReviewResponse found = reviewService.getMyReview(user.getId(), movie.getId()).orElseThrow();
        assertThat(found.content()).hasSize(10_000);
    }

    @Test
    void 만일자_리뷰는_검증에서_거절된다() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        assertThat(validator.validate(new ReviewWriteRequest("가".repeat(10_000)))).isEmpty();
        assertThat(validator.validate(new ReviewWriteRequest("가".repeat(10_001)))).hasSize(1);
    }
}
