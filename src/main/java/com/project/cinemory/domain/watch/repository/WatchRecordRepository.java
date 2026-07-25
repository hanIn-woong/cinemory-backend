package com.project.cinemory.domain.watch.repository;

import com.project.cinemory.domain.watch.entity.WatchRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WatchRecordRepository extends JpaRepository<WatchRecord, Long> {

    Optional<WatchRecord> findByUserIdAndMovieIdAndRepresentativeTrue(Long userId, Long movieId);

    // 대표 삭제 후 재선정 대상 조회 겸, 특정 영화의 전체 시청 기록(회차별) 조회에도 사용
    List<WatchRecord> findByUserIdAndMovieIdOrderByIdDesc(Long userId, Long movieId);

    // "내 영화" 목록 — 대표 기록만, movie는 @EntityGraph로 함께 로딩(N+1 회피)
    @EntityGraph(attributePaths = "movie")
    Page<WatchRecord> findByUserIdAndRepresentativeTrue(Long userId, Pageable pageable);
}
