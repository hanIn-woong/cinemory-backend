package com.project.cinemory.domain.feed.service;

import com.project.cinemory.domain.feed.dto.ActivityPageResponse;
import com.project.cinemory.domain.feed.dto.ActivityResponse;
import com.project.cinemory.domain.feed.dto.FeedScope;
import com.project.cinemory.domain.feed.dto.FeedStripResponse;
import com.project.cinemory.domain.feed.dto.FeedStripsResponse;
import com.project.cinemory.domain.feed.dto.FeedUserResponse;
import com.project.cinemory.domain.feed.repository.ActivityRow;
import com.project.cinemory.domain.feed.repository.FeedQueryRepository;
import com.project.cinemory.domain.feed.repository.FrameRow;
import com.project.cinemory.domain.feed.repository.StripRow;
import com.project.cinemory.domain.follow.repository.FollowRepository;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.global.access.UserAccessPolicy;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.media.ProfileImageUrlResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 소셜 피드 (social-feed-spec 3·4절) — 팔로잉 스트립과 개인 티켓 피드. 활동 테이블 없이 조회 시 원천에서 합성한다(SF-3).
 * 하나의 읽기 전용 트랜잭션 안에서 조회와 조립을 끝낸다.
 */
@Service
@RequiredArgsConstructor
public class FeedService {

    /** 팔로잉 스트립에 오를 자격 기간 (SF-6). */
    private static final int FOLLOWING_DAYS = 30;
    /** 한 줄(스트립)에 보이는 프레임 수 (3-1). */
    private static final int FRAME_COUNT = 6;
    static final int STRIPS_DEFAULT_SIZE = 10;
    static final int STRIPS_MAX_SIZE = 20;
    static final int ACTIVITIES_DEFAULT_SIZE = 20;
    static final int ACTIVITIES_MAX_SIZE = 50;

    private final FeedQueryRepository feedQueryRepository;
    private final ActivityAssembler activityAssembler;
    private final UserRepository userRepository;
    private final FollowRepository followRepository;
    private final UserAccessPolicy userAccessPolicy;
    private final StripOrderCache stripOrderCache;
    private final ProfileImageUrlResolver profileImageUrlResolver;

    public FeedStripsResponse getStrips(Long viewerId, FeedScope scope, String cursor, int size, LocalDateTime seenAfter) {
        return getStrips(viewerId, scope, cursor, size, seenAfter, null);
    }

