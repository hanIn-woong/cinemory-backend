package com.project.cinemory.domain.recommendation.support;

import com.project.cinemory.domain.recommendation.config.RecommendProperties;
import com.project.cinemory.domain.recommendation.index.MovieFeature;
import com.project.cinemory.domain.recommendation.index.MovieFeatureIndex;
import com.project.cinemory.domain.recommendation.service.UserPreferenceProfile;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 추천 테스트 공용 — S-1-7 yml 기본값과 같은 프로퍼티, 특징 빌더, 스냅샷·프로필 조립. */
public final class RecommendFixtures {

    private RecommendFixtures() {
    }

    /** application.yml의 {@code cinemory.recommend.*} 기본값 그대로. */
    public static RecommendProperties properties() {
        return new RecommendProperties(
                new RecommendProperties.Taste(0.5, 0.2, 0.3),
                new RecommendProperties.ForYou(0.65, 0.20, 0.15),
                new RecommendProperties.Query(0.60, 0.25, 0.15, 0.10, 200, 100, 140),
                new RecommendProperties.Quality(6.63, 707),
                new RecommendProperties.Popularity(1598, 16674),
                new RecommendProperties.MinVoteCount(0, 50),
                new RecommendProperties.Wish(0.3, 5, 2),
                2, 100, 20, Duration.ofMinutes(10),
                new RecommendProperties.Cache(1000, 1000, Duration.ofHours(24)),
                new RecommendProperties.RateLimit(10, 200));
    }

    public static MovieBuilder movie(long id) {
        return new MovieBuilder(id);
    }

    public static MovieFeatureIndex.Snapshot snapshot(List<MovieFeature> movies,
                                                      Map<Long, Double> genreShare,
                                                      Map<Long, Double> countryShare) {
        Map<Long, MovieFeature> byId = new HashMap<>();
        movies.forEach(m -> byId.put(m.movieId(), m));
        return new MovieFeatureIndex.Snapshot(movies, byId, genreShare, countryShare);
    }

    /** 평가 축만 채운 프로필(찜 없음) — 값은 이미 0~1 정규화된 것으로 본다. */
    public static UserPreferenceProfile profile(Map<Long, Double> genre, Map<Long, Double> country,
                                                Map<Long, Double> director, Map<Long, Double> actor,
                                                Set<Long> watched, Set<Long> wished) {
        boolean personalized = !genre.isEmpty() || !country.isEmpty() || !director.isEmpty() || !actor.isEmpty();
        return new UserPreferenceProfile(7.0, evalOnly(genre), evalOnly(country), evalOnly(director), actor,
                personalized, watched, wished);
    }

    public static UserPreferenceProfile.Axis evalOnly(Map<Long, Double> values) {
        return new UserPreferenceProfile.Axis(values, Map.of(), 0.3, values);
    }

    /** 기본값: 한국 아님, 애니메이션 아님, 개봉일 2020-01-01, 런타임 110, 평점 7.0 / 1000표, 장르·국가·인물 없음. */
    public static final class MovieBuilder {
        private final long id;
        private long[] genreIds = {};
        private double[] genreWeights = {};
        private long[] countryIds = {};
        private double[] countryWeights = {};
        private long[] directorIds = {};
        private long[] leadActorIds = {};
        private boolean animation;
        private boolean korean;
        private Integer runtime = 110;
        private LocalDate releaseDate = LocalDate.of(2020, 1, 1);
        private Double voteAverage = 7.0;
        private Integer voteCount = 1000;

        private MovieBuilder(long id) {
            this.id = id;
        }

        public MovieBuilder genres(long... idsAndWeightsInThousandths) {
            // (id, weight*1000) 쌍
            List<Long> ids = new ArrayList<>();
            List<Double> weights = new ArrayList<>();
            for (int i = 0; i < idsAndWeightsInThousandths.length; i += 2) {
                ids.add(idsAndWeightsInThousandths[i]);
                weights.add(idsAndWeightsInThousandths[i + 1] / 1000.0);
            }
            this.genreIds = ids.stream().mapToLong(Long::longValue).toArray();
            this.genreWeights = weights.stream().mapToDouble(Double::doubleValue).toArray();
            return this;
        }

        public MovieBuilder countries(long... idsAndWeightsInThousandths) {
            List<Long> ids = new ArrayList<>();
            List<Double> weights = new ArrayList<>();
            for (int i = 0; i < idsAndWeightsInThousandths.length; i += 2) {
                ids.add(idsAndWeightsInThousandths[i]);
                weights.add(idsAndWeightsInThousandths[i + 1] / 1000.0);
            }
            this.countryIds = ids.stream().mapToLong(Long::longValue).toArray();
            this.countryWeights = weights.stream().mapToDouble(Double::doubleValue).toArray();
            return this;
        }

        public MovieBuilder directors(long... ids) {
            this.directorIds = ids;
            return this;
        }

        public MovieBuilder actors(long... ids) {
            this.leadActorIds = ids;
            return this;
        }

        public MovieBuilder animation() {
            this.animation = true;
            return this;
        }

        public MovieBuilder korean() {
            this.korean = true;
            return this;
        }

        public MovieBuilder runtime(Integer runtime) {
            this.runtime = runtime;
            return this;
        }

        public MovieBuilder releaseDate(LocalDate date) {
            this.releaseDate = date;
            return this;
        }

        public MovieBuilder votes(Double average, Integer count) {
            this.voteAverage = average;
            this.voteCount = count;
            return this;
        }

        public MovieFeature build() {
            return new MovieFeature(id, genreIds, genreWeights, countryIds, countryWeights, directorIds, leadActorIds,
                    animation, korean, runtime, releaseDate, voteAverage, voteCount);
        }
    }
}
