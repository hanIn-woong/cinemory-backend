package com.project.cinemory.domain.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.recommendation.index.MovieFeatureIndex;
import com.project.cinemory.domain.user.entity.RoleType;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.domain.watch.entity.WatchRecord;
import com.project.cinemory.domain.watch.repository.WatchRecordRepository;
import com.project.cinemory.global.security.JwtTokenProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 내 취향 추천 묶음 API (controller-layer-spec 5-14-K, 5-14-J "취향 묶음") - HTTP 계약과 서버 측 상태. 추출 규칙의 세부(시드 고정·감독 상한·
 * 풀 소진)는 {@code ForYouBatchServiceTest}가 단위로 검증하고, 여기서는 파라미터 처리와 같은 날 유지·겹침 없음·프로필 변경을 실제 빈으로 본다.
 *
 * <p>풀(상위 50편)이 우리 영화로만 차도록 품질·투표 수가 높은 영화 60편을 만든다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ForYouBatchApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired MovieRepository movieRepository;
    @Autowired UserRepository userRepository;
    @Autowired WatchRecordRepository watchRecordRepository;
    @Autowired MovieFeatureIndex featureIndex;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired EntityManager em;

    private static int seq = 0;

    private List<Movie> movies;
    private User user;
    private String userToken;

    @BeforeEach
    void setUp() {
        movies = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            movies.add(movieRepository.save(Movie.builder().tmdbId(96_500L + i).title("묶음" + i)
                    .releaseDate(LocalDate.of(2015, 1, 1)).voteAverage(BigDecimal.valueOf(9.9)).voteCount(500_000).build()));
        }
        user = userRepository.save(User.createLocal("batch-user" + (++seq) + "@test.com", "{noop}pw", "묶음" + seq));
        userToken = jwtTokenProvider.createAccessToken(user.getId(), RoleType.USER);
        em.flush();
        featureIndex.reload();
    }

    // ── 묶음 형태 ──────────────────────────────────────────────────

    @Test
    void 묶음은_10편이고_hasMore가_있다() throws Exception {
        JsonNode body = call(guest());

        assertThat(body.path("movies")).hasSize(10);
        assertThat(body.path("personalized").asBoolean()).isFalse();
        assertThat(body.path("hasMore").asBoolean()).isTrue();   // 풀 50편 중 10편만 봤다
    }

    @Test
    void 게스트_묶음은_서버에_상태를_남기지_않아_매번_다른_표본이다() throws Exception {
        List<Set<Integer>> samples = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            samples.add(ids(call(guest())));
        }

        assertThat(samples.stream().distinct().count()).isGreaterThan(1);   // 상태가 없으니 같은 요청도 새로 뽑는다
    }

    // ── 같은 날 · 같은 프로필 = 같은 묶음 / 다른 추천 보기 ─────────────

    @Test
    void 같은_날_next_false를_두_번_부르면_같은_묶음이다_무활동_로그인_사용자도_서버_이력을_쓴다() throws Exception {
        List<Integer> first = orderedIds(call(asUser()));
        List<Integer> second = orderedIds(call(asUser()));

        assertThat(first).hasSize(10);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void next_true는_직전_3묶음과_겹치지_않는다() throws Exception {
        watch(movies.get(59));   // 기록이 있는 사용자 - 개인 풀
        em.flush();
        List<Set<Integer>> batches = new ArrayList<>();
        batches.add(ids(call(asUser())));
        for (int i = 0; i < 5; i++) {
            Set<Integer> next = ids(call(asUser().param("next", "true")));
            assertThat(next).hasSize(10);
            for (Set<Integer> recent : batches.subList(Math.max(0, batches.size() - 3), batches.size())) {
                assertThat(next.stream().filter(recent::contains).toList()).isEmpty();
            }
            batches.add(next);
        }
    }

    @Test
    void 기록을_추가하면_next_false여도_새_묶음이다_방금_본_영화는_남지_않는다() throws Exception {
        watch(movies.get(58));
        em.flush();
        Set<Integer> before = ids(call(asUser()));
        Set<Integer> victims = before;   // 현재 묶음에 있던 영화 중 하나를 방금 봤다
        int justWatched = before.iterator().next();

        watch(movieRepository.findById((long) justWatched).orElseThrow());
        em.flush();
        Set<Integer> after = ids(call(asUser()));

        assertThat(after).hasSize(10).doesNotContain(justWatched);
        assertThat(after.stream().filter(victims::contains).toList()).isEmpty();   // 버려진 현재 묶음도 최근 묶음으로 제외된다
    }

    @Test
    void 기록이_바뀌지_않으면_next_false는_계속_같은_묶음이다() throws Exception {
        watch(movies.get(57));
        em.flush();
        List<Integer> first = orderedIds(call(asUser()));
        call(asUser().param("next", "false"));

        assertThat(orderedIds(call(asUser().param("next", "false")))).isEqualTo(first);
    }

    // ── 게스트 exclude ─────────────────────────────────────────────

    @Test
    void 게스트_exclude에_보낸_영화는_다시_받지_않는다() throws Exception {
        Set<Integer> first = ids(call(guest()));

        Set<Integer> second = ids(call(guest().param("exclude", join(first))));

        assertThat(second).hasSize(10);
        assertThat(second.stream().filter(first::contains).toList()).isEmpty();
    }

    @Test
    void 게스트_exclude가_31개여도_200이고_30개만_반영한다() throws Exception {
        List<Long> ids = movies.stream().limit(31).map(Movie::getId).toList();

        JsonNode body = call(guest().param("exclude", ids.stream().map(String::valueOf).collect(Collectors.joining(","))));

        assertThat(body.path("movies")).hasSize(10);
        // 31개 중 가장 오래된(앞) 하나는 제외로 세지 않으니, 뒤 30개는 절대 나오지 않는다
        Set<Long> latest30 = ids.subList(1, 31).stream().collect(Collectors.toSet());
        assertThat(ids(body).stream().map(Integer::longValue).filter(latest30::contains).toList()).isEmpty();
    }

    @Test
    void exclude가_숫자가_아니면_400_INVALID_TYPE_VALUE다() throws Exception {
        mockMvc.perform(guest().param("exclude", "1,abc,3"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TYPE_VALUE"));
    }

    @Test
    void 로그인_사용자가_보낸_exclude는_무시한다() throws Exception {
        // 서버의 묶음 이력을 쓴다 - exclude를 보내도 같은 날 next=false는 같은 묶음이다
        List<Integer> first = orderedIds(call(asUser()));

        List<Integer> again = orderedIds(call(asUser().param("exclude", join(ids(call(guest()))))));

        assertThat(again).isEqualTo(first);
    }

    // ── 디버그 ─────────────────────────────────────────────────────

    @Test
    void 취향_디버그는_풀_50편의_key와_제외_사유를_보여_주고_상태를_바꾸지_않는다() throws Exception {
        watch(movies.get(56));
        em.flush();
        List<Integer> shown = orderedIds(call(asUser()));
        String adminToken = jwtTokenProvider.createAccessToken(
                userRepository.save(User.createLocal("batch-admin" + (++seq) + "@test.com", "{noop}pw", "관리" + seq)).getId(), RoleType.ADMIN);
        em.flush();

        String json = mockMvc.perform(get("/api/admin/recommendations/debug").param("userId", String.valueOf(user.getId()))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode debug = new ObjectMapper().readTree(json);

        assertThat(debug.path("movies")).hasSize(50);
        assertThat(debug.path("draw").path("recentBatches")).hasSize(1);
        int recent = 0;
        int selected = 0;
        for (JsonNode movie : debug.path("movies")) {
            String status = movie.path("batchStatus").asText();
            if ("RECENT_BATCH".equals(status)) {
                recent++;
                assertThat(movie.path("recentBatchIndex").asInt()).isZero();   // 현재 묶음
                assertThat(movie.path("extractionKey").isNull()).isTrue();     // 제외된 영화는 key가 없다
            } else {
                assertThat(movie.path("extractionKey").isNumber()).isTrue();
            }
            if ("SELECTED".equals(status)) {
                selected++;
            }
        }
        assertThat(recent).isEqualTo(10);
        assertThat(selected).isEqualTo(10);

        assertThat(orderedIds(call(asUser()))).isEqualTo(shown);   // 디버그는 사용자의 묶음 상태를 바꾸지 않는다
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────

    private MockHttpServletRequestBuilder guest() {
        return get("/api/recommendations/for-you");
    }

    private MockHttpServletRequestBuilder asUser() {
        return get("/api/recommendations/for-you").header("Authorization", "Bearer " + userToken);
    }

    private JsonNode call(MockHttpServletRequestBuilder request) throws Exception {
        String body = mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return new ObjectMapper().readTree(body);
    }

    private static List<Integer> orderedIds(JsonNode body) {
        List<Integer> ids = new ArrayList<>();
        body.path("movies").forEach(node -> ids.add(node.path("id").asInt()));
        return ids;
    }

    private static Set<Integer> ids(JsonNode body) {
        return new java.util.LinkedHashSet<>(orderedIds(body));
    }

    private static String join(Set<Integer> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private void watch(Movie movie) {
        watchRecordRepository.save(WatchRecord.builder().user(user).movie(movie).representative(true)
                .rating(BigDecimal.valueOf(5.0)).watchDate(LocalDate.of(2024, 1, 1)).build());
    }
}