    /**
     * @param followersSeenAfter 있으면 첫 페이지 응답에 {@code newFollowerCount}(이 시각 이후 ~ asOf까지 새로 나를 팔로우한 수)를 싣는다.
     *                           피드의 {@code seenAfter}와는 다른 기준 시각이다(탭을 지나치기만 해도 0이 되면 안 된다, SF-16)
     */
    @Transactional(readOnly = true)
    public FeedStripsResponse getStrips(Long viewerId, FeedScope scope, String cursor, int size, LocalDateTime seenAfter,
                                        LocalDateTime followersSeenAfter) {
        if (scope == FeedScope.ALL) {
            // F-3(전체 스트립) 전까지는 열지 않는다
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        int pageSize = clamp(size, STRIPS_MAX_SIZE);
        FeedCursor decoded = cursor == null ? null : FeedCursor.decodeStrip(cursor);
        boolean firstPage = decoded == null;
        LocalDateTime asOf = firstPage ? newAsOf() : decoded.asOf();
        LocalDateTime since = asOf.minusDays(FOLLOWING_DAYS);

        FeedStripResponse me = firstPage ? meStrip(viewerId, asOf) : null;

        // 첫 페이지는 항상 새로 계산해 캐시에 넣고, 이후 페이지는 캐시(적중)나 기간 경계 쿼리(미스)로 같은 순서를 얻는다(4-2 · 4-2-A)
        Candidates candidates = firstPage
                ? firstPageCandidates(viewerId, since, asOf, pageSize)
                : laterPageCandidates(viewerId, since, asOf, decoded, pageSize);
        boolean hasNext = candidates.rows().size() > pageSize;
        List<StripRow> raw = hasNext ? candidates.rows().subList(0, pageSize) : candidates.rows();
        // 캐시 적중 페이지는 보는 사람 기준 공개 범위를 다시 거른다 — 첫 페이지 이후 PRIVATE로 바뀐 사람·탈퇴자. 줄어든 페이지는 그대로 보낸다
        List<StripRow> page = candidates.fromCache() ? stillViewable(viewerId, raw) : raw;

        List<FeedStripResponse> content = buildStrips(viewerId, page, since, asOf, seenAfter);
        String nextCursor = null;
        if (hasNext) {
            // 커서는 거르기 전 마지막 행 기준 — 걸러진 사람이 마지막이어도 다음 페이지가 같은 자리에서 시작한다
            StripRow last = raw.get(raw.size() - 1);
            nextCursor = FeedCursor.ofStrip(asOf, last.lastActivityAt(), last.userId()).encode();
        }
        // 나를 팔로우한 사실은 내 정보다 — 팔로워의 공개 범위는 따지지 않는다(10-3). 첫 페이지에서만 센다
        Long newFollowerCount = firstPage && followersSeenAfter != null
                ? followRepository.countByFollowingIdAndCreatedAtGreaterThanAndCreatedAtLessThanEqual(viewerId, followersSeenAfter, asOf)
                : null;
        return new FeedStripsResponse(asOf, me, content, nextCursor, hasNext, newFollowerCount);
    }

    @Transactional(readOnly = true)
    public ActivityPageResponse getActivities(Long viewerId, Long userId, String cursor, int size) {
        int pageSize = clamp(size, ACTIVITIES_MAX_SIZE);
        FeedCursor decoded = cursor == null ? null : FeedCursor.decodeActivity(cursor);
        userAccessPolicy.validateCanView(viewerId, userId);

        LocalDateTime asOf = decoded == null ? newAsOf() : decoded.asOf();
        List<ActivityRow> rows = decoded == null
                ? feedQueryRepository.findActivities(userId, asOf, null, null, null, pageSize + 1)
                : feedQueryRepository.findActivities(userId, asOf, decoded.at(), decoded.k(), decoded.id(), pageSize + 1);
        // hasNext는 FOLLOW를 거르기 전 size+1로 판정한다 — 한 페이지가 size보다 적을 수 있다(3-2)
        boolean hasNext = rows.size() > pageSize;
        List<ActivityRow> page = hasNext ? rows.subList(0, pageSize) : rows;

        List<ActivityResponse> content = List.copyOf(activityAssembler.assemble(viewerId, page).values());
        String nextCursor = null;
        if (hasNext) {
            ActivityRow last = page.get(page.size() - 1);
            nextCursor = FeedCursor.ofActivity(asOf, last.occurredAt(), last.type(), last.sourceId(), last.groupDate()).encode();
        }
        return new ActivityPageResponse(asOf, content, nextCursor, hasNext);
    }

    /** 후보 {@code size+1}명 — {@code fromCache}면 페이지 인원의 공개 범위를 다시 확인해야 한다. */
    private record Candidates(List<StripRow> rows, boolean fromCache) {
    }

    /** 대상 전원의 순서를 새로 계산해 캐시에 넣고(4-2-A) 앞 {@code size+1}명을 자른다. */
    private Candidates firstPageCandidates(Long viewerId, LocalDateTime since, LocalDateTime asOf, int pageSize) {
        List<Long> targets = feedQueryRepository.findViewableFollowingIds(viewerId);
        List<StripRow> order = targets.isEmpty() ? List.of() : feedQueryRepository.findStripOrder(targets, since);
        stripOrderCache.put(viewerId, asOf, order);
        return new Candidates(order.subList(0, Math.min(order.size(), pageSize + 1)), false);
    }

    /** 캐시 적중이면 캐시된 순서를 키셋으로 자르고, 미스면 기간 경계 쿼리로 대체한다(느리지만 커서의 asOf 기준으로 정확하다). */
    private Candidates laterPageCandidates(Long viewerId, LocalDateTime since, LocalDateTime asOf, FeedCursor cursor,
                                           int pageSize) {
        List<StripRow> cached = stripOrderCache.get(viewerId, asOf);
        if (cached != null) {
            int from = firstAfter(cached, cursor.at(), cursor.uid());
            return new Candidates(cached.subList(from, Math.min(cached.size(), from + pageSize + 1)), true);
        }
        List<Long> targets = feedQueryRepository.findViewableFollowingIds(viewerId);
        List<StripRow> rows = targets.isEmpty() ? List.of()
                : feedQueryRepository.findStrips(targets, since, asOf, cursor.at(), cursor.uid(), pageSize + 1);
        return new Candidates(rows, false);
    }

    /** 최근순 목록에서 커서 다음 첫 위치 — {@code last < at OR (last = at AND user_id < uid)}. */
    private static int firstAfter(List<StripRow> order, LocalDateTime at, Long uid) {
        for (int i = 0; i < order.size(); i++) {
            StripRow row = order.get(i);
            if (row.lastActivityAt().isBefore(at) || (row.lastActivityAt().isEqual(at) && row.userId() < uid)) {
                return i;
            }
        }
        return order.size();
    }

    private List<StripRow> stillViewable(Long viewerId, List<StripRow> rows) {
        Set<Long> viewable = userAccessPolicy.filterViewable(viewerId, rows.stream().map(StripRow::userId).toList());
        return rows.stream().filter(row -> viewable.contains(row.userId())).toList();
    }

    /** 내 스트립 — 첫 페이지에만, 기간 없음, 활동이 없어도 {@code frames: []}로 싣는다. */
    private FeedStripResponse meStrip(Long viewerId, LocalDateTime asOf) {
        User me = userRepository.findById(viewerId).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        List<FrameRow> frames = feedQueryRepository.findFrames(List.of(viewerId), null, asOf, null, FRAME_COUNT);
        List<ActivityRow> rows = frames.stream().map(FrameRow::activity).toList();
        Map<ActivityRow, ActivityResponse> assembled = activityAssembler.assemble(viewerId, rows);
        List<ActivityResponse> responses = frames.stream()
                .map(frame -> withRecordCount(assembled.get(frame.activity()), frame))
                .filter(r -> r != null).toList();
        LocalDateTime last = rows.isEmpty() ? null : rows.get(0).occurredAt();
        return new FeedStripResponse(FeedUserResponse.from(me, profileImageUrlResolver.resolve(me.getProfileImage())), last, 0, responses);
    }

    private static ActivityResponse withRecordCount(ActivityResponse response, FrameRow frame) {
        return response == null ? null : response.withWatchRecordCount(frame.recordCount());
    }

    private List<FeedStripResponse> buildStrips(Long viewerId, List<StripRow> page, LocalDateTime since, LocalDateTime asOf,
                                                LocalDateTime seenAfter) {
        if (page.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = page.stream().map(StripRow::userId).toList();
        List<FrameRow> frames = feedQueryRepository.findFrames(userIds, since, asOf, seenAfter, FRAME_COUNT);
        Map<ActivityRow, ActivityResponse> assembled = activityAssembler.assemble(viewerId,
                frames.stream().map(FrameRow::activity).toList());

        Map<Long, List<ActivityResponse>> framesByUser = new HashMap<>();
        Map<Long, Integer> newCounts = new HashMap<>();
        for (FrameRow frame : frames) {
            newCounts.put(frame.activity().userId(), frame.newCount());
            ActivityResponse response = withRecordCount(assembled.get(frame.activity()), frame);
            if (response != null) {
                framesByUser.computeIfAbsent(frame.activity().userId(), id -> new ArrayList<>()).add(response);
            }
        }
        Map<Long, User> users = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        List<FeedStripResponse> result = new ArrayList<>(page.size());
        for (StripRow strip : page) {
            User user = users.get(strip.userId());
            List<ActivityResponse> responses = framesByUser.get(strip.userId());
            // 조회~조립 사이에 사라졌거나(탈퇴·삭제) 보여 줄 프레임이 하나도 남지 않은 줄은 건너뛴다
            if (user == null || responses == null) {
                continue;
            }
            result.add(new FeedStripResponse(FeedUserResponse.from(user, profileImageUrlResolver.resolve(user.getProfileImage())), strip.lastActivityAt(),
                    newCounts.getOrDefault(strip.userId(), 0), responses));
        }
        return result;
    }

    /**
     * 첫 페이지 시각. 초 단위로 올림한다 — {@code created_at}이 DATETIME(0)이라 방금 쓴 행이 반올림으로 다음 초에 찍힐 수 있고,
     * 내림하면 그 행이 첫 조회에서 안 보인다.
     */
    private static LocalDateTime newAsOf() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
    }

    private static int clamp(int size, int max) {
        return Math.max(1, Math.min(size, max));
    }
}
