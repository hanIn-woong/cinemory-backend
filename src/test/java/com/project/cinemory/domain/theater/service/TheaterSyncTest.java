package com.project.cinemory.domain.theater.service;

import com.project.cinemory.domain.theater.dto.TheaterSeedReport;
import com.project.cinemory.domain.theater.entity.Theater;
import com.project.cinemory.domain.theater.repository.TheaterRepository;
import com.project.cinemory.global.infra.kakao.KakaoLocalClient;
import com.project.cinemory.global.infra.kakao.KakaoLocalClient.LatLng;
import com.project.cinemory.global.infra.publicdata.PublicDataTheaterClient;
import com.project.cinemory.global.infra.publicdata.dto.TheaterSourcePage;
import com.project.cinemory.global.infra.publicdata.dto.TheaterSourceRow;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 극장 동기화(cinemap-spec C-5) — 숨김·복구, 수신 불완전 시 중단, 대량 숨김 보류, 예매 링크 보존, 좌표 재사용.
 * 공공데이터·카카오는 목이다. DB는 테스트 트랜잭션 안이라 롤백된다.
 */
@SpringBootTest
@Transactional
class TheaterSyncTest {

    private static final String GRP = "1111";

    @Autowired
    private TheaterSeedService seedService;
    @Autowired
    private TheaterRepository theaterRepository;
    @Autowired
    private EntityManager em;

    @MockitoBean
    private PublicDataTheaterClient publicData;
    @MockitoBean
    private KakaoLocalClient kakao;

    @BeforeEach
    void setUp() {
        // 다른 곳에서 남은 극장이 "영업 중 극장 수"(대량 숨김 기준)를 흔들지 않게 비운다 — 테스트 트랜잭션이라 롤백된다
        theaterRepository.deleteAll();
        em.flush();
        when(publicData.isConfigured()).thenReturn(true);
        when(kakao.isConfigured()).thenReturn(true);
        when(kakao.searchAddress(anyString())).thenReturn(Optional.of(new LatLng(37.1, 127.1)));
    }

    private static String road(String name) {
        return "서울특별시 중구 " + name + "로 1";
    }

    private static String key(String name) {
        return GRP + "|" + road(name);
    }

    private static TheaterSourceRow row(String name) {
        return new TheaterSourceRow(GRP, "M-" + name, "CGV " + name, "01", "영화관", road(name), "", "", "");
    }

    private Theater saveTheater(String name) {
        return theaterRepository.save(Theater.builder().sourceCode(key(name)).name("CGV " + name).chainName("CGV")
                .address(road(name)).latitude(new BigDecimal("37.5000000")).longitude(new BigDecimal("127.0000000"))
                .screenCount(3).build());
    }

    /** 원천이 이 행들을 totalCount와 함께 한 페이지로 준다. 이후 페이지는 비어 있다. */
    private void serve(List<TheaterSourceRow> rows, int totalCount) {
        when(publicData.fetchPage(anyInt(), anyInt())).thenAnswer(inv ->
                (int) inv.getArgument(0) == 1 ? new TheaterSourcePage(totalCount, rows) : new TheaterSourcePage(totalCount, List.of()));
    }

    private Theater reload(Theater theater) {
        em.flush();
        em.clear();
        return theaterRepository.findById(theater.getId()).orElseThrow();
    }

    @Test
    void 사라진_극장은_숨기고_다시_나타난_극장은_복구한다() {
        Theater stays = saveTheater("유지");
        Theater gone = saveTheater("사라짐");
        Theater back = saveTheater("복귀");
        back.close(Instant.parse("2026-10-01T00:00:00Z"));
        em.flush();
        serve(List.of(row("유지"), row("복귀")), 2);

        TheaterSeedReport report = seedService.seed(false, false);

        assertThat(report.aborted()).isFalse();
        assertThat(report.closedTheaters()).isEqualTo(1);
        assertThat(report.reopenedTheaters()).isEqualTo(1);
        assertThat(report.newTheaters()).isZero();
        assertThat(reload(stays).isClosed()).isFalse();
        assertThat(reload(gone).isClosed()).isTrue();
        assertThat(reload(back).isClosed()).isFalse();
    }

    @Test
    void dryRun은_같은_수를_보여주되_DB는_바꾸지_않는다() {
        saveTheater("유지");
        Theater gone = saveTheater("사라짐");
        em.flush();
        serve(List.of(row("유지"), row("신규")), 2);

        TheaterSeedReport report = seedService.seed(true, false);

        assertThat(report.newTheaters()).isEqualTo(1);
        assertThat(report.closedTheaters()).isEqualTo(1);
        assertThat(report.inserted()).isNull();
        assertThat(reload(gone).isClosed()).isFalse();
        assertThat(theaterRepository.count()).isEqualTo(2);
    }

