package com.project.cinemory.domain.movie.entity;

import com.project.cinemory.domain.common.entity.BaseCreatedAtEntity;
import com.project.cinemory.domain.person.entity.Person;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.*;

@Entity
@Table(
        name = "movie_actor",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_movie_actor", columnNames = {"movie_id", "person_id"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MovieActor extends BaseCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movie_id", nullable = false)
    private Movie movie;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "person_id", nullable = false)
    private Person person;

    @Column(name = "character_name", length = 100)
    private String characterName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_tier", nullable = false)
    private RoleTier roleTier;

    private MovieActor(Movie movie, Person person, String characterName, RoleTier roleTier) {
        this.movie = movie;
        this.person = person;
        this.characterName = characterName;
        this.roleTier = roleTier;
    }

    public static MovieActor of(Movie movie, Person person, String characterName, RoleTier roleTier) {
        return new MovieActor(movie, person, characterName, roleTier);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MovieActor that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
