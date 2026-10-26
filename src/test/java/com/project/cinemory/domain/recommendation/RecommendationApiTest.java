package com.project.cinemory.domain.recommendation;

import com.project.cinemory.domain.admin.entity.AdminAction;
import com.project.cinemory.domain.admin.entity.AdminActionLog;
import com.project.cinemory.domain.admin.entity.AdminTargetType;
import com.project.cinemory.domain.admin.repository.AdminActionLogRepository;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.recommendation.index.MovieFeatureIndex;
import com.project.cinemory.domain.recommendation.index.MovieVectorIndex;
import com.project.cinemory.domain.recommendation.repository.MovieEmbeddingRepository;
import com.project.cinemory.domain.recommendation.service.EmbeddingSyncService;
import com.project.cinemory.domain.recommendation.service.EmbeddingText;
import com.project.cinemory.domain.recommendation.service.ForYouRecommendationService;
import com.project.cinemory.domain.recommendation.service.MovieEmbeddingPersister;
import com.project.cinemory.domain.recommendation.service.UserPreferenceProfile;
import com.project.cinemory.domain.recommendation.entity.OverviewSource;
import com.project.cinemory.domain.recommendation.support.FakeEmbeddingClient;
import com.project.cinemory.domain.recommendation.support.FakeEmbeddingConfig;
import com.project.cinemory.domain.user.entity.RoleType;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.domain.watch.entity.WatchRecord;
import com.project.cinemory.domain.watch.repository.WatchRecordRepository;
import com.project.cinemory.domain.wish.entity.WishMovie;
import com.project.cinemory.domain.wish.repository.WishMovieRepository;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.gemini.EmbeddingClientException;
import com.project.cinemory.global.security.JwtTokenProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 추천 API 통합 테스트 (controller-layer-spec 5-14-J) — 보안 · 페이징/캐시 · 검증 · 호출 제한 · 대체 · 관리자.
 * 임베딩은 {@link FakeEmbeddingClient}, 데이터는 트랜잭션 안에서 만든 영화 4편(품질 순 M1 &gt; M2 &gt; M3 &gt; M4)이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FakeEmbeddingConfig.class)
class RecommendationApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired MovieRepository movieRepository;
    @Autowired UserRepository userRepository;
    @Autowired WatchRecordRepository watchRecordRepository;
    @Autowired WishMovieRepository wishMovieRepository;
    @Autowired AdminActionLogRepository logRepository;
    @Autowired MovieEmbeddingRepository embeddingRepository;
    @Autowired MovieEmbeddingPersister embeddingPersister;
    @Autowired MovieFeatureIndex featureIndex;
    @Autowired MovieVectorIndex vectorIndex;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired FakeEmbeddingClient fakeEmbedding;
    @Autowired EntityManager em;

    @MockitoSpyBean ForYouRecommendationService forYouSpy;
    @MockitoSpyBean EmbeddingSyncService syncSpy;

    private Movie m1, m2, m3, m4;
    private User user;
    private String userToken;
    private User admin;
    private String adminToken;

    private static int seq = 0;

    @BeforeEach
    void setUp() {
        fakeEmbedding.reset();
        m1 = movie(98_601L, "M1 최상위", 9.0, 20_000);
        m2 = movie(98_602L, "M2", 8.0, 10_000);
        m3 = movie(98_603L, "M3", 7.0, 5_000);
        m4 = movie(98_604L, "M4 최하위", 5.0, 200);
        user = newUser("rec-user");
        userToken = jwtTokenProvider.createAccessToken(user.getId(), RoleType.USER);
        admin = newUser("rec-admin");
        adminToken = jwtTokenProvider.createAccessToken(admin.getId(), RoleType.ADMIN);
        em.flush();
        featureIndex.reload();
        vectorIndex.reload();   // 임베딩 행이 없으면 빈 인덱스 — 검색 테스트가 필요할 때 withVectors()를 부른다
    }

    // ── 보안 ───────────────────────────────────────────────────────

    @Test
    void 게스트도_for_you는_200이고_개인화되지_않은_공통_결과다() throws Exception {
        mockMvc.perform(get("/api/recommendations/for-you"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personalized").value(false))
                .andExpect(jsonPath("$.degraded").value(false))
                .andExpect(jsonPath("$.conditions").isEmpty())
                .andExpect(jsonPath("$.movies.content[?(@.id == " + m1.getId() + ")]").exists());
    }

    @Test
    void 게스트_search는_401이다() throws Exception {
        mockMvc.perform(get("/api/recommendations/search").param("q", "잔잔한 영화"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 일반_사용자는_관리자_경로가_403이다() throws Exception {
        mockMvc.perform(post("/api/admin/embeddings/sync").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/recommendations/debug").param("userId", "1").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    // ── 응답 형태 ──────────────────────────────────────────────────

    @Test
    void 추천_카드는_표시제목_장르_이유_wished를_담고_품질_순이다() throws Exception {
        ResultActions result = mockMvc.perform(get("/api/recommendations/for-you").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movies.content[0].id").exists())
                .andExpect(jsonPath("$.movies.content[0].title").exists())
                .andExpect(jsonPath("$.movies.content[0].wished").value(false));
        String body = result.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        // 우리 4편의 상대 순서만 본다(테스트 DB에 다른 영화가 있어도 무관하다).
        List<Integer> order = List.of(
                body.indexOf("\"id\":" + m1.getId()), body.indexOf("\"id\":" + m2.getId()),
                body.indexOf("\"id\":" + m3.getId()), body.indexOf("\"id\":" + m4.getId()));
        assertThat(order).doesNotContain(-1).isSorted();
        assertThat(body).contains("\"type\":\"QUALITY\"").contains("M1 최상위");
    }

    // ── 페이징 ─────────────────────────────────────────────────────

    @Test
    void page_곱하기_size가_100_이상이면_빈_결과이고_last다() throws Exception {
        mockMvc.perform(get("/api/recommendations/for-you").param("page", "5").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movies.content").isEmpty())
                .andExpect(jsonPath("$.movies.last").value(true))
                .andExpect(jsonPath("$.movies.page").value(5));
    }

    @Test
    void size_51은_50으로_잘린다() throws Exception {
        mockMvc.perform(get("/api/recommendations/for-you").param("size", "51"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movies.size").value(50));
    }

    @Test
    void 첫째_페이지부터는_캐시에서_자르고_0페이지는_다시_계산한다() throws Exception {
        // 활동이 있는 사용자(개인 캐시) — m4를 이미 봤다.
        watch(user, m4);
        em.flush();

        // 0페이지(size 1) = 최상위 영화. 1페이지 = 그 다음.
        mockMvc.perform(get("/api/recommendations/for-you").param("size", "1").header("Authorization", "Bearer " + userToken))
                .andExpect(jsonPath("$.movies.content[0].id").value(m1.getId().intValue()));
        mockMvc.perform(get("/api/recommendations/for-you").param("page", "1").param("size", "1").header("Authorization", "Bearer " + userToken))
                .andExpect(jsonPath("$.movies.content[0].id").value(m2.getId().intValue()));

        // 그 사이 m2를 봤다 — 1페이지는 캐시에서 자르므로 순위가 흔들리지 않는다(m2가 그대로 나온다).
        watch(user, m2);
        em.flush();
        mockMvc.perform(get("/api/recommendations/for-you").param("page", "1").param("size", "1").header("Authorization", "Bearer " + userToken))
                .andExpect(jsonPath("$.movies.content[0].id").value(m2.getId().intValue()));
        verify(forYouSpy, times(1)).recommend(any(UserPreferenceProfile.class), any());   // 지금까지 계산은 0페이지 한 번뿐

        // 0페이지는 항상 재계산 — m2가 빠지고 캐시가 교체된다. 이제 1페이지는 m3.
        mockMvc.perform(get("/api/recommendations/for-you").param("size", "1").header("Authorization", "Bearer " + userToken))
                .andExpect(jsonPath("$.movies.content[0].id").value(m1.getId().intValue()));
        mockMvc.perform(get("/api/recommendations/for-you").param("page", "1").param("size", "1").header("Authorization", "Bearer " + userToken))
                .andExpect(jsonPath("$.movies.content[0].id").value(m3.getId().intValue()));
        verify(forYouSpy, times(2)).recommend(any(UserPreferenceProfile.class), any());
    }

    @Test
    void 게스트와_무활동_사용자는_전역_공유_캐시를_쓰고_0페이지도_재계산하지_않는다() throws Exception {
        mockMvc.perform(get("/api/recommendations/for-you")).andExpect(status().isOk());
        mockMvc.perform(get("/api/recommendations/for-you")).andExpect(status().isOk());
        mockMvc.perform(get("/api/recommendations/for-you").header("Authorization", "Bearer " + userToken))   // 기록도 찜도 없는 로그인 사용자
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personalized").value(false));

        verify(forYouSpy, times(1)).recommend(any(UserPreferenceProfile.class), any());

        // 인덱스가 재생성되면(06:00 배치 직후) 그때 한 번 다시 계산한다.
        featureIndex.reload();
        mockMvc.perform(get("/api/recommendations/for-you")).andExpect(status().isOk());
        mockMvc.perform(get("/api/recommendations/for-you")).andExpect(status().isOk());
        verify(forYouSpy, times(2)).recommend(any(UserPreferenceProfile.class), any());
    }

    // ── 검증 ───────────────────────────────────────────────────────

    @Test
    void q가_없으면_400이다() throws Exception {
        mockMvc.perform(get("/api/recommendations/search").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"));
    }

    @Test
    void q가_정규화_후_1자거나_101자면_400_INVALID_RECOMMENDATION_QUERY다() throws Exception {
        mockMvc.perform(get("/api/recommendations/search").param("q", "  a  ").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RECOMMENDATION_QUERY"));
        mockMvc.perform(get("/api/recommendations/search").param("q", "가".repeat(101)).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RECOMMENDATION_QUERY"));
        assertThat(fakeEmbedding.queryCalls()).isZero();   // 검증 실패는 Gemini를 부르지 않는다
    }

    // ── 자연어 정상 경로 ───────────────────────────────────────────

    @Test
    void 자연어_추천은_sim으로_재정렬하고_명시_런타임_조건을_알려_준다() throws Exception {
        withVectors();

        mockMvc.perform(get("/api/recommendations/search").param("q", "90분 이내 잔잔한 영화").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(false))
                .andExpect(jsonPath("$.conditions[0].type").value("RUNTIME_MAX"))
                .andExpect(jsonPath("$.conditions[0].value").value(90))
                .andExpect(jsonPath("$.conditions[0].text").value("90분 이하 영화 위주로 골랐어요"));
        assertThat(fakeEmbedding.queryCalls()).isEqualTo(1);
    }

    @Test
    void 자연어_결과는_page_1부터_캐시에서_자르고_찜한_영화는_wished다() throws Exception {
        withVectors();
        wishMovieRepository.save(WishMovie.of(user, m3));
        em.flush();

        String body = mockMvc.perform(get("/api/recommendations/search").param("q", "잔잔한 영화").param("size", "50")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(body).contains("\"id\":" + m3.getId());
        mockMvc.perform(get("/api/recommendations/search").param("q", "잔잔한 영화").param("page", "1").param("size", "1")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());
        assertThat(fakeEmbedding.queryCalls()).isEqualTo(1);   // 1페이지는 캐시 — 임베딩도 다시 부르지 않는다
    }

    // ── 호출 제한 ──────────────────────────────────────────────────

    @Test
    void 열한_번째_실제_호출은_429이고_Retry_After가_온다() throws Exception {
        withVectors();
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/api/recommendations/search").param("q", "서로 다른 질의 " + i).header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/recommendations/search").param("q", "서로 다른 질의 10").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RECOMMENDATION_RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
        assertThat(fakeEmbedding.queryCalls()).isEqualTo(10);   // 429는 Gemini를 부르지 않았다
    }

    @Test
    void 임베딩_캐시_적중은_호출로_세지_않는다() throws Exception {
        withVectors();
        // 같은 질의를 0페이지로 15번 — 결과는 매번 다시 계산하지만 질의 임베딩은 캐시에서 온다.
        for (int i = 0; i < 15; i++) {
            mockMvc.perform(get("/api/recommendations/search").param("q", "같은 질의").header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isOk());
        }

        assertThat(fakeEmbedding.queryCalls()).isEqualTo(1);
    }

    @Test
    void 다른_사용자는_질의_임베딩_캐시를_공유하지만_호출_제한은_따로다() throws Exception {
        withVectors();
        User other = newUser("rec-other");
        em.flush();
        String otherToken = jwtTokenProvider.createAccessToken(other.getId(), RoleType.USER);

        mockMvc.perform(get("/api/recommendations/search").param("q", "공유 질의").header("Authorization", "Bearer " + userToken)).andExpect(status().isOk());
        mockMvc.perform(get("/api/recommendations/search").param("q", "공유 질의").header("Authorization", "Bearer " + otherToken)).andExpect(status().isOk());

        assertThat(fakeEmbedding.queryCalls()).isEqualTo(1);
    }

    // ── 대체 (degraded) ────────────────────────────────────────────

    @Test
    void 임베딩이_실패하면_degraded_취향_추천으로_대체하고_캐시하지_않는다() throws Exception {
        withVectors();
        fakeEmbedding.failQueriesWith(new EmbeddingClientException("timeout"));

        mockMvc.perform(get("/api/recommendations/search").param("q", "실패하는 질의").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.movies.content[?(@.id == " + m1.getId() + ")]").exists());
        // 1페이지도 캐시에 없다 — 대체 결과는 캐시하지 않았으므로 다시 계산하고 다시 degraded다.
        mockMvc.perform(get("/api/recommendations/search").param("q", "실패하는 질의").param("page", "1").param("size", "1")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(jsonPath("$.degraded").value(true));
        assertThat(fakeEmbedding.queryCalls()).isEqualTo(2);

        // 복구되면 다음 요청이 정상 결과로 돌아온다.
        fakeEmbedding.failQueriesWith(null);
        mockMvc.perform(get("/api/recommendations/search").param("q", "실패하는 질의").header("Authorization", "Bearer " + userToken))
                .andExpect(jsonPath("$.degraded").value(false));
    }

    @Test
    void 대체_결과도_명시_런타임_하드_필터를_유지한다() throws Exception {
        withVectors();
        fakeEmbedding.failQueriesWith(new EmbeddingClientException("timeout"));
        // 우리 영화는 런타임이 없어(null) 하드 필터에서 전부 빠진다 → 대체 결과에서도 조건이 유지됨을 확인
        mockMvc.perform(get("/api/recommendations/search").param("q", "90분 이내 영화").header("Authorization", "Bearer " + userToken))
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.conditions[0].type").value("RUNTIME_MAX"))
                .andExpect(jsonPath("$.movies.content[?(@.id == " + m1.getId() + ")]").doesNotExist());
    }

    @Test
    void 벡터_인덱스가_비어_있으면_Gemini를_부르지_않고_degraded다() throws Exception {
        // setUp에서 임베딩 행이 없는 채로 reload했으므로 인덱스가 비어 있다.
        mockMvc.perform(get("/api/recommendations/search").param("q", "인덱스 없는 질의").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(true));

        assertThat(fakeEmbedding.queryCalls()).isZero();
    }

    // ── 관리자 ─────────────────────────────────────────────────────

    @Test
    void sync가_실행_중이면_409이고_감사_로그를_남기지_않는다() throws Exception {
        doThrow(new BusinessException(ErrorCode.EMBEDDING_SYNC_ALREADY_RUNNING)).when(syncSpy).sync(any(), anyInt());

        mockMvc.perform(post("/api/admin/embeddings/sync").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMBEDDING_SYNC_ALREADY_RUNNING"));

        assertThat(logsOf(AdminAction.EMBEDDING_SYNC)).isEmpty();
    }

    @Test
    void sync는_기본_limit_1000으로_실행하고_결과와_감사_로그를_남긴다() throws Exception {
        long afterId = m1.getId() - 1;

        mockMvc.perform(post("/api/admin/embeddings/sync").param("afterId", String.valueOf(afterId)).param("limit", "4")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embedded").value(4))
                .andExpect(jsonPath("$.unchanged").value(0))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.stoppedByRateLimit").value(false))
                .andExpect(jsonPath("$.lastProcessedId").value(m4.getId().intValue()));

        assertThat(embeddingRepository.findByMovieIdIn(List.of(m1.getId(), m4.getId()))).hasSize(2);
        List<AdminActionLog> logs = logsOf(AdminAction.EMBEDDING_SYNC);
        assertThat(logs).singleElement().satisfies(log -> {
            assertThat(log.getAdminUserId()).isEqualTo(admin.getId());
            assertThat(log.getDetail()).containsEntry("embedded", 4).containsEntry("limit", 4);
        });
        // 완료 후 벡터 인덱스가 재생성돼 방금 넣은 영화가 검색된다.
        assertThat(vectorIndex.size()).isGreaterThanOrEqualTo(4);

        // 같은 범위를 다시 돌리면 전부 변경 없음 — 해시 비교.
        mockMvc.perform(post("/api/admin/embeddings/sync").param("afterId", String.valueOf(afterId)).param("limit", "4")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.embedded").value(0))
                .andExpect(jsonPath("$.unchanged").value(4));
    }

    @Test
    void sync의_limit은_1에서_5000_사이여야_한다() throws Exception {
        mockMvc.perform(post("/api/admin/embeddings/sync").param("limit", "0").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/admin/embeddings/sync").param("limit", "5001").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/admin/embeddings/sync").param("afterId", "-1").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
        assertThat(logsOf(AdminAction.EMBEDDING_SYNC)).isEmpty();
    }

    @Test
    void 디버그는_프로필_점수분해_제외건수를_돌려주고_감사_로그를_남긴다() throws Exception {
        watch(user, m4);
        em.flush();

        mockMvc.perform(get("/api/admin/recommendations/debug").param("userId", String.valueOf(user.getId()))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.getId().intValue()))
                .andExpect(jsonPath("$.query").doesNotExist())
                .andExpect(jsonPath("$.profile.watchedCount").value(1))
                .andExpect(jsonPath("$.profile.personalized").value(false))
                .andExpect(jsonPath("$.exclusions.watched").value(1))
                .andExpect(jsonPath("$.movies[0].quality").exists())
                .andExpect(jsonPath("$.movies[0].popularity").exists())
                .andExpect(jsonPath("$.movies[0].score").exists());

        List<AdminActionLog> logs = logsOf(AdminAction.RECOMMENDATION_DEBUG_VIEW);
        assertThat(logs).singleElement().satisfies(log -> {
            assertThat(log.getTargetType()).isEqualTo(AdminTargetType.USER);
            assertThat(log.getTargetId()).isEqualTo(user.getId());
        });
    }

    @Test
    void 디버그_자연어는_sim을_포함하고_호출_제한과_임베딩_캐시에_영향이_없다() throws Exception {
        withVectors();

        mockMvc.perform(get("/api/admin/recommendations/debug").param("userId", String.valueOf(user.getId())).param("q", "디버그 전용 질의")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("디버그 전용 질의"))
                .andExpect(jsonPath("$.movies[0].sim").exists());

        // 같은 질의를 사용자 API로 처음 호출하면 임베딩 캐시가 비어 있으므로 실제 호출이 한 번 더 일어난다.
        mockMvc.perform(get("/api/recommendations/search").param("q", "디버그 전용 질의").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());
        assertThat(fakeEmbedding.queryCalls()).isEqualTo(2);
    }

    @Test
    void 디버그는_없는_회원이면_404다() throws Exception {
        mockMvc.perform(get("/api/admin/recommendations/debug").param("userId", "999999999").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        assertThat(logsOf(AdminAction.RECOMMENDATION_DEBUG_VIEW)).isEmpty();
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────

    private Movie movie(long tmdbId, String title, double average, int votes) {
        return movieRepository.save(Movie.builder().tmdbId(tmdbId).title(title)
                .releaseDate(LocalDate.of(2020, 1, 1))
                .voteAverage(BigDecimal.valueOf(average)).voteCount(votes).build());
    }

    private User newUser(String prefix) {
        return userRepository.save(User.createLocal(prefix + (++seq) + "@test.com", "{noop}pw", prefix + seq));
    }

    private void watch(User who, Movie movie) {
        watchRecordRepository.save(WatchRecord.builder().user(who).movie(movie).representative(true)
                .rating(BigDecimal.valueOf(5.0)).watchDate(LocalDate.of(2024, 1, 1)).build());
    }

    /** 네 영화의 임베딩 행을 넣고 벡터 인덱스를 다시 올린다(자연어 검색이 대체되지 않게). */
    private void withVectors() {
        for (Movie movie : List.of(m1, m2, m3, m4)) {
            EmbeddingText text = new EmbeddingText(movie.getTitle(), "본문", OverviewSource.KO);
            embeddingPersister.upsert(FakeEmbeddingClient.MODEL, List.of(new MovieEmbeddingPersister.Item(
                    movie.getId(), text, text.hash(FakeEmbeddingClient.MODEL), FakeEmbeddingClient.vectorOf("movie:" + movie.getId()))));
        }
        em.flush();
        vectorIndex.reload();
    }

    private List<AdminActionLog> logsOf(AdminAction action) {
        em.flush();
        em.clear();
        return logRepository.findAll().stream().filter(l -> l.getAction() == action).toList();
    }
}
