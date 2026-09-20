package com.project.cinemory.domain.watch.entity;

import com.project.cinemory.domain.common.entity.BaseTimeEntity;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.ott.entity.OttPlatform;
import com.project.cinemory.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "watch_record")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WatchRecord extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movie_id", nullable = false)
    private Movie movie;

    @Column(name = "watch_date")
    private LocalDate watchDate;

    @Column(name = "is_representative", nullable = false)
    private boolean representative;

    @Enumerated(EnumType.STRING)
    @Column(name = "watch_type")
    private WatchType watchType;

    @Column(name = "place_detail", length = 100)
    private String placeDetail;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ott_platform_id")
    private OttPlatform ottPlatform;

    @Column(name = "rating")
    private BigDecimal rating;

    @Column(name = "private_review", length = 1000)
    private String privateReview;

    private static final BigDecimal MIN_RATING = BigDecimal.valueOf(1.0);
    private static final BigDecimal MAX_RATING = BigDecimal.valueOf(10.0);

    @Builder
    private WatchRecord(User user, Movie movie, LocalDate watchDate, boolean representative,
                         WatchType watchType, String placeDetail, OttPlatform ottPlatform,
                         BigDecimal rating, String privateReview) {
        validateRating(rating);
        this.user = user;
        this.movie = movie;
        this.watchDate = watchDate;
        this.representative = representative;
        this.watchType = watchType;
        this.placeDetail = placeDetail;
        this.ottPlatform = ottPlatform;
        this.rating = rating;
        this.privateReview = privateReview;
    }

    /** §7.3 계약상 1.0~10.0(1.0 단위) 범위를 검증한다(v16 정정). rating 자체는 선택 입력이라 null은 허용한다. */
    private static void validateRating(BigDecimal rating) {
        if (rating != null && (rating.compareTo(MIN_RATING) < 0 || rating.compareTo(MAX_RATING) > 0)) {
            throw new IllegalArgumentException("평점은 " + MIN_RATING + " 이상 " + MAX_RATING + " 이하여야 합니다.");
        }
    }

    /** 같은 (user, movie) 내 기존 대표 기록 해제 조율은 WatchRecordService 책임 */
    public void markAsRepresentative() {
        this.representative = true;
    }

    public void unmarkAsRepresentative() {
        this.representative = false;
    }

    /**
     * 시청 기록 수정(전체 치환). {@code movie}·{@code representative}는 제외 —
     * 전자는 바뀌면 다른 기록이고 후자는 markAsRepresentative()/unmarkAsRepresentative()가 전담한다.
     */
    public void update(LocalDate watchDate, WatchType watchType, String placeDetail,
                        OttPlatform ottPlatform, BigDecimal rating, String privateReview) {
        validateRating(rating);
        this.watchDate = watchDate;
        this.watchType = watchType;
        this.placeDetail = placeDetail;
        this.ottPlatform = ottPlatform;
        this.rating = rating;
        this.privateReview = privateReview;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WatchRecord that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
