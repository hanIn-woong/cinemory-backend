package com.project.cinemory.domain.movie.entity;

import com.project.cinemory.domain.common.entity.BaseCreatedAtEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;


@Entity
@Table(name = "movie")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // 빈 객체 생성 방어
public class Movie extends BaseCreatedAtEntity {

    @Id // PK
    @GeneratedValue(strategy = GenerationType.IDENTITY) //데이터가 들어올 때마다 자동 번호 입력
    private Long id;

    @Column(name = "tmdb_id", nullable = false, unique = true)
    private Long tmdbId;

    @Column(name = "kofic_movie_cd", unique = true, length = 20)
    private String koficMovieCd;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "poster_path")
    private String posterPath;

    @Column(name = "release_date")
    private LocalDate releaseDate;

    @Column(name = "overview", length = 1000)
    private String overview;

    @Column(name = "runtime")
    private Integer runtime;

    @Builder
    private Movie(Long tmdbId, String koficMovieCd, String title, String posterPath,
                  LocalDate releaseDate, String overview, Integer runtime) {
        this.tmdbId = tmdbId;
        this.koficMovieCd = koficMovieCd;
        this.title = title;
        this.posterPath = posterPath;
        this.releaseDate = releaseDate;
        this.overview = overview;
        this.runtime = runtime;
    }

    /** KOFIC 박스오피스 배치가 나중에 영화를 매칭시켜줄 때 사용 (최초 TMDB 등록 시점엔 null일 수 있음) */
    public void linkKoficCode(String koficMovieCd) {
        this.koficMovieCd = koficMovieCd;
    }

    public void updateMetadata(String title, String posterPath, String overview, Integer runtime) {
        this.title = title;
        this.posterPath = posterPath;
        this.overview = overview;
        this.runtime = runtime;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Movie movie)) return false;
        return id != null && id.equals(movie.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
