package com.project.cinemory.domain.common.entity;

import com.project.cinemory.domain.movie.entity.Movie;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "collection_movie",
        uniqueConstraints = {                   //복합 유니크 제약 조건 기입
                @UniqueConstraint(
                        name = "uk_collection_movie",
                        columnNames = {"collection_id", "movie_id"}
                )
        })
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CollectionMovie extends BaseCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "collection_id")
    private Collection collection;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movie_id")
    private Movie movie;
}
