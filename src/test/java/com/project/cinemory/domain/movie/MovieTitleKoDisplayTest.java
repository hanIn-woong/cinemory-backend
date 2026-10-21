package com.project.cinemory.domain.movie;

import com.project.cinemory.domain.boxoffice.service.BoxOfficeSyncService;
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
import com.project.cinemory.domain.movie.dto.MovieSearchResponse;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.movie.service.MovieQueryService;
import com.project.cinemory.domain.movie.service.MovieSearchService;
import com.project.cinemory.domain.movie.service.MovieSeedService;
import com.project.cinemory.domain.user.entity.PrivacySetting;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.domain.watch.dto.RecordRatingFilter;
import com.project.cinemory.domain.watch.dto.RecordSort;
import com.project.cinemory.domain.watch.dto.UserMovieListItemResponse;
import com.project.cinemory.domain.watch.dto.WatchRecordCreateRequest;
import com.project.cinemory.domain.watch.service.WatchRecordService;
import com.project.cinemory.domain.wish.dto.WishListItemResponse;
import com.project.cinemory.domain.wish.dto.WishSort;
import com.project.cinemory.domain.wish.service.WishMovieService;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.tmdb.TmdbClient;
import com.project.cinemory.global.infra.tmdb.dto.TmdbGenreListResponse;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 영화 한글 제목의 표시·정렬·검색·매칭·재동기화 보존(admin-spec 12-B-2, BD-10~BD-13, 테스트 12-B-5) — 실 DB.
 * 세 영화로 표시 제목 순서와 원본 제목 순서가 갈리게 만든다(원본 순: Apple &lt; Zebra &lt; 나나 / 표시 순: 가나다 &lt; 나나 &lt; 다라마).
 */
@SpringBootTest
@Transactional
class MovieTitleKoDisplayTest {

    @Autowired
    private MovieRepository movieRepository;
    @Autowired
    private MovieQueryService movieQueryService;
    @Autowired
    private MovieSearchService movieSearchService;
    @Autowired
    private MovieSeedService movieSeedService;
    @Autowired
    private WatchRecordService watchRecordService;
    @Autowired
    private WishMovieService wishMovieService;
    @Autowired
    private CollectionService collectionService;
    @Autowired
    private CollectionRepository collectionRepository;
    @Autowired
    private CollectionMovieRepository collectionMovieRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private GenreRepository genreRepository;
    @Autowired
    private CountryRepository countryRepository;

    @MockitoBean
    private TmdbClient tmdbClient;
    @MockitoBean
    private BoxOfficeSyncService boxOfficeSyncService;   // 이 클래스는 저장소 메서드만 본다 — 서비스 빈은 대역

