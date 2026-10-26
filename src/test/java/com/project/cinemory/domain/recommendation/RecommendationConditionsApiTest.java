package com.project.cinemory.domain.recommendation;

import com.project.cinemory.domain.country.entity.Country;
import com.project.cinemory.domain.country.repository.CountryRepository;
import com.project.cinemory.domain.genre.entity.Genre;
import com.project.cinemory.domain.genre.repository.GenreRepository;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.entity.MovieCountry;
import com.project.cinemory.domain.movie.entity.MovieGenre;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.recommendation.entity.OverviewSource;
import com.project.cinemory.domain.recommendation.index.MovieFeatureIndex;
import com.project.cinemory.domain.recommendation.index.MovieVectorIndex;
import com.project.cinemory.domain.recommendation.service.EmbeddingText;
import com.project.cinemory.domain.recommendation.service.MovieEmbeddingPersister;
import com.project.cinemory.domain.recommendation.support.FakeEmbeddingClient;
import com.project.cinemory.domain.recommendation.support.FakeEmbeddingConfig;
import com.project.cinemory.domain.user.entity.RoleType;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.global.infra.gemini.EmbeddingClientException;
import com.project.cinemory.global.security.JwtTokenProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 질의 조건 확장(M3b Q-2) 통합 테스트 — 국가·개봉 시기·제외 장르 필터 선적용, 부정 개념 임베딩(호출 제한·실패 처리), 대체 경로의 하드 필터 유지.
 *
 * <p>코사인은 {@link FakeEmbeddingClient#vectorOf}로 질의 벡터를 알 수 있다는 점을 이용해 만든다: 미끼 영화는 질의 벡터 그대로(코사인 1.0),
 * 조건에 맞는 영화는 무관한 벡터(코사인 ≈ 0)로 넣는다 — 후필터였다면 상위 200편이 전부 미끼라 결과가 비는 상황이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FakeEmbeddingConfig.class)
class RecommendationConditionsApiTest {

    private static final int HORROR = 27;
    private static final int THRILLER = 53;

    @Autowired MockMvc mockMvc;
    @Autowired MovieRepository movieRepository;
    @Autowired GenreRepository genreRepository;
    @Autowired CountryRepository countryRepository;
    @Autowired UserRepository userRepository;
    @Autowired MovieEmbeddingPersister embeddingPersister;
    @Autowired MovieFeatureIndex featureIndex;
    @Autowired MovieVectorIndex vectorIndex;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired FakeEmbeddingClient fakeEmbedding;
    @Autowired EntityManager em;

    private static int seq = 0;
    private static long nextTmdbId = 97_000L;

    private User user;
    private String token;
    private Country kr;
    private Country jp;
    private Genre horror;
    private Genre thriller;

    @BeforeEach
    void setUp() {
        fakeEmbedding.reset();
        user = userRepository.save(User.createLocal("cond-user" + (++seq) + "@test.com", "{noop}pw", "조건" + seq));
        token = jwtTokenProvider.createAccessToken(user.getId(), RoleType.USER);
        kr = country("KR", "한국");
        jp = country("JP", "일본");
        horror = genre(HORROR, "공포");
        thriller = genre(THRILLER, "스릴러");
    }

    private Country country(String code, String name) {
        return countryRepository.findByCodeIn(List.of(code)).stream().findFirst().orElseGet(() -> countryRepository.save(Country.of(code, name)));
    }

    private Genre genre(int tmdbId, String name) {
        return genreRepository.findByTmdbGenreIdIn(List.of(tmdbId)).stream().findFirst().orElseGet(() -> genreRepository.save(Genre.of(tmdbId, name)));
    }

