package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.recommendation.config.RecommendProperties;
import com.project.cinemory.domain.recommendation.index.MovieFeature;
import com.project.cinemory.domain.recommendation.index.MovieFeatureIndex;
import com.project.cinemory.domain.recommendation.repository.ProfileFingerprint;
import com.project.cinemory.domain.recommendation.repository.UserPreferenceQueryRepository;
import com.project.cinemory.domain.recommendation.service.ForYouBatchSampler.Candidate;
import com.project.cinemory.domain.recommendation.service.ForYouBatchSampler.Selection;
import com.project.cinemory.domain.recommendation.service.RecommendationCache.BatchState;
import com.project.cinemory.domain.recommendation.service.RecommendationCache.Entry;
import com.project.cinemory.domain.recommendation.service.RecommendationResult.RankedMovie;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * 내 취향 추천 - 10편 묶음 + "다른 추천 보기" (M3b-recommendation-spec R-5-3). 무한 스크롤(최대 100편)을 없애고, 상위 후보 안에서만
 * 10편씩 돌려 보여 준다 - 품질은 유지하고 매번 새롭게.
 *
 * <pre>
 * 풀      S 점수 상위 50편 (제외 규칙·R-5-1 적용 후, 감독 상한 전)
 * 제외    직전 3묶음. 풀 − 제외가 10편 미만이면 가장 오래된 묶음부터 제외를 푼다
 * 추출    Gumbel-top-k (key = S/τ + 잡음, τ = 0.05) + 묶음 안 같은 감독 최대 2편 → 묶음 안은 S 내림차순
 * 유지    같은 날(KST) · 같은 프로필 지문이면 다시 들어와도 같은 묶음 (사용자별 상태, 수명 7일)
 * </pre>
 *
 * <p><b>게스트</b>는 상태가 없다 - 앱이 직전에 받은 id를 {@code exclude}(최대 30)로 보낸다. 기록·찜이 없는 로그인 사용자는 게스트와
 * 같은 전역 풀을 쓰되 묶음 이력은 서버에 둔다. 전역 풀은 특징 인덱스 스냅샷이 바뀔 때만 다시 계산한다(공개 경로의 CPU 소모 방지).
 *
 * <p><b>난수원은 주입</b>받는다 - 테스트가 시드를 고정한다. 호출 제한은 없다(Gemini를 부르지 않는다). {@code next=true} 연타는 CPU만 쓰고,
 * 풀 50편 재추출은 수 ms다(풀은 상태에 저장돼 있다).
 */
@Service
public class ForYouBatchService {

    private final ForYouRecommendationService forYouService;
    private final UserPreferenceProfileService profileService;
    private final UserPreferenceQueryRepository queryRepository;
    private final MovieFeatureIndex featureIndex;
    private final RecommendationCache cache;
    private final RecommendProperties properties;
    private final Clock clock;
    private final RandomGenerator random;

    public ForYouBatchService(ForYouRecommendationService forYouService, UserPreferenceProfileService profileService,
                              UserPreferenceQueryRepository queryRepository, MovieFeatureIndex featureIndex,
                              RecommendationCache cache, RecommendProperties properties, Clock clock, RandomGenerator random) {
        this.forYouService = forYouService;
        this.profileService = profileService;
        this.queryRepository = queryRepository;
        this.featureIndex = featureIndex;
        this.cache = cache;
        this.properties = properties;
        this.clock = clock;
        this.random = random;
    }

    /**
     * @param movies       묶음 - S 내림차순
     * @param hasMore      풀에 최근 묶음에 없는 영화가 남았는가
     */
    public record Batch(List<RankedMovie> movies, boolean personalized, boolean hasMore) {
    }

    /** 한 번의 추출 결과와 디버그용 부가 정보. */
    record Draw(Selection selection, int releasedBatches, List<Set<Long>> activeExclusions) {
    }

