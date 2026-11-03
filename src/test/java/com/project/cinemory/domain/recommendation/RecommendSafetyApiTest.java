package com.project.cinemory.domain.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.cinemory.domain.admin.entity.AdminAction;
import com.project.cinemory.domain.admin.entity.AdminActionLog;
import com.project.cinemory.domain.admin.entity.AdminTargetType;
import com.project.cinemory.domain.admin.repository.AdminActionLogRepository;
import com.project.cinemory.domain.country.entity.Country;
import com.project.cinemory.domain.country.repository.CountryRepository;
import com.project.cinemory.domain.genre.entity.Genre;
import com.project.cinemory.domain.genre.repository.GenreRepository;
import com.project.cinemory.domain.keyword.entity.Keyword;
import com.project.cinemory.domain.movie.entity.KrCertification;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.entity.MovieCountry;
import com.project.cinemory.domain.movie.entity.MovieGenre;
import com.project.cinemory.domain.movie.entity.MovieKeyword;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.recommendation.entity.OverviewSource;
import com.project.cinemory.domain.recommendation.index.MovieFeatureIndex;
import com.project.cinemory.domain.recommendation.index.MovieVectorIndex;
import com.project.cinemory.domain.recommendation.service.EmbeddingText;
import com.project.cinemory.domain.recommendation.service.ForYouBatchService;
import com.project.cinemory.domain.recommendation.service.MovieEmbeddingPersister;
import com.project.cinemory.domain.recommendation.support.FakeEmbeddingClient;
import com.project.cinemory.domain.recommendation.support.FakeEmbeddingConfig;
import com.project.cinemory.domain.user.entity.RoleType;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.global.security.JwtTokenProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 안전 필터 통합 테스트 (M3b R-5-1, controller-layer-spec 5-14-J "안전" 그룹) - 추천 제외 자동 판정이 취향 추천 · 게스트 · 자연어 ·
 * {@code /random}에 모두 적용되는지, 관리자 덮어쓰기가 즉시 반영되는지, 가족 의도 질의가 등급 필터로 바뀌는지.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FakeEmbeddingConfig.class)
class RecommendSafetyApiTest {

    private static final int ROMANCE = 10749;
    private static final int DRAMA = 18;
    private static final String QUERY = "안전 필터 검증 전용 질의";   // 질의 임베딩 캐시가 컨텍스트 전체에서 공유되므로 다른 테스트와 겹치지 않는 문장을 쓴다

    @Autowired MockMvc mockMvc;
    @Autowired MovieRepository movieRepository;
    @Autowired GenreRepository genreRepository;
    @Autowired CountryRepository countryRepository;
    @Autowired UserRepository userRepository;
    @Autowired AdminActionLogRepository logRepository;
    @Autowired MovieEmbeddingPersister embeddingPersister;
    @Autowired MovieFeatureIndex featureIndex;
    @Autowired MovieVectorIndex vectorIndex;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired FakeEmbeddingClient fakeEmbedding;
    @Autowired ForYouBatchService batchService;
    @Autowired EntityManager em;

    private static int seq = 0;
    private static long nextTmdbId = 96_000L;

    private long userId;
    private String userToken;
    private String adminToken;
    private User admin;
    private Country kr;
    private Genre romance;
    private Genre drama;

    @BeforeEach
    void setUp() {
        fakeEmbedding.reset();
        User user = userRepository.save(User.createLocal("safe-user" + (++seq) + "@test.com", "{noop}pw", "안전" + seq));
        userId = user.getId();
        userToken = jwtTokenProvider.createAccessToken(user.getId(), RoleType.USER);
        admin = userRepository.save(User.createLocal("safe-admin" + (++seq) + "@test.com", "{noop}pw", "관리" + seq));
        adminToken = jwtTokenProvider.createAccessToken(admin.getId(), RoleType.ADMIN);
        kr = countryRepository.findByCodeIn(List.of("KR")).stream().findFirst().orElseGet(() -> countryRepository.save(Country.of("KR", "한국")));
        romance = genre(ROMANCE, "로맨스");
        drama = genre(DRAMA, "드라마");
    }

