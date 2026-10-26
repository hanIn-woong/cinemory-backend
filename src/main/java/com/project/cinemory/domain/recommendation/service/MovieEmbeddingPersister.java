package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.recommendation.entity.MovieEmbedding;
import com.project.cinemory.domain.recommendation.repository.MovieEmbeddingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 임베딩 결과 저장 전담 빈 (tmdb-sync-spec 6-4 원칙 — HTTP는 트랜잭션 밖, 저장만 여기서).
 * {@link EmbeddingSyncService}와 같은 클래스로 합치면 자기호출이라 {@code @Transactional}이 무시된다.
 */
@Component
@RequiredArgsConstructor
public class MovieEmbeddingPersister {

    private final MovieEmbeddingRepository embeddingRepository;
    private final MovieRepository movieRepository;

    /** 임베딩 결과 한 건. */
    public record Item(long movieId, EmbeddingText text, String hash, float[] vector) {
    }

    /** 행이 있으면 {@code replace}, 없으면 생성한다. 한 번의 호출 = 한 트랜잭션. */
    @Transactional
    public void upsert(String modelName, Collection<Item> items) {
        if (items.isEmpty()) {
            return;
        }
        Map<Long, MovieEmbedding> existing = embeddingRepository
                .findByMovieIdIn(items.stream().map(Item::movieId).toList()).stream()
                .collect(Collectors.toMap(e -> e.getMovie().getId(), Function.identity()));

        List<MovieEmbedding> created = new ArrayList<>();
        for (Item item : items) {
            MovieEmbedding row = existing.get(item.movieId());
            if (row != null) {
                row.replace(modelName, item.vector(), item.text().sourceText(), item.hash(), item.text().overviewSource());
            } else {
                created.add(MovieEmbedding.of(movieRepository.getReferenceById(item.movieId()), modelName,
                        item.vector(), item.text().sourceText(), item.hash(), item.text().overviewSource()));
            }
        }
        embeddingRepository.saveAll(created);
    }
}