    /**
     * @param userId  비로그인이면 {@code null}
     * @param next    {@code true}면 새 묶음, {@code false}면 같은 날·같은 프로필 지문일 때 현재 묶음
     * @param exclude 게스트 전용 - 로그인 사용자가 보내면 무시한다(서버의 묶음 이력을 쓴다). 오래된 것이 앞
     */
    public Batch forYou(Long userId, boolean next, List<Long> exclude) {
        MovieFeatureIndex.Snapshot snapshot = featureIndex.snapshot();
        if (userId == null) {
            return guestBatch(snapshot, exclude);
        }

        LocalDate today = LocalDate.now(clock);
        ProfileFingerprint fingerprint = queryRepository.findFingerprint(userId);
        BatchState state = cache.getBatchState(userId);

        if (!next && state != null && state.date().equals(today) && state.fingerprint().equals(fingerprint)
                && stillRecommendable(state.current(), snapshot)) {
            return new Batch(state.current(), state.personalized(), hasMore(state.pool(), state.recent()));
        }

        // 새 묶음. 풀은 프로필이 그대로이고 인덱스 세대가 같으면 상태에 있는 것을 재사용한다(next 연타 = 재추출만).
        List<RankedMovie> pool;
        boolean personalized;
        if (state != null && state.fingerprint().equals(fingerprint) && state.generation() == snapshot) {
            pool = state.pool();
            personalized = state.personalized();
        } else {
            UserPreferenceProfile profile = profileService.build(userId);
            if (profile.hasActivity()) {
                RecommendationResult result = forYouService.pool(profile);
                pool = trimmed(result.movies());
                personalized = result.personalized();
            } else {
                pool = globalPool();   // 기록도 찜도 없으면 게스트와 같은 전역 풀
                personalized = false;
            }
        }

        List<List<RankedMovie>> previous = state == null ? List.of() : state.recent();
        Draw draw = draw(pool, oldestFirst(previous), snapshot);
        List<RankedMovie> picks = draw.selection().picks();

        List<List<RankedMovie>> recent = new ArrayList<>(properties.forYou().recentBatches());
        recent.add(picks);
        recent.addAll(previous);
        while (recent.size() > properties.forYou().recentBatches()) {
            recent.remove(recent.size() - 1);
        }
        cache.putBatchState(userId, new BatchState(today, fingerprint, snapshot, personalized, pool, List.copyOf(recent)));
        return new Batch(picks, personalized, hasMore(pool, recent));
    }

    private Batch guestBatch(MovieFeatureIndex.Snapshot snapshot, List<Long> rawExclude) {
        List<RankedMovie> pool = globalPool();
        List<Long> exclude = normalizeExclude(rawExclude, properties.forYou().maxExclude());

        // 오래된 id가 앞이다. 묶음 크기로 끊어(최신이 마지막 조각) "가장 오래된 묶음"의 제외부터 풀 수 있게 한다.
        int batchSize = properties.forYou().batchSize();
        List<Set<Long>> groups = new ArrayList<>();
        for (int end = exclude.size(); end > 0; end -= batchSize) {
            groups.add(0, new LinkedHashSet<>(exclude.subList(Math.max(0, end - batchSize), end)));
        }
        Draw draw = draw(pool, groups, snapshot);
        List<RankedMovie> picks = draw.selection().picks();

        Set<Long> seen = new HashSet<>(exclude);
        picks.forEach(m -> seen.add(m.movieId()));
        return new Batch(picks, false, pool.stream().anyMatch(m -> !seen.contains(m.movieId())));
    }

    /**
     * 게스트와 기록·찜 없는 사용자가 공유하는 전역 풀(S 점수 상위 50편). 특징 인덱스 스냅샷이 바뀔 때만 다시 계산한다 -
     * 기동 후 첫 적재 · 06:00 재적재 · 관리자 추천 제외 덮어쓰기 직후.
     */
    public List<RankedMovie> globalPool() {
        return cache.guest(featureIndex.snapshot(), () -> {
            RecommendationResult result = forYouService.pool(UserPreferenceProfile.empty());
            return new Entry(trimmed(result.movies()), false, List.of());
        }).movies();
    }

