package com.project.cinemory.domain.recommendation.index;

import com.project.cinemory.domain.recommendation.repository.MovieFeatureQueryRepository;
import com.project.cinemory.domain.recommendation.repository.MovieFeatureQueryRepository.CountryRow;
import com.project.cinemory.domain.recommendation.repository.MovieFeatureQueryRepository.GenreRow;
import com.project.cinemory.domain.recommendation.repository.MovieFeatureQueryRepository.MovieRow;
import com.project.cinemory.domain.recommendation.repository.MovieFeatureQueryRepository.PersonRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToLongFunction;

/**
 * 채점용 영화 특징 인덱스 (M3b-recommendation-spec S-1-6 · W-1).
 *
 * <p>취향 추천은 요청마다 전 영화를 채점한다. 매번 1.3만 편을 조인하는 대신 영화별 특징을 메모리에 두고
 * 기동 시 + 임베딩 배치 직후에 다시 만들어 {@link AtomicReference}로 교체한다.
 *
 * <p>카탈로그의 장르·국가 비율 {@code p_cat}(W-1-1 lift 분모)도 함께 계산한다.
 */
@Slf4j
@Component
public class MovieFeatureIndex {

    /** 애니메이션 장르의 TMDB id. */
    static final int ANIMATION_TMDB_GENRE_ID = 16;
    /** 주연 = {@code movie_actor.display_order 0~4} (RoleTier.LEAD). */
    static final int LEAD_MAX_DISPLAY_ORDER = 4;
    static final String KOREA_CODE = "KR";

    private final MovieFeatureQueryRepository queryRepository;

    private final AtomicReference<Snapshot> current = new AtomicReference<>(Snapshot.EMPTY);

    public MovieFeatureIndex(MovieFeatureQueryRepository queryRepository) {
        this.queryRepository = queryRepository;
    }

    /**
     * 불변 스냅샷. 요청은 {@link MovieFeatureIndex#snapshot()}으로 한 번 받아 끝까지 쓴다 — 중간에 교체돼도 일관된 데이터를 본다.
     *
     * @param genreShare   {@code p_cat[장르 id]} — 장르가 있는 영화 전체에서 {@code movie_genre.weight}의 평균. 합 = 1
     * @param countryShare {@code p_cat[국가 id]} — 같은 방식
     */
    public record Snapshot(List<MovieFeature> movies, Map<Long, MovieFeature> byId,
                           Map<Long, Double> genreShare, Map<Long, Double> countryShare) {
        static final Snapshot EMPTY = new Snapshot(List.of(), Map.of(), Map.of(), Map.of());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        try {
            reload();
        } catch (RuntimeException e) {
            log.error("특징 인덱스 초기 적재에 실패했습니다. 빈 인덱스로 시작합니다.", e);
        }
    }

    public Snapshot snapshot() {
        return current.get();
    }

    /** 새 스냅샷을 다 만든 뒤 교체한다. 실패하면 기존 스냅샷이 그대로 남는다. */
    public void reload() {
        long startedAt = System.currentTimeMillis();

        List<MovieRow> movies = queryRepository.findMovies();
        Map<Long, List<GenreRow>> genres = group(queryRepository.findGenres(), GenreRow::movieId);
        Map<Long, List<CountryRow>> countries = group(queryRepository.findCountries(), CountryRow::movieId);
        Map<Long, List<PersonRow>> directors = group(queryRepository.findDirectors(), PersonRow::movieId);
        Map<Long, List<PersonRow>> actors = group(queryRepository.findLeadActors(LEAD_MAX_DISPLAY_ORDER), PersonRow::movieId);

        List<MovieFeature> features = new ArrayList<>(movies.size());
        Map<Long, Double> genreSum = new HashMap<>();
        Map<Long, Double> countrySum = new HashMap<>();
        int moviesWithGenre = 0;
        int moviesWithCountry = 0;

        for (MovieRow m : movies) {
            List<GenreRow> g = genres.getOrDefault(m.movieId(), List.of());
            List<CountryRow> c = countries.getOrDefault(m.movieId(), List.of());

            long[] genreIds = new long[g.size()];
            double[] genreWeights = new double[g.size()];
            boolean animation = false;
            for (int i = 0; i < g.size(); i++) {
                genreIds[i] = g.get(i).genreId();
                genreWeights[i] = g.get(i).weight().doubleValue();
                animation |= g.get(i).tmdbGenreId() == ANIMATION_TMDB_GENRE_ID;
                genreSum.merge(genreIds[i], genreWeights[i], Double::sum);
            }
            if (!g.isEmpty()) {
                moviesWithGenre++;
            }

            long[] countryIds = new long[c.size()];
            double[] countryWeights = new double[c.size()];
            for (int i = 0; i < c.size(); i++) {
                countryIds[i] = c.get(i).countryId();
                countryWeights[i] = c.get(i).weight().doubleValue();
                countrySum.merge(countryIds[i], countryWeights[i], Double::sum);
            }
            if (!c.isEmpty()) {
                moviesWithCountry++;
            }

            features.add(new MovieFeature(
                    m.movieId(),
                    genreIds, genreWeights,
                    countryIds, countryWeights,
                    personIds(directors.get(m.movieId())),
                    personIds(actors.get(m.movieId())),
                    animation,
                    isKorean(c),
                    m.runtime(),
                    m.releaseDate(),
                    m.voteAverage() == null ? null : m.voteAverage().doubleValue(),
                    m.voteCount()));
        }

        Map<Long, MovieFeature> byId = new HashMap<>(features.size() * 2);
        for (MovieFeature f : features) {
            byId.put(f.movieId(), f);
        }
        current.set(new Snapshot(
                Collections.unmodifiableList(features),
                Collections.unmodifiableMap(byId),
                share(genreSum, moviesWithGenre),
                share(countrySum, moviesWithCountry)));
        log.info("특징 인덱스 적재 완료. movies={}, elapsed={}ms", features.size(), System.currentTimeMillis() - startedAt);
    }

    /** 대표국 = {@code movie_country.weight} 최댓값. KR의 weight가 최댓값과 같으면 한국 영화다(동률이면 KR 우선). */
    static boolean isKorean(List<CountryRow> countries) {
        if (countries.isEmpty()) {
            return false;
        }
        BigDecimal max = countries.stream().map(CountryRow::weight).max(BigDecimal::compareTo).orElseThrow();
        return countries.stream().anyMatch(c -> KOREA_CODE.equals(c.code()) && c.weight().compareTo(max) == 0);
    }

    private static Map<Long, Double> share(Map<Long, Double> sums, int movieCount) {
        Map<Long, Double> shares = new HashMap<>(sums.size() * 2);
        if (movieCount > 0) {
            sums.forEach((id, sum) -> shares.put(id, sum / movieCount));
        }
        return Collections.unmodifiableMap(shares);
    }

    private static long[] personIds(List<PersonRow> rows) {
        return rows == null ? new long[0] : rows.stream().mapToLong(PersonRow::personId).toArray();
    }

    private static <T> Map<Long, List<T>> group(List<T> rows, ToLongFunction<T> movieId) {
        Map<Long, List<T>> grouped = new HashMap<>();
        for (T row : rows) {
            grouped.computeIfAbsent(movieId.applyAsLong(row), k -> new ArrayList<>()).add(row);
        }
        return grouped;
    }
}
