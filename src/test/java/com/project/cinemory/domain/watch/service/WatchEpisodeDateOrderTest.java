package com.project.cinemory.domain.watch.service;

import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.watch.dto.WatchRecordCreateRequest;
import com.project.cinemory.domain.watch.dto.WatchRecordResponse;
import com.project.cinemory.domain.watch.dto.WatchRecordUpdateRequest;
import com.project.cinemory.domain.watch.entity.WatchRecord;
import com.project.cinemory.domain.watch.entity.WatchType;
import com.project.cinemory.domain.watch.repository.WatchRecordRepository;
import com.project.cinemory.domain.wish.entity.WishMovie;
import com.project.cinemory.domain.wish.repository.WishMovieRepository;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 회차 관람일 순서 검증 (service-layer-spec 4-3-A, 테스트 ①~⑥ + 경계). 실 DB({@code cinemory_test})를 쓰고 테스트마다 롤백한다.
 * 회차 번호는 id 순서("만든 순서 = 본 순서")라, 생성 순서가 곧 회차 순서다.
 */
@SpringBootTest
@Transactional
class WatchEpisodeDateOrderTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @Autowired
    private WatchRecordService service;
    @Autowired
    private WatchRecordRepository watchRecordRepository;
    @Autowired
    private WishMovieRepository wishMovieRepository;
    @Autowired
    private EntityManager em;

    private User user() {
        User user = User.createOAuth("episode" + SEQ.incrementAndGet() + "@episode.test", "회차테스트", null);
        em.persist(user);
        return user;
    }

    private Movie movie() {
        Movie movie = Movie.builder().tmdbId(700_000_000L + SEQ.incrementAndGet() % 90_000_000L).title("회차 테스트 영화").build();
        em.persist(movie);
        return movie;
    }

    private WatchRecordResponse add(User user, Movie movie, LocalDate date) {
        return service.addWatchRecord(user.getId(),
                new WatchRecordCreateRequest(movie.getId(), date, WatchType.THEATER, null, null, null, null));
    }

    private WatchRecordResponse update(User user, Long recordId, LocalDate date, BigDecimal rating) {
        return service.updateWatchRecord(user.getId(), recordId,
                new WatchRecordUpdateRequest(date, WatchType.THEATER, null, null, rating, null));
    }

    private static void assertOutOfOrder(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WATCH_DATE_OUT_OF_ORDER));
    }

    // ---- ① ~ ③ 생성 -----------------------------------------------------------------------------

    @Test
    void T1_1회차가_10월31일이면_10월30일_회차_생성은_거부된다() {
        User user = user();
        Movie movie = movie();
        add(user, movie, LocalDate.of(2026, 10, 31));

        assertOutOfOrder(() -> add(user, movie, LocalDate.of(2026, 10, 30)));
        assertThat(watchRecordRepository.findByUserIdAndMovieIdOrderByIdAsc(user.getId(), movie.getId())).hasSize(1);
    }

    @Test
    void T2_같은_날짜_회차_생성은_허용된다() {
        User user = user();
        Movie movie = movie();
        add(user, movie, LocalDate.of(2026, 10, 31));

        add(user, movie, LocalDate.of(2026, 10, 31));

        assertThat(watchRecordRepository.findByUserIdAndMovieIdOrderByIdAsc(user.getId(), movie.getId())).hasSize(2);
    }

    @Test
    void T3_날짜_없는_회차만_있으면_어떤_날짜로도_생성된다() {
        User user = user();
        Movie movie = movie();
        add(user, movie, null);

        add(user, movie, LocalDate.of(1999, 1, 1));

        assertThat(watchRecordRepository.findByUserIdAndMovieIdOrderByIdAsc(user.getId(), movie.getId())).hasSize(2);
    }

    @Test
    void T6_날짜_없이_생성하면_검증을_건너뛴다() {
        User user = user();
        Movie movie = movie();
        add(user, movie, LocalDate.of(2026, 10, 31));

        add(user, movie, null);

        assertThat(watchRecordRepository.findByUserIdAndMovieIdOrderByIdAsc(user.getId(), movie.getId())).hasSize(2);
    }

    @Test
    void 거부된_생성은_부수효과가_없다_찜이_남고_대표도_바뀌지_않는다() {
        User user = user();
        Movie movie = movie();
        WatchRecordResponse first = add(user, movie, LocalDate.of(2026, 10, 31));
        em.persist(WishMovie.of(user, movie)); // 첫 기록이 찜을 지웠으므로 다시 찜한다
        em.flush();

        assertOutOfOrder(() -> add(user, movie, LocalDate.of(2026, 10, 1)));

        assertThat(wishMovieRepository.findByUserIdAndMovieId(user.getId(), movie.getId())).isPresent();
        assertThat(watchRecordRepository.findByUserIdAndMovieIdAndRepresentativeTrue(user.getId(), movie.getId()))
                .get().extracting(WatchRecord::getId).isEqualTo(first.id());
    }

    @Test
    void 다른_영화_다른_사용자의_기록은_판정에_영향을_주지_않는다() {
        User me = user();
        User other = user();
        Movie movie = movie();
        Movie otherMovie = movie();
        add(me, otherMovie, LocalDate.of(2026, 12, 31));
        add(other, movie, LocalDate.of(2026, 12, 31));

        add(me, movie, LocalDate.of(2026, 1, 1)); // 예외 없이 통과
    }

    // ---- ④ ~ ⑤ 수정 -----------------------------------------------------------------------------

    @Test
    void T4_1회차를_2회차_이후_날짜로_수정하면_거부되고_이웃_사이_날짜는_허용된다() {
        User user = user();
        Movie movie = movie();
        WatchRecordResponse first = add(user, movie, LocalDate.of(2026, 10, 1));
        WatchRecordResponse second = add(user, movie, LocalDate.of(2026, 10, 10));
        add(user, movie, LocalDate.of(2026, 10, 20));

        assertOutOfOrder(() -> update(user, first.id(), LocalDate.of(2026, 10, 11), null));
        update(user, first.id(), LocalDate.of(2026, 10, 10), null);   // 다음 회차와 같은 날 — 허용
        update(user, second.id(), LocalDate.of(2026, 10, 15), null);  // 앞뒤 사이 — 허용
        assertOutOfOrder(() -> update(user, second.id(), LocalDate.of(2026, 9, 30), null)); // 앞 회차(10/10)보다 이전
    }

    @Test
    void 수정에서_날짜_없는_이웃은_건너뛰고_가장_가까운_날짜_있는_회차와_비교한다() {
        User user = user();
        Movie movie = movie();
        add(user, movie, LocalDate.of(2026, 10, 1));
        add(user, movie, null);
        WatchRecordResponse third = add(user, movie, LocalDate.of(2026, 10, 20));

        assertOutOfOrder(() -> update(user, third.id(), LocalDate.of(2026, 9, 30), null)); // 건너뛰고 1회차(10/1)와 비교
        update(user, third.id(), LocalDate.of(2026, 10, 1), null);
    }

    @Test
    void 수정에서_날짜를_지우는_것은_항상_허용된다() {
        User user = user();
        Movie movie = movie();
        add(user, movie, LocalDate.of(2026, 10, 1));
        WatchRecordResponse second = add(user, movie, LocalDate.of(2026, 10, 20));

        WatchRecordResponse updated = update(user, second.id(), null, null);

        assertThat(updated.watchDate()).isNull();
    }

    @Test
    void T5_이미_어긋난_회차의_별점만_수정하면_성공한다() {
        User user = user();
        Movie movie = movie();
        // 검증 도입 전 데이터를 직접 만든다 — 1회차 10/31, 2회차 10/1(어긋남)
        WatchRecord first = WatchRecord.builder().user(user).movie(movie).watchDate(LocalDate.of(2026, 10, 31))
                .watchType(WatchType.THEATER).build();
        em.persist(first);
        WatchRecord second = WatchRecord.builder().user(user).movie(movie).watchDate(LocalDate.of(2026, 10, 1))
                .watchType(WatchType.THEATER).build();
        em.persist(second);
        em.flush();

        WatchRecordResponse updated = update(user, second.getId(), LocalDate.of(2026, 10, 1), new BigDecimal("8.0"));

        assertThat(updated.rating()).isEqualByComparingTo("8.0");
        // 날짜를 바꾸려 하면 그때는 판정한다
        assertOutOfOrder(() -> update(user, second.getId(), LocalDate.of(2026, 10, 2), null));
    }
}
