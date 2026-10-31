package com.project.cinemory.domain.movie.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.project.cinemory.domain.movie.dto.MovieSearchResponse;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.global.infra.tmdb.TmdbClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * TMDB 제안 조회가 실패해도 사용자가 친 검색어는 로그에 남지 않는다 (settings-spec ST-10 ③).
 * HTTP 클라이언트 예외의 메시지에는 쿼리가 든 요청 URL이 실려 오므로, 로그 문장의 {@code query=}만 지워서는 부족하다.
 */
@ExtendWith(MockitoExtension.class)
class MovieSearchServiceLogTest {

    private static final String QUERY = "나만아는비밀검색어";

    @Mock
    private MovieRepository movieRepository;
    @Mock
    private TmdbClient tmdbClient;

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(MovieSearchService.class);

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    void TMDB_제안_조회가_실패해도_검색어는_로그에_없고_검색은_빈_제안으로_성공한다() {
        given(movieRepository.findByTitleContainingOrOriginalTitleContainingOrTitleKoContaining(
                anyString(), anyString(), anyString(), any(Pageable.class))).willReturn(Page.empty());
        given(tmdbClient.searchMovieForSuggestions(anyString(), any())).willThrow(new ResourceAccessException(
                "I/O error on GET request for \"https://api.themoviedb.org/3/search/movie?query=" + QUERY + "\""));

        MovieSearchResponse response = new MovieSearchService(movieRepository, tmdbClient).search(QUERY, null, 1);

        assertThat(response.suggestions()).isEqualTo(List.of());
        List<ILoggingEvent> warns = appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertThat(warns).as("실패는 WARN으로 반드시 남는다(감춰진 실패를 볼 수단)").hasSize(1);
        assertThat(warns.get(0).getFormattedMessage()).doesNotContain(QUERY).contains("ResourceAccessException");
        assertThat(warns.get(0).getThrowableProxy()).as("예외 객체를 로그에 넘기지 않는다").isNull();
    }
}
