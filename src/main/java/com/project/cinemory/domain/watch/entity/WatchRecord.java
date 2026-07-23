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
    private boolean isRepresentative;

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

    @Column(name = "review", length = 1000)
    private String review;

    @Builder
    private WatchRecord(User user, Movie movie, LocalDate watchDate, boolean isRepresentative,
                         WatchType watchType, String placeDetail, OttPlatform ottPlatform,
                         Double rating, String review) {
        this.user = user;
        this.movie = movie;
        this.watchDate = watchDate;
        this.isRepresentative = isRepresentative;
        this.watchType = watchType;
        this.placeDetail = placeDetail;
        this.ottPlatform = ottPlatform;
        this.rating = rating;
        this.review = review;
    }

    /** 같은 (user, movie) 내 기존 대표 기록 해제 조율은 WatchRecordService 책임 */
    public void markAsRepresentative() {
        this.isRepresentative = true;
    }

    public void unmarkAsRepresentative() {
        this.isRepresentative = false;
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
