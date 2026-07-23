package com.project.cinemory.domain.movie.entity;

import com.project.cinemory.domain.common.entity.BaseCreatedAtEntity;
import com.project.cinemory.domain.genre.entity.Genre;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(
        name = "movie_genre",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_movie_genre", columnNames = {"movie_id", "genre_id"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MovieGenre extends BaseCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movie_id", nullable = false)
    private Movie movie;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "genre_id", nullable = false)
    private Genre genre;

    @Column(name = "weight", nullable = false, precision = 4, scale = 3)
    private BigDecimal weight;

    private MovieGenre(Movie movie, Genre genre, BigDecimal weight) {
        this.movie = movie;
        this.genre = genre;
        this.weight = weight;
    }

    public static MovieGenre of(Movie movie, Genre genre, BigDecimal weight) {
        return new MovieGenre(movie, genre, weight);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MovieGenre that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
