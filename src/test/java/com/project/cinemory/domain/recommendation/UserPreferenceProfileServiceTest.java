package com.project.cinemory.domain.recommendation;

import com.project.cinemory.domain.genre.entity.Genre;
import com.project.cinemory.domain.genre.repository.GenreRepository;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.entity.MovieActor;
import com.project.cinemory.domain.movie.entity.MovieDirector;
import com.project.cinemory.domain.movie.entity.MovieGenre;
import com.project.cinemory.domain.movie.entity.RoleTier;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.person.entity.Person;
import com.project.cinemory.domain.person.repository.PersonRepository;
import com.project.cinemory.domain.recommendation.config.RecommendProperties;
import com.project.cinemory.domain.recommendation.index.MovieFeatureIndex;
import com.project.cinemory.domain.recommendation.repository.MovieFeatureQueryRepository;
import com.project.cinemory.domain.recommendation.repository.UserPreferenceQueryRepository;
import com.project.cinemory.domain.recommendation.service.UserPreferenceProfile;
import com.project.cinemory.domain.recommendation.service.UserPreferenceProfileService;
import com.project.cinemory.domain.recommendation.support.RecommendFixtures;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.domain.watch.entity.WatchRecord;
import com.project.cinemory.domain.watch.repository.WatchRecordRepository;
import com.project.cinemory.domain.wish.entity.WishMovie;
import com.project.cinemory.domain.wish.repository.WishMovieRepository;
import jakarta.persistence.EntityManager;
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
 * 프로필 집계 쿼리(평균 중심 합, 배우 규칙, 찜 제외)를 실 DB(cinemory_test)로 검증한다.
 *
 * <pre>
 * 시청(대표·별점): A=10 (드라마·감독D·주연L·조연S·엑스트라X) / B=4 (공포) / C=10 (애니메이션·배우AN)  → m = 8
 * 찜: W(공포) · A(이미 봄)
 * </pre>
 */
@SpringBootTest
@Transactional
class UserPreferenceProfileServiceTest {

    @Autowired MovieRepository movieRepository;
    @Autowired GenreRepository genreRepository;
    @Autowired PersonRepository personRepository;
    @Autowired UserRepository userRepository;
    @Autowired WatchRecordRepository watchRecordRepository;
    @Autowired WishMovieRepository wishMovieRepository;
    @Autowired UserPreferenceQueryRepository preferenceQueryRepository;
    @Autowired MovieFeatureQueryRepository featureQueryRepository;
    @Autowired EntityManager em;

    private Genre genre(int tmdbId, String name) {
        return genreRepository.findByTmdbGenreIdIn(List.of(tmdbId)).stream().findFirst()
                .orElseGet(() -> genreRepository.save(Genre.of(tmdbId, name)));
    }

    private Movie movie(long tmdbId) {
        return movieRepository.save(Movie.builder().tmdbId(tmdbId).title("m" + tmdbId)
                .releaseDate(LocalDate.of(2020, 1, 1)).runtime(100).build());
    }

    private void watch(User user, Movie movie, double rating, boolean representative) {
        watchRecordRepository.save(WatchRecord.builder().user(user).movie(movie).representative(representative)
                .rating(BigDecimal.valueOf(rating)).watchDate(LocalDate.of(2024, 1, 1)).build());
    }

    private MovieActor actor(Movie movie, Person person, int order, RoleTier tier) {
        return MovieActor.builder().movie(movie).person(person).displayOrder(order).roleTier(tier).build();
    }

