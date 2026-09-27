package com.project.cinemory.domain.collection.entity;

import com.project.cinemory.domain.common.entity.BaseTimeEntity;
import com.project.cinemory.domain.movie.entity.Movie;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.*;

@Entity
@Table(
        name = "collection_movie",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_collection_movie", columnNames = {"collection_id", "movie_id"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CollectionMovie extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "collection_id", nullable = false)
    private Collection collection;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movie_id", nullable = false)
    private Movie movie;

    // 컬렉션 안의 사용자 지정 순서(오름차순이 위). 음수·불연속 허용 — Collection.position과 같은 규칙.
    @Column(name = "position", nullable = false)
    private int position;

    private CollectionMovie(Collection collection, Movie movie, int position) {
        this.collection = collection;
        this.movie = movie;
        this.position = position;
    }

    public static CollectionMovie of(Collection collection, Movie movie, int position) {
        return new CollectionMovie(collection, movie, position);
    }

    public void changePosition(int position) {
        this.position = position;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CollectionMovie that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
