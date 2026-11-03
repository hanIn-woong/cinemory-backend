package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.movie.entity.RecommendOverride;
import com.project.cinemory.domain.recommendation.index.MovieFeature;
import com.project.cinemory.domain.recommendation.index.MovieFeatureIndex;
import com.project.cinemory.domain.recommendation.repository.ProfileFingerprint;
import com.project.cinemory.domain.recommendation.repository.UserPreferenceQueryRepository;
import com.project.cinemory.domain.recommendation.service.ForYouBatchService.Batch;
import com.project.cinemory.domain.recommendation.service.RecommendationResult.ExclusionCounts;
import com.project.cinemory.domain.recommendation.service.RecommendationResult.RankedMovie;
import com.project.cinemory.domain.recommendation.support.RecommendFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 취향 추천 10편 묶음 (M3b R-5-3, controller-layer-spec 5-14-J "취향 묶음") - 풀·제외·추출·유지·게스트. 난수원은 시드를 고정한 {@link Random},
 * 날짜는 조작 가능한 시계, 점수 계산(풀)은 가짜다 - 이 서비스의 책임인 "무엇을 어떻게 돌려 보여 주나"만 본다.
 */
class ForYouBatchServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final long USER = 1L;
    private static final long OTHER_USER = 2L;

    /** 날짜를 바꿀 수 있는 시계. */
    private static final class MutableClock extends Clock {
        private Instant instant = LocalDate.of(2026, 11, 3).atTime(10, 0).atZone(KST).toInstant();

        void setDate(LocalDate date) {
            instant = date.atTime(10, 0).atZone(KST).toInstant();
        }

        @Override
        public ZoneId getZone() {
            return KST;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private MovieFeatureIndex featureIndex;
    private ForYouRecommendationService forYouService;
    private UserPreferenceProfileService profileService;
    private UserPreferenceQueryRepository queryRepository;
    private RecommendationCache cache;
    private MutableClock clock;
    private ForYouBatchService service;

    private List<MovieFeature> features;
    private ProfileFingerprint fingerprint = new ProfileFingerprint(3, 1, 30, 10);

    @BeforeEach
    void setUp() {
        featureIndex = mock(MovieFeatureIndex.class);
        forYouService = mock(ForYouRecommendationService.class);
        profileService = mock(UserPreferenceProfileService.class);
        queryRepository = mock(UserPreferenceQueryRepository.class);
        clock = new MutableClock();
        cache = new RecommendationCache(RecommendFixtures.properties());
        service = newService(new Random(42));

        // 활동이 있는 사용자 - 개인 풀. 활동이 없는 사용자는 전역 풀.
        UserPreferenceProfile active = RecommendFixtures.profile(Map.of(1L, 1.0), Map.of(), Map.of(), Map.of(), Set.of(900L), Set.of());
        when(profileService.build(USER)).thenReturn(active);
        when(profileService.build(OTHER_USER)).thenReturn(UserPreferenceProfile.empty());
        when(queryRepository.findFingerprint(USER)).thenAnswer(inv -> fingerprint);
        when(queryRepository.findFingerprint(OTHER_USER)).thenReturn(new ProfileFingerprint(0, 0, 0, 0));
        catalog(movies(50));
        poolOf(50, true);
    }

    private ForYouBatchService newService(Random random) {
        return new ForYouBatchService(forYouService, profileService, queryRepository, featureIndex, cache,
                RecommendFixtures.properties(), clock, random);
    }

    // ── 픽스처 ─────────────────────────────────────────────────────

    /** 영화 n편(id 1..n) - 감독은 모두 다르다. */
    private static List<MovieFeature> movies(int n) {
        List<MovieFeature> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            list.add(RecommendFixtures.movie(i).directors(1000 + i).build());
        }
        return list;
    }

    private void catalog(List<MovieFeature> movies) {
        this.features = movies;
        when(featureIndex.snapshot()).thenReturn(RecommendFixtures.snapshot(movies, Map.of(), Map.of()));
    }

    /** 점수가 id가 클수록 낮은 풀 n편 (id 1..n). */
    private static RecommendationResult pool(int n, boolean personalized) {
        List<RankedMovie> ranked = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            ranked.add(new RankedMovie(i, 0.9 - 0.01 * (i - 1), null, false, null));
        }
        return new RecommendationResult(ranked, personalized, List.of(), ExclusionCounts.none());
    }

    private void poolOf(int n, boolean personalized) {
        when(forYouService.pool(any(UserPreferenceProfile.class))).thenAnswer(inv -> {
            UserPreferenceProfile profile = inv.getArgument(0);
            return pool(n, personalized && profile.hasActivity());
        });
    }

    private static Set<Long> idsOf(Batch batch) {
        return batch.movies().stream().map(RankedMovie::movieId).collect(Collectors.toSet());
    }

    private static List<Long> orderedIdsOf(Batch batch) {
        return batch.movies().stream().map(RankedMovie::movieId).toList();
    }

    // ── 같은 날 · 같은 프로필 = 같은 묶음 ─────────────────────────────

    @Test
    void 같은_날_next_false를_두_번_부르면_같은_10편이고_풀을_다시_계산하지_않는다() {
        Batch first = service.forYou(USER, false, null);
        Batch second = service.forYou(USER, false, null);

        assertThat(first.movies()).hasSize(10);
        assertThat(orderedIdsOf(second)).isEqualTo(orderedIdsOf(first));
        verify(forYouService, times(1)).pool(any(UserPreferenceProfile.class));
    }

    @Test
    void 묶음_안은_점수_내림차순이다() {
        Batch batch = service.forYou(USER, false, null);

        assertThat(batch.movies()).extracting(RankedMovie::score).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void 날짜가_바뀌면_새_묶음이고_어제_묶음은_제외된다() {
        Batch today = service.forYou(USER, false, null);

        clock.setDate(LocalDate.of(2026, 11, 4));
        Batch tomorrow = service.forYou(USER, false, null);

        assertThat(idsOf(tomorrow)).doesNotContainAnyElementsOf(idsOf(today));   // 이력이 하루를 넘어 이어진다 - 매일 같은 추천을 막는다
    }

    @Test
    void 기록이나_찜이_바뀌면_next_false여도_새_묶음이다() {
        Batch before = service.forYou(USER, false, null);

        fingerprint = new ProfileFingerprint(4, 1, 31, 10);   // 기록 추가
        Batch after = service.forYou(USER, false, null);

        assertThat(idsOf(after)).doesNotContainAnyElementsOf(idsOf(before));
        verify(forYouService, times(2)).pool(any(UserPreferenceProfile.class));   // 프로필이 바뀌었으니 풀도 다시 계산
        assertThat(orderedIdsOf(service.forYou(USER, false, null))).isEqualTo(orderedIdsOf(after));   // 새 지문으로 다시 유지
    }

    @Test
    void 지문의_네_값_중_하나만_바뀌어도_새_묶음이다() {
        List<ProfileFingerprint> changes = List.of(
                new ProfileFingerprint(4, 1, 30, 10), new ProfileFingerprint(3, 2, 30, 10),
                new ProfileFingerprint(3, 1, 31, 10), new ProfileFingerprint(3, 1, 30, 11));
        for (ProfileFingerprint changed : changes) {
            Batch before = service.forYou(USER, false, null);
            fingerprint = changed;
            Batch after = service.forYou(USER, false, null);

            assertThat(idsOf(after)).as("지문 %s", changed).doesNotContainAnyElementsOf(idsOf(before));
        }
    }

    // ── 다른 추천 보기 ─────────────────────────────────────────────

    @Test
    void next_true는_직전_3묶음과_겹치지_않는다() {
        List<Set<Long>> batches = new ArrayList<>();
        batches.add(idsOf(service.forYou(USER, false, null)));
        for (int i = 0; i < 6; i++) {
            Set<Long> next = idsOf(service.forYou(USER, true, null));
            assertThat(next).hasSize(10);
            // 방금까지의 최근 3묶음(가장 최근 3개)과 겹치면 안 된다. 그보다 오래된 묶음은 다시 나올 수 있다.
            for (Set<Long> recent : batches.subList(Math.max(0, batches.size() - 3), batches.size())) {
                assertThat(next).doesNotContainAnyElementsOf(recent);
            }
            batches.add(next);
        }
    }

    @Test
    void next_true_연타는_풀을_다시_계산하지_않는다() {
        service.forYou(USER, false, null);
        for (int i = 0; i < 5; i++) {
            service.forYou(USER, true, null);
        }

        verify(forYouService, times(1)).pool(any(UserPreferenceProfile.class));   // 풀은 상태에 저장돼 있다 - 재추출은 수 ms
    }

    @Test
    void next_true는_이미_본_묶음을_상태에_밀어_넣어_다시_들어와도_마지막_묶음이_유지된다() {
        service.forYou(USER, false, null);
        Batch other = service.forYou(USER, true, null);

        assertThat(orderedIdsOf(service.forYou(USER, false, null))).isEqualTo(orderedIdsOf(other));
    }

    @Test
    void 사용자마다_묶음_이력이_따로다() {
        Batch mine = service.forYou(USER, false, null);
        service.forYou(OTHER_USER, false, null);
        service.forYou(OTHER_USER, true, null);

        assertThat(orderedIdsOf(service.forYou(USER, false, null))).isEqualTo(orderedIdsOf(mine));
    }

    // ── 풀 소진 ────────────────────────────────────────────────────

    @Test
    void 풀에서_제외를_뺀_영화가_10편_미만이면_가장_오래된_묶음부터_제외를_푼다() {
        poolOf(25, true);   // 25편 풀 - 두 묶음(20편)이면 5편만 남는다
        Set<Long> first = idsOf(service.forYou(USER, false, null));
        Set<Long> second = idsOf(service.forYou(USER, true, null));
        assertThat(second).doesNotContainAnyElementsOf(first);

        Set<Long> third = idsOf(service.forYou(USER, true, null));

        // 남은 5편 < 10 -> 첫 묶음의 제외가 풀린다. 둘째(더 최근) 묶음은 계속 제외된다.
        assertThat(third).hasSize(10);
        assertThat(third).doesNotContainAnyElementsOf(second);
    }

    @Test
    void 풀이_묶음_크기보다_작으면_가능한_만큼만_돌려준다() {
        poolOf(6, true);

        Batch batch = service.forYou(USER, false, null);

        assertThat(batch.movies()).hasSize(6);
        assertThat(service.forYou(USER, true, null).movies()).hasSize(6);   // 제외를 전부 풀어도 같은 6편 - 멈추지 않는다
    }

    @Test
    void hasMore는_풀에_최근_묶음에_없는_영화가_남았는지다() {
        poolOf(25, true);

        assertThat(service.forYou(USER, false, null).hasMore()).isTrue();    // 25 - 10 = 15 남음
        assertThat(service.forYou(USER, true, null).hasMore()).isTrue();     // 25 - 20 = 5 남음
        poolOf(10, true);
        fingerprint = new ProfileFingerprint(9, 9, 99, 99);                  // 풀 10편 = 한 묶음이면 더 볼 게 없다
        assertThat(service.forYou(USER, false, null).hasMore()).isFalse();
    }

    // ── 다양성 ─────────────────────────────────────────────────────

    @Test
    void 묶음_안_같은_감독은_최대_2편이다() {
        // 앞 30편이 같은 감독 - 점수가 높은 영화들이 한 감독에 몰렸다
        List<MovieFeature> shared = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            shared.add(RecommendFixtures.movie(i).directors(i <= 30 ? 7L : 1000L + i).build());
        }
        catalog(shared);

        for (long seed = 0; seed < 50; seed++) {
            cache = new RecommendationCache(RecommendFixtures.properties());
            service = newService(new Random(seed));
            Batch batch = service.forYou(USER, false, null);

            assertThat(batch.movies()).hasSize(10);
            assertThat(batch.movies().stream().filter(m -> m.movieId() <= 30).count()).isLessThanOrEqualTo(2);
            // 다음 묶음에도 같은 상한
            assertThat(service.forYou(USER, true, null).movies().stream().filter(m -> m.movieId() <= 30).count()).isLessThanOrEqualTo(2);
        }
    }

    // ── 게스트 ─────────────────────────────────────────────────────

    @Test
    void 게스트는_exclude로_받은_영화를_다시_받지_않는다() {
        Set<Long> seen = new HashSet<>();
        List<Long> exclude = new ArrayList<>();
        for (int round = 0; round < 3; round++) {   // 앱이 받은 id를 이어 붙여 보낸다
            Batch batch = service.forYou(null, false, List.copyOf(exclude));

            assertThat(batch.movies()).hasSize(10);
            assertThat(idsOf(batch).stream().filter(seen::contains).toList()).isEmpty();   // 앞서 받은 영화는 다시 오지 않는다
            assertThat(batch.personalized()).isFalse();
            seen.addAll(idsOf(batch));
            exclude.addAll(orderedIdsOf(batch));
        }
        assertThat(cache.getBatchState(USER)).isNull();   // 게스트는 서버에 상태를 남기지 않는다
    }

    @Test
    void 게스트_exclude는_최대_30개이고_초과분은_앞에서부터_자른다() {
        List<Long> excess = LongStream.rangeClosed(1, 31).boxed().toList();

        List<Long> kept = ForYouBatchService.normalizeExclude(excess, 30);

        assertThat(kept).hasSize(30);
        assertThat(kept).doesNotContain(1L).contains(2L, 31L);   // 가장 오래된(앞쪽) 하나가 잘렸다
        assertThat(ForYouBatchService.normalizeExclude(List.of(5L, 5L, 6L), 30)).containsExactly(5L, 6L);   // 중복 제거
        assertThat(ForYouBatchService.normalizeExclude(null, 30)).isEmpty();
    }

    @Test
    void exclude_31개를_보내면_앞쪽_하나는_제외로_세지_않는다() {
        catalog(movies(41));
        poolOf(41, true);
        List<Long> exclude = LongStream.rangeClosed(1, 31).boxed().toList();
        boolean firstAppeared = false;

        for (long seed = 0; seed < 60; seed++) {
            service = newService(new Random(seed));
            Set<Long> picked = idsOf(service.forYou(null, false, exclude));

            // 2..31(30개)은 제외된다. 가장 앞의 1은 잘렸으니 풀에 남은 11편(1, 32..41) 중에서 뽑힐 수 있다.
            assertThat(picked).doesNotContainAnyElementsOf(LongStream.rangeClosed(2, 31).boxed().toList());
            firstAppeared |= picked.contains(1L);
        }
        assertThat(firstAppeared).isTrue();
    }

    @Test
    void 게스트_exclude로_풀이_모자라면_가장_오래된_것부터_제외를_푼다() {
        poolOf(25, true);
        List<Long> exclude = LongStream.rangeClosed(1, 20).boxed().toList();   // 앞 10개가 오래된 묶음, 뒤 10개가 최근 묶음

        Set<Long> picked = idsOf(service.forYou(null, false, exclude));

        // 남은 5편(21..25) < 10 -> 오래된 1..10의 제외가 풀린다. 최근 11..20은 계속 제외.
        assertThat(picked).hasSize(10);
        assertThat(picked).doesNotContainAnyElementsOf(LongStream.rangeClosed(11, 20).boxed().toList());
    }

    @Test
    void 로그인_사용자가_보낸_exclude는_무시한다() {
        Set<Long> excluded = LongStream.rangeClosed(1, 30).boxed().collect(Collectors.toSet());
        boolean ignored = false;
        for (long seed = 0; seed < 20; seed++) {
            cache = new RecommendationCache(RecommendFixtures.properties());
            service = newService(new Random(seed));
            ignored |= idsOf(service.forYou(USER, false, List.copyOf(excluded))).stream().anyMatch(excluded::contains);
        }
        assertThat(ignored).isTrue();   // 서버의 묶음 이력을 쓰므로 상위 영화(1~30)가 그대로 뽑힌다
    }

    // ── 전역 풀 ────────────────────────────────────────────────────

    @Test
    void 기록도_찜도_없는_로그인_사용자는_게스트와_같은_전역_풀을_쓰고_한_번만_계산한다() {
        service.forYou(null, false, null);
        service.forYou(OTHER_USER, false, null);
        service.forYou(null, true, null);

        verify(forYouService, times(1)).pool(any(UserPreferenceProfile.class));
        assertThat(service.forYou(OTHER_USER, false, null).personalized()).isFalse();
    }

    @Test
    void 개인_풀은_사용자_프로필로_계산하고_personalized를_전달한다() {
        assertThat(service.forYou(USER, false, null).personalized()).isTrue();
    }

    @Test
    void 인덱스_세대가_바뀌면_전역_풀을_다시_계산한다() {
        service.forYou(null, false, null);
        catalog(movies(50));   // 새 스냅샷 객체 = 06:00 재적재 · 관리자 덮어쓰기 직후

        service.forYou(null, false, null);

        verify(forYouService, times(2)).pool(any(UserPreferenceProfile.class));
    }

    // ── 추천 제외 반영 ─────────────────────────────────────────────

    @Test
    void 현재_묶음에_추천_제외된_영화가_생기면_같은_날이어도_새_묶음을_뽑는다() {
        Batch first = service.forYou(USER, false, null);
        long victim = first.movies().get(0).movieId();

        // 관리자가 그 영화를 BLOCK - 새 스냅샷
        List<MovieFeature> updated = features.stream()
                .map(f -> f.movieId() == victim ? f.withRecommendOverride(RecommendOverride.BLOCK) : f).toList();
        catalog(updated);
        Batch second = service.forYou(USER, false, null);

        assertThat(idsOf(second)).doesNotContain(victim);
        assertThat(idsOf(second)).doesNotContainAnyElementsOf(idsOf(first));   // 같은 묶음을 그대로 돌려주지 않는다
    }

    @Test
    void 풀에_남아_있어도_추천_제외된_영화는_뽑지_않는다() {
        // 풀 계산 이후 인덱스가 바뀐 상황 - 상태의 풀은 낡았지만 추출 단계가 막는다
        service.forYou(USER, false, null);
        List<MovieFeature> updated = features.stream()
                .map(f -> f.movieId() <= 40 ? f.withRecommendOverride(RecommendOverride.BLOCK) : f).toList();
        catalog(updated);

        for (int i = 0; i < 5; i++) {
            assertThat(idsOf(service.forYou(USER, true, null))).allSatisfy(id -> assertThat(id).isGreaterThan(40L));
        }
    }

    // ── 시드 재현 · 디버그 ──────────────────────────────────────────

    @Test
    void 같은_시드와_같은_입력이면_같은_묶음이다() {
        cache = new RecommendationCache(RecommendFixtures.properties());
        List<Long> a = orderedIdsOf(newService(new Random(99)).forYou(null, false, null));
        cache = new RecommendationCache(RecommendFixtures.properties());
        List<Long> b = orderedIdsOf(newService(new Random(99)).forYou(null, false, null));

        assertThat(a).isEqualTo(b);
    }

    @Test
    void 디버그는_풀_50편의_key와_제외_사유를_보여_주되_상태를_바꾸지_않는다() {
        Batch current = service.forYou(USER, false, null);
        Batch second = service.forYou(USER, true, null);   // 이력: [second, current]
        UserPreferenceProfile profile = profileService.build(USER);

        ForYouBatchService.PoolDebug debug = service.debug(USER, profile);

        assertThat(debug.pool().movies()).hasSize(50);
        // 최근 묶음(현재 = 0, 직전 = 1)은 제외 사유로 번호가 붙는다
        assertThat(debug.recentBatchIndex().keySet()).containsAll(idsOf(second)).containsAll(idsOf(current));
        idsOf(second).forEach(id -> assertThat(debug.recentBatchIndex()).containsEntry(id, 0));
        idsOf(current).forEach(id -> assertThat(debug.recentBatchIndex()).containsEntry(id, 1));
        // 제외되지 않은 후보에는 key가 있고, 이번 시뮬레이션으로 10편이 뽑힌다
        assertThat(debug.keys()).hasSize(30);
        assertThat(debug.selected()).hasSize(10).doesNotContainAnyElementsOf(debug.recentBatchIndex().keySet());
        assertThat(debug.recentBatches()).hasSize(2);
        // 상태는 그대로 - 사용자가 다시 들어오면 같은 현재 묶음이다
        assertThat(orderedIdsOf(service.forYou(USER, false, null))).isEqualTo(orderedIdsOf(second));
    }
}
