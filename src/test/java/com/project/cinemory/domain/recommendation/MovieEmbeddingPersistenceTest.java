package com.project.cinemory.domain.recommendation;

import com.project.cinemory.domain.country.entity.Country;
import com.project.cinemory.domain.country.repository.CountryRepository;
import com.project.cinemory.domain.genre.entity.Genre;
import com.project.cinemory.domain.genre.repository.GenreRepository;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.entity.MovieActor;
import com.project.cinemory.domain.movie.entity.MovieCountry;
import com.project.cinemory.domain.movie.entity.MovieDirector;
import com.project.cinemory.domain.movie.entity.MovieGenre;
import com.project.cinemory.domain.movie.entity.RoleTier;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.person.entity.Person;
import com.project.cinemory.domain.person.repository.PersonRepository;
import com.project.cinemory.domain.recommendation.entity.MovieEmbedding;
import com.project.cinemory.domain.recommendation.entity.OverviewSource;
import com.project.cinemory.domain.recommendation.index.MovieFeature;
import com.project.cinemory.domain.recommendation.index.MovieFeatureIndex;
import com.project.cinemory.domain.recommendation.index.MovieVectorIndex;
import com.project.cinemory.domain.recommendation.repository.MovieEmbeddingRepository;
import com.project.cinemory.domain.recommendation.repository.MovieFeatureQueryRepository;
import com.project.cinemory.domain.recommendation.service.EmbeddingText;
import com.project.cinemory.domain.recommendation.service.MovieEmbeddingPersister;
import com.project.cinemory.domain.recommendation.support.FakeEmbeddingClient;
import com.project.cinemory.global.infra.gemini.VectorCodec;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V32 마이그레이션 · 엔티티 매핑(ddl-auto=validate) · 영속화 · 인덱스 적재를 실 DB(cinemory_test)로 검증한다.
 * 인덱스는 빈이 아니라 테스트용 인스턴스를 쓴다 — 빈 상태를 롤백되는 테스트 데이터로 오염시키지 않는다.
 */
@SpringBootTest
@Transactional
class MovieEmbeddingPersistenceTest {

    @Autowired MovieRepository movieRepository;
    @Autowired MovieEmbeddingRepository embeddingRepository;
    @Autowired MovieEmbeddingPersister persister;
    @Autowired MovieFeatureQueryRepository featureQueryRepository;
    @Autowired GenreRepository genreRepository;
    @Autowired CountryRepository countryRepository;
    @Autowired PersonRepository personRepository;
    @Autowired EntityManager em;

    private Movie newMovie(long tmdbId) {
        return movieRepository.save(Movie.builder().tmdbId(tmdbId).title("영화" + tmdbId)
                .releaseDate(LocalDate.of(2020, 1, 1)).runtime(100).build());
    }

    private static EmbeddingText text(String body) {
        return new EmbeddingText("제목", body, OverviewSource.KO);
    }

    @Test
    void 저장하고_읽으면_벡터_해시_출처가_그대로다() {
        Movie movie = newMovie(98_901L);
        float[] vector = FakeEmbeddingClient.vectorOf("v1");
        EmbeddingText text = text("본문");

        persister.upsert("m", List.of(new MovieEmbeddingPersister.Item(movie.getId(), text, text.hash("m"), vector)));
        em.flush();
        em.clear();

        MovieEmbedding row = embeddingRepository.findByMovieIdIn(List.of(movie.getId())).get(0);
        assertThat(VectorCodec.decode(row.getEmbedding())).containsExactly(vector);
        assertThat(row.getModelName()).isEqualTo("m");
        assertThat(row.getDimensions()).isEqualTo((short) 768);
        assertThat(row.getSourceHash()).isEqualTo(text.hash("m")).hasSize(64);
        assertThat(row.getSourceText()).isEqualTo(text.sourceText());
        assertThat(row.getOverviewSource()).isEqualTo(OverviewSource.KO);
        assertThat(row.getCreatedAt()).isNotNull();
    }

