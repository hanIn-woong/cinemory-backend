package com.project.cinemory.domain.movie.service;

import com.project.cinemory.domain.collection.entity.Collection;
import com.project.cinemory.domain.collection.entity.CollectionMovie;
import com.project.cinemory.domain.collection.repository.CollectionMovieRepository;
import com.project.cinemory.domain.collection.repository.CollectionRepository;
import com.project.cinemory.domain.country.entity.Country;
import com.project.cinemory.domain.country.repository.CountryRepository;
import com.project.cinemory.domain.genre.entity.Genre;
import com.project.cinemory.domain.genre.repository.GenreRepository;
import com.project.cinemory.domain.movie.dto.AdminMovieDetailResponse;
import com.project.cinemory.domain.movie.dto.AdminMovieSort;
import com.project.cinemory.domain.movie.dto.AdminMovieSummaryResponse;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.entity.MovieCountry;
import com.project.cinemory.domain.movie.entity.MovieDirector;
import com.project.cinemory.domain.movie.entity.MovieGenre;
import com.project.cinemory.domain.movie.repository.MovieCountryRepository;
import com.project.cinemory.domain.movie.repository.MovieDirectorRepository;
import com.project.cinemory.domain.movie.repository.MovieGenreRepository;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.person.entity.Person;
import com.project.cinemory.domain.person.repository.PersonRepository;
import com.project.cinemory.domain.review.entity.Review;
import com.project.cinemory.domain.review.repository.ReviewRepository;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.domain.watch.dto.WatchRecordCreateRequest;
import com.project.cinemory.domain.watch.service.WatchRecordService;
import com.project.cinemory.domain.wish.entity.WishMovie;
import com.project.cinemory.domain.wish.repository.WishMovieRepository;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 관리자 영화 조회(admin-spec 3-4·3-5, 테스트 7절). 제목은 고유 토큰({@code zq9})으로 실 데이터와 분리한다. */
@SpringBootTest
@Transactional
class AdminMovieQueryServiceTest {

    @Autowired
    private AdminMovieQueryService service;
    @Autowired
    private MovieRepository movieRepository;
    @Autowired
    private GenreRepository genreRepository;
    @Autowired
    private CountryRepository countryRepository;
    @Autowired
    private PersonRepository personRepository;
    @Autowired
    private MovieGenreRepository movieGenreRepository;
    @Autowired
    private MovieCountryRepository movieCountryRepository;
    @Autowired
    private MovieDirectorRepository movieDirectorRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WatchRecordService watchRecordService;
    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private WishMovieRepository wishMovieRepository;
    @Autowired
    private CollectionRepository collectionRepository;
    @Autowired
    private CollectionMovieRepository collectionMovieRepository;

    private Movie older;    // 2010, 원제 있음
    private Movie newer;    // 2020
    private Movie noDate;   // 개봉일 없음, 원제 없음

    @BeforeEach
    void setUp() {
        older = movieRepository.save(Movie.builder().tmdbId(98_201L).title("zq9 오래된 영화")
                .originalTitle("Zq9 Old Movie").releaseDate(LocalDate.of(2010, 1, 1))
                .voteAverage(BigDecimal.valueOf(7.5)).voteCount(100).build());
        newer = movieRepository.save(Movie.builder().tmdbId(98_202L).title("zq9 새 영화")
                .releaseDate(LocalDate.of(2020, 1, 1)).build());
        noDate = movieRepository.save(Movie.builder().tmdbId(98_203L).title("zq9 날짜 없는 영화").build());
    }

    private List<Long> ids(String q, Long tmdbId, AdminMovieSort sort) {
        return service.getMovies(q, tmdbId, sort, 0, 50).content().stream()
                .map(AdminMovieSummaryResponse::movieId).toList();
    }

    @Test
    void q는_제목과_원제_양쪽에_걸린다() {
        assertThat(ids("zq9", null, AdminMovieSort.ID_DESC))
                .containsExactly(noDate.getId(), newer.getId(), older.getId());
        // 원제로만 걸리는 검색어
        assertThat(ids("old movie", null, AdminMovieSort.ID_DESC)).containsExactly(older.getId());
        // 제목으로만 걸리는 검색어
        assertThat(ids("새 영화", null, AdminMovieSort.ID_DESC)).containsExactly(newer.getId());
    }

