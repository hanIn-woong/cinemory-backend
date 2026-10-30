package com.project.cinemory.domain.feed.service;

import com.project.cinemory.domain.collection.entity.Collection;
import com.project.cinemory.domain.collection.repository.CollectionMovieCountProjection;
import com.project.cinemory.domain.collection.repository.CollectionMovieRepository;
import com.project.cinemory.domain.collection.repository.CollectionPreviewPosterProjection;
import com.project.cinemory.domain.feed.dto.ActivityResponse;
import com.project.cinemory.domain.feed.dto.AddedMovies;
import com.project.cinemory.domain.feed.dto.CollectionDetail;
import com.project.cinemory.domain.feed.dto.FeedUserResponse;
import com.project.cinemory.domain.feed.dto.WatchDetail;
import com.project.cinemory.domain.feed.entity.ActivityType;
import com.project.cinemory.domain.feed.repository.ActivityRow;
import com.project.cinemory.domain.feed.repository.AddedMovieRow;
import com.project.cinemory.domain.feed.repository.FeedQueryRepository;
import com.project.cinemory.domain.follow.entity.Follow;
import com.project.cinemory.domain.movie.dto.MovieSummaryResponse;
import com.project.cinemory.domain.review.entity.Review;
import com.project.cinemory.domain.watch.entity.WatchRecord;
import com.project.cinemory.domain.wish.entity.WishMovie;
import com.project.cinemory.global.access.UserAccessPolicy;
import com.project.cinemory.global.infra.media.ProfileImageUrlResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 활동 행 → 응답 조립 (4-4). 타입별로 나눠 IN절 벌크 조회 → {@code Map} → 원래 순서로 조립한다(프로젝트 표준).
 *
 * <p>조회와 조립 사이에 원천 행이 지워졌으면 <b>조용히 건너뛴다</b>(예외 없음). {@code FOLLOW}는 대상이 보는 사람에게
 * 보일 때만 남긴다 — 그렇지 않으면 PRIVATE 사용자의 존재가 피드로 드러난다. 호출자의 트랜잭션 안에서 돈다.
 */
@Component
@RequiredArgsConstructor
public class ActivityAssembler {

    /** 컬렉션 미리보기·추가분 포스터 최대 장수 (3-3). */
    private static final int POSTER_LIMIT = 4;

    @PersistenceContext
    private EntityManager em;

    private final FeedQueryRepository feedQueryRepository;
    private final CollectionMovieRepository collectionMovieRepository;
    private final UserAccessPolicy userAccessPolicy;
    private final ProfileImageUrlResolver profileImageUrlResolver;

    /**
     * {@code rows}와 같은 순서의 {행 → 응답}. 원천이 사라졌거나 가려야 하는 행은 결과에서 빠진다.
     * 호출자가 사람별로 되돌려 묶을 수 있게 행을 키로 돌려준다(한 번의 벌크 조회로 여러 사람을 조립하기 위해서다).
     */
    public Map<ActivityRow, ActivityResponse> assemble(Long viewerId, List<ActivityRow> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<ActivityType, Set<Long>> idsByType = new HashMap<>();
        for (ActivityRow row : rows) {
            idsByType.computeIfAbsent(row.type(), type -> new HashSet<>()).add(row.sourceId());
        }

        Map<Long, WatchRecord> watchRecords = fetch(WatchRecord.class, "select w from WatchRecord w join fetch w.movie "
                + "left join fetch w.ottPlatform where w.id in :ids", idsByType.get(ActivityType.WATCH_RECORD), WatchRecord::getId);
        Map<Long, Review> reviews = fetch(Review.class,
                "select r from Review r join fetch r.movie where r.id in :ids", idsByType.get(ActivityType.REVIEW), Review::getId);
        Map<Long, WishMovie> wishes = fetch(WishMovie.class,
                "select w from WishMovie w join fetch w.movie where w.id in :ids", idsByType.get(ActivityType.WISH), WishMovie::getId);
        Map<Long, Follow> follows = fetch(Follow.class,
                "select f from Follow f join fetch f.following where f.id in :ids", idsByType.get(ActivityType.FOLLOW), Follow::getId);

        Set<Long> collectionIds = new HashSet<>();
        collectionIds.addAll(idsByType.getOrDefault(ActivityType.COLLECTION_CREATED, Set.of()));
        collectionIds.addAll(idsByType.getOrDefault(ActivityType.COLLECTION_MOVIES_ADDED, Set.of()));
        Map<Long, CollectionDetail> collections = collectionDetails(collectionIds);
        Map<AddedKey, AddedMovies> added = addedMovies(rows);

        Set<Long> followeeIds = follows.values().stream().map(f -> f.getFollowing().getId()).collect(Collectors.toSet());
        Set<Long> viewableFollowees = userAccessPolicy.filterViewable(viewerId, followeeIds);

        Map<ActivityRow, ActivityResponse> result = new LinkedHashMap<>();
        for (ActivityRow row : rows) {
            ActivityResponse response = switch (row.type()) {
                case WATCH_RECORD -> watchRecordResponse(row, watchRecords.get(row.sourceId()));
                case REVIEW -> reviewResponse(row, reviews.get(row.sourceId()));
                case WISH -> wishResponse(row, wishes.get(row.sourceId()));
                case COLLECTION_CREATED -> collectionCreatedResponse(row, collections.get(row.sourceId()));
                case COLLECTION_MOVIES_ADDED -> collectionMoviesAddedResponse(row, collections.get(row.sourceId()),
                        added.get(new AddedKey(row.sourceId(), row.groupDate())));
                case FOLLOW -> followResponse(row, follows.get(row.sourceId()), viewableFollowees);
            };
            if (response != null) {
                result.put(row, response);
            }
        }
        return result;
    }

