package com.project.cinemory.domain.recommendation.entity;

import com.project.cinemory.domain.common.entity.BaseTimeEntity;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.global.infra.gemini.VectorCodec;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 영화 한 편의 임베딩 (M3b-recommendation-spec E-4-1). 영화당 1행 — 모델이 바뀌면 덮어쓴다.
 *
 * <p>벡터 변환은 {@link VectorCodec} 한 곳에서만 한다. 이 엔티티는 바이트를 그대로 들고 있다.
 */
@Entity
@Table(
        name = "movie_embedding",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_movie_embedding_movie", columnNames = {"movie_id"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MovieEmbedding extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movie_id", nullable = false)
    private Movie movie;

    @Column(name = "model_name", nullable = false, length = 50)
    private String modelName;

    @Column(name = "dimensions", nullable = false)
    private Short dimensions;

    // float32 x 768, little-endian. VARBINARY(3072) — length를 명시해야 ddl-auto=validate를 통과한다(BLOB 아님).
    @Column(name = "embedding", nullable = false, length = VectorCodec.BYTES)
    private byte[] embedding;

    @Column(name = "source_text", nullable = false, length = 4000)
    private String sourceText;

    // SHA-256 hex. CHAR(64)라 VARCHAR가 아닌 CHAR로 선언해야 validate가 통과한다.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "source_hash", nullable = false, length = 64)
    private String sourceHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "overview_source", nullable = false, length = 10)
    private OverviewSource overviewSource;

    private MovieEmbedding(Movie movie, String modelName, float[] vector, String sourceText,
                           String sourceHash, OverviewSource overviewSource) {
        this.movie = movie;
        replace(modelName, vector, sourceText, sourceHash, overviewSource);
    }

    public static MovieEmbedding of(Movie movie, String modelName, float[] vector, String sourceText,
                                    String sourceHash, OverviewSource overviewSource) {
        return new MovieEmbedding(movie, modelName, vector, sourceText, sourceHash, overviewSource);
    }

    /** 텍스트·모델이 바뀌어 다시 임베딩했을 때 내용을 통째로 교체한다. */
    public void replace(String modelName, float[] vector, String sourceText,
                        String sourceHash, OverviewSource overviewSource) {
        this.modelName = modelName;
        this.dimensions = (short) vector.length;
        this.embedding = VectorCodec.encode(vector);
        this.sourceText = sourceText;
        this.sourceHash = sourceHash;
        this.overviewSource = overviewSource;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MovieEmbedding that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
