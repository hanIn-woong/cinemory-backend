package com.project.cinemory.domain.movie;

import com.project.cinemory.domain.movie.dto.WatchProvidersResponse;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.movie.service.MovieWatchProviderService;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.tmdb.TmdbClient;
import com.project.cinemory.global.infra.tmdb.dto.TmdbWatchProviders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 어디서 볼 수 있나 (service-layer-spec 4-2-B ③ · 테스트 ⑤): KR 있음 / 없음 / TMDB 오류(200 빈 응답, 실패는 캐시하지 않음) /
 * 같은 영화 두 번째 호출은 TMDB를 부르지 않음(캐시). 캐시가 컨텍스트 안에서 살아 있어 영화마다 새 id를 쓰고,
 * 영화 행은 서비스가 다른 트랜잭션에서 읽으므로 테스트 트랜잭션 없이 저장한다(끝나면 지운다).
 */
@SpringBootTest
@AutoConfigureMockMvc
class MovieWatchProvidersTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000L);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MovieWatchProviderService service;
    @Autowired
    private MovieRepository movieRepository;
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TmdbClient tmdbClient;

    private final List<Long> createdMovieIds = new java.util.ArrayList<>();

    @BeforeEach
    void resetMock() {
        reset(tmdbClient);
    }

    @AfterEach
    void cleanUp() {
        movieRepository.deleteAllById(createdMovieIds);
        createdMovieIds.clear();
    }

    private Movie movie() {
        Movie movie = movieRepository.save(Movie.builder().tmdbId(650_000_000L + SEQ.incrementAndGet()).title("제공처 영화").build());
        createdMovieIds.add(movie.getId());
        return movie;
    }

    private static TmdbWatchProviders krProviders() {
        String json = """
                {"results": {
                  "US": {"link": "https://us", "flatrate": [{"provider_id": 1, "provider_name": "US only", "display_priority": 1}]},
                  "KR": {"link": "https://kr",
                         "flatrate": [{"provider_id": 3, "provider_name": "Watcha", "logo_path": "/w.png", "display_priority": 9},
                                      {"provider_id": 8, "provider_name": "Netflix", "logo_path": "/n.png", "display_priority": 1}],
                         "rent": [{"provider_id": 2, "provider_name": "Apple TV", "logo_path": "/a.png", "display_priority": 2}]}
                }}
                """;
        return JSON.readValue(json, TmdbWatchProviders.class);
    }

    @Test
    void T1_KR_항목이_있으면_우선순위_순으로_담고_다른_국가는_쓰지_않는다() {
        Movie movie = movie();
        when(tmdbClient.fetchWatchProviders(movie.getTmdbId())).thenReturn(krProviders());

        WatchProvidersResponse response = service.getWatchProviders(movie.getId());

        assertThat(response.link()).isEqualTo("https://kr");
        assertThat(response.flatrate()).extracting(WatchProvidersResponse.ProviderResponse::name).containsExactly("Netflix", "Watcha");
        assertThat(response.flatrate().get(0).logoPath()).isEqualTo("/n.png");
        assertThat(response.rent()).extracting(WatchProvidersResponse.ProviderResponse::name).containsExactly("Apple TV");
        assertThat(response.buy()).isEmpty();
    }

    @Test
    void T2_KR_항목이_없으면_link_null과_빈_배열이다() {
        Movie movie = movie();
        when(tmdbClient.fetchWatchProviders(movie.getTmdbId()))
                .thenReturn(new TmdbWatchProviders(Map.of("US", new TmdbWatchProviders.Region("https://us", List.of(), List.of(), List.of()))));

        WatchProvidersResponse response = service.getWatchProviders(movie.getId());

        assertThat(response.link()).isNull();
        assertThat(response.flatrate()).isEmpty();
        assertThat(response.rent()).isEmpty();
        assertThat(response.buy()).isEmpty();
    }

    @Test
    void T3_같은_영화의_두_번째_호출은_TMDB를_부르지_않는다_빈_결과도_캐시한다() {
        Movie withKr = movie();
        when(tmdbClient.fetchWatchProviders(withKr.getTmdbId())).thenReturn(krProviders());
        Movie empty = movie();
        when(tmdbClient.fetchWatchProviders(empty.getTmdbId())).thenReturn(new TmdbWatchProviders(Map.of()));

        service.getWatchProviders(withKr.getId());
        service.getWatchProviders(withKr.getId());
        service.getWatchProviders(empty.getId());
        service.getWatchProviders(empty.getId());

        verify(tmdbClient, times(1)).fetchWatchProviders(withKr.getTmdbId());
        verify(tmdbClient, times(1)).fetchWatchProviders(empty.getTmdbId());
    }

    @Test
    void T4_TMDB_오류는_빈_응답이고_캐시하지_않아_다음_호출이_다시_TMDB로_간다() {
        Movie movie = movie();
        when(tmdbClient.fetchWatchProviders(movie.getTmdbId()))
                .thenThrow(new RestClientException("timeout"))
                .thenReturn(krProviders());

        WatchProvidersResponse failed = service.getWatchProviders(movie.getId());
        WatchProvidersResponse recovered = service.getWatchProviders(movie.getId());

        assertThat(failed.link()).isNull();
        assertThat(failed.flatrate()).isEmpty();
        assertThat(recovered.link()).isEqualTo("https://kr");
        verify(tmdbClient, times(2)).fetchWatchProviders(movie.getTmdbId());
    }

    @Test
    void T5_없는_영화는_404이고_TMDB를_부르지_않는다() {
        assertThatThrownBy(() -> service.getWatchProviders(999_999_999L))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MOVIE_NOT_FOUND));

        verify(tmdbClient, times(0)).fetchWatchProviders(anyLong());
    }

    @Test
    void T6_엔드포인트는_로그인_없이_200이고_TMDB_오류에도_200이다() throws Exception {
        Movie ok = movie();
        when(tmdbClient.fetchWatchProviders(ok.getTmdbId())).thenReturn(krProviders());
        Movie broken = movie();
        when(tmdbClient.fetchWatchProviders(broken.getTmdbId())).thenThrow(new RestClientException("down"));

        mockMvc.perform(get("/api/movies/" + ok.getId() + "/watch-providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.link").value("https://kr"))
                .andExpect(jsonPath("$.flatrate[0].name").value("Netflix"));
        mockMvc.perform(get("/api/movies/" + broken.getId() + "/watch-providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.link").doesNotExist())
                .andExpect(jsonPath("$.flatrate").isEmpty());
        mockMvc.perform(get("/api/movies/999999999/watch-providers")).andExpect(status().isNotFound());
    }
}
