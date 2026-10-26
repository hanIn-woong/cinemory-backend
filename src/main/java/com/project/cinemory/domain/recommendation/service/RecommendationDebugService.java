package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.country.entity.Country;
import com.project.cinemory.domain.country.repository.CountryRepository;
import com.project.cinemory.domain.genre.entity.Genre;
import com.project.cinemory.domain.genre.repository.GenreRepository;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.person.entity.Person;
import com.project.cinemory.domain.person.repository.PersonRepository;
import com.project.cinemory.domain.recommendation.dto.AppliedConditionResponse;
import com.project.cinemory.domain.recommendation.dto.RecommendationDebugResponse;
import com.project.cinemory.domain.recommendation.dto.RecommendationDebugResponse.Axis;
import com.project.cinemory.domain.recommendation.dto.RecommendationDebugResponse.Entry;
import com.project.cinemory.domain.recommendation.index.MovieVectorIndex;
import com.project.cinemory.domain.recommendation.service.QueryConstraintExtractor.QueryConstraints;
import com.project.cinemory.domain.recommendation.service.RecommendationResult.RankedMovie;
import com.project.cinemory.domain.recommendation.service.RecommendationResult.ScoreBreakdown;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.gemini.EmbeddingClient;
import com.project.cinemory.global.infra.gemini.EmbeddingClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 점수 분해 디버그 (M3b-recommendation-spec S-1-7, controller-layer-spec 5-14-G) — 가중치를 감이 아니라 숫자로 조정하기 위한 관리자 도구.
 *
 * <p><b>캐시를 쓰지 않고 항상 계산한다.</b> 호출 제한에는 세지 않는다. 자연어 디버그는 질의 임베딩을 실제로 호출한다
 * (임베딩 캐시도 거치지 않는다 — 항상 현재 모델의 결과를 본다). 감사 로그는 Controller가 남긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationDebugService {

    /** 축마다 상위 몇 개를 보여 줄지. 배우 축은 수천 명이라 전부 내리지 않는다. */
    static final int AXIS_TOP = 20;

    private final UserRepository userRepository;
    private final UserPreferenceProfileService profileService;
    private final ForYouRecommendationService forYouService;
    private final QueryRecommendationService queryService;
    private final QueryConstraintExtractor constraintExtractor;
    private final EmbeddingClient embeddingClient;
    private final MovieVectorIndex vectorIndex;
    private final MovieRepository movieRepository;
    private final GenreRepository genreRepository;
    private final CountryRepository countryRepository;
    private final PersonRepository personRepository;

    /**
     * @param rawQuery 있으면 자연어, 없으면(null·공백) 취향 추천
     * @throws BusinessException {@code USER_NOT_FOUND}(404), {@code INVALID_RECOMMENDATION_QUERY}(400), {@code EXTERNAL_API_ERROR}(임베딩 실패)
     */
    public RecommendationDebugResponse debug(long userId, String rawQuery) {
        if (!userRepository.existsById(userId)) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        UserPreferenceProfile profile = profileService.build(userId);

        String query = rawQuery == null || rawQuery.isBlank() ? null : RecommendationPageService.normalizeQuery(rawQuery);
        RecommendationResult result;
        List<AppliedConditionResponse> conditions = List.of();
        if (query == null) {
            result = forYouService.recommend(profile, null);
        } else {
            QueryConstraints constraints = constraintExtractor.extract(query);
            conditions = constraints.conditions();
            if (vectorIndex.isEmpty()) {
                result = new RecommendationResult(List.of(), profile.personalized(), conditions,
                        new RecommendationResult.ExclusionCounts(0, 0, 0, 0, 0, 0));
            } else {
                result = queryService.recommend(profile, constraints, embed(query));
            }
        }
        return assemble(userId, query, conditions, profile, result);
    }

    private float[] embed(String query) {
        try {
            return embeddingClient.embedQuery(query);
        } catch (EmbeddingClientException e) {
            log.warn("디버그 질의 임베딩 실패. reason={}", e.getMessage());
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR, "질의 임베딩에 실패했습니다.");
        }
    }

    private RecommendationDebugResponse assemble(long userId, String query, List<AppliedConditionResponse> conditions,
                                                 UserPreferenceProfile profile, RecommendationResult result) {
        Set<Long> movieIds = new HashSet<>();
        Set<Long> personIds = new HashSet<>();
        for (RankedMovie ranked : result.movies()) {
            movieIds.add(ranked.movieId());
            if (ranked.breakdown().personId() != null) {
                personIds.add(ranked.breakdown().personId());
            }
        }
        Axis genres = axis(profile.genre(), AXIS_TOP);
        Axis countries = axis(profile.country(), AXIS_TOP);
        Axis directors = axis(profile.director(), AXIS_TOP);
        Axis actors = actorAxis(profile.actor(), AXIS_TOP);
        personIds.addAll(directors.top().stream().map(Entry::id).toList());
        personIds.addAll(actors.top().stream().map(Entry::id).toList());

        Map<Long, String> genreNames = names(genreRepository.findAllById(genres.top().stream().map(Entry::id).toList()), Genre::getId, Genre::getName);
        Map<Long, String> countryNames = names(countryRepository.findAllById(countries.top().stream().map(Entry::id).toList()), Country::getId, Country::getName);
        Map<Long, String> personNames = names(personRepository.findAllById(personIds), Person::getId, Person::displayName);
        Map<Long, String> titles = names(movieRepository.findAllById(movieIds), Movie::getId, Movie::displayTitle);

        List<RecommendationDebugResponse.Movie> movies = result.movies().stream().map(r -> {
            ScoreBreakdown b = r.breakdown();
            return new RecommendationDebugResponse.Movie(r.movieId(), titles.get(r.movieId()), b.sim(),
                    b.genreTerm(), b.countryTerm(), b.personTerm(), b.personKind(), b.personId(),
                    b.personId() == null ? null : personNames.get(b.personId()), b.taste(), b.quality(),
                    b.popularity(), b.runtimeBonus(), b.score(), r.reason(), r.wished());
        }).toList();

        RecommendationResult.ExclusionCounts x = result.exclusions();
        return new RecommendationDebugResponse(userId, query, conditions,
                new RecommendationDebugResponse.Profile(profile.meanRating(), profile.personalized(),
                        profile.watchedMovieIds().size(), profile.wishedMovieIds().size(),
                        named(genres, genreNames), named(countries, countryNames),
                        named(directors, personNames), named(actors, personNames)),
                movies,
                new RecommendationDebugResponse.Exclusions(x.watched(), x.wished(), x.unreleased(),
                        x.belowVoteFloor(), x.runtimeFilter(), x.directorCap()));
    }

    /** 결합값 내림차순 상위 {@code limit}. 이름은 나중에 채운다. */
    private static Axis axis(UserPreferenceProfile.Axis source, int limit) {
        List<Entry> top = source.combined().entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(e -> new Entry(e.getKey(), null,
                        source.evaluation().getOrDefault(e.getKey(), 0.0),
                        source.interest().getOrDefault(e.getKey(), 0.0), e.getValue()))
                .toList();
        return new Axis(source.lambda(), source.combined().size(), top);
    }

    /** 배우 축은 평가에서만 온다 — 관심 0, λ 0. */
    private static Axis actorAxis(Map<Long, Double> actors, int limit) {
        List<Entry> top = actors.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(e -> new Entry(e.getKey(), null, e.getValue(), 0.0, e.getValue()))
                .toList();
        return new Axis(0.0, actors.size(), top);
    }

    private static Axis named(Axis axis, Map<Long, String> names) {
        return new Axis(axis.lambda(), axis.size(), axis.top().stream()
                .map(e -> new Entry(e.id(), names.get(e.id()), e.evaluation(), e.interest(), e.combined()))
                .toList());
    }

    private static <T> Map<Long, String> names(Iterable<T> entities, Function<T, Long> id, Function<T, String> name) {
        Map<Long, String> map = new HashMap<>();
        for (T entity : entities) {
            map.put(id.apply(entity), name.apply(entity));
        }
        return map;
    }
}
