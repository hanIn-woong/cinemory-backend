package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.recommendation.config.RecommendProperties;
import com.project.cinemory.domain.recommendation.dto.ReasonType;
import com.project.cinemory.domain.recommendation.index.MovieFeature;
import com.project.cinemory.domain.recommendation.service.UserPreferenceProfile.Axis;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 영화 한 편의 점수 구성 요소 계산 (M3b-recommendation-spec S-1-2 · S-1-3 · S-1-3-B · S-1-5). 상태가 없다.
 *
 * <p>모든 구성 요소는 0~1이다 — 가중치가 곧 비중이 된다. 가중치·상수는 전부 {@link RecommendProperties}에서 온다.
 */
@Component
@RequiredArgsConstructor
public class RecommendationScorer {

    private final RecommendProperties properties;

    /** 취향 점수 T의 분해. {@code *InterestDominant}는 그 항의 대표 요소가 평가보다 찜 관심에서 왔는지(W-1-4 이유 선택). */
    public record Taste(double t,
                        double genreTerm, Long genreId, boolean genreInterestDominant,
                        double countryTerm, Long countryId, boolean countryInterestDominant,
                        double personTerm, ReasonType personKind, Long personId, boolean personInterestDominant) {
    }

    /**
     * T = wG·Σ(movie_genre.weight·G) + wC·Σ(movie_country.weight·C) + wP·P  (S-1-2)
     * <br>P = max(D[감독들], A[주연]) — 합이 아니라 max(합이면 앙상블·시리즈물이 항상 이긴다). 애니메이션이면 배우 항을 보지 않는다.
     */
    public Taste taste(MovieFeature movie, UserPreferenceProfile profile) {
        RecommendProperties.Taste w = properties.taste();

        double genreTerm = 0;
        double bestGenre = 0;
        Long genreId = null;
        for (int i = 0; i < movie.genreIds().length; i++) {
            double contribution = movie.genreWeights()[i] * profile.genre().value(movie.genreIds()[i]);
            genreTerm += contribution;
            if (contribution > bestGenre) {
                bestGenre = contribution;
                genreId = movie.genreIds()[i];
            }
        }

        double countryTerm = 0;
        double bestCountry = 0;
        Long countryId = null;
        for (int i = 0; i < movie.countryIds().length; i++) {
            double contribution = movie.countryWeights()[i] * profile.country().value(movie.countryIds()[i]);
            countryTerm += contribution;
            if (contribution > bestCountry) {
                bestCountry = contribution;
                countryId = movie.countryIds()[i];
            }
        }

        double directorMax = 0;
        Long directorId = null;
        for (long id : movie.directorIds()) {
            double value = profile.director().value(id);
            if (value > directorMax) {
                directorMax = value;
                directorId = id;
            }
        }
        double actorMax = 0;
        Long actorId = null;
        if (!movie.animation()) {
            for (long id : movie.leadActorIds()) {
                double value = profile.actor().getOrDefault(id, 0.0);
                if (value > actorMax) {
                    actorMax = value;
                    actorId = id;
                }
            }
        }

        double personTerm = Math.max(directorMax, actorMax);
        ReasonType personKind = null;
        Long personId = null;
        boolean personInterestDominant = false;
        if (personTerm > 0) {
            if (directorMax >= actorMax) {
                personId = directorId;
                personInterestDominant = interestDominant(profile.director(), directorId);
                personKind = personInterestDominant ? ReasonType.WISH_DIRECTOR : ReasonType.DIRECTOR;
            } else {
                personId = actorId;
                personKind = ReasonType.ACTOR;
            }
        }

        double t = w.genre() * genreTerm + w.country() * countryTerm + w.person() * personTerm;
        return new Taste(t,
                genreTerm, genreId, genreId != null && interestDominant(profile.genre(), genreId),
                countryTerm, countryId, countryId != null && interestDominant(profile.country(), countryId),
                personTerm, personKind, personId, personInterestDominant);
    }

    /** 관심 기여({@code λ·I})가 평가 기여({@code (1−λ)·E})보다 크면 "찜 목록에 많은 …" 이유를 쓴다(W-1-4). */
    private static boolean interestDominant(Axis axis, long id) {
        double interest = axis.lambda() * axis.interest().getOrDefault(id, 0.0);
        double evaluation = (1 - axis.lambda()) * axis.evaluation().getOrDefault(id, 0.0);
        return interest > evaluation;
    }

    /**
     * Q = (v·R + k·M) / (v + k) / 10  (S-1-3) — 투표 수 보정 평균. 표가 적은 영화를 평균 쪽으로 당긴다.
     * {@code vote_count} null/0 또는 평점 null이면 {@code M / 10}.
     */
    public double quality(MovieFeature movie) {
        double m = properties.quality().mean();
        double k = properties.quality().minVotes();
        double v = movie.voteCount() == null ? 0 : movie.voteCount();
        if (v <= 0 || movie.voteAverage() == null) {
            return m / 10.0;
        }
        return (v * movie.voteAverage() + k * m) / (v + k) / 10.0;
    }

