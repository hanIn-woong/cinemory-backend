package com.project.cinemory.domain.review.entity;

import com.project.cinemory.domain.common.entity.BaseTimeEntity;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.*;

@Entity
@Table(
        name = "review",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_review", columnNames = {"user_id", "movie_id"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Review extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movie_id", nullable = false)
    private Movie movie;

    @Column(name = "rating", nullable = false)
    private Double rating;

    @Column(name = "content", nullable = false, length = 2000)
    private String content;

    @Builder
    private Review(User user, Movie movie, Double rating, String content) {
        this.user = user;
        this.movie = movie;
        this.rating = rating;
        this.content = content;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Review that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
