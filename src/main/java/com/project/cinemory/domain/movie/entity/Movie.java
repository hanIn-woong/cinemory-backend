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

    // length 1000은 TMDB의 입력 제한과 같은 값이다 (TMDB가 overview를 1000자로 제한한다).
    // v11에서 4000으로 넓혔다가 v12에서 되돌렸다 — 넓힌 근거였던 "1000자를 넘을 수 있다"가
    // 사실이 아니었다. 자세한 경위는 tmdb-sync-spec D-4 참고.
    //
    // 상한과 TMDB 제한이 같아 여유가 0이므로, 초과 시 절단(997자 + "...")은 그대로 남긴다.
    // 절단은 MovieSyncService 책임이며 엔티티는 검증하지 않는다 —
    // 상한 초과는 외부 API 계약 변화지 도메인 불변식이 아니다.
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

    /**
     * ⚠️ {@code title}/{@code posterPath}/{@code overview}가 같은 타입(String)으로
     * 연속이라 순서를 바꿔도 컴파일된다. 호출부가 {@code MovieSyncPersister} 한 곳뿐이므로
     * 값 객체 대신 {@code @Builder} 필드 순서와 동일하게 유지해 리뷰로 잡는다
     * (tmdb-sync-spec 6-4).
     */
    public void updateMetadata(String title, String posterPath, String overview,
                                Integer runtime, LocalDate releaseDate) {
        this.title = title;
        this.posterPath = posterPath;
        this.overview = overview;
        this.runtime = runtime;
        this.releaseDate = releaseDate;
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
