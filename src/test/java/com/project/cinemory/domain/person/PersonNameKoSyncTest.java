package com.project.cinemory.domain.person;

import com.project.cinemory.domain.collection.dto.CollectionMovieListItemResponse;
import com.project.cinemory.domain.collection.entity.Collection;
import com.project.cinemory.domain.collection.entity.CollectionMovie;
import com.project.cinemory.domain.collection.repository.CollectionMovieRepository;
import com.project.cinemory.domain.collection.repository.CollectionRepository;
import com.project.cinemory.domain.collection.service.CollectionService;
import com.project.cinemory.domain.country.entity.Country;
import com.project.cinemory.domain.country.repository.CountryRepository;
import com.project.cinemory.domain.genre.entity.Genre;
import com.project.cinemory.domain.genre.repository.GenreRepository;
import com.project.cinemory.domain.movie.dto.MovieDetailResponse;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.entity.MovieActor;
import com.project.cinemory.domain.movie.entity.MovieDirector;
import com.project.cinemory.domain.movie.entity.RoleTier;
import com.project.cinemory.domain.movie.repository.MovieActorRepository;
import com.project.cinemory.domain.movie.repository.MovieDirectorRepository;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.movie.service.AdminMovieQueryService;
import com.project.cinemory.domain.movie.service.MovieQueryService;
import com.project.cinemory.domain.movie.service.MovieSeedService;
import com.project.cinemory.domain.person.entity.Person;
import com.project.cinemory.domain.person.repository.PersonRepository;
import com.project.cinemory.domain.user.entity.PrivacySetting;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.global.infra.tmdb.TmdbClient;
import com.project.cinemory.global.infra.tmdb.dto.TmdbCast;
import com.project.cinemory.global.infra.tmdb.dto.TmdbCredits;
import com.project.cinemory.global.infra.tmdb.dto.TmdbCrew;
import com.project.cinemory.global.infra.tmdb.dto.TmdbMovieDetailResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 인물 한글명의 표시와 재동기화 보존(admin-spec 12-2·BD-7, 테스트 12-5) — 실 DB 한 번씩.
 * ① TMDB 재동기화가 {@code name}만 덮어쓰고 {@code name_ko}는 보존한다(BD-7의 핵심).
 * ② 영화 상세·컬렉션 영화 목록·관리자 영화 상세가 한글명을 내보내고, 한글명이 없으면 원본으로 폴백한다.
 */
@SpringBootTest
@Transactional
class PersonNameKoSyncTest {

    private static final long TMDB_MOVIE_ID = 98_501L;
    private static final long TMDB_ACTOR_ID = 98_511L;
    private static final long TMDB_DIRECTOR_ID = 98_512L;

    @Autowired
    private MovieSeedService movieSeedService;
    @Autowired
    private MovieQueryService movieQueryService;
    @Autowired
    private AdminMovieQueryService adminMovieQueryService;
    @Autowired
    private CollectionService collectionService;
    @Autowired
    private MovieRepository movieRepository;
    @Autowired
    private PersonRepository personRepository;
    @Autowired
    private MovieActorRepository movieActorRepository;
    @Autowired
    private MovieDirectorRepository movieDirectorRepository;
    @Autowired
    private GenreRepository genreRepository;
    @Autowired
    private CountryRepository countryRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CollectionRepository collectionRepository;
    @Autowired
    private CollectionMovieRepository collectionMovieRepository;

    @MockitoBean
    private TmdbClient tmdbClient;

    private Movie movie;
    private Person actor;      // 한글명 있음
    private Person director;   // 한글명 없음 → 원본 폴백

    @BeforeEach
    void setUp() {
        genreRepository.save(Genre.of(990_501, "한글명테스트장르"));
        countryRepository.save(Country.of("ZX", "한글명테스트국"));
        movie = movieRepository.save(Movie.builder().tmdbId(TMDB_MOVIE_ID).title("한글명 테스트").build());
        actor = personRepository.save(Person.of(TMDB_ACTOR_ID, "Tom Hanks", "/actor.jpg"));
        director = personRepository.save(Person.of(TMDB_DIRECTOR_ID, "Robert Zemeckis", "/director.jpg"));
        actor.changeNameKo("톰 행크스");
        movieActorRepository.save(MovieActor.builder().movie(movie).person(actor).characterName("포레스트")
                .displayOrder(0).roleTier(RoleTier.LEAD).build());
        movieDirectorRepository.save(MovieDirector.of(movie, director));
    }

