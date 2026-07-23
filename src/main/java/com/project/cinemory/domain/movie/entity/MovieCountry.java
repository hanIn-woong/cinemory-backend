package com.project.cinemory.domain.movie.entity;

import com.project.cinemory.domain.common.entity.BaseCreatedAtEntity;
import com.project.cinemory.domain.country.entity.Country;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(
        name = "movie_country",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_movie_country", columnNames = {"movie_id", "country_id"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MovieCountry extends BaseCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movie_id", nullable = false)
    private Movie movie;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "country_id", nullable = false)
    private Country country;

    @Column(name = "weight", nullable = false, precision = 4, scale = 3)
    private BigDecimal weight;

    private MovieCountry(Movie movie, Country country, BigDecimal weight) {
        this.movie = movie;
        this.country = country;
        this.weight = weight;
    }

    public static MovieCountry of(Movie movie, Country country, BigDecimal weight) {
        return new MovieCountry(movie, country, weight);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MovieCountry that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