    // ── 자동 판정: 모든 추천 경로에 적용 ─────────────────────────────

    @Test
    void 형태_규칙_영화는_게스트_취향_추천에서_빠지고_ALLOW면_나온다() throws Exception {
        Movie shape = shapeRuleMovie("형태규칙");
        Movie normal = movie("정상", KrCertification.AGE_15, 110, 1_000);
        reload();

        assertThat(guestForYou()).contains(normal.getId().intValue()).doesNotContain(shape.getId().intValue());

        overridePatch(shape.getId(), "{\"override\":\"ALLOW\"}").andExpect(status().isNoContent());
        assertThat(guestForYou()).contains(shape.getId().intValue());   // 인덱스·게스트 캐시가 즉시 갱신된다 (재기동·재적재 없음)

        overridePatch(shape.getId(), "{\"override\":null}").andExpect(status().isNoContent());   // 해제 - 다시 자동 판정
        assertThat(guestForYou()).doesNotContain(shape.getId().intValue());
    }

    @Test
    void 개인_풀과_HTTP_묶음에서도_빠진다() throws Exception {
        Movie shape = shapeRuleMovie("형태규칙2");
        reload();

        // 개인 풀(관리자 디버그는 풀 50편 전체를 보여 준다)
        String debug = mockMvc.perform(get("/api/admin/recommendations/debug").param("userId", String.valueOf(userId))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Integer> poolIds = new ArrayList<>();
        new ObjectMapper().readTree(debug).path("movies").forEach(node -> poolIds.add(node.path("id").asInt()));
        assertThat(poolIds).doesNotContain(shape.getId().intValue());

        // 실제 묶음 응답 - 무작위라 여러 번 뽑아 본다(게스트 + 로그인)
        for (int i = 0; i < 12; i++) {
            for (String auth : new String[]{null, "Bearer " + userToken}) {
                var request = get("/api/recommendations/for-you").param("next", "true");
                String body = mockMvc.perform(auth == null ? request : request.header("Authorization", auth))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
                List<Integer> ids = new ArrayList<>();
                new ObjectMapper().readTree(body).path("movies").forEach(node -> ids.add(node.path("id").asInt()));
                assertThat(ids).doesNotContain(shape.getId().intValue());
            }
        }
    }

    @Test
    void 형태_규칙_영화는_자연어_검색에서_코사인이_가장_높아도_빠지고_ALLOW면_나온다() throws Exception {
        Movie shape = shapeRuleMovie("형태규칙3");
        Movie normal = movie("정상3", KrCertification.AGE_15, 110, 1_000);
        embed(shape, queryVector(QUERY));
        embed(normal, FakeEmbeddingClient.vectorOf("unrelated"));
        reload();

        assertThat(search(QUERY)).contains(normal.getId().intValue()).doesNotContain(shape.getId().intValue());

        overridePatch(shape.getId(), "{\"override\":\"ALLOW\"}").andExpect(status().isNoContent());
        assertThat(search(QUERY)).contains(shape.getId().intValue());
    }

    @Test
    void 규칙_밖_영화도_BLOCK이면_추천에서_빠진다() throws Exception {
        Movie normal = movie("정상4", KrCertification.AGE_15, 110, 1_000);
        embed(normal, queryVector(QUERY));
        reload();
        assertThat(search(QUERY)).contains(normal.getId().intValue());
        assertThat(guestForYou()).contains(normal.getId().intValue());

        overridePatch(normal.getId(), "{\"override\":\"BLOCK\"}").andExpect(status().isNoContent());

        assertThat(search(QUERY)).doesNotContain(normal.getId().intValue());
        assertThat(guestForYou()).doesNotContain(normal.getId().intValue());
    }

    @Test
    void 차단_키워드가_붙은_영화와_제한상영가는_추천에서_빠진다() throws Exception {
        Movie keywordMovie = movie("키워드", KrCertification.AGE_15, 110, 1_000);
        em.persist(MovieKeyword.of(keywordMovie, keyword("Erotic Movie")));   // 대소문자 무시
        Movie restricted = movie("제한상영가", KrCertification.RESTRICTED, 110, 1_000);
        Movie normal = movie("정상5", KrCertification.AGE_15, 110, 1_000);
        reload();

        assertThat(guestForYou()).contains(normal.getId().intValue())
                .doesNotContain(keywordMovie.getId().intValue(), restricted.getId().intValue());
    }

    @Test
    void 홈_배경_랜덤_포스터에서도_추천_제외_영화는_뜨지_않는다() throws Exception {
        Movie shape = shapeRuleMovie("형태규칙6");
        Movie blockedByAdmin = movie("관리자차단", KrCertification.ALL, 110, 1_000);
        reload();
        overridePatch(blockedByAdmin.getId(), "{\"override\":\"BLOCK\"}").andExpect(status().isNoContent());

        for (int i = 0; i < 5; i++) {   // 무작위 표본이라 여러 번 본다
            String body = mockMvc.perform(get("/api/movies/random").param("size", "50"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            List<Integer> ids = new ArrayList<>();
            new ObjectMapper().readTree(body).forEach(node -> ids.add(node.path("id").asInt()));
            assertThat(ids).doesNotContain(shape.getId().intValue(), blockedByAdmin.getId().intValue());
        }
    }

    // ── 가족 의도 → 등급 하드 필터 ───────────────────────────────────

    @Test
    void 가족_질의는_전체_12세만_남기고_등급_없음은_0건이며_FAMILY_SAFE_칩을_단다() throws Exception {
        String query = "가족끼리 볼 영화";
        float[] vector = queryVector(query);
        Movie all = movie("전체", KrCertification.ALL, 100, 1_000);
        Movie age12 = movie("12세", KrCertification.AGE_12, 100, 1_000);
        Movie age15 = movie("15세", KrCertification.AGE_15, 100, 1_000);
        Movie age18 = movie("청불", KrCertification.AGE_18, 100, 1_000);
        Movie none = movie("등급없음", null, 100, 1_000);
        for (Movie m : List.of(all, age12, age15, age18, none)) {
            embed(m, vector);   // 전부 코사인 1.0 - 등급 필터가 아니면 모두 나온다
        }
        reload();

        String body = searchBody(query);
        List<Integer> ids = contentIds(body);

        assertThat(ids).contains(all.getId().intValue(), age12.getId().intValue())
                .doesNotContain(age15.getId().intValue(), age18.getId().intValue(), none.getId().intValue());
        assertThat(new ObjectMapper().readTree(body).path("conditions")).anySatisfy(chip -> {
            assertThat(chip.path("type").asText()).isEqualTo("FAMILY_SAFE");
            assertThat(chip.path("text").asText()).isEqualTo("전체·12세 관람가만 골랐어요");
        });
    }

    @Test
    void 가족_의도가_없으면_등급과_무관하게_나온다() throws Exception {
        Movie age18 = movie("청불2", KrCertification.AGE_18, 140, 1_000);   // 형태 규칙 밖(140분)
        embed(age18, queryVector(QUERY));
        reload();

        assertThat(search(QUERY)).contains(age18.getId().intValue());
    }

    // ── 덮어쓰기 API ───────────────────────────────────────────────

    @Test
    void 덮어쓰기는_ADMIN만_할_수_있다() throws Exception {
        Movie movie = movie("권한", KrCertification.ALL, 110, 1_000);
        em.flush();

        mockMvc.perform(patch("/api/admin/movies/{id}/recommend-override", movie.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"override\":\"BLOCK\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/admin/movies/{id}/recommend-override", movie.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"override\":\"BLOCK\"}").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        assertThat(logsOf()).isEmpty();
    }

    @Test
    void 없는_영화는_404이고_로그를_남기지_않는다() throws Exception {
        overridePatch(999_999_999L, "{\"override\":\"BLOCK\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MOVIE_NOT_FOUND"));

        assertThat(logsOf()).isEmpty();
    }

    @Test
    void 잘못된_값은_400이다() throws Exception {
        Movie movie = movie("잘못된값", KrCertification.ALL, 110, 1_000);
        em.flush();

        overridePatch(movie.getId(), "{\"override\":\"MAYBE\"}").andExpect(status().isBadRequest());

        assertThat(logsOf()).isEmpty();
    }

    @Test
    void 변경은_이전_값과_새_값을_감사_로그로_남기고_같은_값이면_남기지_않는다() throws Exception {
        Movie movie = movie("감사", KrCertification.ALL, 110, 1_000);
        em.flush();

        overridePatch(movie.getId(), "{\"override\":\"BLOCK\"}").andExpect(status().isNoContent());
        overridePatch(movie.getId(), "{\"override\":\"BLOCK\"}").andExpect(status().isNoContent());   // 멱등 - 기록 없음
        overridePatch(movie.getId(), "{}").andExpect(status().isNoContent());                         // 해제

        List<AdminActionLog> logs = logsOf();
        assertThat(logs).hasSize(2);
        assertThat(logs).allSatisfy(log -> {
            assertThat(log.getAdminUserId()).isEqualTo(admin.getId());
            assertThat(log.getTargetType()).isEqualTo(AdminTargetType.MOVIE);
            assertThat(log.getTargetId()).isEqualTo(movie.getId());
        });
        assertThat(logs.get(0).getDetail()).containsEntry("before", null).containsEntry("after", "BLOCK");
        assertThat(logs.get(1).getDetail()).containsEntry("before", "BLOCK").containsEntry("after", null);
    }

    // ── 관리자 영화 상세의 recommendSafety ───────────────────────────

    @Test
    void 관리자_영화_상세는_자동_판정_사유와_덮어쓰기를_보여_준다() throws Exception {
        Movie shape = shapeRuleMovie("상세1");
        Movie keywordMovie = movie("상세2", KrCertification.AGE_15, 110, 1_000);
        em.persist(MovieKeyword.of(keywordMovie, keyword("softcore")));
        Movie normal = movie("상세3", KrCertification.AGE_15, 110, 1_000);
        em.flush();

        adminDetail(shape.getId())
                .andExpect(jsonPath("$.recommendSafety.autoBlocked").value(true))
                .andExpect(jsonPath("$.recommendSafety.reasons[0]").value("SHAPE_RULE"))
                .andExpect(jsonPath("$.recommendSafety.override").doesNotExist());
        adminDetail(keywordMovie.getId())
                .andExpect(jsonPath("$.recommendSafety.autoBlocked").value(true))
                .andExpect(jsonPath("$.recommendSafety.reasons[0]").value("KEYWORD"));
        adminDetail(normal.getId())
                .andExpect(jsonPath("$.recommendSafety.autoBlocked").value(false))
                .andExpect(jsonPath("$.recommendSafety.reasons").isEmpty());

        overridePatch(shape.getId(), "{\"override\":\"ALLOW\"}").andExpect(status().isNoContent());
        adminDetail(shape.getId())
                .andExpect(jsonPath("$.recommendSafety.autoBlocked").value(true))   // 자동 판정은 그대로, 덮어쓰기만 표시된다
                .andExpect(jsonPath("$.recommendSafety.override").value("ALLOW"));
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────

    /** 청불 + 로맨스·드라마 + 75분 + 5표 - 국내 에로 영화의 형태. 한국 영화라 투표 수 하한에는 걸리지 않는다. */
    private Movie shapeRuleMovie(String title) {
        Movie movie = movie(title, KrCertification.AGE_18, 75, 5);
        em.persist(MovieGenre.of(movie, romance, new BigDecimal("0.500")));
        em.persist(MovieGenre.of(movie, drama, new BigDecimal("0.500")));
        return movie;
    }

    private Movie movie(String title, KrCertification certification, int runtime, int votes) {
        Movie movie = Movie.builder().tmdbId(nextTmdbId++).title(title).posterPath("/safe" + nextTmdbId + ".jpg")
                .releaseDate(LocalDate.of(2017, 1, 1)).runtime(runtime)
                .voteAverage(BigDecimal.valueOf(8.0)).voteCount(votes).build();
        movie.changeKrCertification(certification);
        movie = movieRepository.save(movie);
        em.persist(MovieCountry.of(movie, kr, new BigDecimal("1.000")));
        return movie;
    }

    private Genre genre(int tmdbId, String name) {
        return genreRepository.findByTmdbGenreIdIn(List.of(tmdbId)).stream().findFirst()
                .orElseGet(() -> genreRepository.save(Genre.of(tmdbId, name)));
    }

    private Keyword keyword(String name) {
        Keyword keyword = Keyword.of(nextTmdbId++, name);
        em.persist(keyword);
        return keyword;
    }

    private static float[] queryVector(String query) {
        return FakeEmbeddingClient.vectorOf("query:" + query);
    }

    private void embed(Movie movie, float[] vector) {
        EmbeddingText text = new EmbeddingText(movie.getTitle(), "본문", OverviewSource.KO);
        embeddingPersister.upsert(FakeEmbeddingClient.MODEL, List.of(new MovieEmbeddingPersister.Item(
                movie.getId(), text, text.hash(FakeEmbeddingClient.MODEL), vector)));
    }

    private void reload() {
        em.flush();
        featureIndex.reload();
        vectorIndex.reload();
    }

    /**
     * 게스트·무활동 사용자가 공유하는 전역 풀(S 점수 상위 50편)의 영화 id. 묶음은 이 풀에서 무작위로 10편을 뽑으므로 HTTP 응답으로는
     * "이 영화가 풀에 있나"를 결정적으로 확인할 수 없다 - 풀을 직접 본다(추천 제외는 풀 계산 단계에서 적용된다). 스냅샷 세대가 바뀌면
     * 다시 계산되는 전역 캐시를 그대로 거치므로 덮어쓰기의 즉시 반영도 여기서 확인된다.
     */
    private List<Integer> guestForYou() {
        return batchService.globalPool().stream().map(m -> (int) m.movieId()).toList();
    }

    private String searchBody(String q) throws Exception {
        return mockMvc.perform(get("/api/recommendations/search").param("q", q).param("size", "50")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private List<Integer> search(String q) throws Exception {
        return contentIds(searchBody(q));
    }

    private static List<Integer> contentIds(String body) throws Exception {
        JsonNode content = new ObjectMapper().readTree(body).path("movies").path("content");
        List<Integer> ids = new ArrayList<>();
        content.forEach(node -> ids.add(node.path("id").asInt()));
        return ids;
    }

    private ResultActions overridePatch(long movieId, String json) throws Exception {
        return mockMvc.perform(patch("/api/admin/movies/{id}/recommend-override", movieId)
                .contentType(MediaType.APPLICATION_JSON).content(json).header("Authorization", "Bearer " + adminToken));
    }

    private ResultActions adminDetail(long movieId) throws Exception {
        return mockMvc.perform(get("/api/admin/movies/{id}", movieId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    private List<AdminActionLog> logsOf() {
        em.flush();
        em.clear();
        return logRepository.findAll().stream()
                .filter(l -> l.getAction() == AdminAction.MOVIE_RECOMMEND_OVERRIDE)
                .sorted(java.util.Comparator.comparing(AdminActionLog::getId)).toList();
    }
}
