package com.project.cinemory.domain.report.service;

import com.project.cinemory.domain.genre.entity.Genre;
import com.project.cinemory.domain.genre.repository.GenreRepository;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.entity.MovieActor;
import com.project.cinemory.domain.movie.entity.MovieDirector;
import com.project.cinemory.domain.movie.entity.MovieGenre;
import com.project.cinemory.domain.movie.entity.RoleTier;
import com.project.cinemory.domain.movie.repository.MovieActorRepository;
import com.project.cinemory.domain.movie.repository.MovieDirectorRepository;
import com.project.cinemory.domain.movie.repository.MovieGenreRepository;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.person.entity.Person;
import com.project.cinemory.domain.person.repository.PersonRepository;
import com.project.cinemory.domain.report.dto.PersonRankItemResponse;
import com.project.cinemory.domain.user.entity.PrivacySetting;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.domain.watch.dto.WatchRecordCreateRequest;
import com.project.cinemory.domain.watch.service.WatchRecordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 선호 인물 집계 검증(M3a-report-spec 11절, service-layer-spec 4-8-J·4-8-K) — 애니메이션 제외 · 평균 중심 합 ·
 * score > 0 · EXTRA 제외 · 별점 0건 가드 · 동률 규칙 · TOP 10 · 화면용 averageRating. 네이티브 쿼리라 실 DB({@code cinemory_test})로 고정한다.
 */
@SpringBootTest
@Transactional
class PersonPreferenceScoreTest {

    @Autowired
    private ReportService reportService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MovieRepository movieRepository;
    @Autowired
    private GenreRepository genreRepository;
    @Autowired
    private PersonRepository personRepository;
    @Autowired
    private MovieGenreRepository movieGenreRepository;
    @Autowired
    private MovieActorRepository movieActorRepository;
    @Autowired
    private MovieDirectorRepository movieDirectorRepository;
    @Autowired
    private WatchRecordService watchRecordService;