    private User user;
    private Movie zebra;   // title "Zebra"  + title_ko "가나다"
    private Movie noKo;    // title "나나"   (한글 제목 없음 → 원본이 한글이라 그대로)
    private Movie apple;   // title "Apple"  + title_ko "다라마"

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.createLocal("title-ko-test@test.com", "{noop}pw", "제목테스트"));
        user.changePrivacySetting(PrivacySetting.PUBLIC);
        zebra = movieRepository.save(Movie.builder().tmdbId(98_701L).title("Zebra").build());
        noKo = movieRepository.save(Movie.builder().tmdbId(98_702L).title("나나").build());
        apple = movieRepository.save(Movie.builder().tmdbId(98_703L).title("Apple").build());
        zebra.changeTitleKo("가나다");
        apple.changeTitleKo("다라마");
        movieRepository.flush();
    }

    private void record(Movie movie) {
        watchRecordService.addWatchRecord(user.getId(),
                new WatchRecordCreateRequest(movie.getId(), null, null, null, null, null, null));
    }

    @Test
    void 영화_상세와_목록은_한글_제목을_내보내고_없으면_원본이다() {
        assertThat(movieQueryService.getMovieDetail(zebra.getId()).title()).isEqualTo("가나다");
        assertThat(movieQueryService.getMovieDetail(noKo.getId()).title()).isEqualTo("나나");

        var list = movieQueryService.getMovieList(PageRequest.of(0, 100)).getContent();
        assertThat(list).filteredOn(m -> m.id().equals(apple.getId())).extracting(m -> m.title()).containsExactly("다라마");
        assertThat(list).filteredOn(m -> m.id().equals(noKo.getId())).extracting(m -> m.title()).containsExactly("나나");
    }

    @Test
    void 내_영화와_찜과_컬렉션_목록도_표시_제목이다() {
        record(zebra);
        wishMovieService.toggleWish(user.getId(), apple.getId());
        Collection collection = collectionRepository.save(Collection.builder().user(user).name("c").position(0).build());
        collectionMovieRepository.save(CollectionMovie.of(collection, zebra, 0));

        List<UserMovieListItemResponse> mine = watchRecordService.getUserMovieList(user.getId(), user.getId(),
                PageRequest.of(0, 20), RecordSort.RECENT, RecordRatingFilter.NONE).getContent();
        assertThat(mine).extracting(UserMovieListItemResponse::title).containsExactly("가나다");

        List<WishListItemResponse> wishes = wishMovieService.getUserWishList(user.getId(), user.getId(),
                PageRequest.of(0, 20), WishSort.RECENT).getContent();
        assertThat(wishes).extracting(WishListItemResponse::title).containsExactly("다라마");

        List<CollectionMovieListItemResponse> inCollection = collectionService
                .getCollectionMovies(user.getId(), collection.getId(), PageRequest.of(0, 20)).getContent();
        assertThat(inCollection).extracting(CollectionMovieListItemResponse::title).containsExactly("가나다");
    }

    @Test
    void 제목순_정렬은_표시_제목_기준이다_오름과_내림() {
        record(zebra);
        record(noKo);
        record(apple);
        wishMovieService.toggleWish(user.getId(), zebra.getId());
        wishMovieService.toggleWish(user.getId(), noKo.getId());
        wishMovieService.toggleWish(user.getId(), apple.getId());

        // 표시 순: 가나다(zebra) < 나나(noKo) < 다라마(apple). 원본 제목 순이었다면 Apple < Zebra < 나나
        assertThat(records(RecordSort.TITLE)).containsExactly(zebra.getId(), noKo.getId(), apple.getId());
        assertThat(records(RecordSort.TITLE_DESC)).containsExactly(apple.getId(), noKo.getId(), zebra.getId());
        assertThat(wishes(WishSort.TITLE)).containsExactly(zebra.getId(), noKo.getId(), apple.getId());
        assertThat(wishes(WishSort.TITLE_DESC)).containsExactly(apple.getId(), noKo.getId(), zebra.getId());
    }

    private List<Long> records(RecordSort sort) {
        return watchRecordService.getUserMovieList(user.getId(), user.getId(), PageRequest.of(0, 20), sort,
                RecordRatingFilter.NONE).map(UserMovieListItemResponse::movieId).getContent();
    }

    private List<Long> wishes(WishSort sort) {
        return wishMovieService.getUserWishList(user.getId(), user.getId(), PageRequest.of(0, 20), sort)
                .map(WishListItemResponse::movieId).getContent();
    }

    @Test
    void 앱_영화_검색_registered는_한글_제목으로도_걸린다() {
        // TMDB 제안은 외부라 실패로 대체 — registered만 본다
        when(tmdbClient.searchMovie(any(), any())).thenThrow(new BusinessException(ErrorCode.TMDB_RATE_LIMITED));

        MovieSearchResponse response = movieSearchService.search("가나다", null, 1);

        assertThat(response.registered().content()).extracting(m -> m.id()).containsExactly(zebra.getId());
        assertThat(response.registered().content().get(0).title()).isEqualTo("가나다");
    }

    @Test
    void 박스오피스_재매칭의_제목_일치는_title과_title_ko_둘_다다() {
        assertThat(movieRepository.findByTitleOrTitleKo("가나다")).extracting(Movie::getId).containsExactly(zebra.getId());
        assertThat(movieRepository.findByTitleOrTitleKo("Zebra")).extracting(Movie::getId).containsExactly(zebra.getId());
        assertThat(movieRepository.findByTitleOrTitleKo("나나")).extracting(Movie::getId).containsExactly(noKo.getId());
        assertThat(movieRepository.findByTitleOrTitleKo("없는 제목")).isEmpty();
    }

    @Test
    void TMDB_재동기화는_title만_덮어쓰고_title_ko는_보존한다() {
        genreRepository.save(Genre.of(990_701, "제목테스트장르"));
        countryRepository.save(Country.of("ZW", "제목테스트국"));
        TmdbMovieDetailResponse detail = new TmdbMovieDetailResponse(98_701L, false, "Zebra (2024)", "Zebra", null,
                "2024-01-01", "줄거리", 100, null, 7.0, 10,
                List.of(new TmdbGenreListResponse.Item(990_701, "제목테스트장르")), null, null, null, null, null, null);
        when(tmdbClient.fetchMovieDetail(98_701L)).thenReturn(detail);

        movieSeedService.resyncOne(zebra.getId());
        movieRepository.flush();

        Movie reloaded = movieRepository.findById(zebra.getId()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("Zebra (2024)");   // 원본은 갱신됐다
        assertThat(reloaded.getTitleKo()).isEqualTo("가나다");          // 한글 제목은 그대로
        assertThat(reloaded.displayTitle()).isEqualTo("가나다");
    }
}
