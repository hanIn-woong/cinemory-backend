package com.project.cinemory.domain.movie.entity;

import com.project.cinemory.domain.common.entity.BaseCreatedAtEntity;
import com.project.cinemory.domain.company.entity.Company;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.*;

/** 영화 ↔ 제작사 매핑(tmdb-sync-spec 6-12-3). {@code displayOrder}는 TMDB 응답 순서. */
@Entity
@Table(
        name = "movie_company",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_movie_company", columnNames = {"movie_id", "company_id"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MovieCompany extends BaseCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movie_id", nullable = false)
    private Movie movie;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    private MovieCompany(Movie movie, Company company, int displayOrder) {
        this.movie = movie;
        this.company = company;
        this.displayOrder = displayOrder;
    }

    public static MovieCompany of(Movie movie, Company company, int displayOrder) {
        return new MovieCompany(movie, company, displayOrder);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MovieCompany that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