    @Test
    void TMDB_재동기화는_name만_덮어쓰고_name_ko는_보존한다() {
        TmdbMovieDetailResponse detail = new TmdbMovieDetailResponse(TMDB_MOVIE_ID, false, "한글명 테스트", "Test", null,
                "2024-01-01", "줄거리", 100, null, 7.0, 10,
                List.of(new com.project.cinemory.global.infra.tmdb.dto.TmdbGenreListResponse.Item(990_501, "한글명테스트장르")),
                null, null,
                new TmdbCredits(
                        List.of(new TmdbCast(TMDB_ACTOR_ID, "Thomas Jeffrey Hanks", null, "/actor-new.jpg", "포레스트", 0)),
                        List.of(new TmdbCrew(TMDB_DIRECTOR_ID, "Robert L. Zemeckis", null, "/director.jpg", "Director"))),
                null, null, null);
        when(tmdbClient.fetchMovieDetail(TMDB_MOVIE_ID)).thenReturn(detail);

        movieSeedService.resyncOne(movie.getId());
        personRepository.flush();

        Person reloadedActor = personRepository.findByTmdbPersonIdIn(List.of(TMDB_ACTOR_ID)).get(0);
        assertThat(reloadedActor.getName()).isEqualTo("Thomas Jeffrey Hanks");      // 원본은 갱신됐다
        assertThat(reloadedActor.getProfilePath()).isEqualTo("/actor-new.jpg");
        assertThat(reloadedActor.getNameKo()).isEqualTo("톰 행크스");                // 한글명은 그대로
        assertThat(reloadedActor.displayName()).isEqualTo("톰 행크스");

        Person reloadedDirector = personRepository.findByTmdbPersonIdIn(List.of(TMDB_DIRECTOR_ID)).get(0);
        assertThat(reloadedDirector.getName()).isEqualTo("Robert L. Zemeckis");
        assertThat(reloadedDirector.getNameKo()).isNull();
    }

    @Test
    void 영화_상세는_한글명이_있으면_그것_없으면_원본을_내보낸다() {
        MovieDetailResponse detail = movieQueryService.getMovieDetail(movie.getId());

        assertThat(detail.actors()).extracting(a -> a.name()).containsExactly("톰 행크스");
        assertThat(detail.directors()).extracting(d -> d.name()).containsExactly("Robert Zemeckis");
    }

    @Test
    void 컬렉션_영화_목록의_감독_이름도_표시_이름이다() {
        User user = userRepository.save(User.createLocal("namenko-test@test.com", "{noop}pw", "한글명테스트"));
        user.changePrivacySetting(PrivacySetting.PUBLIC);
        Collection collection = collectionRepository.save(Collection.builder().user(user).name("c").position(0).build());
        collectionMovieRepository.save(CollectionMovie.of(collection, movie, 0));
        director.changeNameKo("로버트 저메키스");

        List<CollectionMovieListItemResponse> items = collectionService
                .getCollectionMovies(user.getId(), collection.getId(), PageRequest.of(0, 20)).getContent();

        assertThat(items).hasSize(1);
        assertThat(items.get(0).directorNames()).isEqualTo("로버트 저메키스");
    }

    @Test
    void 관리자_영화_상세의_감독은_표시_이름이고_출연진_조회는_원본과_한글명을_따로_싣는다() {
        director.changeNameKo("로버트 저메키스");

        assertThat(adminMovieQueryService.getMovie(movie.getId()).directors()).containsExactly("로버트 저메키스");

        var credits = adminMovieQueryService.getCredits(movie.getId());
        assertThat(credits.cast()).hasSize(1);
        assertThat(credits.cast().get(0).name()).isEqualTo("Tom Hanks");
        assertThat(credits.cast().get(0).nameKo()).isEqualTo("톰 행크스");
        assertThat(credits.cast().get(0).displayOrder()).isZero();
        assertThat(credits.cast().get(0).roleTier()).isEqualTo(RoleTier.LEAD);
        assertThat(credits.cast().get(0).characterName()).isEqualTo("포레스트");
        assertThat(credits.directors()).hasSize(1);
        assertThat(credits.directors().get(0).name()).isEqualTo("Robert Zemeckis");
        assertThat(credits.directors().get(0).nameKo()).isEqualTo("로버트 저메키스");
        assertThat(credits.directors().get(0).displayOrder()).isNull();
    }
}
