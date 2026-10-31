package com.project.cinemory.domain.watch.service;

import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.watch.dto.WatchRecordCreateRequest;
import com.project.cinemory.domain.watch.dto.WatchRecordResponse;
import com.project.cinemory.domain.watch.entity.WatchType;
import com.project.cinemory.global.security.JwtTokenProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 회차 목록(getWatchLog)은 id 오름차순 — 1회차가 먼저 (service-layer-spec 4-3, 2026-11-01).
 * 대표 기본값이 "처음 기록"이라 목록의 첫 줄과 대표가 일치한다. 실 DB({@code cinemory_test})를 쓰고 테스트마다 롤백한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WatchLogOrderTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private WatchRecordService service;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private EntityManager em;

    private User user() {
        User user = User.createOAuth("watchlog" + SEQ.incrementAndGet() + "@watchlog.test", "회차순서", null);
        em.persist(user);
        return user;
    }

    private Movie movie() {
        Movie movie = Movie.builder().tmdbId(500_000_000L + SEQ.incrementAndGet() % 90_000_000L).title("회차 순서 영화").build();
        em.persist(movie);
        return movie;
    }

    private WatchRecordResponse add(User user, Movie movie, LocalDate date) {
        return service.addWatchRecord(user.getId(),
                new WatchRecordCreateRequest(movie.getId(), date, WatchType.THEATER, null, null, null, null));
    }

    @Test
    void 회차_목록은_1회차가_먼저이고_첫_줄이_대표다() {
        User user = user();
        Movie movie = movie();
        WatchRecordResponse first = add(user, movie, LocalDate.of(2026, 10, 1));
        WatchRecordResponse second = add(user, movie, LocalDate.of(2026, 10, 2));
        WatchRecordResponse third = add(user, movie, LocalDate.of(2026, 10, 3));

        List<WatchRecordResponse> log = service.getWatchLog(user.getId(), user.getId(), movie.getId());

        assertThat(log).extracting(WatchRecordResponse::id).containsExactly(first.id(), second.id(), third.id());
        assertThat(log.get(0).representative()).isTrue();
        assertThat(log.get(1).representative()).isFalse();
    }

    @Test
    void 관람일이_없는_회차도_만든_순서_그대로_id_오름차순이다() {
        User user = user();
        Movie movie = movie();
        WatchRecordResponse first = add(user, movie, null);
        WatchRecordResponse second = add(user, movie, LocalDate.of(2026, 10, 2));
        WatchRecordResponse third = add(user, movie, null);

        assertThat(service.getWatchLog(user.getId(), user.getId(), movie.getId()))
                .extracting(WatchRecordResponse::id).containsExactly(first.id(), second.id(), third.id());
    }

    @Test
    void REST_응답도_같은_순서다() throws Exception {
        User user = user();
        Movie movie = movie();
        WatchRecordResponse first = add(user, movie, LocalDate.of(2026, 10, 1));
        WatchRecordResponse second = add(user, movie, LocalDate.of(2026, 10, 2));
        em.flush();

        String path = "/api/users/" + user.getId() + "/records/movies/" + movie.getId();
        JsonNode body = JSON.readTree(mockMvc.perform(get(path)
                        .header("Authorization", "Bearer " + jwtTokenProvider.createAccessToken(user.getId(), user.getRole())))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        List<Long> ids = new ArrayList<>();
        body.forEach(node -> ids.add(node.get("id").asLong()));
        assertThat(ids).containsExactly(first.id(), second.id());
    }
}
