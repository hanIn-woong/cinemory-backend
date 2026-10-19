package com.project.cinemory.domain.movie.service;

import com.project.cinemory.domain.country.entity.Country;
import com.project.cinemory.domain.country.repository.CountryRepository;
import com.project.cinemory.domain.genre.entity.Genre;
import com.project.cinemory.domain.genre.repository.GenreRepository;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieGenreRepository;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.tmdb.TmdbClient;
import com.project.cinemory.global.infra.tmdb.dto.TmdbGenreListResponse;
import com.project.cinemory.global.infra.tmdb.dto.TmdbMovieDetailResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 단건 재동기화 {@code MovieSeedService.resyncOne}(admin-spec AD-9, 테스트 7절). TMDB는 대역으로 바꾼다.
 * 참조 테이블 가드를 통과시키려고 장르·국가 행을 하나씩 직접 넣는다(롤백된다).
 */
@SpringBootTest
@Transactional
class MovieResyncOneTest {

    private static final long TMDB_ID = 98_301L;

    @Autowired
    private MovieSeedService movieSeedService;
    @Autowired
    private MovieRepository movieRepository;
    @Autowired
    private GenreRepository genreRepository;
    @Autowired
    private CountryRepository countryRepository;
    @Autowired
    private MovieGenreRepository movieGenreRepository;

    @MockitoBean
    private TmdbClient tmdbClient;

    private Movie movie;

    @BeforeEach
    void setUp() {
        genreRepository.save(Genre.of(990_301, "재동기화테스트장르"));
        countryRepository.save(Country.of("ZY", "재동기화테스트국"));
        movie = movieRepository.save(Movie.builder().tmdbId(TMDB_ID).title("옛 제목").build());
    }

    private TmdbMovieDetailResponse detail(String title) {
        return new TmdbMovieDetailResponse(TMDB_ID, false, title, "Original", "/poster.jpg", "2024-05-01",
                "줄거리", 120, "/backdrop.jpg", 8.4, 1234,
                List.of(new TmdbGenreListResponse.Item(990_301, "재동기화테스트장르")), null, null, null);
    }

    @Test
    void 성공하면_같은_행이_갱신되고_id는_불변이며_행도_늘지_않고_매핑은_교체된다() {
        when(tmdbClient.fetchMovieDetail(TMDB_ID)).thenReturn(detail("새 제목"));
        long before = movieRepository.count();

        movieSeedService.resyncOne(movie.getId());
        movieSeedService.resyncOne(movie.getId());   // 두 번 해도 매핑이 중복되지 않는다

        Movie reloaded = movieRepository.findById(movie.getId()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("새 제목");
        assertThat(reloaded.getVoteCount()).isEqualTo(1234);
        assertThat(movieRepository.count()).isEqualTo(before);
        assertThat(movieRepository.findByTmdbId(TMDB_ID)).get().extracting(Movie::getId).isEqualTo(movie.getId());
        assertThat(movieGenreRepository.findByMovieId(movie.getId())).hasSize(1);
    }

    @Test
    void 일괄_작업이_실행_중이면_409이고_TMDB를_부르지_않는다() {
        AtomicBoolean running = (AtomicBoolean) ReflectionTestUtils.getField(movieSeedService, "running");
        running.set(true);
        try {
            assertThatThrownBy(() -> movieSeedService.resyncOne(movie.getId()))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SEED_ALREADY_RUNNING));
            verify(tmdbClient, never()).fetchMovieDetail(anyLong());
        } finally {
            running.set(false);   // 빈이 공유되므로 반드시 되돌린다
        }
    }

    @Test
    void 끝나면_플래그가_풀려_다음_호출이_가능하다() {
        when(tmdbClient.fetchMovieDetail(TMDB_ID)).thenThrow(new BusinessException(ErrorCode.TMDB_RATE_LIMITED));

        assertThatThrownBy(() -> movieSeedService.resyncOne(movie.getId())).isInstanceOf(BusinessException.class);

        AtomicBoolean running = (AtomicBoolean) ReflectionTestUtils.getField(movieSeedService, "running");
        assertThat(running.get()).isFalse();
    }

    @Test
    void TMDB_429와_404는_흡수되지_않고_그대로_전파되며_우리_행은_유지된다() {
        when(tmdbClient.fetchMovieDetail(TMDB_ID)).thenThrow(new BusinessException(ErrorCode.TMDB_RATE_LIMITED));
        assertThatThrownBy(() -> movieSeedService.resyncOne(movie.getId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TMDB_RATE_LIMITED));

        doThrow(new BusinessException(ErrorCode.TMDB_MOVIE_NOT_FOUND)).when(tmdbClient).fetchMovieDetail(TMDB_ID);
        assertThatThrownBy(() -> movieSeedService.resyncOne(movie.getId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TMDB_MOVIE_NOT_FOUND));

        assertThat(movieRepository.findById(movie.getId())).get().extracting(Movie::getTitle).isEqualTo("옛 제목");
    }

    @Test
    void 없는_영화는_MOVIE_NOT_FOUND다() {
        assertThatThrownBy(() -> movieSeedService.resyncOne(Long.MAX_VALUE))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MOVIE_NOT_FOUND));
    }
}