    /** 영화 한 편 + 대표국/장르/개봉일/표 수 + 벡터. 인덱스 재생성은 호출부가 한다. */
    private Movie movie(String title, Country country, Genre genre, LocalDate releaseDate, float[] vector) {
        Movie movie = movieRepository.save(Movie.builder().tmdbId(nextTmdbId++).title(title).releaseDate(releaseDate)
                .voteAverage(BigDecimal.valueOf(7.0)).voteCount(1_000).build());
        if (country != null) {
            em.persist(MovieCountry.of(movie, country, new BigDecimal("1.000")));
        }
        if (genre != null) {
            em.persist(MovieGenre.of(movie, genre, new BigDecimal("1.000")));
        }
        EmbeddingText text = new EmbeddingText(title, "본문", OverviewSource.KO);
        embeddingPersister.upsert(FakeEmbeddingClient.MODEL, List.of(new MovieEmbeddingPersister.Item(
                movie.getId(), text, text.hash(FakeEmbeddingClient.MODEL), vector)));
        return movie;
    }

    private void reloadIndexes() {
        em.flush();
        featureIndex.reload();
        vectorIndex.reload();
    }

    private ResultActions search(String q) throws Exception {
        return mockMvc.perform(get("/api/recommendations/search").param("q", q).param("size", "50").header("Authorization", "Bearer " + token));
    }

    private List<Integer> contentIds(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        com.fasterxml.jackson.databind.JsonNode content = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("movies").path("content");
        List<Integer> ids = new ArrayList<>();
        content.forEach(node -> ids.add(node.path("id").asInt()));
        return ids;
    }

    private String idsOf(List<Movie> movies) {
        return movies.stream().map(m -> String.valueOf(m.getId())).collect(Collectors.joining(","));
    }

    // ── 필터 선적용 (Q-2-4) ─────────────────────────────────────────

    @Test
    void 구십년대_한국_영화는_상위_200편이_전부_미끼여도_조건에_맞는_영화만_나온다() throws Exception {
        String query = "90년대 한국 영화";
        float[] queryVector = FakeEmbeddingClient.vectorOf("query:" + query);

        // 미끼 210편: 질의와 코사인 1.0 — 하지만 국가 정보가 없거나 2020년대다. 후필터였다면 상위 200편이 전부 이 영화들이다.
        for (int i = 0; i < 210; i++) {
            movie("미끼" + i, i % 2 == 0 ? null : jp, null, LocalDate.of(2021, 1, 1), queryVector);
        }
        // 조건에 맞는 영화: 한국 · 1990년대 — 질의와 무관한 벡터(코사인 ≈ 0)
        List<Movie> targets = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            targets.add(movie("한국90_" + i, kr, null, LocalDate.of(1990 + i * 3, 6, 1), FakeEmbeddingClient.vectorOf("unrelated-" + i)));
        }
        // 한 가지씩만 어긋나는 영화들 — 국가, 연대
        Movie koreanButNewer = movie("한국2010", kr, null, LocalDate.of(2012, 6, 1), FakeEmbeddingClient.vectorOf("unrelated-x"));
        Movie japaneseNineties = movie("일본90", jp, null, LocalDate.of(1995, 6, 1), FakeEmbeddingClient.vectorOf("unrelated-y"));
        reloadIndexes();

