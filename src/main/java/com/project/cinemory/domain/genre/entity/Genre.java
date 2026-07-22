package com.project.cinemory.domain.genre.entity;

import com.project.cinemory.domain.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.*;

@Entity
@Table(name = "genre")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)

public class Genre extends BaseTimeEntity{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tmdb_genre_id", nullable = false, unique = true)
    private Integer tmdbGenreId;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    private Genre(Integer tmdbGenreId, String name) {
        this.tmdbGenreId = tmdbGenreId;
        this.name = name;
    }

    public static Genre of(Integer tmdbGenreId, String name) {
        return new Genre(tmdbGenreId, name);
    }

    /** TMDB 동기화 시 이름이 변경된 경우에만 갱신 (불필요한 dirty checking 방지) */
    public void rename(String newName) {
        if (!this.name.equals(newName)) {
            this.name = newName;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Genre genre)) return false;
        return id != null && id.equals(genre.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