    /**
     * Pop = min(1, log(1 + v) / log(1 + v99[그룹]))  (S-1-3-B ②) — 그룹은 한국/그 외. {@code vote_count} null → 0.
     * 한국 영화를 한국 안에서의 인지도로 잰다(TMDB가 한국 영화를 구조적으로 덜 다룬다).
     */
    public double popularity(MovieFeature movie) {
        if (movie.voteCount() == null || movie.voteCount() <= 0) {
            return 0;
        }
        double v99 = movie.korean() ? properties.popularity().v99Korean() : properties.popularity().v99Other();
        return Math.min(1.0, Math.log1p(movie.voteCount()) / Math.log1p(v99));
    }

    /** 투표 수 하한 (S-1-3-B ①). 한국은 0(하한 없음). {@code vote_count} null은 하한 대상에서 제외(= 탈락). */
    public boolean passesVoteFloor(MovieFeature movie) {
        int floor = movie.korean() ? properties.minVoteCount().korean() : properties.minVoteCount().other();
        if (floor <= 0) {
            return true;
        }
        return movie.voteCount() != null && movie.voteCount() >= floor;
    }

    /** 취향 추천 S = taste·T + quality·Q + popularity·Pop (S-1-4). */
    public double forYouScore(Taste taste, double quality, double popularity) {
        RecommendProperties.ForYou w = properties.forYou();
        return w.taste() * taste.t() + w.quality() * quality + w.popularity() * popularity;
    }

    /** 자연어 S = similarity·sim + taste·T + quality·Q + runtimeBonus·[런타임 선호 충족] (S-1-4). 부정 개념 감점이 없는 질의용. */
    public double queryScore(double sim, Taste taste, double quality, double runtimeBonus) {
        return queryScore(sim, taste, quality, runtimeBonus, 0.0);
    }

    /**
     * 부정 개념 감점을 포함한 자연어 점수: {@code … − γ·negSim} (Q-2-3 ②).
     *
     * @param negativeSim 후보 안 min-max로 0~1로 만든 부정 개념 유사도. 부정 개념이 없으면 0
     */
    public double queryScore(double sim, Taste taste, double quality, double runtimeBonus, double negativeSim) {
        RecommendProperties.Query w = properties.query();
        return w.similarity() * sim + w.taste() * taste.t() + w.quality() * quality + w.runtimeBonus() * runtimeBonus
                - w.negativePenalty() * negativeSim;
    }

    /**
     * 추천 이유 선택 (S-1-5) — <b>가중치를 곱한 기여</b>가 가장 큰 항. 동률이면 인물 > 장르 > 국가 > 품질 > 인지도.
     *
     * @param tasteScale  최종 점수에서 T의 가중치(취향 추천 {@code forYou.taste}, 자연어 {@code query.taste})
     * @param qualityScale 최종 점수에서 Q의 가중치
     * @param popularityContribution 가중치를 곱한 Pop 기여. 자연어는 Pop이 없어 0
     * @param otherContribution 이유가 될 수 없는 항(자연어의 sim·런타임 가산)의 최대 기여. 이 값이 가장 크면 {@code null}을 돌려준다
     */
    public ReasonChoice chooseReason(Taste taste, double tasteScale, double quality, double qualityScale,
                                     double popularityContribution, double otherContribution) {
        RecommendProperties.Taste w = properties.taste();
        double person = tasteScale * w.person() * taste.personTerm();
        double genre = tasteScale * w.genre() * taste.genreTerm();
        double country = tasteScale * w.country() * taste.countryTerm();
        double q = qualityScale * quality;

        double max = Math.max(Math.max(person, genre), Math.max(Math.max(country, q), popularityContribution));
        if (otherContribution >= max) {
            return null;
        }
        if (max == person && person > 0) {
            return new ReasonChoice(taste.personKind(), taste.personId());
        }
        if (max == genre && genre > 0) {
            return new ReasonChoice(taste.genreInterestDominant() ? ReasonType.WISH_GENRE : ReasonType.GENRE, taste.genreId());
        }
        if (max == country && country > 0) {
            return new ReasonChoice(taste.countryInterestDominant() ? ReasonType.WISH_COUNTRY : ReasonType.COUNTRY, taste.countryId());
        }
        if (max == q) {
            return new ReasonChoice(ReasonType.QUALITY, null);
        }
        return new ReasonChoice(ReasonType.POPULARITY, null);
    }

    /** 이름 조회 전의 이유 — 대상 id만 갖는다. */
    public record ReasonChoice(ReasonType type, Long targetId) {
    }
}