    @Test
    void 같은_영화를_다시_저장하면_행이_늘지_않고_내용이_교체된다() {
        Movie movie = newMovie(98_902L);
        EmbeddingText first = text("옛 본문");
        EmbeddingText second = new EmbeddingText("제목", "새 본문", OverviewSource.EN);
        float[] newVector = FakeEmbeddingClient.vectorOf("v2");

        persister.upsert("m", List.of(new MovieEmbeddingPersister.Item(movie.getId(), first, first.hash("m"), FakeEmbeddingClient.vectorOf("v1"))));
        em.flush();
        em.clear();
        persister.upsert("m2", List.of(new MovieEmbeddingPersister.Item(movie.getId(), second, second.hash("m2"), newVector)));
        em.flush();
        em.clear();

        List<MovieEmbedding> rows = embeddingRepository.findByMovieIdIn(List.of(movie.getId()));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getModelName()).isEqualTo("m2");
        assertThat(rows.get(0).getOverviewSource()).isEqualTo(OverviewSource.EN);
        assertThat(VectorCodec.decode(rows.get(0).getEmbedding())).containsExactly(newVector);
        assertThat(embeddingRepository.findHashesByMovieIdIn(List.of(movie.getId())))
                .singleElement().satisfies(h -> assertThat(h.getSourceHash()).isEqualTo(second.hash("m2")));
    }

    @Test
    void 벡터_인덱스는_현재_모델과_일치하는_행만_올린다() {
        Movie current = newMovie(98_903L);
        Movie stale = newMovie(98_904L);
        FakeEmbeddingClient client = new FakeEmbeddingClient("current-model");
        EmbeddingText t = text("본문");
        float[] currentVector = FakeEmbeddingClient.vectorOf("current");
        persister.upsert("current-model", List.of(new MovieEmbeddingPersister.Item(current.getId(), t, t.hash("current-model"), currentVector)));
        // 모델 교체 중 섞인 옛 벡터 — 질의 벡터와 똑같아도 무시돼야 한다.
        persister.upsert("old-model", List.of(new MovieEmbeddingPersister.Item(stale.getId(), t, t.hash("old-model"), currentVector)));
        em.flush();

        MovieVectorIndex index = new MovieVectorIndex(embeddingRepository, client);
        index.reload();

        List<MovieVectorIndex.Hit> hits = index.search(currentVector, 10_000);
        assertThat(hits).extracting(MovieVectorIndex.Hit::movieId).contains(current.getId()).doesNotContain(stale.getId());
        assertThat(hits.get(0).movieId()).isEqualTo(current.getId());
    }

    @Test
    void 특징_인덱스는_장르_국가_인물_애니메이션_대표국을_채운다() {
        // 애니메이션 판정은 tmdb_genre_id = 16 이다. 참조 데이터가 이미 있으면 재사용하고 없으면 만든다.
        Genre animation = genreRepository.findByTmdbGenreIdIn(List.of(16)).stream().findFirst()
                .orElseGet(() -> genreRepository.save(Genre.of(16, "애니메이션")));
        Genre drama = genreRepository.findByTmdbGenreIdIn(List.of(18)).stream().findFirst()
                .orElseGet(() -> genreRepository.save(Genre.of(18, "드라마")));
        Country kr = countryRepository.findByCodeIn(List.of("KR")).stream().findFirst()
                .orElseGet(() -> countryRepository.save(Country.of("KR", "한국")));
        Country us = countryRepository.findByCodeIn(List.of("US")).stream().findFirst()
                .orElseGet(() -> countryRepository.save(Country.of("US", "미국")));
        Person director = personRepository.save(Person.of(9_990_001L, "감독", null));
        Person lead = personRepository.save(Person.of(9_990_002L, "주연", null));
        Person extra = personRepository.save(Person.of(9_990_003L, "엑스트라", null));

        Movie korean = newMovie(98_905L);
        em.persist(MovieGenre.of(korean, animation, new BigDecimal("0.500")));
        em.persist(MovieGenre.of(korean, drama, new BigDecimal("0.500")));
        em.persist(MovieCountry.of(korean, kr, new BigDecimal("0.600")));
        em.persist(MovieCountry.of(korean, us, new BigDecimal("0.400")));
        em.persist(MovieDirector.of(korean, director));
        em.persist(MovieActor.builder().movie(korean).person(lead).displayOrder(0).roleTier(RoleTier.LEAD).build());
        em.persist(MovieActor.builder().movie(korean).person(extra).displayOrder(30).roleTier(RoleTier.EXTRA).build());

        Movie foreign = newMovie(98_906L);
        em.persist(MovieCountry.of(foreign, us, new BigDecimal("0.600")));
        em.persist(MovieCountry.of(foreign, kr, new BigDecimal("0.400")));
        em.flush();

        MovieFeatureIndex index = new MovieFeatureIndex(featureQueryRepository);
        index.reload();
        MovieFeatureIndex.Snapshot snapshot = index.snapshot();

        MovieFeature k = snapshot.byId().get(korean.getId());
        assertThat(k.animation()).isTrue();
        assertThat(k.korean()).isTrue();
        assertThat(k.representativeCountryCode()).isEqualTo("KR");
        assertThat(k.genreTmdbIds()).containsExactlyInAnyOrder(16, 18);
        assertThat(k.genreIds()).containsExactlyInAnyOrder(animation.getId(), drama.getId());
        assertThat(k.genreWeights()).containsExactly(0.5, 0.5);
        assertThat(k.directorIds()).containsExactly(director.getId());
        assertThat(k.leadActorIds()).containsExactly(lead.getId());   // display_order 30(EXTRA)은 제외
        assertThat(k.runtime()).isEqualTo(100);
        assertThat(k.releaseDate()).isEqualTo(LocalDate.of(2020, 1, 1));

        MovieFeature f = snapshot.byId().get(foreign.getId());
        assertThat(f.korean()).isFalse();
        assertThat(f.representativeCountryCode()).isEqualTo("US");   // US 0.6 > KR 0.4
        assertThat(f.animation()).isFalse();
        assertThat(f.genreIds()).isEmpty();

        assertThat(snapshot.genreShare().get(animation.getId())).isPositive();
        assertThat(snapshot.genreShare().values().stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(snapshot.countryShare().values().stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));
    }
}
