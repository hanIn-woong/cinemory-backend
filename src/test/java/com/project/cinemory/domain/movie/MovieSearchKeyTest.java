package com.project.cinemory.domain.movie;

import com.project.cinemory.domain.admin.entity.AdminAction;
import com.project.cinemory.domain.admin.entity.AdminActionLog;
import com.project.cinemory.domain.admin.repository.AdminActionLogRepository;
import com.project.cinemory.domain.movie.dto.MovieSearchResponse;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.movie.service.MovieSearchService;
import com.project.cinemory.domain.user.entity.RoleType;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.tmdb.TmdbClient;
import com.project.cinemory.global.security.JwtTokenProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 검색 정규화 키와 자동 완성 (service-layer-spec 4-2-C 테스트 ①~⑤ + 자동 완성 + 재생성 API). 실 DB, 테스트마다 롤백.
 * 실 데이터와 섞이지 않게 영화 제목에 드문 토큰(zqk·vqz·kwj)을 쓰고 id로 단정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MovieSearchKeyTest {

    private static long tmdb = 99_100L;

    @Autowired
    private MovieSearchService movieSearchService;
    @Autowired
    private MovieRepository movieRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AdminActionLogRepository logRepository;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private TmdbClient tmdbClient;   // suggestions는 대역 - null 응답은 서비스가 빈 배열로 처리한다

    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        adminToken = jwtTokenProvider.createAccessToken(
                userRepository.save(User.createLocal("sk-admin@test.com", "{noop}pw", "검색키관리자")).getId(), RoleType.ADMIN);
        userToken = jwtTokenProvider.createAccessToken(
                userRepository.save(User.createLocal("sk-user@test.com", "{noop}pw", "검색키일반")).getId(), RoleType.USER);
    }

    private Movie movie(String title, String originalTitle, int votes) {
        Movie movie = movieRepository.save(Movie.builder().tmdbId(++tmdb).title(title).originalTitle(originalTitle)
                .voteCount(votes).releaseDate(LocalDate.of(2020, 5, 5)).posterPath("/p" + tmdb + ".jpg").build());
        em.flush();
        return movie;
    }

    private List<Long> registeredIds(String query) {
        MovieSearchResponse response = movieSearchService.search(query, null, 1);
        return response.registered().content().stream().map(m -> m.id()).toList();
    }

    private String storedKey(Movie movie) {
        em.flush();
        return jdbc.queryForObject("SELECT search_key FROM movie WHERE id = ?", String.class, movie.getId());
    }

    // ---- ① 띄어쓰기·문장부호를 무시하고 찾는다 ----------------------------------------------------

    @Test
    void 띄어쓰기와_쉼표가_달라도_같은_영화를_찾는다() {
        Movie movie = movie("그대, 별처럼 zqk", null, 1);

        assertThat(registeredIds("그대 별처럼 zqk")).contains(movie.getId());
        assertThat(registeredIds("그대별처럼zqk")).contains(movie.getId());
        assertThat(registeredIds("그대,별처럼 zqk")).contains(movie.getId());
        assertThat(registeredIds("  그대, 별처럼 ZQK ")).contains(movie.getId());
    }

    // ---- ② 원제로도 찾고 하이픈을 무시한다 ---------------------------------------------------------

    @Test
    void spiderman으로_Spider_Man을_찾는다() {
        Movie movie = movie("스파이더맨 zqk", "Spider-Man: No Way Home zqk", 1);

        assertThat(registeredIds("spiderman nowayhome zqk")).contains(movie.getId());
        assertThat(registeredIds("SPIDER MAN: no way")).contains(movie.getId());
    }

    @Test
    void 한글_제목_title_ko로도_찾는다() {
        Movie movie = movie("Zqk English", null, 1);
        movie.changeTitleKo("꿈의 정원 zqk");
        em.flush();

        assertThat(registeredIds("꿈의정원zqk")).contains(movie.getId());
        assertThat(registeredIds("zqk english")).as("영문 제목도 그대로 찾힌다").contains(movie.getId());
    }

    // ---- ③ 제목 경계를 넘는 오탐이 없다 --------------------------------------------------------------

    @Test
    void 한_영화의_title_끝과_original_title_앞을_이은_문자열로는_찾히지_않는다() {
        Movie movie = movie("qzxfoo", "barqzx", 1);

        assertThat(storedKey(movie)).isEqualTo("qzxfoo barqzx");
        assertThat(registeredIds("foobar")).doesNotContain(movie.getId());
        assertThat(registeredIds("foo bar")).as("공백이 있어도 정규화 후 foobar").doesNotContain(movie.getId());
        assertThat(registeredIds("qzxfoo")).contains(movie.getId());
        assertThat(registeredIds("barqzx")).contains(movie.getId());
    }

    @Test
    void title_ko와_original_title_사이도_경계가_있다() {
        Movie movie = movie("Aaa", "ccczqk", 1);
        movie.changeTitleKo("bbbzqk");
        em.flush();

        assertThat(storedKey(movie)).isEqualTo("aaa bbbzqk ccczqk");
        assertThat(registeredIds("zqkccc")).doesNotContain(movie.getId());
        assertThat(registeredIds("aaabbbzqk")).doesNotContain(movie.getId());
    }

    // ---- ④ 문장부호만 입력하면 400 -------------------------------------------------------------------

    @Test
    void 문장부호만_입력하면_400이다() throws Exception {
        assertThatThrownBy(() -> movieSearchService.search("!?, -", null, 1))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        mockMvc.perform(get("/api/movies/search").param("query", "..."))
                .andExpect(status().isBadRequest());
    }

    // ---- ⑤ 제목이 바뀌면 키가 갱신된다 -----------------------------------------------------------------

    @Test
    void 제목을_고치면_키가_따라_바뀐다() {
        Movie movie = movie("Old Title zqk", null, 1);
        assertThat(storedKey(movie)).isEqualTo("oldtitlezqk");

        movie.changeTitleKo("새 제목, zqk");
        assertThat(storedKey(movie)).isEqualTo("oldtitlezqk 새제목zqk");

        movie.updateMetadata("Brand New zqk", null, null, null, null, "Original Name zqk", null, null, null, null, null);
        assertThat(storedKey(movie)).isEqualTo("brandnewzqk 새제목zqk originalnamezqk");
        assertThat(registeredIds("brand new zqk")).contains(movie.getId());
        assertThat(registeredIds("oldtitlezqk")).doesNotContain(movie.getId());
    }

    @Test
    void 검색어의_퍼센트와_언더스코어는_와일드카드가_아니다() {
        Movie movie = movie("Wild zqk", null, 1);

        // 정규화에서 % _ 는 문장부호로 지워지므로 "wild%zqk" → "wildzqk" 로 찾힌다. 와일드카드로 해석돼 다른 영화가 걸리지 않는다.
        assertThat(registeredIds("wild%zqk")).contains(movie.getId());
        assertThat(registeredIds("w_ldzqk")).as("_ 는 지워져 wldzqk").doesNotContain(movie.getId());
    }

    // ---- 자동 완성 -------------------------------------------------------------------------------------

    @Test
    void 접두_일치가_먼저이고_같은_조건_안에서는_vote_count_순이다() throws Exception {
        Movie contains = movie("The vqz", null, 9_999);        // 중간 포함 - 표가 제일 많아도 뒤
        Movie prefixLow = movie("vqz Rising", null, 10);
        Movie prefixHigh = movie("vqz Zero", null, 500);
        Movie secondTitlePrefix = movie("Xyz", "vqz Origin", 20); // 두 번째 제목의 접두 - 구분 공백 뒤 시작

        var result = mockMvc.perform(get("/api/movies/autocomplete").param("q", "VQZ"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        tools.jackson.databind.JsonNode json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(body);

        List<Long> ids = new java.util.ArrayList<>();
        json.forEach(n -> ids.add(n.get("movieId").asLong()));
        assertThat(ids).containsExactly(prefixHigh.getId(), secondTitlePrefix.getId(), prefixLow.getId(), contains.getId());
        assertThat(json.get(0).get("title").asString()).isEqualTo("vqz Zero");
        assertThat(json.get(0).get("year").asInt()).isEqualTo(2020);
        assertThat(json.get(0).get("posterPath").asString()).startsWith("/p");
    }

    @Test
    void 띄어쓰기와_문장부호를_무시하고_자동_완성한다() throws Exception {
        Movie movie = movie("그대, 별처럼 kwj", null, 1);

        mockMvc.perform(get("/api/movies/autocomplete").param("q", "그대별"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.movieId == " + movie.getId() + ")]").exists());
    }

    @Test
    void limit은_기본_8_상한_10이고_작게도_줄일_수_있다() throws Exception {
        for (int i = 0; i < 12; i++) {
            movie("kwj movie " + i, null, i);
        }

        mockMvc.perform(get("/api/movies/autocomplete").param("q", "kwj")).andExpect(jsonPath("$.length()").value(8));
        mockMvc.perform(get("/api/movies/autocomplete").param("q", "kwj").param("limit", "50")).andExpect(jsonPath("$.length()").value(10));
        mockMvc.perform(get("/api/movies/autocomplete").param("q", "kwj").param("limit", "3")).andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void 정규화_결과가_비거나_q가_없으면_빈_배열이고_로그인_없이_부른다() throws Exception {
        mockMvc.perform(get("/api/movies/autocomplete").param("q", "!?, -"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/movies/autocomplete"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 자동_완성은_TMDB를_부르지_않는다() throws Exception {
        movie("kwj only db", null, 1);

        mockMvc.perform(get("/api/movies/autocomplete").param("q", "kwj")).andExpect(status().isOk());

        org.mockito.Mockito.verifyNoInteractions(tmdbClient);
    }

    // ---- 재생성 API ------------------------------------------------------------------------------------

    @Test
    void 재생성은_비어_있던_키를_채우고_감사_로그를_남기며_다시_돌려도_안전하다() throws Exception {
        Movie a = movie("재생성 A zqk", "Rebuild A", 1);
        Movie b = movie("재생성 B zqk", null, 1);
        jdbc.update("UPDATE movie SET search_key = NULL WHERE id IN (?, ?)", a.getId(), b.getId());
        em.clear();
        assertThat(registeredIds("재생성a zqk")).as("키가 비어 있으면 검색에 걸리지 않는다").doesNotContain(a.getId());

        mockMvc.perform(post("/api/admin/movies/search-key/rebuild").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processed").isNumber())
                .andExpect(jsonPath("$.updated").isNumber());

        assertThat(storedKey(a)).isEqualTo("재생성azqk rebuilda");
        assertThat(storedKey(b)).isEqualTo("재생성bzqk");
        assertThat(registeredIds("재생성 A zqk")).contains(a.getId());

        mockMvc.perform(post("/api/admin/movies/search-key/rebuild").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(0));

        List<AdminActionLog> logs = logRepository.findAll().stream()
                .filter(l -> l.getAction() == AdminAction.MOVIE_SEARCH_KEY_REBUILD).toList();
        assertThat(logs).hasSize(2);
        assertThat(logs.get(0).getDetail()).containsKeys("processed", "updated");
    }

    @Test
    void 재생성은_관리자만_할_수_있다() throws Exception {
        mockMvc.perform(post("/api/admin/movies/search-key/rebuild").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/movies/search-key/rebuild"))
                .andExpect(status().isUnauthorized());
    }
}
