package com.project.cinemory.domain.recommendation.repository;

import com.project.cinemory.domain.movie.entity.KrCertification;
import com.project.cinemory.domain.movie.entity.RecommendOverride;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * {@code MovieFeatureIndex} 적재용 전량 스캔 (M3b-recommendation-spec S-1-6).
 *
 * <p>엔티티가 아니라 필요한 컬럼만 읽는다 — 1.3만 편 x 연관을 엔티티로 올리면 영속성 컨텍스트가 비대해진다.
 * 모든 쿼리는 id 순으로 정렬해 적재 결과가 결정적이다.
 */
@Repository
public class MovieFeatureQueryRepository {

    @PersistenceContext
    private EntityManager em;

    public record MovieRow(long movieId, Integer runtime, LocalDate releaseDate, BigDecimal voteAverage, Integer voteCount,
                           KrCertification krCertification, RecommendOverride recommendOverride) {
    }

    public record GenreRow(long movieId, long genreId, int tmdbGenreId, BigDecimal weight) {
    }

    public record CountryRow(long movieId, long countryId, String code, BigDecimal weight) {
    }

    public record PersonRow(long movieId, long personId) {
    }

    @Transactional(readOnly = true)
    public List<MovieRow> findMovies() {
        return em.createQuery("select m.id, m.runtime, m.releaseDate, m.voteAverage, m.voteCount, m.krCertification, m.recommendOverride from Movie m order by m.id", Object[].class)
                .getResultList().stream()
                .map(r -> new MovieRow((Long) r[0], (Integer) r[1], (LocalDate) r[2], (BigDecimal) r[3], (Integer) r[4],
                        (KrCertification) r[5], (RecommendOverride) r[6]))
                .toList();
    }

    /**
     * 차단 키워드가 붙은 영화 id (추천 제외 자동 판정 ① 보조 규칙, R-5-1). 키워드 이름은 대소문자를 무시해 비교한다.
     * 전량 조인 대신 차단 목록에 걸리는 행만 읽는다. 목록이 비면 쿼리를 보내지 않는다.
     *
     * @param lowerCaseNames 소문자로 정규화한 차단 키워드
     */
    @Transactional(readOnly = true)
    public Set<Long> findMovieIdsWithKeyword(Collection<String> lowerCaseNames) {
        if (lowerCaseNames.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(em.createQuery("select distinct mk.movie.id from MovieKeyword mk where lower(mk.keyword.name) in :names", Long.class)
                .setParameter("names", lowerCaseNames)
                .getResultList());
    }

    @Transactional(readOnly = true)
    public List<GenreRow> findGenres() {
        return em.createQuery("select mg.movie.id, mg.genre.id, mg.genre.tmdbGenreId, mg.weight from MovieGenre mg order by mg.movie.id, mg.genre.id", Object[].class)
                .getResultList().stream()
                .map(r -> new GenreRow((Long) r[0], (Long) r[1], (Integer) r[2], (BigDecimal) r[3]))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CountryRow> findCountries() {
        return em.createQuery("select mc.movie.id, mc.country.id, mc.country.code, mc.weight from MovieCountry mc order by mc.movie.id, mc.country.id", Object[].class)
                .getResultList().stream()
                .map(r -> new CountryRow((Long) r[0], (Long) r[1], (String) r[2], (BigDecimal) r[3]))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PersonRow> findDirectors() {
        return em.createQuery("select md.movie.id, md.person.id from MovieDirector md order by md.movie.id, md.person.id", Object[].class)
                .getResultList().stream()
                .map(r -> new PersonRow((Long) r[0], (Long) r[1]))
                .toList();
    }

    /** 주연 = {@code display_order 0~4}(LEAD, RoleTier). */
    @Transactional(readOnly = true)
    public List<PersonRow> findLeadActors(int maxDisplayOrder) {
        return em.createQuery("select ma.movie.id, ma.person.id from MovieActor ma where ma.displayOrder <= :max order by ma.movie.id, ma.displayOrder, ma.person.id", Object[].class)
                .setParameter("max", maxDisplayOrder)
                .getResultList().stream()
                .map(r -> new PersonRow((Long) r[0], (Long) r[1]))
                .toList();
    }
}