        ResultActions result = search(query).andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(false))
                .andExpect(jsonPath("$.conditions[?(@.type == 'COUNTRY')].label").value("한국"))
                .andExpect(jsonPath("$.conditions[?(@.type == 'RELEASE_PERIOD')].label").value("1990년대"))
                .andExpect(jsonPath("$.movies.totalElements").value(3));

        List<Integer> ids = contentIds(result);
        assertThat(ids).containsExactlyInAnyOrderElementsOf(targets.stream().map(m -> m.getId().intValue()).toList());
        assertThat(ids).doesNotContain(koreanButNewer.getId().intValue(), japaneseNineties.getId().intValue());
        assertThat(fakeEmbedding.queryCalls()).isEqualTo(1);   // 본 질의 하나(부정 개념 없음)
    }

    @Test
    void 공포_말고_스릴러는_공포_장르_영화가_0건이다() throws Exception {
        float[] queryVector = FakeEmbeddingClient.vectorOf("query:누아르 스릴러물");
        Movie horrorMovie = movie("공포영화", kr, horror, LocalDate.of(2020, 1, 1), queryVector);          // 가장 유사하지만 공포
        Movie horrorThriller = movie("공포스릴러", kr, horror, LocalDate.of(2020, 1, 1), queryVector);
        horrorThriller.getId();
        em.persist(MovieGenre.of(horrorThriller, thriller, new BigDecimal("0.500")));
        Movie thrillerMovie = movie("스릴러영화", kr, thriller, LocalDate.of(2020, 1, 1), FakeEmbeddingClient.vectorOf("other"));
        Movie noGenre = movie("장르없음", kr, null, LocalDate.of(2020, 1, 1), FakeEmbeddingClient.vectorOf("other2"));
        reloadIndexes();

        ResultActions result = search("공포 말고 누아르 스릴러물").andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(false))
                .andExpect(jsonPath("$.conditions[0].type").value("EXCLUDE_GENRE"))
                .andExpect(jsonPath("$.conditions[0].label").value("공포"))
                .andExpect(jsonPath("$.conditions[0].text").value("공포 제외"));

        List<Integer> ids = contentIds(result);
        assertThat(ids).contains(thrillerMovie.getId().intValue(), noGenre.getId().intValue());
        assertThat(ids).doesNotContain(horrorMovie.getId().intValue(), horrorThriller.getId().intValue());
        assertThat(fakeEmbedding.queryTexts()).containsExactly("누아르 스릴러물");   // 본 질의에서 "공포 말고"가 지워졌다
    }

    @Test
    void 일본_영화_느낌은_국가_조건이_아니라_분위기라_COUNTRY가_없다() throws Exception {
        movie("일본영화", jp, null, LocalDate.of(2020, 1, 1), FakeEmbeddingClient.vectorOf("a"));
        Movie korean = movie("한국영화", kr, null, LocalDate.of(2020, 1, 1), FakeEmbeddingClient.vectorOf("b"));
        reloadIndexes();

        ResultActions result = search("일본 영화 느낌의 잔잔한 영화").andExpect(status().isOk())
                .andExpect(jsonPath("$.conditions").isEmpty());

        assertThat(contentIds(result)).contains(korean.getId().intValue());   // 한국 영화도 후보에 남는다
    }

    @Test
    void 한국_영화_말고는_한국_영화를_결과에서_뺀다() throws Exception {
        float[] queryVector = FakeEmbeddingClient.vectorOf("query:액션");
        Movie korean = movie("한국액션", kr, null, LocalDate.of(2020, 1, 1), queryVector);
        Movie japanese = movie("일본액션", jp, null, LocalDate.of(2020, 1, 1), FakeEmbeddingClient.vectorOf("c"));
        reloadIndexes();

        ResultActions result = search("한국 영화 말고 액션").andExpect(status().isOk())
                .andExpect(jsonPath("$.conditions[0].type").value("EXCLUDE_COUNTRY"));

        assertThat(contentIds(result)).contains(japanese.getId().intValue()).doesNotContain(korean.getId().intValue());
    }

    @Test
    void 본_질의가_공포_말고_하나뿐이면_Gemini를_부르지_않고_필터만으로_답한다() throws Exception {
        Movie horrorMovie = movie("공포만", kr, horror, LocalDate.of(2020, 1, 1), FakeEmbeddingClient.vectorOf("h"));
        Movie other = movie("그냥영화", kr, null, LocalDate.of(2020, 1, 1), FakeEmbeddingClient.vectorOf("o"));
        reloadIndexes();

        ResultActions result = search("공포 영화 말고").andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(false));

        assertThat(fakeEmbedding.queryCalls()).isZero();
        assertThat(contentIds(result)).contains(other.getId().intValue()).doesNotContain(horrorMovie.getId().intValue());
    }

    // ── 부정 개념 임베딩 (Q-2-3 ②) ───────────────────────────────────

    @Test
    void 잔인하지_않은_스릴러는_본_질의와_부정_개념을_각각_임베딩한다() throws Exception {
        movie("영화A", kr, null, LocalDate.of(2020, 1, 1), FakeEmbeddingClient.vectorOf("p"));
        movie("영화B", kr, null, LocalDate.of(2020, 1, 1), FakeEmbeddingClient.vectorOf("q"));
        reloadIndexes();

        search("살벌하지 않은 느와르풍 범죄극").andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(false))
                .andExpect(jsonPath("$.conditions[0].type").value("AVOID_MOOD"))
                .andExpect(jsonPath("$.conditions[0].label").value("살벌하다"))
                .andExpect(jsonPath("$.conditions[0].text").value("살벌한 영화는 뒤로 미뤘어요"));

        assertThat(fakeEmbedding.queryTexts()).containsExactly("느와르풍 범죄극", "살벌하다");
    }

    @Test
    void 부정_개념의_임베딩이_실패하면_감점만_생략하고_degraded가_아니다() throws Exception {
        movie("영화C", kr, null, LocalDate.of(2020, 1, 1), FakeEmbeddingClient.vectorOf("r"));
        reloadIndexes();
        fakeEmbedding.failQueriesWhen(text -> text.equals("험악하다"), new EmbeddingClientException("timeout"));

        search("험악하지 않은 스릴러").andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(false))
                .andExpect(jsonPath("$.conditions[?(@.type == 'AVOID_MOOD')]").doesNotExist())   // 적용되지 않았으므로 칩도 없다
                .andExpect(jsonPath("$.movies.content").isNotEmpty());
    }

    @Test
    void 본_질의의_임베딩이_실패하면_degraded이고_AVOID_MOOD_칩은_내리지_않는다() throws Exception {
        movie("영화D", kr, null, LocalDate.of(2020, 1, 1), FakeEmbeddingClient.vectorOf("s"));
        reloadIndexes();
        fakeEmbedding.failQueriesWhen(text -> text.equals("범죄 스릴러"), new EmbeddingClientException("timeout"));

        search("흉측하지 않은 범죄 스릴러").andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.conditions[?(@.type == 'AVOID_MOOD')]").doesNotExist());
    }

    @Test
    void 부정_개념이_셋이면_본_질의와_둘만_임베딩한다() throws Exception {
        movie("영화E", kr, null, LocalDate.of(2020, 1, 1), FakeEmbeddingClient.vectorOf("t"));
        reloadIndexes();

        search("음산하지 않은 눅눅하지 않은 칙칙하지 않은 서스펜스").andExpect(status().isOk())
                .andExpect(jsonPath("$.conditions[?(@.type == 'AVOID_MOOD')].label").value(org.hamcrest.Matchers.contains("음산하다", "눅눅하다")));

        assertThat(fakeEmbedding.queryTexts()).containsExactly("서스펜스", "음산하다", "눅눅하다");   // "칙칙하다"는 쓰지 않고, 본 질의에서도 지워졌다
    }

    @Test
    void 본_질의가_비면_부정_개념만_임베딩하고_후보는_필터를_통과한_전체다() throws Exception {
        Movie a = movie("영화F", kr, null, LocalDate.of(2020, 1, 1), FakeEmbeddingClient.vectorOf("u"));
        Movie b = movie("영화G", kr, null, LocalDate.of(2020, 1, 1), FakeEmbeddingClient.vectorOf("v"));
        reloadIndexes();

        ResultActions result = search("참혹하지 않은 영화").andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(false))
                .andExpect(jsonPath("$.conditions[0].label").value("참혹하다"));

        assertThat(fakeEmbedding.queryTexts()).containsExactly("참혹하다");
        assertThat(contentIds(result)).contains(a.getId().intValue(), b.getId().intValue());
    }

    @Test
    void 본_질의가_비고_부정_개념도_모두_실패하면_degraded다() throws Exception {
        movie("영화H", kr, null, LocalDate.of(2020, 1, 1), FakeEmbeddingClient.vectorOf("w"));
        reloadIndexes();
        fakeEmbedding.failQueriesWith(new EmbeddingClientException("timeout"));

        search("처참하지 않은 영화").andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(true));
    }

    @Test
    void 부정_개념_임베딩도_호출_제한에_센다_캐시에_걸린_것은_세지_않는다() throws Exception {
        movie("영화I", kr, null, LocalDate.of(2020, 1, 1), FakeEmbeddingClient.vectorOf("x"));
        reloadIndexes();

        // 본 질의 1 + 부정 개념 2 = 실제 호출 3번
        search("섬뜩하지 않은 끔찍하지 않은 호흡이 긴 서스펜스극").andExpect(status().isOk());
        assertThat(fakeEmbedding.queryCalls()).isEqualTo(3);
        // 같은 질의를 다시(0페이지) — 세 임베딩이 모두 캐시에 있어 호출도, 제한 카운트도 늘지 않는다.
        search("섬뜩하지 않은 끔찍하지 않은 호흡이 긴 서스펜스극").andExpect(status().isOk());
        assertThat(fakeEmbedding.queryCalls()).isEqualTo(3);

        // 서로 다른 질의 7개 → 누적 10번. 11번째 실제 호출은 429.
        for (int i = 0; i < 7; i++) {
            search("분당 한도 시험 질의 " + i).andExpect(status().isOk());
        }
        search("분당 한도 시험 질의 초과").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RECOMMENDATION_RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
        assertThat(fakeEmbedding.queryCalls()).isEqualTo(10);
    }

    // ── 대체 경로의 하드 필터 ───────────────────────────────────────

    @Test
    void 임베딩이_실패해_대체돼도_국가와_시기_필터는_유지된다() throws Exception {
        Movie target = movie("대체한국90", kr, null, LocalDate.of(1995, 1, 1), FakeEmbeddingClient.vectorOf("y"));
        Movie koreanNewer = movie("대체한국신작", kr, null, LocalDate.of(2015, 1, 1), FakeEmbeddingClient.vectorOf("z"));
        Movie japanese = movie("대체일본90", jp, null, LocalDate.of(1995, 1, 1), FakeEmbeddingClient.vectorOf("zz"));
        reloadIndexes();
        fakeEmbedding.failQueriesWith(new EmbeddingClientException("timeout"));

        ResultActions result = search("대체 시험 90년대 한국 영화").andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.conditions[?(@.type == 'COUNTRY')]").exists())
                .andExpect(jsonPath("$.conditions[?(@.type == 'RELEASE_PERIOD')]").exists());

        List<Integer> ids = contentIds(result);
        assertThat(ids).contains(target.getId().intValue());
        assertThat(ids).doesNotContain(koreanNewer.getId().intValue(), japanese.getId().intValue());
    }

    @Test
    void 벡터_인덱스가_비어_있어도_대체_결과는_하드_필터를_유지한다() throws Exception {
        Movie target = movie("인덱스없음한국", kr, null, LocalDate.of(2020, 1, 1), FakeEmbeddingClient.vectorOf("n1"));
        Movie japanese = movie("인덱스없음일본", jp, null, LocalDate.of(2020, 1, 1), FakeEmbeddingClient.vectorOf("n2"));
        em.flush();
        featureIndex.reload();
        em.createQuery("delete from MovieEmbedding").executeUpdate();
        vectorIndex.reload();   // 임베딩이 하나도 없는 상태

        ResultActions result = search("인덱스 없음 한국 영화").andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(true));

        assertThat(contentIds(result)).contains(target.getId().intValue()).doesNotContain(japanese.getId().intValue());
        assertThat(fakeEmbedding.queryCalls()).isZero();
    }
}
