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
 * 선호 인물 집계 개정(M3a-report-spec 11절, service-layer-spec 4-8-J) 검증 — 애니메이션 제외 · 베이지안 평균 ·
 * 최소 2편 · EXTRA 제외 · 별점 0건 가드 · 동률 규칙. 네이티브 쿼리라 실 DB({@code cinemory_test})로 고정한다.
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
        movieActorRepository.save(MovieActor.builder()
                .movie(movie).person(person).characterName("역").displayOrder(0).roleTier(tier).build());
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
            watch(m, 8);
        }

        assertThat(topActors()).isEmpty();
        assertThat(reportService.getYearlyReport(null, user.getId(), 2026).mostWatchedActor()).isNull();
    }

    @Test
    void 애니메이션_영화의_감독은_감독_목록에_남는다() {
        Person director = person("애니감독");
        for (int i = 0; i < 2; i++) {
            Movie m = movie(true);
            movieDirectorRepository.save(MovieDirector.of(m, director));
            watch(m, 8);
        }

        assertThat(topDirectors()).extracting(PersonRankItemResponse::name).containsExactly("애니감독");
    }

    @Test
    void 편수가_많아도_평균이_낮으면_적게_본_고평가_인물에게_밀린다() {
        Person many = person("다작배우");   // 7점 × 5편 주연
        Person few = person("소수배우");    // 10점 × 2편 주연
        for (int i = 0; i < 5; i++) {
            Movie m = movie(false);
            cast(m, many, RoleTier.LEAD);
            watch(m, 7);
        }
        for (int i = 0; i < 2; i++) {
            Movie m = movie(false);
            cast(m, few, RoleTier.LEAD);
            watch(m, 10);
        }

        // m = (35 + 20) / 7. 소수배우 (10 + m)/2 ≈ 8.93 > 다작배우 (17.5 + m)/3.5 ≈ 7.25
        assertThat(topActors()).extracting(PersonRankItemResponse::name).containsExactly("소수배우", "다작배우");
        double m = 55.0 / 7;
        assertThat(topActors().get(0).score().doubleValue()).isCloseTo((10 + m) / 2, within(0.001));
        assertThat(topActors().get(1).score().doubleValue()).isCloseTo((17.5 + m) / 3.5, within(0.001));
    }

    @Test
    void 한_편만_본_인물은_목록에_없다() {
        Person once = person("한편배우");
        Person twice = person("두편배우");
        Movie first = movie(false);
        Movie second = movie(false);
        cast(first, once, RoleTier.LEAD);
        cast(first, twice, RoleTier.LEAD);
        cast(second, twice, RoleTier.LEAD);
        watch(first, 10);
        watch(second, 6);

        assertThat(topActors()).extracting(PersonRankItemResponse::name).containsExactly("두편배우");
    }

    @Test
    void EXTRA로만_나온_배우는_목록에_없다() {
        Person extra = person("엑스트라");
        Person lead = person("주연");
        for (int i = 0; i < 2; i++) {
            Movie m = movie(false);
            cast(m, extra, RoleTier.EXTRA);
            cast(m, lead, RoleTier.LEAD);
            watch(m, 8);
        }

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
    void 점수가_같으면_증거량이_큰_쪽이_위다() {
        // 전부 8점이라 m = 8 → 모든 인물의 score가 정확히 8. 동률을 Σw(LEAD 1.0 > SUPPORTING 0.8)로 가른다.
        Person supporting = person("조연");
        Person lead = person("주연");
        for (int i = 0; i < 2; i++) {
            Movie m = movie(false);
            cast(m, supporting, RoleTier.SUPPORTING);
            cast(m, lead, RoleTier.LEAD);
            watch(m, 8);
        }

        assertThat(topActors()).extracting(PersonRankItemResponse::name).containsExactly("주연", "조연");
    }
}
