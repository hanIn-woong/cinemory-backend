package com.project.cinemory.domain.review.service;

import com.project.cinemory.domain.comment.entity.TargetType;
import com.project.cinemory.domain.comment.repository.CommentRepository;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.review.dto.ReviewResponse;
import com.project.cinemory.domain.review.dto.ReviewWriteRequest;
import com.project.cinemory.domain.review.entity.Review;
import com.project.cinemory.domain.review.repository.ReviewRepository;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.global.access.UserAccessPolicy;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final MovieRepository movieRepository;
    private final UserRepository userRepository;
    private final CommentRepository commentRepository;
    private final UserAccessPolicy userAccessPolicy;

    @Transactional
    public ReviewResponse writeReview(Long userId, Long movieId, ReviewWriteRequest request) {
        Movie movie = movieRepository.findById(movieId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MOVIE_NOT_FOUND));

        Review review = reviewRepository.findByUserIdAndMovieId(userId, movieId)
                .map(existing -> {
                    existing.update(request.rating(), request.content());
                    return existing;
                })
                .orElseGet(() -> reviewRepository.save(
                        Review.of(userRepository.getReferenceById(userId), movie, request.rating(), request.content())
                ));

        return ReviewResponse.from(review);
    }

    @Transactional
    public void deleteReview(Long userId, Long movieId) {
        Review review = reviewRepository.findByUserIdAndMovieId(userId, movieId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_NOT_FOUND));
        // comment.target_id는 다형 참조라 FK가 없어 DB가 정리해주지 않는다.
        // 방치하면 재사용된 AUTO_INCREMENT id에 과거 댓글이 붙어 보이므로 명시적으로 삭제한다.
        commentRepository.deleteByTarget(TargetType.REVIEW, review.getId());
        reviewRepository.delete(review);
    }

    public Optional<ReviewResponse> getMyReview(Long userId, Long movieId) {
        return reviewRepository.findByUserIdAndMovieId(userId, movieId)
                .map(ReviewResponse::from);
    }

    /**
     * 영화 상세 화면의 리뷰 목록.
     *
     * <p>다른 조회 API와 달리 <b>작성자가 여러 명</b>이라 단건 판정을 반복할 수 없다.
     * 페이지 내 작성자 id를 모아 {@code filterViewable}로 벌크 판정(최대 3쿼리)한 뒤
     * 비공개 작성자의 리뷰를 걸러낸다.
     *
     * <p><b>한계</b>: 조회 후 필터링이므로 페이지당 실제 항목 수가 요청 size보다 적을 수 있고
     * {@code totalElements}에는 가려진 리뷰도 포함된다. 정확한 페이징이 필요해지면
     * 공개범위 조건을 쿼리로 내리는 방식(작성자 조인 + 맞팔 서브쿼리)으로 전환해야 한다.
     * 캡스톤 스코프에서는 구현 단순성을 우선해 현 방식을 유지한다.
     */
    public Page<ReviewResponse> getMovieReviews(Long viewerId, Long movieId, Pageable pageable) {
        Page<Review> reviewPage = reviewRepository.findByMovieId(movieId, pageable);

        List<Long> authorIds = reviewPage.getContent().stream()
                .map(review -> review.getUser().getId())
                .toList();
        Set<Long> viewableAuthorIds = userAccessPolicy.filterViewable(viewerId, authorIds);

        List<ReviewResponse> visibleReviews = reviewPage.getContent().stream()
                .filter(review -> viewableAuthorIds.contains(review.getUser().getId()))
                .map(ReviewResponse::from)
                .toList();

        return new PageImpl<>(visibleReviews, pageable, reviewPage.getTotalElements());
    }
}
