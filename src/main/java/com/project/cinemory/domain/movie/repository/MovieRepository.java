package com.project.cinemory.domain.movie.repository;

import com.project.cinemory.domain.movie.entity.Movie;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MovieRepository extends JpaRepository<Movie, Long> {

    Optional<Movie> findByTmdbId(Long tmdbId);

    // 관리자 대시보드 — 동기화 이력(synced_at)이 없는 영화 수(V26 도입 전 등록분 포함, admin-spec 13-1)
    long countBySyncedAtIsNull();

    boolean existsByTmdbId(Long tmdbId);

    // 박스오피스 수집 배치의 1순위 매칭 — kofic_movie_cd 직접 매칭 (벌크 1쿼리)
    List<Movie> findByKoficMovieCdIn(Collection<String> koficMovieCds);

    // 관리자 수동 연결·해제(admin-spec 17-2·17-3) — uk로 최대 1건
    Optional<Movie> findByKoficMovieCd(String koficMovieCd);

    // 재매칭 배치의 2순위 매칭 — 제목 완전 일치 (동명이인 판별은 서비스에서 처리)
    // title 또는 title_ko 완전 일치(admin-spec BD-13) — TMDB가 영문 제목을 준 영화도 관리자가 넣은 한글 제목으로 박스오피스에 매칭된다.
    // 동명이인 판별(2건 이상이면 보류)은 호출부(BoxOfficeSyncService)가 그대로 한다.
    @Query("select m from Movie m where m.title = :title or m.titleKo = :title")
    List<Movie> findByTitleOrTitleKo(@Param("title") String title);

    // 재매칭 보조 — 공백·문장부호를 뺀 소문자 제목끼리 비교한다(release-build-spec §7 O-2 ③). 입력도 같은 규칙으로 정규화해서 넘긴다.
    @Query("""
        select m from Movie m
        where lower(replace(replace(replace(replace(replace(m.title, ' ', ''), ':', ''), '-', ''), '·', ''), '!', '')) = :normalized
           or lower(replace(replace(replace(replace(replace(m.titleKo, ' ', ''), ':', ''), '-', ''), '·', ''), '!', '')) = :normalized
        """)
    List<Movie> findByNormalizedTitle(@Param("normalized") String normalized);

    // 영화 검색의 registered 섹션 (6-8, 4-2-C) - 정규화 키(search_key)에 대한 LIKE %key%다. 키는 세 제목을 각각 정규화해
    // 공백 한 칸으로 이은 값이라(Movie.refreshSearchKey) 띄어쓰기·문장부호가 달라도 걸리고 제목 경계를 넘는 오탐은 없다.
    // 풀스캔이다 - 선행 와일드카드는 B-tree를 못 타 인덱스는 무의미하다(약 1.3만 행이라 수 ms, 커지면 ngram FULLTEXT).
    // :pattern은 LikePattern.contains(SearchKey.normalize(q)) - 이스케이프 문자는 '!'. 정렬은 Pageable의 Sort.
    @Query("select m from Movie m where m.searchKey like :pattern escape '!'")
    Page<Movie> searchBySearchKey(@Param("pattern") String pattern, Pageable pageable);

    // 자동 완성 (4-2-C ②) - 우리 DB만. 어느 한 제목이 검색어로 시작하면(키 맨 앞 또는 구분 공백 바로 뒤) 먼저,
    // 그 안에서 vote_count 많은 순, 마지막으로 id. 패턴은 모두 LikePattern 이스케이프를 거친다.
    @Query("""
            select m from Movie m
            where m.searchKey like :contains escape '!'
            order by case when (m.searchKey like :prefix escape '!' or m.searchKey like :afterSeparator escape '!')
                          then 0 else 1 end,
                     coalesce(m.voteCount, 0) desc, m.id asc
            """)
    List<Movie> autocomplete(@Param("contains") String contains, @Param("prefix") String prefix,
                             @Param("afterSeparator") String afterSeparator, Pageable pageable);

    // 관리자 영화 목록 (admin-spec 3-4) — 제목/원제 부분 일치(LikePattern, 이스케이프 문자 '!') AND tmdbId 정확 일치(선택).
    // 정렬은 호출한 Pageable의 Sort(AdminMovieSort).
    @Query("""
            select m from Movie m
            where (m.title like :pattern escape '!' or m.originalTitle like :pattern escape '!'
                   or m.titleKo like :pattern escape '!')
              and (:tmdbId is null or m.tmdbId = :tmdbId)
            """)
    Page<Movie> searchForAdmin(@Param("pattern") String pattern, @Param("tmdbId") Long tmdbId, Pageable pageable);

    // 영화 검색의 suggestions 중복 제거 (6-8) — TMDB 결과 중 이미 등록된 tmdbId를 걸러낸다.
    List<Movie> findByTmdbIdIn(Collection<Long> tmdbIds);

    // resync의 id 커서 (6-9) — 조건 없이 전체를 돈다. updated_at 기준을 기각한 이유는
    // MovieSeedService.resync 문서 참고 (dirty check가 무변경 시 UPDATE를 안 내 같은 행을 계속 잡는다).
    List<Movie> findByIdGreaterThanOrderByIdAsc(Long fromId, Pageable pageable);

    // 홈 화면 배경용 랜덤 표본 (2026-09-02 추가, 5-2 참고). 추천 제외 영화(M3b R-5-1)는 :excludedIds로 뺀다 - 첫 화면에 선정적 포스터가 뜨지 않게.
    // ⚠️ :excludedIds는 비어 있으면 안 된다(빈 IN 목록은 SQL 오류). 호출부가 없으면 존재하지 않는 id(-1)를 하나 넣는다.
    // ⚠️ RAND()는 JPQL 표준이 아니므로 native query여야 한다.
    // ⚠️ poster_path IS NOT NULL 필터가 이 메서드의 존재 이유다 — 배경에 빈칸이 생기지 않게 한다.
    // ⚠️ Movie.sortTitle은 @Formula(V29, BD-11)라 native SELECT *에는 컬럼이 없다 — 같은 식을 별칭으로 내려줘야
    // 엔티티 매핑이 "Column 'sorttitle' not found"로 500이 나지 않는다(게스트 홈 배경이 이 쿼리만 쓴다).
    @Query(value = "SELECT m.*, COALESCE(m.title_ko, m.title) AS sortTitle FROM movie m "
                 + "WHERE m.poster_path IS NOT NULL AND m.id NOT IN (:excludedIds) ORDER BY RAND() LIMIT :size",
           nativeQuery = true)
    List<Movie> findRandomWithPoster(@Param("size") int size, @Param("excludedIds") Collection<Long> excludedIds);
}