    @Test
    void tmdbId는_정확히_일치하고_q와_함께_오면_AND다() {
        assertThat(ids(null, 98_202L, AdminMovieSort.ID_DESC)).containsExactly(newer.getId());
        assertThat(ids("zq9", 98_202L, AdminMovieSort.ID_DESC)).containsExactly(newer.getId());
        assertThat(ids("오래된", 98_202L, AdminMovieSort.ID_DESC)).isEmpty();
        assertThat(ids("zq9", 982L, AdminMovieSort.ID_DESC)).isEmpty();
    }

    @Test
    void 정렬_enum별_순서_개봉일_NULL은_뒤다() {
        assertThat(ids("zq9", null, AdminMovieSort.ID_DESC))
                .containsExactly(noDate.getId(), newer.getId(), older.getId());
        assertThat(ids("zq9", null, AdminMovieSort.RELEASE_DATE_DESC))
                .containsExactly(newer.getId(), older.getId(), noDate.getId());
    }

    @Test
    void 목록_행은_TMDB_경로와_평점을_그대로_싣는다() {
        AdminMovieSummaryResponse row = service.getMovies("zq9 오래된", null, AdminMovieSort.ID_DESC, 0, 20)
                .content().get(0);

        assertThat(row.tmdbId()).isEqualTo(98_201L);
        assertThat(row.originalTitle()).isEqualTo("Zq9 Old Movie");
        assertThat(row.voteAverage()).isEqualByComparingTo("7.5");
        assertThat(row.voteCount()).isEqualTo(100);
    }

    @Test
    void 상세는_장르_국가_감독_이름과_사용_지표를_싣는다() {
        Genre genre = genreRepository.save(Genre.of(990_001, "관리자테스트장르"));
        Country country = countryRepository.save(Country.of("ZZ", "관리자테스트국"));
        Person director = personRepository.save(Person.of(990_001L, "관리자테스트감독", null));
        movieGenreRepository.save(MovieGenre.of(older, genre, BigDecimal.ONE));
        movieCountryRepository.save(MovieCountry.of(older, country, BigDecimal.ONE));
        movieDirectorRepository.save(MovieDirector.of(older, director));

        User user = userRepository.save(User.createLocal("admin-movie-test@test.com", "{noop}pw", "영화관리자테스트"));
        watchRecordService.addWatchRecord(user.getId(),
                new WatchRecordCreateRequest(older.getId(), LocalDate.of(2026, 1, 1), null, null, null, null, null));
        watchRecordService.addWatchRecord(user.getId(),
                new WatchRecordCreateRequest(older.getId(), LocalDate.of(2026, 2, 1), null, null, null, null, null));
        reviewRepository.save(Review.of(user, older, "리뷰"));
        wishMovieRepository.save(WishMovie.of(user, older));
        Collection collection = collectionRepository.save(
                Collection.builder().user(user).name("c").position(0).build());
        collectionMovieRepository.save(CollectionMovie.of(collection, older, 0));

        AdminMovieDetailResponse detail = service.getMovie(older.getId());

        assertThat(detail.genres()).containsExactly("관리자테스트장르");
        assertThat(detail.countries()).containsExactly("관리자테스트국");
        assertThat(detail.directors()).containsExactly("관리자테스트감독");
        assertThat(detail.castCount()).isZero();
        assertThat(detail.usage().watchRecordCount()).isEqualTo(2);
        assertThat(detail.usage().reviewCount()).isEqualTo(1);
        assertThat(detail.usage().wishCount()).isEqualTo(1);
        assertThat(detail.usage().collectionCount()).isEqualTo(1);
    }

    @Test
    void 없는_영화는_MOVIE_NOT_FOUND다() {
        assertThatThrownBy(() -> service.getMovie(Long.MAX_VALUE))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MOVIE_NOT_FOUND));
    }
}