    /**
     * 게스트 {@code exclude} 정리 - 중복을 없애고(첫 등장 순서 유지) 최대 {@code max}개만 남긴다. 초과분은 <b>앞에서부터</b>(오래된 것부터) 자른다.
     */
    static List<Long> normalizeExclude(List<Long> raw, int max) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(raw.stream().filter(java.util.Objects::nonNull).toList()));
        return ids.size() > max ? List.copyOf(ids.subList(ids.size() - max, ids.size())) : List.copyOf(ids);
    }

    // ── 추출 ───────────────────────────────────────────────────────

    /**
     * 풀에서 한 묶음을 뽑는다. {@code exclusions}는 <b>오래된 것이 앞</b>인 제외 묶음들이다. 풀 − 제외가 모자라면(묶음 크기 미만) 가장 오래된
     * 묶음부터 제외를 하나씩 풀며 다시 뽑는다 - 감독 상한 때문에 모자란 경우도 같다. 전부 풀어도 모자라면 가능한 만큼만 돌려준다.
     */
    Draw draw(List<RankedMovie> pool, List<Set<Long>> exclusions, MovieFeatureIndex.Snapshot snapshot) {
        int batchSize = properties.forYou().batchSize();
        Selection selection = null;
        int released = 0;
        for (; released <= exclusions.size(); released++) {
            Set<Long> excluded = new HashSet<>();
            exclusions.subList(released, exclusions.size()).forEach(excluded::addAll);

            List<Candidate> available = new ArrayList<>();
            for (RankedMovie movie : pool) {
                MovieFeature feature = snapshot.byId().get(movie.movieId());
                // 인덱스 재생성 사이에 삭제됐거나 추천 제외가 된 영화는 풀에 남아 있어도 뽑지 않는다
                if (!excluded.contains(movie.movieId()) && feature != null && !feature.recommendBlocked()) {
                    available.add(new Candidate(movie, feature.directorIds()));
                }
            }
            selection = ForYouBatchSampler.select(available, batchSize, properties.forYou().temperature(),
                    properties.maxPerDirector(), random);
            if (selection.picks().size() >= batchSize) {
                break;
            }
        }
        int actualReleased = Math.min(released, exclusions.size());
        return new Draw(selection, actualReleased, List.copyOf(exclusions.subList(actualReleased, exclusions.size())));
    }

    /** 풀에 최근 묶음에 없는 영화가 남았는가. */
    private static boolean hasMore(List<RankedMovie> pool, List<List<RankedMovie>> recent) {
        Set<Long> seen = new HashSet<>();
        recent.forEach(batch -> batch.forEach(m -> seen.add(m.movieId())));
        return pool.stream().anyMatch(m -> !seen.contains(m.movieId()));
    }

    /** 현재 묶음의 영화가 모두 아직 추천 가능한가(삭제 · 관리자 제외 반영) - 아니면 같은 날이어도 새로 뽑는다. */
    private static boolean stillRecommendable(List<RankedMovie> batch, MovieFeatureIndex.Snapshot snapshot) {
        for (RankedMovie movie : batch) {
            MovieFeature feature = snapshot.byId().get(movie.movieId());
            if (feature == null || feature.recommendBlocked()) {
                return false;
            }
        }
        return true;
    }

    /** 최신이 앞인 묶음 목록 → 오래된 것이 앞인 id 집합 목록. */
    private static List<Set<Long>> oldestFirst(List<List<RankedMovie>> newestFirst) {
        List<Set<Long>> groups = new ArrayList<>(newestFirst.size());
        for (List<RankedMovie> batch : newestFirst) {
            Set<Long> ids = new LinkedHashSet<>();
            batch.forEach(m -> ids.add(m.movieId()));
            groups.add(ids);
        }
        Collections.reverse(groups);
        return groups;
    }

    /** 상태에 오래 두는 풀은 점수 분해(디버그용)를 떼어 낸다 - 사용자당 메모리를 줄인다. */
    private static List<RankedMovie> trimmed(Collection<RankedMovie> movies) {
        List<RankedMovie> pool = new ArrayList<>(movies.size());
        for (RankedMovie m : movies) {
            pool.add(new RankedMovie(m.movieId(), m.score(), m.reason(), false, null));
        }
        return List.copyOf(pool);
    }

    // ── 디버그 ─────────────────────────────────────────────────────

    /**
     * 관리자 디버그 - 풀 50편 전체의 점수 분해와 이번 추출의 key · 상태를 보여 준다. <b>상태를 바꾸지 않는다</b>(시뮬레이션): 사용자의 실제 묶음
     * 이력을 읽어 "지금 다른 추천 보기를 누르면" 어떻게 되는지 새 난수로 한 번 뽑아 본다. 실제로 보여 준 묶음의 key는 저장하지 않으므로 같지 않다.
     */
    public PoolDebug debug(long userId, UserPreferenceProfile profile) {
        MovieFeatureIndex.Snapshot snapshot = featureIndex.snapshot();
        RecommendationResult full = forYouService.pool(profile);
        BatchState state = cache.getBatchState(userId);
        List<List<RankedMovie>> recent = state == null ? List.of() : state.recent();

        Draw draw = draw(trimmed(full.movies()), oldestFirst(recent), snapshot);

        // 제외 사유: 최근 묶음 번호(0 = 현재 묶음). 풀려난 묶음(가장 오래된 쪽 = recent의 뒤쪽)은 제외가 아니다.
        Map<Long, Integer> recentIndex = new HashMap<>();
        int activeCount = recent.size() - draw.releasedBatches();
        for (int i = 0; i < activeCount; i++) {
            final int index = i;
            recent.get(i).forEach(m -> recentIndex.putIfAbsent(m.movieId(), index));
        }

        Set<Long> selected = new HashSet<>();
        draw.selection().picks().forEach(m -> selected.add(m.movieId()));
        return new PoolDebug(full, draw.selection().keys(), selected, draw.selection().directorCapped(), recentIndex,
                draw.releasedBatches(), recent.stream().map(batch -> batch.stream().map(RankedMovie::movieId).toList()).toList());
    }

    /**
     * @param pool            풀 50편(점수 분해 포함, S 내림차순)
     * @param keys            추출 후보의 key. 제외된 영화는 없다
     * @param recentBatchIndex 제외된 영화가 속한 최근 묶음 번호(0 = 현재 묶음)
     * @param recentBatches   사용자의 최근 묶음 이력(최신이 앞)
     */
    public record PoolDebug(RecommendationResult pool, Map<Long, Double> keys, Set<Long> selected, Set<Long> directorCapped,
                            Map<Long, Integer> recentBatchIndex, int releasedBatches, List<List<Long>> recentBatches) {
    }
}