    private <T> Map<Long, T> fetch(Class<T> type, String jpql, Set<Long> ids, Function<T, Long> idOf) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return em.createQuery(jpql, type).setParameter("ids", ids).getResultList().stream()
                .collect(Collectors.toMap(idOf, Function.identity()));
    }

    /** 컬렉션의 지금 상태 — 기존 컬렉션 목록 쿼리(편수·미리보기 포스터)를 재사용한다. */
    private Map<Long, CollectionDetail> collectionDetails(Set<Long> collectionIds) {
        if (collectionIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = List.copyOf(collectionIds);
        Map<Long, Long> counts = collectionMovieRepository.countGroupByCollectionIdIn(ids).stream()
                .collect(Collectors.toMap(CollectionMovieCountProjection::getCollectionId, CollectionMovieCountProjection::getCount));
        Map<Long, List<String>> posters = new HashMap<>();
        for (CollectionPreviewPosterProjection p : collectionMovieRepository.findPreviewPostersByCollectionIdIn(ids, POSTER_LIMIT)) {
            posters.computeIfAbsent(p.getCollectionId(), id -> new ArrayList<>()).add(p.getPosterPath());
        }
        Map<Long, CollectionDetail> result = new HashMap<>();
        for (Collection collection : em.createQuery("select c from Collection c where c.id in :ids", Collection.class)
                .setParameter("ids", ids).getResultList()) {
            result.put(collection.getId(), new CollectionDetail(collection.getId(), collection.getName(),
                    counts.getOrDefault(collection.getId(), 0L), posters.getOrDefault(collection.getId(), List.of())));
        }
        return result;
    }

    /** (컬렉션, 날짜) 그룹의 편수·포스터 — 컬렉션 id로 한 번에 읽고 날짜로 나눈다. */
    private Map<AddedKey, AddedMovies> addedMovies(List<ActivityRow> rows) {
        List<ActivityRow> addedRows = rows.stream().filter(r -> r.type() == ActivityType.COLLECTION_MOVIES_ADDED).toList();
        if (addedRows.isEmpty()) {
            return Map.of();
        }
        Set<Long> collectionIds = addedRows.stream().map(ActivityRow::sourceId).collect(Collectors.toSet());
        LocalDate min = addedRows.stream().map(ActivityRow::groupDate).min(LocalDate::compareTo).orElseThrow();
        LocalDate max = addedRows.stream().map(ActivityRow::groupDate).max(LocalDate::compareTo).orElseThrow();

        Map<AddedKey, List<AddedMovieRow>> grouped = feedQueryRepository
                .findAddedMovies(collectionIds, min.atStartOfDay(), max.plusDays(1).atStartOfDay()).stream()
                .collect(Collectors.groupingBy(r -> new AddedKey(r.collectionId(), r.addedDate())));
        Map<AddedKey, AddedMovies> result = new HashMap<>();
        grouped.forEach((key, movies) -> result.put(key, new AddedMovies(key.date(), movies.size(),
                movies.stream().map(AddedMovieRow::posterPath).filter(p -> p != null).limit(POSTER_LIMIT).toList())));
        return result;
    }

    private ActivityResponse watchRecordResponse(ActivityRow row, WatchRecord record) {
        if (record == null) {
            return null;
        }
        return new ActivityResponse(row.type(), row.occurredAt(), row.sourceId(),
                MovieSummaryResponse.from(record.getMovie()), WatchDetail.from(record), null, null, null, null);
    }

    private ActivityResponse reviewResponse(ActivityRow row, Review review) {
        if (review == null) {
            return null;
        }
        return new ActivityResponse(row.type(), row.occurredAt(), row.sourceId(),
                MovieSummaryResponse.from(review.getMovie()), null, review.getContent(), null, null, null);
    }

    private ActivityResponse wishResponse(ActivityRow row, WishMovie wish) {
        if (wish == null) {
            return null;
        }
        return new ActivityResponse(row.type(), row.occurredAt(), row.sourceId(),
                MovieSummaryResponse.from(wish.getMovie()), null, null, null, null, null);
    }

    private ActivityResponse collectionCreatedResponse(ActivityRow row, CollectionDetail collection) {
        if (collection == null) {
            return null;
        }
        return new ActivityResponse(row.type(), row.occurredAt(), row.sourceId(), null, null, null, collection, null, null);
    }

    private ActivityResponse collectionMoviesAddedResponse(ActivityRow row, CollectionDetail collection, AddedMovies added) {
        if (collection == null || added == null) {
            return null;
        }
        return new ActivityResponse(row.type(), row.occurredAt(), row.sourceId(), null, null, null, collection, added, null);
    }

    private ActivityResponse followResponse(ActivityRow row, Follow follow, Set<Long> viewableFollowees) {
        if (follow == null || !viewableFollowees.contains(follow.getFollowing().getId())) {
            return null;
        }
        return new ActivityResponse(row.type(), row.occurredAt(), row.sourceId(), null, null, null, null, null,
                FeedUserResponse.from(follow.getFollowing(),
                        profileImageUrlResolver.resolve(follow.getFollowing().getProfileImage())));
    }

    private record AddedKey(Long collectionId, LocalDate date) {
    }
}
