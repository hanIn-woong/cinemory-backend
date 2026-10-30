package com.project.cinemory.domain.user.entity;

import com.project.cinemory.domain.common.entity.BaseCreatedAtEntity;
import com.project.cinemory.domain.movie.entity.Movie;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 인생 영화 (profile-spec PR-3) — 사용자당 최대 3편, 순서 있음. {@code user}↔{@code movie} 다대다의 매핑 엔티티.
 * 순서(1~3)와 영화는 각각 사용자 안에서 유일하다. 교체는 엔티티 삭제가 아니라 벌크 삭제 후 새로 넣는다(flush 순서 함정 — v39-delta).
 */
@Entity
@Table(
        name = "user_favorite_movie",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_user_favorite_movie", columnNames = {"user_id", "movie_id"}),
                @UniqueConstraint(name = "uk_user_favorite_position", columnNames = {"user_id", "position"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserFavoriteMovie extends BaseCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movie_id", nullable = false)
    private Movie movie;

    // 1~3 (DB CHECK chk_user_favorite_position). TINYINT 컬럼 — ddl-auto: validate가 INTEGER로 오해하지 않게 JDBC 타입을 명시한다.
    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "position", nullable = false)
    private int position;

    private UserFavoriteMovie(User user, Movie movie, int position) {
        this.user = user;
        this.movie = movie;
        this.position = position;
    }

    public static UserFavoriteMovie of(User user, Movie movie, int position) {
        return new UserFavoriteMovie(user, movie, position);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserFavoriteMovie that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
