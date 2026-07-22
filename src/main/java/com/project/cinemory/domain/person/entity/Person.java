package com.project.cinemory.domain.person.entity;

import com.project.cinemory.domain.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.*;

@Entity
@Table(name = "person")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Person extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tmdb_person_id", nullable = false, unique = true)
    private Long tmdbPersonId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "profile_path", length = 500)
    private String profilePath;

    private Person(Long tmdbPersonId, String name, String profilePath) {
        this.tmdbPersonId = tmdbPersonId;
        this.name = name;
        this.profilePath = profilePath;
    }

    public static Person of(Long tmdbPersonId, String name, String profilePath) {
        return new Person(tmdbPersonId, name, profilePath);
    }

    public void updateProfile(String name, String profilePath) {
        this.name = name;
        this.profilePath = profilePath;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Person person)) return false;
        return id != null && id.equals(person.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}