    @Test
    void 수신_행_수가_totalCount와_다르면_아무것도_반영하지_않는다() {
        Theater existing = saveTheater("기존");
        em.flush();
        // totalCount는 250인데 3행만 오고 다음 페이지는 비어 있다 — 일부만 받고 끊긴 상황
        serve(List.of(row("A"), row("B"), row("C")), 250);

        TheaterSeedReport report = seedService.seed(false, false);

        assertThat(report.aborted()).isTrue();
        assertThat(report.abortReason()).contains("totalCount");
        assertThat(theaterRepository.count()).isEqualTo(1);   // 신규 삽입 없음
        assertThat(reload(existing).isClosed()).isFalse();    // "기존"이 원천에 없어도 숨기지 않음
        verify(kakao, never()).searchAddress(anyString());     // 분석·좌표 조회도 하지 않음
    }

    @Test
    void 최대_페이지에_도달하면_중단한다() {
        saveTheater("기존");
        em.flush();
        // 페이지마다 새 행 1개, totalCount는 아주 크다 → 200페이지에서 멈춘다
        when(publicData.fetchPage(anyInt(), anyInt())).thenAnswer(inv -> {
            int page = inv.getArgument(0);
            return new TheaterSourcePage(1_000_000, List.of(row("P" + page)));
        });

        TheaterSeedReport report = seedService.seed(false, false);

        assertThat(report.aborted()).isTrue();
        assertThat(report.abortReason()).contains("최대 페이지");
        assertThat(theaterRepository.count()).isEqualTo(1);
    }

    @Test
    void 대량_숨김은_보류하고_allowMassClose면_실행한다() {
        List<Theater> existing = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            existing.add(saveTheater("기존" + i));
        }
        em.flush();
        serve(List.of(row("신규")), 1);

        // 영업 중 30곳이 전부 사라짐 → 기준 max(5%, 10곳)=10 초과 → 숨김 보류, 신규는 반영
        TheaterSeedReport held = seedService.seed(false, false);

        assertThat(held.massCloseHeld()).isEqualTo(30);
        assertThat(held.closedTheaters()).isZero();
        assertThat(held.newTheaters()).isEqualTo(1);
        assertThat(reload(existing.get(0)).isClosed()).isFalse();

        TheaterSeedReport forced = seedService.seed(false, true);

        assertThat(forced.massCloseHeld()).isZero();
        assertThat(forced.closedTheaters()).isEqualTo(30);
        assertThat(reload(existing.get(0)).isClosed()).isTrue();
    }

    @Test
    void 숨긴_극장의_예매_링크는_보존되고_보고서에_나온다() {
        Theater gone = saveTheater("사라짐");
        gone.updateBookingUrl("https://example.com/book");
        em.flush();
        serve(List.of(row("다른곳")), 1);

        TheaterSeedReport report = seedService.seed(false, false);

        assertThat(report.closedWithBookingUrl()).hasSize(1);
        assertThat(report.closedWithBookingUrl().get(0).bookingUrl()).isEqualTo("https://example.com/book");
        assertThat(reload(gone).getBookingUrl()).isEqualTo("https://example.com/book");
    }

    @Test
    void 시드를_다시_돌려도_booking_url은_덮어쓰지_않는다() {
        Theater theater = saveTheater("유지");
        theater.updateBookingUrl("https://example.com/book");
        em.flush();
        // 원천 이름이 바뀌어도(갱신 대상) 예매 링크는 그대로여야 한다
        serve(List.of(new TheaterSourceRow(GRP, "M-유지", "CGV 새이름", "01", "영화관", road("유지"), "", "", "")), 1);

        seedService.seed(false, false);
        seedService.seed(false, false);

        Theater after = reload(theater);
        assertThat(after.getName()).isEqualTo("CGV 새이름");
        assertThat(after.getBookingUrl()).isEqualTo("https://example.com/book");
    }

    @Test
    void 기존_극장은_저장된_좌표를_쓰고_카카오를_부르지_않는다() {
        Theater existing = saveTheater("기존");
        em.flush();
        serve(List.of(row("기존")), 1);

        TheaterSeedReport report = seedService.seed(false, false);

        assertThat(report.geocodeReused()).isEqualTo(1);
        assertThat(report.geocodeCalled()).isZero();
        verify(kakao, never()).searchAddress(anyString());
        Theater after = reload(existing);
        assertThat(after.getLatitude()).isEqualByComparingTo("37.5000000");   // 카카오가 줄 37.1이 아님
    }

    @Test
    void 신규_극장만_카카오를_부른다() {
        saveTheater("기존");
        em.flush();
        serve(List.of(row("기존"), row("신규")), 2);

        TheaterSeedReport report = seedService.seed(false, false);

        assertThat(report.geocodeReused()).isEqualTo(1);
        assertThat(report.geocodeCalled()).isEqualTo(1);
        assertThat(report.newTheaters()).isEqualTo(1);
    }
}
