package com.project.cinemory.domain.collection.service;

import com.project.cinemory.domain.collection.dto.AddMoviesToCollectionRequest;
import com.project.cinemory.domain.collection.dto.AddMoviesToCollectionResponse;
import com.project.cinemory.domain.collection.dto.CollectionCreateRequest;
import com.project.cinemory.domain.collection.dto.CollectionMovieListItemResponse;
import com.project.cinemory.domain.collection.dto.CollectionMovieOrderRequest;
import com.project.cinemory.domain.collection.dto.CollectionOrderRequest;
import com.project.cinemory.domain.collection.dto.CollectionResponse;
import com.project.cinemory.domain.collection.dto.CollectionUpdateRequest;
import com.project.cinemory.domain.collection.entity.Collection;
import com.project.cinemory.domain.collection.entity.CollectionMovie;
import com.project.cinemory.domain.collection.repository.CollectionMovieCountProjection;
import com.project.cinemory.domain.collection.repository.CollectionMovieRepository;
import com.project.cinemory.domain.collection.repository.CollectionPreviewPosterProjection;
import com.project.cinemory.domain.collection.repository.CollectionRepository;
import com.project.cinemory.domain.comment.entity.TargetType;
import com.project.cinemory.domain.comment.repository.CommentRepository;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieDirectorRepository;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.global.access.UserAccessPolicy;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CollectionService {

    private static final int PREVIEW_POSTER_LIMIT = 5;

    private final CollectionRepository collectionRepository;
    private final CollectionMovieRepository collectionMovieRepository;
    private final MovieRepository movieRepository;
    private final UserRepository userRepository;
    private final MovieDirectorRepository movieDirectorRepository;
    private final CommentRepository commentRepository;
    private final UserAccessPolicy userAccessPolicy;

    @Transactional
    public CollectionResponse createCollection(Long userId, CollectionCreateRequest request) {
        // 맨 위(MIN-1)에 넣는다 — 만들자마자 보이지 않으면 찾지 못한다(4-5-A)
        Collection collection = Collection.builder()
                .user(userRepository.getReferenceById(userId))
                .name(request.name())
                .description(request.description())
                .position(collectionRepository.findMinPositionByUserId(userId) - 1)
                .build();
        collectionRepository.save(collection);
        return CollectionResponse.from(collection, 0L, List.of());
    }

    @Transactional
    public CollectionResponse updateCollection(Long userId, Long collectionId, CollectionUpdateRequest request) {
        Collection collection = getOwnedCollectionOrThrow(userId, collectionId);
        collection.update(request.name(), request.description());

        long movieCount = collectionMovieRepository.countGroupByCollectionIdIn(List.of(collectionId)).stream()
                .findFirst()
                .map(CollectionMovieCountProjection::getCount)
                .orElse(0L);
        List<String> previewPosterPaths = findPreviewPostersByCollectionId(List.of(collectionId))
                .getOrDefault(collectionId, List.of());
        return CollectionResponse.from(collection, movieCount, previewPosterPaths);
    }

    @Transactional
    public void deleteCollection(Long userId, Long collectionId) {
        Collection collection = getOwnedCollectionOrThrow(userId, collectionId);
        collectionMovieRepository.deleteAllByCollectionId(collectionId);
        // comment.target_id는 다형 참조라 FK가 없어 DB가 정리해주지 않는다.
        // 방치하면 재사용된 AUTO_INCREMENT id에 과거 댓글이 붙어 보이므로 명시적으로 삭제한다.
        commentRepository.deleteByTarget(TargetType.COLLECTION, collectionId);
        collectionRepository.delete(collection);
    }

    /**
     * 컬렉션 목록 — 타인의 프로필에서도 호출되므로 공개범위 검증이 선행된다.
     * <p>
     * 정렬은 사용자 지정 순서(position)로 고정된다 — {@code pageable}의 클라이언트 자유 {@code sort}는
     * 페이지 번호·크기만 남기고 버린다(5-4-A ①, 5-0-D).
     */
    public Page<CollectionResponse> getCollections(Long viewerId, Long targetUserId, Pageable pageable) {
        userAccessPolicy.validateCanView(viewerId, targetUserId);

        Page<Collection> collectionPage = collectionRepository.findByUserIdOrderByPositionAscIdDesc(
                targetUserId, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
        List<Long> collectionIds = collectionPage.getContent().stream()
                .map(Collection::getId)
                .toList();
        // 빈 페이지면 IN ()이 되므로 벌크 쿼리를 건너뛴다(4-5-A)
        if (collectionIds.isEmpty()) {
            return collectionPage.map(collection -> CollectionResponse.from(collection, 0L, List.of()));
        }

        Map<Long, Long> movieCountByCollectionId = collectionMovieRepository.countGroupByCollectionIdIn(collectionIds).stream()
                .collect(Collectors.toMap(
                        CollectionMovieCountProjection::getCollectionId,
                        CollectionMovieCountProjection::getCount
                ));
        Map<Long, List<String>> previewPostersByCollectionId = findPreviewPostersByCollectionId(collectionIds);

        return collectionPage.map(collection -> CollectionResponse.from(
                collection,
                movieCountByCollectionId.getOrDefault(collection.getId(), 0L),
                previewPostersByCollectionId.getOrDefault(collection.getId(), List.of())
        ));
    }

    /**
     * 컬렉션 순서 재작성 — 받은 배열 순서대로 0..N-1. 보낸 집합이 사용자의 컬렉션 전체와
     * 정확히 일치해야 한다(누락·중복·타인 소유 → 400, 5-4-A ②).
     */
    @Transactional
    public void reorderCollections(Long userId, CollectionOrderRequest request) {
        Map<Long, Collection> collectionById = collectionRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(Collection::getId, Function.identity()));

        List<Long> collectionIds = request.collectionIds();
        validateSameSet(collectionIds, collectionById.keySet());

        for (int i = 0; i < collectionIds.size(); i++) {
            collectionById.get(collectionIds.get(i)).changePosition(i);
        }
    }

    /** 컬렉션 안의 영화 순서 재작성 — 규칙은 {@link #reorderCollections}와 같다. */
    @Transactional
    public void reorderCollectionMovies(Long userId, Long collectionId, CollectionMovieOrderRequest request) {
        getOwnedCollectionOrThrow(userId, collectionId);
        Map<Long, CollectionMovie> collectionMovieByMovieId = collectionMovieRepository.findByCollectionId(collectionId).stream()
                .collect(Collectors.toMap(collectionMovie -> collectionMovie.getMovie().getId(), Function.identity()));

        List<Long> movieIds = request.movieIds();
        validateSameSet(movieIds, collectionMovieByMovieId.keySet());

        for (int i = 0; i < movieIds.size(); i++) {
            collectionMovieByMovieId.get(movieIds.get(i)).changePosition(i);
        }
    }

    @Transactional
    public AddMoviesToCollectionResponse addMoviesToCollection(Long userId, Long collectionId, AddMoviesToCollectionRequest request) {
        Collection collection = getOwnedCollectionOrThrow(userId, collectionId);
        List<Long> movieIds = request.movieIds();

        List<Movie> movies = movieRepository.findAllById(movieIds);
        if (movies.size() != movieIds.size()) {
            throw new BusinessException(ErrorCode.MOVIE_NOT_FOUND);
        }

        Set<Long> existingMovieIds = collectionMovieRepository.findByCollectionIdAndMovieIdIn(collectionId, movieIds).stream()
                .map(collectionMovie -> collectionMovie.getMovie().getId())
                .collect(Collectors.toSet());

        // 요청 배열 순서대로 MIN-1, MIN-2, … — 전부 같은 값을 주면 순서가 다시 미지정이 된다(4-5-A)
        Map<Long, Movie> movieById = movies.stream()
                .collect(Collectors.toMap(Movie::getId, Function.identity()));
        int nextPosition = collectionMovieRepository.findMinPositionByCollectionId(collectionId) - 1;
        List<CollectionMovie> toAdd = new ArrayList<>();
        for (Long movieId : movieIds) {
            if (!existingMovieIds.contains(movieId)) {
                toAdd.add(CollectionMovie.of(collection, movieById.get(movieId), nextPosition--));
            }
        }

        collectionMovieRepository.saveAll(toAdd);

        return new AddMoviesToCollectionResponse(toAdd.size(), movieIds.size() - toAdd.size());
    }

    @Transactional
    public void removeMovieFromCollection(Long userId, Long collectionId, Long movieId) {
        getOwnedCollectionOrThrow(userId, collectionId);
        CollectionMovie collectionMovie = collectionMovieRepository.findByCollectionIdAndMovieId(collectionId, movieId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COLLECTION_MOVIE_NOT_FOUND));
        collectionMovieRepository.delete(collectionMovie);
    }

    /**
     * 컬렉션 상세(담긴 영화 목록).
     * 대상 사용자 id를 인자로 받지 않으므로, 소유자 id를 프로젝션 1쿼리로 조회해 공개범위를 검증한다.
     * 정렬은 position 고정이며 클라이언트 자유 {@code sort}는 버린다(5-4-A ①).
     */
    public Page<CollectionMovieListItemResponse> getCollectionMovies(Long viewerId, Long collectionId, Pageable pageable) {
        Long ownerId = collectionRepository.findOwnerIdById(collectionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COLLECTION_NOT_FOUND));
        userAccessPolicy.validateCanView(viewerId, ownerId);

        Page<CollectionMovie> collectionMoviePage = collectionMovieRepository.findByCollectionIdOrderByPositionAscIdDesc(
                collectionId, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
        List<Long> movieIds = collectionMoviePage.getContent().stream()
                .map(collectionMovie -> collectionMovie.getMovie().getId())
                .toList();

        Map<Long, String> directorNamesByMovieId = movieDirectorRepository.findByMovieIdIn(movieIds).stream()
                .collect(Collectors.groupingBy(
                        movieDirector -> movieDirector.getMovie().getId(),
                        Collectors.mapping(movieDirector -> movieDirector.getPerson().getName(), Collectors.joining(", "))
                ));

        return collectionMoviePage.map(collectionMovie -> CollectionMovieListItemResponse.from(
                collectionMovie,
                directorNamesByMovieId.getOrDefault(collectionMovie.getMovie().getId(), "")
        ));
    }

    /** 호출자는 {@code collectionIds}가 비지 않았음을 보장해야 한다(native IN절). 목록 순서는 쿼리의 position ASC를 유지한다. */
    private Map<Long, List<String>> findPreviewPostersByCollectionId(List<Long> collectionIds) {
        return collectionMovieRepository.findPreviewPostersByCollectionIdIn(collectionIds, PREVIEW_POSTER_LIMIT).stream()
                .collect(Collectors.groupingBy(
                        CollectionPreviewPosterProjection::getCollectionId,
                        Collectors.mapping(CollectionPreviewPosterProjection::getPosterPath, Collectors.toList())
                ));
    }

    /** 크기 비교만으로는 중복을 못 잡으므로 Set 크기까지 대조한다(4-5-A). */
    private void validateSameSet(List<Long> requestedIds, Set<Long> actualIds) {
        Set<Long> requestedIdSet = new HashSet<>(requestedIds);
        if (requestedIdSet.size() != requestedIds.size() || !requestedIdSet.equals(actualIds)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private Collection getOwnedCollectionOrThrow(Long userId, Long collectionId) {
        Collection collection = collectionRepository.findById(collectionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COLLECTION_NOT_FOUND));
        if (!collection.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.COLLECTION_ACCESS_DENIED);
        }
        return collection;
    }
}