    @Test
    void 평균_중심_합으로_프로필이_만들어지고_애니메이션_배우와_엑스트라는_빠진다() {
        Genre drama = genre(18, "드라마");
        Genre horror = genre(27, "공포");
        Genre animation = genre(16, "애니메이션");
        Person director = personRepository.save(Person.of(9_980_001L, "D", null));
        Person lead = personRepository.save(Person.of(9_980_002L, "L", null));
        Person supporting = personRepository.save(Person.of(9_980_003L, "S", null));
        Person extra = personRepository.save(Person.of(9_980_004L, "X", null));
        Person voiceActor = personRepository.save(Person.of(9_980_005L, "AN", null));

        Movie a = movie(98_701L), b = movie(98_702L), c = movie(98_703L), w = movie(98_704L);
        em.persist(MovieGenre.of(a, drama, new BigDecimal("1.000")));
        em.persist(MovieGenre.of(b, horror, new BigDecimal("1.000")));
        em.persist(MovieGenre.of(c, animation, new BigDecimal("1.000")));
        em.persist(MovieGenre.of(w, horror, new BigDecimal("1.000")));
        em.persist(MovieDirector.of(a, director));
        em.persist(actor(a, lead, 0, RoleTier.LEAD));
        em.persist(actor(a, supporting, 6, RoleTier.SUPPORTING));
        em.persist(actor(a, extra, 40, RoleTier.EXTRA));
        em.persist(actor(c, voiceActor, 0, RoleTier.LEAD));

        User user = userRepository.save(User.createLocal("profile-it@cinemory.com", "pw", "프로필"));
        watch(user, a, 10, true);
        watch(user, b, 4, true);
        watch(user, c, 10, true);
        wishMovieRepository.save(WishMovie.of(user, w));
        wishMovieRepository.save(WishMovie.of(user, a));
        em.flush();

        MovieFeatureIndex featureIndex = new MovieFeatureIndex(featureQueryRepository);
        featureIndex.reload();
        UserPreferenceProfileService service = new UserPreferenceProfileService(preferenceQueryRepository, featureIndex,
                RecommendFixtures.properties());

        UserPreferenceProfile profile = service.build(user.getId());

        assertThat(profile.meanRating()).isCloseTo(8.0, within(1e-9));
        // 드라마 (10−8)·1 = 2, 애니메이션 2, 공포 (4−8) = −4 → 평가에서는 0(빠짐)
        assertThat(profile.genre().evaluation()).containsEntry(drama.getId(), 1.0).containsEntry(animation.getId(), 1.0)
                .doesNotContainKey(horror.getId());
        // 공포는 찜(W)에서만 → 평가 축이 비어 있지 않으므로 λ = 0.3 → 0.3·관심(1.0)
        assertThat(profile.genre().lambda()).isEqualTo(0.3);
        assertThat(profile.genre().value(horror.getId())).isCloseTo(0.3, within(1e-9));
        // 이미 본 A의 찜(드라마)은 관심 계산에서 제외 → 드라마는 평가분(0.7)만
        assertThat(profile.genre().value(drama.getId())).isCloseTo(0.7, within(1e-9));
        // 감독 D: (10−8)/1 = 2 → 1.0. 평가 축이 있으니 λ=0.3 적용 → 0.7
        assertThat(profile.director().value(director.getId())).isCloseTo(0.7, within(1e-9));
        // 배우: LEAD 2·0.5 = 1.0 → 정규화 1.0, SUPPORTING 2·0.4 = 0.8 → 0.8. EXTRA(w=0)와 애니메이션(성우)은 빠진다.
        assertThat(profile.actor()).containsEntry(lead.getId(), 1.0).containsEntry(supporting.getId(), 0.8)
                .doesNotContainKeys(extra.getId(), voiceActor.getId());
        assertThat(profile.personalized()).isTrue();
        assertThat(profile.watchedMovieIds()).containsExactlyInAnyOrder(a.getId(), b.getId(), c.getId());
        assertThat(profile.wishedMovieIds()).containsExactlyInAnyOrder(w.getId(), a.getId());
    }

    @Test
    void 대표가_아닌_기록과_별점_없는_기록은_평가에서_빠지지만_시청_제외에는_들어간다() {
        Genre drama = genre(18, "드라마");
        Movie rep = movie(98_711L), rewatch = movie(98_712L), unrated = movie(98_713L);
        em.persist(MovieGenre.of(rep, drama, new BigDecimal("1.000")));
        em.persist(MovieGenre.of(rewatch, drama, new BigDecimal("1.000")));
        em.persist(MovieGenre.of(unrated, drama, new BigDecimal("1.000")));
        User user = userRepository.save(User.createLocal("profile-it2@cinemory.com", "pw", "프로필2"));
        watch(user, rep, 10, true);
        watch(user, rewatch, 2, false);                                  // 대표 아님 → 평가 제외
        watchRecordRepository.save(WatchRecord.builder().user(user).movie(unrated).representative(true)
                .watchDate(LocalDate.of(2024, 1, 1)).build());           // 별점 없음 → 평가 제외
        em.flush();

        assertThat(preferenceQueryRepository.findMeanRating(user.getId())).isCloseTo(10.0, within(1e-9));
        // 모든 별점이 같으면 (별점 − m) = 0 → 양수 합이 없어 전 축이 비고 콜드 스타트와 같다
        assertThat(preferenceQueryRepository.findGenreScores(user.getId(), 10.0)).isEmpty();
        assertThat(preferenceQueryRepository.findWatchedMovieIds(user.getId()))
                .containsExactlyInAnyOrder(rep.getId(), rewatch.getId(), unrated.getId());
    }

    @Test
    void 기록도_찜도_없는_사용자와_게스트는_빈_프로필이다() {
        User user = userRepository.save(User.createLocal("profile-it3@cinemory.com", "pw", "프로필3"));
        UserPreferenceProfileService service = new UserPreferenceProfileService(preferenceQueryRepository,
                new MovieFeatureIndex(featureQueryRepository), RecommendFixtures.properties());

        UserPreferenceProfile empty = service.build(user.getId());
        UserPreferenceProfile guest = service.build(null);

        assertThat(empty.personalized()).isFalse();
        assertThat(empty.hasActivity()).isFalse();
        assertThat(empty.meanRating()).isNull();
        assertThat(guest.personalized()).isFalse();
        assertThat(guest.hasActivity()).isFalse();
    }

    @Autowired RecommendProperties boundProperties;

    @Test
    void application_yml의_cinemory_recommend가_스펙_기본값으로_바인딩된다() {
        // 테스트 픽스처(RecommendFixtures.properties)가 yml과 어긋나면 서비스 테스트가 다른 값을 검증하게 되므로 여기서 묶는다.
        assertThat(boundProperties).isEqualTo(RecommendFixtures.properties());
        assertThat(boundProperties.forYou().taste() + boundProperties.forYou().quality() + boundProperties.forYou().popularity())
                .isCloseTo(1.0, within(1e-9));
    }
}
