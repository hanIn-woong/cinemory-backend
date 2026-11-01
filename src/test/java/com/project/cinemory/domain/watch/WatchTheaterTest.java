package com.project.cinemory.domain.watch;

import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.theater.dto.TheaterSummaryResponse;
import com.project.cinemory.domain.theater.entity.Theater;
import com.project.cinemory.domain.theater.service.TheaterQueryService;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.watch.dto.WatchRecordCreateRequest;
import com.project.cinemory.domain.watch.dto.WatchRecordResponse;
import com.project.cinemory.domain.watch.dto.WatchRecordUpdateRequest;
import com.project.cinemory.domain.watch.entity.WatchType;
import com.project.cinemory.domain.watch.service.WatchRecordService;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 시청 기록 ↔ 극장 연결 (service-layer-spec 4-3-C, 테스트 ①~⑧). 실 DB({@code cinemory_test})를 쓰고 테스트마다 롤백한다.
 * 검색어는 테스트마다 고유한 접두를 써서 실 시드 극장(약 720곳)과 섞이지 않게 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WatchTheaterTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @Autowired
    private WatchRecordService watchRecordService;
    @Autowired
    private TheaterQueryService theaterQueryService;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager em;

    // ---- 픽스처 -------------------------------------------------------------------------------------

    private User user() {
        User user = User.createOAuth("theater" + SEQ.incrementAndGet() + "@theater.test", "극장유저", null);
        em.persist(user);
        return user;
    }

    private Movie movie() {
        Movie movie = Movie.builder().tmdbId(500_000_000L + SEQ.incrementAndGet() % 90_000_000L).title("극장 영화").build();
        em.persist(movie);
        return movie;
    }

    private Theater theater(String name) {
        Theater theater = Theater.builder()
                .sourceCode("test|" + SEQ.incrementAndGet())
                .name(name)
                .chainName("테스트체인")
                .address("서울시 테스트구 " + name)
                .latitude(new BigDecimal("37.5000000"))
                .longitude(new BigDecimal("127.0000000"))
                .build();
        em.persist(theater);
        em.flush();
        return theater;
    }

    private void close(Theater theater) {
        theater.close(Instant.now());
        em.flush();
        em.clear();
    }

    private WatchRecordResponse add(User user, Movie movie, WatchType type, String placeDetail, Long theaterId) {
        return watchRecordService.addWatchRecord(user.getId(), new WatchRecordCreateRequest(
                movie.getId(), LocalDate.of(2026, 10, 1), type, placeDetail, null, null, null, theaterId));
    }

    private WatchRecordResponse update(User user, Long recordId, WatchType type, String placeDetail,
                                       BigDecimal rating, Long theaterId) {
        return watchRecordService.updateWatchRecord(user.getId(), recordId, new WatchRecordUpdateRequest(
                LocalDate.of(2026, 10, 1), type, placeDetail, null, rating, null, theaterId));
    }

    private static void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    // ---- ① 저장 · 조회 ------------------------------------------------------------------------------

    @Test
    void T1_극장을_연결해_저장하면_응답에_theater가_담기고_상영관은_placeDetail에_남는다() {
        User user = user();
        Theater theater = theater("저장극장" + SEQ.incrementAndGet());

        WatchRecordResponse created = add(user, movie(), WatchType.THEATER, "IMAX · 4관", theater.getId());
        em.flush();
        em.clear();

        assertThat(created.theater()).isNotNull();
        assertThat(created.theater().id()).isEqualTo(theater.getId());
        assertThat(created.theater().name()).isEqualTo(theater.getName());
        assertThat(created.theater().address()).isEqualTo(theater.getAddress());   // 같은 이름 지점 구분용
        assertThat(created.theater().closed()).isFalse();
        assertThat(created.placeDetail()).isEqualTo("IMAX · 4관");

        WatchRecordResponse log = watchRecordService.getWatchLog(user.getId(), user.getId(), created.movieId()).get(0);
        assertThat(log.theater().name()).isEqualTo(theater.getName());
    }

    @Test
    void T1_직접_입력이면_theater가_null이고_극장명이_placeDetail에_남는다() {
        WatchRecordResponse created = add(user(), movie(), WatchType.THEATER, "동네극장", null);

        assertThat(created.theater()).isNull();
        assertThat(created.placeDetail()).isEqualTo("동네극장");
    }

    // ---- ② 방식 조합 --------------------------------------------------------------------------------

    @Test
    void T2_극장이_아닌_방식에_theaterId를_붙이면_400이다() {
        User user = user();
        Movie movie = movie();
        Long theaterId = theater("조합극장" + SEQ.incrementAndGet()).getId();

        assertCode(() -> add(user, movie, WatchType.OTT, null, theaterId), ErrorCode.INVALID_WATCH_TYPE_THEATER_COMBINATION);
        assertCode(() -> add(user, movie, WatchType.TV, null, theaterId), ErrorCode.INVALID_WATCH_TYPE_THEATER_COMBINATION);
        assertCode(() -> add(user, movie, null, null, theaterId), ErrorCode.INVALID_WATCH_TYPE_THEATER_COMBINATION);
    }

    @Test
    void T2_존재하지_않는_극장은_THEATER_NOT_FOUND다() {
        assertCode(() -> add(user(), movie(), WatchType.THEATER, null, 999_999_999L), ErrorCode.THEATER_NOT_FOUND);
    }

    @Test
    void T2_수정에서_방식을_극장_밖으로_바꾸면서_theaterId를_남기면_400이고_비워서_보내면_연결이_풀린다() {
        User user = user();
        Theater theater = theater("수정극장" + SEQ.incrementAndGet());
        WatchRecordResponse created = add(user, movie(), WatchType.THEATER, null, theater.getId());

        assertCode(() -> update(user, created.id(), WatchType.TV, "OCN", null, theater.getId()),
                ErrorCode.INVALID_WATCH_TYPE_THEATER_COMBINATION);

        WatchRecordResponse cleared = update(user, created.id(), WatchType.TV, "OCN", null, null);
        assertThat(cleared.theater()).isNull();
    }

    // ---- ③ ~ ⑤ 폐업 -------------------------------------------------------------------------------

    @Test
    void T3_폐업_처리_후에도_기존_기록은_이름과_closed_true로_조회된다() {
        User user = user();
        Theater theater = theater("폐업극장" + SEQ.incrementAndGet());
        WatchRecordResponse created = add(user, movie(), WatchType.THEATER, null, theater.getId());
        em.flush();
        close(theater);

        WatchRecordResponse log = watchRecordService.getWatchLog(user.getId(), user.getId(), created.movieId()).get(0);

        assertThat(log.theater().name()).isEqualTo(theater.getName());
        assertThat(log.theater().closed()).isTrue();
    }

    @Test
    void T4_폐업_극장_기록의_별점만_수정하면_성공한다() {
        User user = user();
        Theater theater = theater("폐업수정" + SEQ.incrementAndGet());
        WatchRecordResponse created = add(user, movie(), WatchType.THEATER, "2관", theater.getId());
        em.flush();
        close(theater);

        WatchRecordResponse updated = update(user, created.id(), WatchType.THEATER, "2관", BigDecimal.valueOf(8), theater.getId());

        assertThat(updated.rating()).isEqualByComparingTo("8");
        assertThat(updated.theater().id()).isEqualTo(theater.getId());
        assertThat(updated.theater().closed()).isTrue();
    }

    @Test
    void T5_다른_폐업_극장으로_바꾸거나_폐업_극장으로_새로_만들면_THEATER_CLOSED다() {
        User user = user();
        Theater open = theater("영업극장" + SEQ.incrementAndGet());
        Theater closed = theater("닫은극장" + SEQ.incrementAndGet());
        WatchRecordResponse created = add(user, movie(), WatchType.THEATER, null, open.getId());
        em.flush();
        close(closed);

        assertCode(() -> update(user, created.id(), WatchType.THEATER, null, null, closed.getId()), ErrorCode.THEATER_CLOSED);
        assertCode(() -> add(user, movie(), WatchType.THEATER, null, closed.getId()), ErrorCode.THEATER_CLOSED);
    }

    // ---- ⑥ 물리 삭제 · CHECK -----------------------------------------------------------------------

    @Test
    void T6_기록이_참조하는_극장을_물리_삭제하면_FK로_실패한다() {
        User user = user();
        Theater theater = theater("참조극장" + SEQ.incrementAndGet());
        add(user, movie(), WatchType.THEATER, null, theater.getId());
        em.flush();

        assertThatThrownBy(() -> jdbc.update("DELETE FROM theater WHERE id = ?", theater.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void T6_극장이_아닌_방식에_theater_id를_직접_넣으면_CHECK가_막는다() {
        User user = user();
        Theater theater = theater("체크극장" + SEQ.incrementAndGet());
        WatchRecordResponse tv = add(user, movie(), WatchType.TV, "OCN", null);
        em.flush();

        assertThatThrownBy(() -> jdbc.update("UPDATE watch_record SET theater_id = ? WHERE id = ?", theater.getId(), tv.id()))
                .hasMessageContaining("chk_watch_record_theater"); // CHECK 위반(3819)은 스프링이 DataIntegrityViolation으로 분류하지 않는다
    }

    // ---- ⑦ 검색 ------------------------------------------------------------------------------------

    @Test
    void T7_검색은_폐업을_제외하고_이름이_접두로_시작하는_것을_먼저_준다() {
        String key = "검색키" + SEQ.incrementAndGet();
        Theater inName = theater("가나다" + key);          // 이름에 포함(접두 아님)
        Theater prefix = theater(key + "시네마");          // 접두 일치
        Theater closed = theater(key + "폐업관");
        close(closed);

        List<TheaterSummaryResponse> result = theaterQueryService.searchTheaters(key, null);

        assertThat(result).extracting(TheaterSummaryResponse::id).containsExactly(prefix.getId(), inName.getId());
        assertThat(result).extracting(TheaterSummaryResponse::address).containsExactly(prefix.getAddress(), inName.getAddress());
    }

    @Test
    void T7_limit은_상한_50으로_자르고_빈_검색어는_400이다() {
        String key = "한도키" + SEQ.incrementAndGet();
        for (int i = 0; i < 3; i++) {
            theater(key + i);
        }

        assertThat(theaterQueryService.searchTheaters(key, 2)).hasSize(2);
        assertThat(theaterQueryService.searchTheaters(key, 9999)).hasSize(3);
        assertCode(() -> theaterQueryService.searchTheaters("   ", null), ErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    void T7_검색어의_퍼센트와_밑줄은_와일드카드가_아니다() {
        String key = "와일드" + SEQ.incrementAndGet();
        theater(key + "A");

        assertThat(theaterQueryService.searchTheaters(key + "%", null)).isEmpty();
        assertThat(theaterQueryService.searchTheaters(key + "_", null)).isEmpty();
    }

    @Test
    void T7_검색은_로그인_없이_되고_recent는_로그인이_필요하다() throws Exception {
        String key = "공개키" + SEQ.incrementAndGet();
        theater(key);
        em.flush();

        mockMvc.perform(get("/api/theaters/search").param("q", key)).andExpect(status().isOk());
        mockMvc.perform(get("/api/theaters/search").param("q", " ")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/users/me/theaters/recent")).andExpect(status().isUnauthorized());
    }

    // ---- ⑧ 최근 극장 -------------------------------------------------------------------------------

    @Test
    void T8_최근_극장은_중복_없이_최대_5곳이고_최근_기록한_순서이며_폐업은_뺀다() {
        User user = user();
        Movie movie = movie();
        List<Theater> theaters = java.util.stream.IntStream.range(0, 7)
                .mapToObj(i -> theater("최근극장" + SEQ.incrementAndGet())).toList();
        for (Theater t : theaters) {
            add(user, movie, WatchType.THEATER, null, t.getId());
        }
        add(user, movie, WatchType.THEATER, null, theaters.get(0).getId()); // 첫 극장을 다시 가장 최근으로
        em.flush();
        close(theaters.get(6)); // 가장 최근에 새로 간 극장을 폐업 처리

        List<TheaterSummaryResponse> recent = theaterQueryService.getRecentTheaters(user.getId());

        assertThat(recent).extracting(TheaterSummaryResponse::id).containsExactly(
                theaters.get(0).getId(), theaters.get(5).getId(), theaters.get(4).getId(),
                theaters.get(3).getId(), theaters.get(2).getId());
        assertThat(recent).allSatisfy(t -> assertThat(t.address()).isNotBlank());
    }

    @Test
    void T8_극장_기록이_없으면_빈_목록이다() {
        assertThat(theaterQueryService.getRecentTheaters(user().getId())).isEmpty();
    }
}
