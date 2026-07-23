package com.project.cinemory.domain.wish.entity;

import com.project.cinemory.domain.common.entity.BaseTimeEntity;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.*;

@Entity
@Table(
        name = "wish_movie",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_wish_movie", columnNames = {"user_id", "movie_id"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WishMovie extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movie_id", nullable = false)
    private Movie movie;

    private WishMovie(User user, Movie movie) {
        this.user = user;
        this.movie = movie;
    }

    public static WishMovie of(User user, Movie movie) {
        return new WishMovie(user, movie);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WishMovie that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
