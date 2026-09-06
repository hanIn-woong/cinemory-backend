package com.project.cinemory.domain.genre.service;

import com.project.cinemory.domain.genre.entity.Genre;
import com.project.cinemory.domain.genre.repository.GenreRepository;
import com.project.cinemory.global.infra.tmdb.TmdbClient;
import com.project.cinemory.global.infra.tmdb.dto.TmdbGenreListResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * TMDB 장르 참조 테이블 선행 적재 (6-1).
 *
 * <p><b>영화 동기화보다 반드시 먼저 실행해야 한다.</b> {@code genre}가 비어 있으면
 * {@code movie_genre}를 만들 수 없고, 동기화는 미등록 장르를 만나면 그 자리에서
 * 생성하지 않고 {@code GENRE_NOT_FOUND}로 실패시킨다 — 참조 테이블의 출처를
 * 이 시드 하나로 유지해 정체불명 행이 생기지 않게 하기 위함이다.
 *
 * <p>멱등하다. 재실행하면 신규만 저장하고 기존은 이름만 갱신한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenreSeedService {

    private final TmdbClient tmdbClient;
    private final GenreRepository genreRepository;

    /**
     * TMDB 장르 목록을 읽어 upsert한다.
     *
     * @return 신규 적재 건수 (갱신 건수는 로그로만 남긴다)
     */
    @Transactional
    public int seedAll() {
        List<TmdbGenreListResponse.Item> items = tmdbClient.fetchMovieGenres();
        if (items.isEmpty()) {
            log.warn("TMDB 장르 목록이 비어 있어 적재를 건너뜁니다.");
            return 0;
        }

        // TMDB가 같은 id를 두 번 내려보내는 경우를 방어한다.
        // uk_genre_tmdb_id 위반으로 트랜잭션 전체가 롤백되는 것보다 앞단에서 접는 편이 낫다.
        Map<Integer, TmdbGenreListResponse.Item> byTmdbId = items.stream()
                .filter(item -> item.id() != null && item.name() != null && !item.name().isBlank())
                .collect(Collectors.toMap(
                        TmdbGenreListResponse.Item::id,
                        Function.identity(),
                        (first, duplicate) -> first,
                        LinkedHashMap::new));

        // 기존 행을 1쿼리로 읽어 신규/기존을 가른다
        Map<Integer, Genre> existing = genreRepository.findByTmdbGenreIdIn(byTmdbId.keySet()).stream()
                .collect(Collectors.toMap(Genre::getTmdbGenreId, Function.identity()));

        List<Genre> toInsert = new ArrayList<>();
        int updated = 0;

        for (TmdbGenreListResponse.Item item : byTmdbId.values()) {
            Genre found = existing.get(item.id());
            if (found == null) {
                toInsert.add(Genre.of(item.id(), item.name()));
            } else {
                // rename()이 값 비교 후에만 바꾸므로 실제 변경이 없으면 UPDATE가 나가지 않는다
                found.rename(item.name());
                updated++;
            }
        }

        genreRepository.saveAll(toInsert);
        log.info("TMDB 장르 시드 완료. 신규={}, 기존={}", toInsert.size(), updated);
        return toInsert.size();
    }
}
