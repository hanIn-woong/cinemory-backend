package com.project.cinemory.domain.movie.entity;

import com.project.cinemory.domain.common.entity.BaseCreatedAtEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Formula;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;


@Entity
@Table(name = "movie")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // 빈 객체 생성 방어
public class Movie extends BaseCreatedAtEntity {

    public static final int TITLE_KO_MAX_LENGTH = 255;

    @Id // PK
    @GeneratedValue(strategy = GenerationType.IDENTITY) //데이터가 들어올 때마다 자동 번호 입력
    private Long id;

    @Column(name = "tmdb_id", nullable = false, unique = true)
    private Long tmdbId;

    @Column(name = "kofic_movie_cd", unique = true, length = 20)
    private String koficMovieCd;

    @Column(name = "title", nullable = false)
    private String title;

    // 관리자가 직접 넣는 한글 제목(V29, admin-spec BD-10). TMDB 재동기화는 건드리지 않는다 — title은 TMDB 값으로 남는다.
    @Column(name = "title_ko", length = 255)
    private String titleKo;

    // 제목순 정렬 전용(BD-11) — 화면에 보이는 제목(displayTitle) 기준으로 정렬하려는 읽기 전용 파생 값. 표시에는 displayTitle()을 쓴다.
    @Formula("COALESCE(title_ko, title)")
    private String sortTitle;

    // 원어 제목. 검색 매칭용 (v13) — LIKE '%avatar%'가 ko-KR title에는 안 걸리는 문제를
    // 실측해서 추가했다. 이미 TMDB 응답에서 title 폴백(resolveTitle)으로 받고 있었으나
    // 저장하지 않고 있었다. tmdb-sync-spec 6-9
    @Column(name = "original_title")
    private String originalTitle;

    @Column(name = "poster_path")
    private String posterPath;

    // 상세 화면 16:9 배경. null이 흔하다(인지도 낮은 작품) — 프론트에 폴백이 필요하다.
    @Column(name = "backdrop_path")
    private String backdropPath;

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

    // 한국어 태그라인(V30). 없으면 NULL — 번역률은 백필 후 실측한다. 임베딩 입력용(tmdb-sync-spec 6-11).
    @Column(name = "tagline", length = 255)
    private String tagline;

    // 영문 줄거리(V30). overview(ko) 유무와 무관하게 항상 저장한다 — 무엇을 쓸지는 소비 쪽(M3-b E-2)이 정한다.
    @Column(name = "overview_en", length = 1000)
    private String overviewEn;

    @Column(name = "runtime")
    private Integer runtime;

    // TMDB가 소수 첫째 자리까지 주고 최대 10.0이라 decimal(3,1)이 정확히 맞는다.
    // double이면 8.433이 그대로 들어와 표시할 때마다 반올림이 필요해진다 (v13).
    @Column(name = "vote_average", precision = 3, scale = 1)
    private BigDecimal voteAverage;

    // 평점 신뢰도 표시 + M3 콜드 스타트용 하한. voteAverage와 항상 세트로 쓴다 —
    // "3표 10.0"과 "22,061표 8.4"가 평점만으로는 구별되지 않는다 (v13).
    @Column(name = "vote_count")
    private Integer voteCount;

    // 마지막 TMDB 동기화 시각 (V26). 도입 전에 등록된 영화는 NULL — 백필하지 않는다(admin-spec AD-10).
    @Column(name = "synced_at")
    private LocalDateTime syncedAt;

    @Builder
    private Movie(Long tmdbId, String koficMovieCd, String title, String originalTitle,
                  String posterPath, String backdropPath, LocalDate releaseDate, String overview,
                  Integer runtime, BigDecimal voteAverage, Integer voteCount,
                  String tagline, String overviewEn) {
        this.tmdbId = tmdbId;
        this.koficMovieCd = koficMovieCd;
        this.title = title;
        this.originalTitle = originalTitle;
        this.posterPath = posterPath;
        this.backdropPath = backdropPath;
        this.releaseDate = releaseDate;
        this.overview = overview;
        this.runtime = runtime;
        this.voteAverage = voteAverage;
        this.voteCount = voteCount;
        this.tagline = tagline;
        this.overviewEn = overviewEn;
    }

    /** TMDB 동기화가 끝난 시각을 남긴다. 호출부는 {@code MovieSyncPersister} 한 곳이다. */
    public void markSynced(LocalDateTime syncedAt) {
        this.syncedAt = syncedAt;
    }

    /**
     * 한글 제목을 바꾼다 — 앞뒤 공백을 자르고, 빈 문자열·{@code null}은 "해제"({@code null})로 저장한다.
     * 255자를 넘으면 {@code IllegalArgumentException}(요청 검증이 먼저 막지만 도메인 불변식으로도 지킨다).
     */
    public void changeTitleKo(String titleKo) {
        String normalized = titleKo == null ? null : titleKo.trim();
        if (normalized != null && normalized.isEmpty()) {
            normalized = null;
        }
        if (normalized != null && normalized.length() > TITLE_KO_MAX_LENGTH) {
            throw new IllegalArgumentException("한글 제목은 " + TITLE_KO_MAX_LENGTH + "자를 넘을 수 없습니다.");
        }
        this.titleKo = normalized;
    }

    /** 앱·리포트에 내보내는 표시 제목 — 한글 제목이 있으면 그것, 없으면 TMDB 제목(admin-spec BD-10). */
    public String displayTitle() {
        return titleKo != null ? titleKo : title;
    }

    /** KOFIC 박스오피스 배치가 나중에 영화를 매칭시켜줄 때 사용 (최초 TMDB 등록 시점엔 null일 수 있음) */
    public void linkKoficCode(String koficMovieCd) {
        this.koficMovieCd = koficMovieCd;
    }

    /**
     * ⚠️ {@code title}/{@code posterPath}/{@code overview}가 같은 타입(String)으로
     * 연속이고, v13에서 추가된 {@code originalTitle}/{@code backdropPath}도 마찬가지라
     * 순서를 바꿔도 컴파일된다. 호출부가 {@code MovieSyncPersister} 한 곳뿐이므로
     * 값 객체 대신 파라미터 순서를 유지해 리뷰로 잡는다 — 다만 파라미터가 9개가 되어
     * 이 전제가 약해지고 있다. 값 객체 재검토는 tmdb-sync-spec 잔여 #25 참고.
     *
     * <p>⚠️ V30(6-11)으로 {@code tagline}/{@code overviewEn}이 끝에 붙어 연속 {@code String} 구간이
     * 하나 더 생겼다({@code {tagline, overviewEn}}). 순서를 바꿔도 컴파일되므로 호출부를 반드시 대조할 것.
     */
    public void updateMetadata(String title, String posterPath, String overview,
                                Integer runtime, LocalDate releaseDate,
                                String originalTitle, String backdropPath,
                                BigDecimal voteAverage, Integer voteCount,
                                String tagline, String overviewEn) {
        this.title = title;
        this.posterPath = posterPath;
        this.overview = overview;
        this.runtime = runtime;
        this.releaseDate = releaseDate;
        this.originalTitle = originalTitle;
        this.backdropPath = backdropPath;
        this.voteAverage = voteAverage;
        this.voteCount = voteCount;
        this.tagline = tagline;
        this.overviewEn = overviewEn;
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