    private User user;
    private Genre animation;
    private long nextTmdbId = 95_000L;
    private long nextPersonTmdbId = 95_500L;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.createLocal("person-score@test.com", "{noop}password", "인물점수"));
        user.changePrivacySetting(PrivacySetting.PUBLIC);
        // 시드로 이미 있을 수 있다(tmdb_genre_id는 UNIQUE) — 있으면 재사용한다.
        animation = genreRepository.findByTmdbGenreIdIn(List.of(16)).stream().findFirst()
                .orElseGet(() -> genreRepository.save(Genre.of(16, "애니메이션")));
    }

    private Movie movie(boolean isAnimation) {
        long tmdbId = nextTmdbId++;
        Movie movie = movieRepository.save(Movie.builder().tmdbId(tmdbId).title("인물 영화 " + tmdbId).build());
        if (isAnimation) {
            movieGenreRepository.save(MovieGenre.of(movie, animation, BigDecimal.ONE));
        }
        return movie;
    }

    private Person person(String name) {
        return personRepository.save(Person.of(nextPersonTmdbId++, name, null));
    }

    private void cast(Movie movie, Person person, RoleTier tier) {
        cast(movie, person, tier, 0);
    }

    private void cast(Movie movie, Person person, RoleTier tier, int displayOrder) {
        movieActorRepository.save(MovieActor.builder()
                .movie(movie).person(person).characterName("역").displayOrder(displayOrder).roleTier(tier).build());
    }

    private void watch(Movie movie, Integer rating) {
        watchRecordService.addWatchRecord(user.getId(), new WatchRecordCreateRequest(movie.getId(),
                LocalDate.of(2026, 3, 1), null, null, null, rating == null ? null : BigDecimal.valueOf(rating), null));
    }

    private List<PersonRankItemResponse> topActors() {
        return reportService.getStatistics(null, user.getId()).topActors();
    }

    private List<PersonRankItemResponse> topDirectors() {
        return reportService.getStatistics(null, user.getId()).topDirectors();
    }

    @Test
    void 애니메이션_영화에만_나온_성우는_배우_목록과_연간_최다_배우에서_빠진다() {
        Person voice = person("성우");
        for (int i = 0; i < 2; i++) {
            Movie m = movie(true);
            cast(m, voice, RoleTier.LEAD);
            watch(m, 10);
        }
        watch(movie(false), 4);   // m를 낮춰 점수 자체는 양수 — 빠지는 이유가 애니메이션뿐임을 보인다

        assertThat(topActors()).isEmpty();
        assertThat(reportService.getYearlyReport(null, user.getId(), 2026).mostWatchedActor()).isNull();
    }

    @Test
    void 애니메이션_영화의_감독은_감독_목록에_남는다() {
        Person director = person("애니감독");
        for (int i = 0; i < 2; i++) {
            Movie m = movie(true);
            movieDirectorRepository.save(MovieDirector.of(m, director));
            watch(m, 10);
        }
        watch(movie(false), 4);

        assertThat(topDirectors()).extracting(PersonRankItemResponse::name).containsExactly("애니감독");
    }

    @Test
    void 많이_보고_좋게_본_인물이_위이고_평균_이하로_본_인물은_빠진다() {
        Person a = person("A배우");   // 7점 × 20편
        Person b = person("B배우");   // 9점 × 10편
        Person c = person("C배우");   // 10점 × 2편 (모두 주연)
        for (int i = 0; i < 20; i++) {
            Movie m = movie(false);
            cast(m, a, RoleTier.LEAD);
            watch(m, 7);
        }
        for (int i = 0; i < 10; i++) {
            Movie m = movie(false);
            cast(m, b, RoleTier.LEAD);
            watch(m, 9);
        }
        for (int i = 0; i < 2; i++) {
            Movie m = movie(false);
            cast(m, c, RoleTier.LEAD);
            watch(m, 10);
        }

        // m = 250/32 = 7.8125. A: (7 − m)×0.5×20 < 0 → 제외, B ≈ 5.94, C ≈ 2.19
        assertThat(topActors()).extracting(PersonRankItemResponse::name).containsExactly("B배우", "C배우");
        double m = 250.0 / 32;
        assertThat(topActors().get(0).score().doubleValue()).isCloseTo((9 - m) * 0.5 * 10, within(0.001));
    }

    @Test
    void 평균보다_높게_본_영화_한_편의_인물도_포함되고_평균_이하만_받은_인물은_없다() {
        Person once = person("한편배우");
        Person low = person("저평가배우");
        Movie high = movie(false);
        Movie lowMovie = movie(false);
        cast(high, once, RoleTier.LEAD);
        cast(lowMovie, low, RoleTier.LEAD);
        watch(high, 10);
        watch(lowMovie, 6);

        // m = 8. 한편배우 (10 − 8)×0.5 = 1 > 0 → 최소 편수 조건이 없다. 저평가배우는 음수 → 제외
        assertThat(topActors()).extracting(PersonRankItemResponse::name).containsExactly("한편배우");
    }

    @Test
    void 평균과_같은_별점만_받은_인물은_없다() {
        Person actor = person("평범배우");
        for (int i = 0; i < 2; i++) {
            Movie m = movie(false);
            cast(m, actor, RoleTier.LEAD);
            watch(m, 8);
        }

        assertThat(topActors()).isEmpty();   // m = 8 → score = 0
    }

    @Test
    void EXTRA로만_나온_배우는_목록에_없다() {
        Person extra = person("엑스트라");
        Person lead = person("주연");
        Movie high = movie(false);
        Movie low = movie(false);
        cast(high, extra, RoleTier.EXTRA);
        cast(high, lead, RoleTier.LEAD);
        watch(high, 10);
        watch(low, 4);

        assertThat(topActors()).extracting(PersonRankItemResponse::name).containsExactly("주연");
    }

    @Test
    void 별점_기록이_없으면_빈_목록이고_예외가_없다() {
        Person actor = person("무별점배우");
        for (int i = 0; i < 2; i++) {
            Movie m = movie(false);
            cast(m, actor, RoleTier.LEAD);
            movieDirectorRepository.save(MovieDirector.of(m, actor));
            watch(m, null);
        }

        assertThat(topActors()).isEmpty();
        assertThat(topDirectors()).isEmpty();
    }

    @Test
    void 점수가_같으면_출연_순번이_앞선_배우가_위다() {
        // 같은 두 영화에 같은 등급으로 나온 공동 출연진 — score가 정확히 같다.
        Person later = person("뒤순번");
        Person earlier = person("앞순번");
        Movie first = movie(false);
        Movie second = movie(false);
        Movie low = movie(false);
        for (Movie m : List.of(first, second)) {
            cast(m, later, RoleTier.LEAD, 3);
            cast(m, earlier, RoleTier.LEAD, 1);
            watch(m, 10);
        }
        watch(low, 4);

        assertThat(topActors()).extracting(PersonRankItemResponse::name).containsExactly("앞순번", "뒤순번");
    }

    @Test
    void 인물이_열한_명_이상이면_정확히_열_명이다() {
        Movie high = movie(false);
        Movie low = movie(false);
        for (int i = 0; i < 11; i++) {
            cast(high, person("배우" + i), RoleTier.LEAD);
        }
        watch(high, 10);
        watch(low, 4);

        assertThat(topActors()).hasSize(10);
    }

    @Test
    void averageRating은_등급_가중_평균_별점이다() {
        Person actor = person("가중배우");
        Movie lead = movie(false);
        Movie minor = movie(false);
        cast(lead, actor, RoleTier.LEAD);
        cast(minor, actor, RoleTier.MINOR);
        watch(lead, 10);
        watch(minor, 6);

        // m = 8. score = (10−8)×0.5 + (6−8)×0.1 = 0.8 > 0. averageRating = (10×0.5 + 6×0.1) / 0.6
        assertThat(topActors()).hasSize(1);
        assertThat(topActors().get(0).averageRating().doubleValue()).isCloseTo(5.6 / 0.6, within(0.01));
        assertThat(topActors().get(0).count()).isEqualTo(2L);
    }
}
