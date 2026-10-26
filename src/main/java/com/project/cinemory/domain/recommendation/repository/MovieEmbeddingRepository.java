package com.project.cinemory.domain.recommendation.repository;

import com.project.cinemory.domain.recommendation.entity.MovieEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface MovieEmbeddingRepository extends JpaRepository<MovieEmbedding, Long> {

    /** 배치의 해시 비교용 — movie_id IN 벌크 조회(service-layer-spec 4-2). 벡터·본문을 읽지 않도록 해시만 가져온다. */
    @Query("select e.movie.id as movieId, e.sourceHash as sourceHash from MovieEmbedding e where e.movie.id in :movieIds")
    List<HashProjection> findHashesByMovieIdIn(@Param("movieIds") Collection<Long> movieIds);

    /** 갱신 대상 행 조회 — 영속 상태로 받아 replace()의 dirty checking에 맡긴다. */
    @Query("select e from MovieEmbedding e where e.movie.id in :movieIds")
    List<MovieEmbedding> findByMovieIdIn(@Param("movieIds") Collection<Long> movieIds);

    /** 인덱스 적재용 — 현재 모델·차원과 일치하는 행만. 모델 교체 중 섞인 옛 벡터는 무시한다(E-4-5). 단위 청크 조회에 쓴다. */
    @Query("select e.movie.id as movieId, e.embedding as embedding from MovieEmbedding e " +
            "where e.modelName = :modelName and e.dimensions = :dimensions and e.movie.id > :afterMovieId " +
            "order by e.movie.id asc")
    List<VectorProjection> findVectorsAfter(@Param("modelName") String modelName,
                                           @Param("dimensions") Short dimensions,
                                           @Param("afterMovieId") Long afterMovieId,
                                           org.springframework.data.domain.Pageable pageable);

    @Query("select count(e) from MovieEmbedding e where e.modelName = :modelName and e.dimensions = :dimensions")
    long countByModel(@Param("modelName") String modelName, @Param("dimensions") Short dimensions);

    interface HashProjection {
        Long getMovieId();

        String getSourceHash();
    }

    interface VectorProjection {
        Long getMovieId();

        byte[] getEmbedding();
    }
}
