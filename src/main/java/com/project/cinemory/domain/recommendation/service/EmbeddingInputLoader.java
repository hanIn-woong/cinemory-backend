package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.entity.MovieActor;
import com.project.cinemory.domain.movie.entity.MovieCountry;
import com.project.cinemory.domain.movie.entity.MovieDirector;
import com.project.cinemory.domain.movie.entity.MovieGenre;
import com.project.cinemory.domain.movie.entity.MovieKeyword;
import com.project.cinemory.domain.movie.repository.MovieActorRepository;
import com.project.cinemory.domain.movie.repository.MovieCountryRepository;
import com.project.cinemory.domain.movie.repository.MovieDirectorRepository;
import com.project.cinemory.domain.movie.repository.MovieGenreRepository;
import com.project.cinemory.domain.movie.repository.MovieKeywordRepository;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * id 커서로 영화 한 묶음을 읽어 임베딩 입력({@link EmbeddingTextBuilder.Input})으로 바꾼다.
 *
 * <p>연관 데이터는 IN절 벌크 조회(service-layer-spec 4-2) — 100편이면 쿼리 6회다. 읽기 전용 트랜잭션을 여기서 닫고
 * 반환하므로 호출부(HTTP 호출 구간)는 커넥션을 쥐지 않는다.
 */
@Component
@RequiredArgsConstructor
public class EmbeddingInputLoader {

    // movie_actor.display_order 0~4가 LEAD(RoleTier). 상위 3명은 항상 LEAD지만 order가 비연속일 수 있어 21명 구간(0~20)에서 고른다.
    private static final int LEAD_CANDIDATE_MAX_ORDER = 20;

    private final MovieRepository movieRepository;
    private final MovieGenreRepository movieGenreRepository;
    private final MovieCountryRepository movieCountryRepository;
    private final MovieDirectorRepository movieDirectorRepository;
    private final MovieActorRepository movieActorRepository;
    private final MovieKeywordRepository movieKeywordRepository;

    /** 임베딩 대상 영화 한 편: 영화 id와 조립 결과. */
    public record Prepared(long movieId, EmbeddingText text) {
    }

    @Transactional(readOnly = true)
    public List<Prepared> load(long afterId, int size) {
        List<Movie> movies = movieRepository.findByIdGreaterThanOrderByIdAsc(afterId, PageRequest.of(0, size));
        if (movies.isEmpty()) {
            return List.of();
        }
        List<Long> ids = movies.stream().map(Movie::getId).toList();

        Map<Long, List<MovieGenre>> genres = movieGenreRepository.findByMovieIdIn(ids).stream()
                .collect(Collectors.groupingBy(g -> g.getMovie().getId()));
        Map<Long, List<MovieCountry>> countries = movieCountryRepository.findByMovieIdIn(ids).stream()
                .collect(Collectors.groupingBy(c -> c.getMovie().getId()));
        Map<Long, List<MovieDirector>> directors = movieDirectorRepository.findByMovieIdIn(ids).stream()
                .collect(Collectors.groupingBy(d -> d.getMovie().getId()));
        Map<Long, List<MovieActor>> actors = group(movieActorRepository.findLeadCandidatesByMovieIdIn(ids, LEAD_CANDIDATE_MAX_ORDER),
                a -> a.getMovie().getId());
        Map<Long, List<MovieKeyword>> keywords = movieKeywordRepository.findByMovieIdIn(ids).stream()
                .collect(Collectors.groupingBy(k -> k.getMovie().getId()));

        List<Prepared> result = new ArrayList<>(movies.size());
        for (Movie movie : movies) {
            long id = movie.getId();
            EmbeddingTextBuilder.Input input = new EmbeddingTextBuilder.Input(
                    movie.displayTitle(),
                    movie.getOriginalTitle(),
                    movie.getReleaseDate(),
                    countries.getOrDefault(id, List.of()).stream()
                            .map(c -> new EmbeddingTextBuilder.Country(c.getId(), c.getWeight(), c.getCountry().getName()))
                            .toList(),
                    genres.getOrDefault(id, List.of()).stream()
                            .map(g -> new EmbeddingTextBuilder.Named(g.getGenre().getId(), g.getGenre().getName()))
                            .toList(),
                    movie.getRuntime(),
                    directors.getOrDefault(id, List.of()).stream()
                            .map(d -> new EmbeddingTextBuilder.Named(d.getId(), d.getPerson().displayName()))
                            .toList(),
                    actors.getOrDefault(id, List.of()).stream()
                            .map(a -> new EmbeddingTextBuilder.Actor(a.getDisplayOrder(), a.getPerson().getId(), a.getPerson().displayName()))
                            .toList(),
                    movie.getTagline(),
                    keywords.getOrDefault(id, List.of()).stream()
                            .map(k -> new EmbeddingTextBuilder.Named(k.getId(), k.getKeyword().getName()))
                            .toList(),
                    movie.getOverview(),
                    movie.getOverviewEn());
            result.add(new Prepared(id, EmbeddingTextBuilder.build(input)));
        }
        return result;
    }

    private static <T> Map<Long, List<T>> group(Collection<T> items, java.util.function.Function<T, Long> key) {
        return items.stream().collect(Collectors.groupingBy(key));
    }
}
