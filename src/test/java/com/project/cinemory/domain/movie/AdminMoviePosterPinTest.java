package com.project.cinemory.domain.movie;

import com.project.cinemory.domain.admin.entity.AdminAction;
import com.project.cinemory.domain.admin.entity.AdminActionLog;
import com.project.cinemory.domain.admin.repository.AdminActionLogRepository;
import com.project.cinemory.domain.country.entity.Country;
import com.project.cinemory.domain.country.repository.CountryRepository;
import com.project.cinemory.domain.genre.entity.Genre;
import com.project.cinemory.domain.genre.repository.GenreRepository;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.movie.service.MovieSeedService;
import com.project.cinemory.domain.movie.service.MovieSyncService;
import com.project.cinemory.domain.user.entity.RoleType;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.tmdb.TmdbClient;
import com.project.cinemory.global.infra.tmdb.dto.TmdbGenreListResponse;
import com.project.cinemory.global.infra.tmdb.dto.TmdbMovieDetailResponse;
import com.project.cinemory.global.infra.tmdb.dto.TmdbMovieImagesResponse;
import com.project.cinemory.global.infra.tmdb.dto.TmdbMovieImagesResponse.Poster;
import com.project.cinemory.global.security.JwtTokenProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 포스터 고정(admin-spec 18) - 후보 정렬·상한 · 후보에 없는 경로 400 · 고정 후 재동기화/온디맨드 sync 모두 유지 ·
 * 해제 후 재동기화로 TMDB 값 복귀 · TMDB 오류 502 · 감사 로그 · 인가. TMDB는 대역으로 바꾼다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminMoviePosterPinTest {

    private static final long TMDB_ID = 98_821L;
    private static final String TMDB_DEFAULT = "/tmdb-default.jpg";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MovieRepository movieRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private GenreRepository genreRepository;
    @Autowired
    private CountryRepository countryRepository;
    @Autowired
    private AdminActionLogRepository logRepository;
    @Autowired
    private MovieSeedService movieSeedService;
    @Autowired
    private MovieSyncService movieSyncService;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private EntityManager em;

    @MockitoBean
    private TmdbClient tmdbClient;

    private String adminToken;
    private String userToken;
    private Movie movie;

    @BeforeEach
    void setUp() {
        User admin = userRepository.save(User.createLocal("poster-admin@test.com", "{noop}pw", "포스터관리자"));
        adminToken = jwtTokenProvider.createAccessToken(admin.getId(), RoleType.ADMIN);
        User user = userRepository.save(User.createLocal("poster-user@test.com", "{noop}pw", "일반"));
        userToken = jwtTokenProvider.createAccessToken(user.getId(), RoleType.USER);
        // 재동기화의 참조 테이블 가드를 통과시킨다(롤백된다)
        genreRepository.save(Genre.of(990_321, "포스터테스트장르"));
        countryRepository.save(Country.of("ZX", "포스터테스트국"));
        movie = movieRepository.save(Movie.builder().tmdbId(TMDB_ID).title("zqt Poster Movie").posterPath("/old.jpg").build());
        em.flush();

        when(tmdbClient.fetchMovieImages(TMDB_ID)).thenReturn(new TmdbMovieImagesResponse(List.of(
                poster("/en-high.jpg", "en", 9.0), poster("/ko-low.jpg", "ko", 1.0), poster("/none.jpg", null, 5.0),
                poster("/ko-high.jpg", "ko", 7.0), poster("/en-low.jpg", "en", 2.0))));
        when(tmdbClient.fetchMovieDetail(TMDB_ID)).thenReturn(detail());
    }

    private static Poster poster(String path, String language, Double vote) {
        return new Poster(path, language, 1000, 1500, vote);
    }

    private TmdbMovieDetailResponse detail() {
        return new TmdbMovieDetailResponse(TMDB_ID, false, "zqt Poster Movie", "Original", TMDB_DEFAULT, "2024-05-01",
                "줄거리", 120, "/backdrop.jpg", 8.4, 1234,
                List.of(new TmdbGenreListResponse.Item(990_321, "포스터테스트장르")), null, null, null, null, null, null);
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + adminToken);
    }

    private String body(String filePath) {
        return "{\"filePath\":\"" + filePath + "\"}";
    }

    private Movie reload() {
        em.flush();
        em.clear();
        return movieRepository.findById(movie.getId()).orElseThrow();
    }

    private List<AdminActionLog> logs(AdminAction action) {
        em.flush();
        em.clear();
        return logRepository.findAll().stream().filter(l -> l.getAction() == action).toList();
    }

    @Test
    void 후보는_한국어_언어없음_영어_순이고_같은_언어_안에서는_평점순이다() throws Exception {
        mockMvc.perform(asAdmin(get("/api/admin/movies/{id}/posters", movie.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value("/old.jpg"))
                .andExpect(jsonPath("$.pinned").doesNotExist())
                .andExpect(jsonPath("$.candidates[0].filePath").value("/ko-high.jpg"))
                .andExpect(jsonPath("$.candidates[1].filePath").value("/ko-low.jpg"))
                .andExpect(jsonPath("$.candidates[2].filePath").value("/none.jpg"))
                .andExpect(jsonPath("$.candidates[3].filePath").value("/en-high.jpg"))
                .andExpect(jsonPath("$.candidates[4].filePath").value("/en-low.jpg"));
    }

    @Test
    void 후보는_최대_50장만_내려간다() throws Exception {
        List<Poster> many = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            many.add(poster("/p" + i + ".jpg", "en", (double) i));
        }
        when(tmdbClient.fetchMovieImages(TMDB_ID)).thenReturn(new TmdbMovieImagesResponse(many));

        mockMvc.perform(asAdmin(get("/api/admin/movies/{id}/posters", movie.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()").value(50));
    }

    @Test
    void TMDB_오류는_502다() throws Exception {
        when(tmdbClient.fetchMovieImages(TMDB_ID)).thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_ERROR));

        mockMvc.perform(asAdmin(get("/api/admin/movies/{id}/posters", movie.getId())))
                .andExpect(status().isBadGateway());
    }

    @Test
    void 고정하면_표시_포스터가_바뀌고_감사_로그가_남는다() throws Exception {
        mockMvc.perform(asAdmin(put("/api/admin/movies/{id}/poster", movie.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body("/ko-high.jpg")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posterPath").value("/ko-high.jpg"))
                .andExpect(jsonPath("$.posterPathPinned").value("/ko-high.jpg"));

        Movie reloaded = reload();
        assertThat(reloaded.getPosterPath()).isEqualTo("/ko-high.jpg");
        assertThat(reloaded.getPosterPathPinned()).isEqualTo("/ko-high.jpg");
        List<AdminActionLog> logs = logs(AdminAction.MOVIE_POSTER_PIN);
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getDetail()).containsEntry("before", "/old.jpg").containsEntry("after", "/ko-high.jpg");
    }

    @Test
    void 후보에_없는_경로는_400이고_바뀌지_않는다() throws Exception {
        mockMvc.perform(asAdmin(put("/api/admin/movies/{id}/poster", movie.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body("/someone-elses.jpg")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_POSTER_PATH"));

        assertThat(reload().getPosterPathPinned()).isNull();
        assertThat(logs(AdminAction.MOVIE_POSTER_PIN)).isEmpty();
    }

    @Test
    void filePath가_비어_있으면_400() throws Exception {
        mockMvc.perform(asAdmin(put("/api/admin/movies/{id}/poster", movie.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body("")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 같은_값을_다시_고정하면_TMDB도_감사_로그도_건드리지_않는다() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(asAdmin(put("/api/admin/movies/{id}/poster", movie.getId()))
                            .contentType(MediaType.APPLICATION_JSON).content(body("/ko-high.jpg")))
                    .andExpect(status().isOk());
        }

        verify(tmdbClient, times(1)).fetchMovieImages(TMDB_ID);
        assertThat(logs(AdminAction.MOVIE_POSTER_PIN)).hasSize(1);
    }

    @Test
    void 고정_후_재동기화와_온디맨드_sync_모두_고정_값을_유지한다() throws Exception {
        mockMvc.perform(asAdmin(put("/api/admin/movies/{id}/poster", movie.getId()))
                .contentType(MediaType.APPLICATION_JSON).content(body("/ko-high.jpg"))).andExpect(status().isOk());
        em.flush();
        em.clear();

        movieSeedService.resyncOne(movie.getId());
        assertThat(reload().getPosterPath()).isEqualTo("/ko-high.jpg");

        movieSyncService.syncFromTmdb(TMDB_ID);
        Movie after = reload();
        assertThat(after.getPosterPath()).isEqualTo("/ko-high.jpg");
        assertThat(after.getPosterPathPinned()).isEqualTo("/ko-high.jpg");
        assertThat(after.getTitle()).isEqualTo("zqt Poster Movie");   // 다른 메타데이터는 정상적으로 동기화된다
    }

    @Test
    void 고정이_없으면_재동기화가_TMDB_포스터로_갱신한다() {
        movieSeedService.resyncOne(movie.getId());

        assertThat(reload().getPosterPath()).isEqualTo(TMDB_DEFAULT);
    }

    @Test
    void 해제하면_재동기화로_TMDB_기본_포스터가_즉시_돌아온다() throws Exception {
        mockMvc.perform(asAdmin(put("/api/admin/movies/{id}/poster", movie.getId()))
                .contentType(MediaType.APPLICATION_JSON).content(body("/ko-high.jpg"))).andExpect(status().isOk());

        mockMvc.perform(asAdmin(delete("/api/admin/movies/{id}/poster", movie.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resynced").value(true))
                .andExpect(jsonPath("$.movie.posterPath").value(TMDB_DEFAULT))
                .andExpect(jsonPath("$.movie.posterPathPinned").doesNotExist());

        Movie reloaded = reload();
        assertThat(reloaded.getPosterPath()).isEqualTo(TMDB_DEFAULT);
        assertThat(reloaded.getPosterPathPinned()).isNull();
        List<AdminActionLog> logs = logs(AdminAction.MOVIE_POSTER_UNPIN);
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getDetail()).containsEntry("before", "/ko-high.jpg");
    }

    @Test
    void 일괄_작업_중이면_해제만_하고_resynced는_false다() throws Exception {
        mockMvc.perform(asAdmin(put("/api/admin/movies/{id}/poster", movie.getId()))
                .contentType(MediaType.APPLICATION_JSON).content(body("/ko-high.jpg"))).andExpect(status().isOk());

        AtomicBoolean running = (AtomicBoolean) ReflectionTestUtils.getField(movieSeedService, "running");
        running.set(true);
        try {
            mockMvc.perform(asAdmin(delete("/api/admin/movies/{id}/poster", movie.getId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.resynced").value(false))
                    .andExpect(jsonPath("$.movie.posterPathPinned").doesNotExist())
                    .andExpect(jsonPath("$.movie.posterPath").value("/ko-high.jpg"));
            verify(tmdbClient, never()).fetchMovieDetail(anyLong());
        } finally {
            running.set(false);   // 빈이 공유되므로 반드시 되돌린다
        }
    }

    @Test
    void 고정이_없는_영화의_해제는_기록을_남기지_않는다() throws Exception {
        mockMvc.perform(asAdmin(delete("/api/admin/movies/{id}/poster", movie.getId())))
                .andExpect(status().isOk());

        assertThat(logs(AdminAction.MOVIE_POSTER_UNPIN)).isEmpty();
    }

    @Test
    void 없는_영화는_404() throws Exception {
        mockMvc.perform(asAdmin(get("/api/admin/movies/{id}/posters", -1L))).andExpect(status().isNotFound());
        mockMvc.perform(asAdmin(put("/api/admin/movies/{id}/poster", -1L))
                .contentType(MediaType.APPLICATION_JSON).content(body("/a.jpg"))).andExpect(status().isNotFound());
        mockMvc.perform(asAdmin(delete("/api/admin/movies/{id}/poster", -1L))).andExpect(status().isNotFound());
    }

    @Test
    void 일반_사용자와_비로그인은_거부된다() throws Exception {
        mockMvc.perform(get("/api/admin/movies/{id}/posters", movie.getId())).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/admin/movies/{id}/poster", movie.getId()).header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("/ko-high.jpg")))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/admin/movies/{id}/poster", movie.getId()).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }
}
