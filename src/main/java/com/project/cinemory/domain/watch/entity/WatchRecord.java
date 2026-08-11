package com.project.cinemory.domain.watch.entity;

import com.project.cinemory.domain.common.entity.BaseTimeEntity;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.ott.entity.OttPlatform;
import com.project.cinemory.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.*;

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
    private Double rating;

    // 공개 대표 리뷰인 Review 엔티티와 혼동 방지를 위해 필드명은 note로 명명, 컬럼명은 기존 review 유지
    @Column(name = "review", length = 1000)
    private String note;

    private static final double MIN_RATING = 0.0;
    private static final double MAX_RATING = 10.0;

    @Builder
    private WatchRecord(User user, Movie movie, LocalDate watchDate, boolean representative,
                         WatchType watchType, String placeDetail, OttPlatform ottPlatform,
                         Double rating, String note) {
        validateRating(rating);
        this.user = user;
        this.movie = movie;
        this.watchDate = watchDate;
        this.representative = representative;
        this.watchType = watchType;
        this.placeDetail = placeDetail;
        this.ottPlatform = ottPlatform;
        this.rating = rating;
        this.note = note;
    }

    /** {@code Review}와 같은 0~10 척도를 쓴다(Step5 5-3-A). rating 자체는 선택 입력이라 null은 허용한다. */
    private static void validateRating(Double rating) {
        if (rating != null && (rating < MIN_RATING || rating > MAX_RATING)) {
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
