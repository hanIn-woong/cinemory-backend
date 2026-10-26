package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.movie.dto.GenreResponse;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.entity.MovieGenre;
import com.project.cinemory.domain.movie.repository.MovieGenreRepository;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.recommendation.config.RecommendProperties;
import com.project.cinemory.domain.recommendation.dto.RecommendationResponse;
import com.project.cinemory.domain.recommendation.dto.RecommendedMovieResponse;
import com.project.cinemory.domain.recommendation.service.RecommendationCache.Entry;
import com.project.cinemory.domain.recommendation.service.RecommendationResult.RankedMovie;
import com.project.cinemory.global.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 확정된 순위 목록의 한 페이지를 응답 DTO로 만든다 (controller-layer-spec 5-14-C~D). 영화·장르는 IN절 벌크 조회(service-layer-spec 4-2).
 * 읽기 전용 트랜잭션을 여기서만 열고 닫는다 — 질의 임베딩 호출은 이 밖에서 끝나 있다.
 */
@Component
@RequiredArgsConstructor
public class RecommendationResponseAssembler {

    private final MovieRepository movieRepository;
    private final MovieGenreRepository movieGenreRepository;
    private final RecommendProperties properties;

    @Transactional(readOnly = true)
    public RecommendationResponse assemble(Entry entry, int page, int size, boolean degraded) {
        List<RankedMovie> all = entry.movies();
        int total = all.size();
        long fromIndex = (long) page * size;
        // page × size ≥ 100(결과 상한)이면 빈 content, last = true.
        boolean beyond = fromIndex >= properties.maxResults() || fromIndex >= total;
        List<RankedMovie> slice = beyond ? List.of() : all.subList((int) fromIndex, (int) Math.min(total, fromIndex + size));

        Map<Long, Movie> movies = movieRepository.findAllById(slice.stream().map(RankedMovie::movieId).toList()).stream()
                .collect(Collectors.toMap(Movie::getId, m -> m));
        Map<Long, List<GenreResponse>> genres = genresOf(slice);

        List<RecommendedMovieResponse> content = slice.stream()
                .filter(r -> movies.containsKey(r.movieId()))   // 캐시 수명 안에 삭제된 영화는 건너뛴다
                .map(r -> {
                    Movie m = movies.get(r.movieId());
                    return new RecommendedMovieResponse(m.getId(), m.displayTitle(), m.getPosterPath(), m.getReleaseDate(),
                            genres.getOrDefault(m.getId(), List.of()), r.reason(), r.wished());
                })
                .toList();

        int totalPages = total == 0 ? 0 : (int) Math.ceil(total / (double) size);
        boolean last = beyond || fromIndex + size >= total;
        return new RecommendationResponse(
                new PageResponse<>(content, page, size, total, totalPages, page == 0, last),
                entry.personalized(), entry.conditions(), degraded);
    }

    private Map<Long, List<GenreResponse>> genresOf(List<RankedMovie> slice) {
        if (slice.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<GenreResponse>> genres = new HashMap<>();
        for (MovieGenre mg : movieGenreRepository.findByMovieIdIn(slice.stream().map(RankedMovie::movieId).toList())) {
            genres.computeIfAbsent(mg.getMovie().getId(), k -> new ArrayList<>()).add(GenreResponse.from(mg.getGenre()));
        }
        genres.values().forEach(list -> list.sort(Comparator.comparing(GenreResponse::id)));
        return genres;
    }
}
