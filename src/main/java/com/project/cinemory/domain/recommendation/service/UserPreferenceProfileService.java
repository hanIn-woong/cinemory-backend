package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.recommendation.config.RecommendProperties;
import com.project.cinemory.domain.recommendation.index.MovieFeature;
import com.project.cinemory.domain.recommendation.index.MovieFeatureIndex;
import com.project.cinemory.domain.recommendation.repository.UserPreferenceQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 사용자 선호 프로필 조립 (M3b-recommendation-spec S-1-1 · W-1). DB는 집계 쿼리 6개만 읽는다(S-1-6) —
 * 찜 영화의 장르·국가·감독은 {@link MovieFeatureIndex} 스냅샷에서 가져온다.
 *
 * <p>기록도 찜도 없는 사용자나 게스트({@code userId == null})는 {@link UserPreferenceProfile#empty()}를 받는다 —
 * 별도 분기 없이 같은 코드 경로가 "품질·인지도 순"(R-3)을 낸다.
 */
@Service
@RequiredArgsConstructor
public class UserPreferenceProfileService {

    private final UserPreferenceQueryRepository queryRepository;
    private final MovieFeatureIndex featureIndex;
    private final RecommendProperties properties;

    public UserPreferenceProfile build(Long userId) {
        if (userId == null) {
            return UserPreferenceProfile.empty();
        }
        MovieFeatureIndex.Snapshot snapshot = featureIndex.snapshot();
        Set<Long> watched = queryRepository.findWatchedMovieIds(userId);
        Set<Long> wished = queryRepository.findWishedMovieIds(userId);

        ProfileCalculator.EvaluationScores evaluation = evaluationOf(userId);

        // 이미 본 영화는 찜 계산에서 제외한다 — 별점이 더 정확한 신호이고 이중 반영을 막는다(W-1-4).
        List<MovieFeature> wishedUnwatched = new ArrayList<>();
        for (Long movieId : wished) {
            MovieFeature feature = snapshot.byId().get(movieId);
            if (feature != null && !watched.contains(movieId)) {
                wishedUnwatched.add(feature);
            }
        }
        return ProfileCalculator.calculate(evaluation, wishedUnwatched,
                snapshot.genreShare(), snapshot.countryShare(), watched, wished, properties.wish());
    }

    private ProfileCalculator.EvaluationScores evaluationOf(long userId) {
        Double mean = queryRepository.findMeanRating(userId);
        if (mean == null) {
            return ProfileCalculator.EvaluationScores.none();
        }
        Map<Long, Double> genre = queryRepository.findGenreScores(userId, mean);
        Map<Long, Double> country = queryRepository.findCountryScores(userId, mean);
        Map<Long, Double> director = queryRepository.findDirectorScores(userId, mean);
        Map<Long, Double> actor = queryRepository.findActorScores(userId, mean, MovieFeatureIndex.ANIMATION_TMDB_GENRE_ID);
        return new ProfileCalculator.EvaluationScores(mean, genre, country, director, actor);
    }
}
