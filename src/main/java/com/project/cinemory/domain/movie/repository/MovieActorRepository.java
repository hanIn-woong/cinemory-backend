package com.project.cinemory.domain.movie.repository;

import com.project.cinemory.domain.movie.entity.MovieActor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MovieActorRepository extends JpaRepository<MovieActor, Long> {

    /**
     * 재동기화 "전량 삭제 후 재삽입"의 삭제 단계 (tmdb-sync-spec 6-4).
     *
     * <p>파생 delete(`void deleteByMovieId(Long)`)를 쓰지 않는 이유 — Spring Data의 파생
     * delete는 엔티티를 전부 select한 뒤 하나씩 {@code remove()}한다(출연진 200명이면
     * select 1 + delete 200회). 벌크 DML로 1문으로 처리한다.
     *
     * <p>{@code flushAutomatically = true}만 쓰고 {@code clearAutomatically}는 쓰지 않는다.
     * 후자는 영속성 컨텍스트를 통째로 비워 작업 중인 {@code Movie}가 detach되고
     * {@code updateMetadata()}의 dirty checking이 사라진다.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from MovieActor ma where ma.movie.id = :movieId")
    void deleteByMovieId(@Param("movieId") Long movieId);

    /** 관리자 인물 목록의 출연 건수(admin-spec 12-3) — 페이지의 인물 ID로 묶어 1쿼리. 출연 기록이 없는 인물은 행이 없다. */
    @Query("select ma.person.id as personId, count(ma) as count from MovieActor ma where ma.person.id in :personIds group by ma.person.id")
    List<com.project.cinemory.domain.person.repository.PersonCreditCountProjection> countGroupByPersonIdIn(
            @Param("personIds") java.util.Collection<Long> personIds);

    /**
     * 임베딩 입력용 주연 후보 벌크 조회(M3-b E-1). {@code display_order <= maxDisplayOrder}만 가져오고
     * 상위 N명 선별·정렬은 호출부가 한다 — 영화당 수백 행인 EXTRA를 읽지 않기 위해서다.
     */
    @EntityGraph(attributePaths = "person")
    @Query("select ma from MovieActor ma where ma.movie.id in :movieIds and ma.displayOrder <= :maxDisplayOrder")
    List<MovieActor> findLeadCandidatesByMovieIdIn(@Param("movieIds") java.util.Collection<Long> movieIds,
                                                   @Param("maxDisplayOrder") Integer maxDisplayOrder);

    /** 관리자 영화 상세의 출연진 수 (admin-spec 3-5) — 목록은 싣지 않는다. */
    long countByMovieId(Long movieId);

    /**
     * 출연진 전체를 TMDB 원본 순서로 조회한다.
     *
     * <p>v11에서 {@code OrderByRoleTierAsc}를 대체했다. 기존 메서드는 MySQL {@code ENUM}이
     * 값을 선언 순서 인덱스로 저장하는 덕에 LEAD→SUPPORTING→MINOR로 <b>우연히</b> 맞고
     * 있었을 뿐, tier 내부 순서는 무보장이었다. cast 전량 저장으로 EXTRA 그룹 하나가
     * 수백 명이 되면서 순서가 사실상 임의가 되므로 명시적 정렬 컬럼으로 바꿨다.
     */
    @EntityGraph(attributePaths = "person")
    List<MovieActor> findByMovieIdOrderByDisplayOrderAsc(Long movieId);

    /**
     * 전체 출연진 페이징 조회 (tmdb-sync-spec 잔여 #7, {@code GET /api/movies/{id}/cast}).
     *
     * <p>상세({@link #findByMovieIdAndDisplayOrderLessThanEqualOrderByDisplayOrderAsc})가
     * 상위 21명만 싣는 대신 이 메서드가 전량(최대 수백 명)을 페이지 단위로 내준다.
     */
    @EntityGraph(attributePaths = "person")
    Page<MovieActor> findByMovieIdOrderByDisplayOrderAsc(Long movieId, Pageable pageable);

    /**
     * 영화 상세용 주요 출연진 조회.
     *
     * <p>cast를 자르지 않고 전량 저장하므로 전체를 내려보내면 응답이 영화당 수백 행이 된다.
     * 상세에는 {@code displayOrder <= maxDisplayOrder}만 싣고, 전체 출연진은 별도
     * 엔드포인트가 맡는다 (controller-layer-spec 잔여 #10).
     */
    @EntityGraph(attributePaths = "person")
    List<MovieActor> findByMovieIdAndDisplayOrderLessThanEqualOrderByDisplayOrderAsc(
            Long movieId, Integer maxDisplayOrder);
}
