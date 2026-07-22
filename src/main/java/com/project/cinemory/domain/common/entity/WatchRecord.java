package com.project.cinemory.domain.common.entity;

import com.project.cinemory.domain.movie.entity.Movie;
import jakarta.persistence.*;
import lombok.*;
import jakarta.persistence.Id;

import java.time.LocalDate;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED) //접근 가능 정도
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class WatchRecord extends BaseCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movie_id")
    private Movie movie;

    private LocalDate watchDate;

    private Double rating;

    @Column(length = 1000)
    private String review;
}
