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

    @Column(name = "character_name", length = 255)
    private String characterName;

    /**
     * TMDB {@code credits.cast[].order} 원본값. 출연진 표시 순서의 유일한 근거다.
     *
     * <p>재동기화가 "전량 삭제 후 재삽입"이라 {@code id} 삽입 순은 매 동기화마다 흔들린다.
     * 수정 메서드를 두지 않는 이유도 같다 — 갱신 경로 자체가 없다.
     *
     * <p>⚠️ 배열 인덱스가 아니라 {@code order} 필드값이다. TMDB는 연속 정수를 보장하지 않는다.
     */
    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_tier", nullable = false)
    private RoleTier roleTier;

    @Builder
    private MovieActor(Movie movie, Person person, String characterName,
                       Integer displayOrder, RoleTier roleTier) {
        this.movie = movie;
        this.person = person;
        this.characterName = characterName;
        this.displayOrder = displayOrder;
        this.roleTier = roleTier;
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
