package com.project.cinemory.domain.boxoffice.entity;

import com.project.cinemory.domain.common.entity.BaseCreatedAtEntity;
import com.project.cinemory.domain.movie.entity.Movie;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(
        name = "box_office_record",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_box_office_record", columnNames = {"target_date", "rank_type", "kofic_movie_cd"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BoxOfficeRecord extends BaseCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "rank_type", nullable = false)
    private RankType rankType;

    @Column(name = "box_office_rank", nullable = false)
    private Integer boxOfficeRank;

    @Column(name = "rank_change")
    private Integer rankChange;

    @Column(name = "is_new", nullable = false)
    private boolean isNew;

    @Column(name = "kofic_movie_cd", nullable = false, length = 20)
    private String koficMovieCd;

    @Column(name = "movie_title_snapshot", nullable = false)
    private String movieTitleSnapshot;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movie_id")
    private Movie movie;

    @Column(name = "sales_amount", nullable = false)
    private Long salesAmount;

    @Column(name = "sales_share", precision = 5, scale = 2)
    private BigDecimal salesShare;

    @Column(name = "audience_count", nullable = false)
    private Integer audienceCount;

    @Column(name = "audience_acc", nullable = false)
    private Long audienceAcc;

    @Column(name = "screen_count")
    private Integer screenCount;

    @Column(name = "show_count")
    private Integer showCount;

    @Builder
    private BoxOfficeRecord(LocalDate targetDate, RankType rankType, Integer boxOfficeRank, Integer rankChange,
                             boolean isNew, String koficMovieCd, String movieTitleSnapshot, Movie movie,
                             Long salesAmount, BigDecimal salesShare, Integer audienceCount, Long audienceAcc,
                             Integer screenCount, Integer showCount) {
        this.targetDate = targetDate;
        this.rankType = rankType;
        this.boxOfficeRank = boxOfficeRank;
        this.rankChange = rankChange;
        this.isNew = isNew;
        this.koficMovieCd = koficMovieCd;
        this.movieTitleSnapshot = movieTitleSnapshot;
        this.movie = movie;
        this.salesAmount = salesAmount != null ? salesAmount : 0L;
        this.salesShare = salesShare;
        this.audienceCount = audienceCount != null ? audienceCount : 0;
        this.audienceAcc = audienceAcc != null ? audienceAcc : 0L;
        this.screenCount = screenCount;
        this.showCount = showCount;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BoxOfficeRecord that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
