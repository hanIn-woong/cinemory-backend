package com.project.cinemory.domain.recommendation;

import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.recommendation.repository.MovieEmbeddingRepository;
import com.project.cinemory.domain.recommendation.service.ForYouRecommendationService;
import com.project.cinemory.domain.recommendation.service.QueryConstraintExtractor;
import com.project.cinemory.domain.recommendation.service.QueryRecommendationService;
import com.project.cinemory.domain.recommendation.service.RecommendationResult;
import com.project.cinemory.domain.recommendation.service.RecommendationResult.RankedMovie;
import com.project.cinemory.domain.recommendation.service.UserPreferenceProfile;
import com.project.cinemory.domain.recommendation.service.UserPreferenceProfileService;
import com.project.cinemory.global.infra.gemini.VectorCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <b>수동 실행 전용</b> — 로컬 개발 DB({@code cinemory})의 실제 계정으로 상위 20편 샘플을 출력한다(M3-b PR 2 보고용).
 * Gemini를 호출하지 않는다. 평소 {@code ./gradlew test}에서는 실행되지 않는다.
 *
 * <pre>
 * MANUAL_RECOMMEND_SAMPLE=true MANUAL_RECOMMEND_USER_ID=276 [MANUAL_QUERY_SEED_MOVIE_ID=&lt;임베딩된 영화 id&gt; MANUAL_QUERY_TEXT="짧은 영화"] \
 *   ./gradlew cleanTest test --tests '*RecommendationSampleManualRunner' -i
 * </pre>
 *
 * <p>자연어 샘플은 질의 임베딩 API를 쓰지 않고 <b>임베딩된 영화 한 편의 벡터를 질의 벡터로 빌려</b> 점수 파이프라인만 확인한다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/cinemory?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul"
})
@EnabledIfEnvironmentVariable(named = "MANUAL_RECOMMEND_SAMPLE", matches = "true")
class RecommendationSampleManualRunner {

    @Autowired ForYouRecommendationService forYou;
    @Autowired QueryRecommendationService query;
    @Autowired UserPreferenceProfileService profileService;
    @Autowired QueryConstraintExtractor extractor;
    @Autowired MovieRepository movieRepository;
    @Autowired MovieEmbeddingRepository embeddingRepository;

    @Test
    void 상위_20편_샘플을_출력한다() {
        long userId = Long.parseLong(System.getenv("MANUAL_RECOMMEND_USER_ID"));
        UserPreferenceProfile profile = profileService.build(userId);

        System.out.println("[sample] userId=" + userId + " m=" + profile.meanRating() + " personalized=" + profile.personalized()
                + " genres=" + profile.genre().combined().size() + " countries=" + profile.country().combined().size()
                + " directors=" + profile.director().combined().size() + " actors=" + profile.actor().size()
                + " watched=" + profile.watchedMovieIds().size() + " wished=" + profile.wishedMovieIds().size());

        long t0 = System.nanoTime();
        RecommendationResult result = forYou.recommend(profile, null);
        System.out.println("[sample] for-you computed in " + (System.nanoTime() - t0) / 1_000_000 + "ms, results=" + result.movies().size()
                + " exclusions=" + result.exclusions());
        print("for-you", result);

        String seed = System.getenv("MANUAL_QUERY_SEED_MOVIE_ID");
        if (seed != null && !seed.isBlank()) {
            String text = System.getenv().getOrDefault("MANUAL_QUERY_TEXT", "");
            float[] vector = VectorCodec.decode(embeddingRepository.findByMovieIdIn(List.of(Long.parseLong(seed))).get(0).getEmbedding());
            long q0 = System.nanoTime();
            RecommendationResult queryResult = query.recommend(profile, extractor.extract(text), vector);
            System.out.println("[sample] query(seed movie " + seed + ", text='" + text + "') in " + (System.nanoTime() - q0) / 1_000_000
                    + "ms, conditions=" + queryResult.conditions() + " exclusions=" + queryResult.exclusions());
            print("query", queryResult);
        }
    }

    private void print(String label, RecommendationResult result) {
        List<RankedMovie> top = result.movies().stream().limit(20).toList();
        Map<Long, Movie> movies = movieRepository.findAllById(top.stream().map(RankedMovie::movieId).toList()).stream()
                .collect(Collectors.toMap(Movie::getId, m -> m));
        int rank = 1;
        for (RankedMovie r : top) {
            Movie m = movies.get(r.movieId());
            System.out.printf("[sample] %s #%d %.3f  %s (%s) votes=%s  reason=%s%s%n", label, rank++, r.score(),
                    m.displayTitle(), m.getReleaseDate() == null ? "?" : m.getReleaseDate().getYear(), m.getVoteCount(),
                    r.reason() == null ? "-" : r.reason().text(), r.wished() ? "  [찜]" : "");
        }
    }
}
