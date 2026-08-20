package com.project.cinemory.domain.person.entity;

import com.project.cinemory.domain.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.*;

import java.util.Objects;

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

    /**
     * TMDB 재동기화 시 값이 바뀐 경우에만 갱신한다 (불필요한 dirty checking 방지).
     *
     * <p>{@code Genre.rename()} / {@code Country.rename()}과 같은 원칙이다. 무조건 대입하면
     * cast 재동기화마다 출연진 전원에게 UPDATE가 나간다. {@code profilePath}가 nullable이라
     * {@code Objects.equals}로 비교한다.
     */
    public void updateProfile(String name, String profilePath) {
        if (!Objects.equals(this.name, name)) {
            this.name = name;
        }
        if (!Objects.equals(this.profilePath, profilePath)) {
            this.profilePath = profilePath;
        }
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