package com.project.cinemory.domain.collection.service;

import com.project.cinemory.domain.collection.dto.AddMoviesToCollectionRequest;
import com.project.cinemory.domain.collection.dto.AddMoviesToCollectionResponse;
import com.project.cinemory.domain.collection.dto.CollectionCreateRequest;
import com.project.cinemory.domain.collection.dto.CollectionMovieListItemResponse;
import com.project.cinemory.domain.collection.dto.CollectionResponse;
import com.project.cinemory.domain.collection.dto.CollectionUpdateRequest;
import com.project.cinemory.domain.collection.entity.Collection;
import com.project.cinemory.domain.collection.entity.CollectionMovie;
import com.project.cinemory.domain.collection.repository.CollectionMovieCountProjection;
import com.project.cinemory.domain.collection.repository.CollectionMovieRepository;
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
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CollectionService {

    private final CollectionRepository collectionRepository;
    private final CollectionMovieRepository collectionMovieRepository;
    private final MovieRepository movieRepository;
    private final UserRepository userRepository;
    private final MovieDirectorRepository movieDirectorRepository;
    private final CommentRepository commentRepository;
    private final UserAccessPolicy userAccessPolicy;

    @Transactional
    public CollectionResponse createCollection(Long userId, CollectionCreateRequest request) {
        Collection collection = Collection.of(
                userRepository.getReferenceById(userId), request.name(), request.description());
        collectionRepository.save(collection);
        return CollectionResponse.from(collection, 0L);
    }

    @Transactional
    public CollectionResponse updateCollection(Long userId, Long collectionId, CollectionUpdateRequest request) {
        Collection collection = getOwnedCollectionOrThrow(userId, collectionId);
        collection.update(request.name(), request.description());

        long movieCount = collectionMovieRepository.countGroupByCollectionIdIn(List.of(collectionId)).stream()
                .findFirst()
                .map(CollectionMovieCountProjection::getCount)
                .orElse(0L);
        return CollectionResponse.from(collection, movieCount);
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

    /** 컬렉션 목록 — 타인의 프로필에서도 호출되므로 공개범위 검증이 선행된다. */
    public Page<CollectionResponse> getCollections(Long viewerId, Long targetUserId, Pageable pageable) {
        userAccessPolicy.validateCanView(viewerId, targetUserId);

        Page<Collection> collectionPage = collectionRepository.findByUserId(targetUserId, pageable);
        List<Long> collectionIds = collectionPage.getContent().stream()
                .map(Collection::getId)
                .toList();

        Map<Long, Long> movieCountByCollectionId = collectionMovieRepository.countGroupByCollectionIdIn(collectionIds).stream()
                .collect(Collectors.toMap(
                        CollectionMovieCountProjection::getCollectionId,
                        CollectionMovieCountProjection::getCount
                ));

        return collectionPage.map(collection -> CollectionResponse.from(
                collection,
                movieCountByCollectionId.getOrDefault(collection.getId(), 0L)
        ));
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

        List<CollectionMovie> toAdd = movies.stream()
                .filter(movie -> !existingMovieIds.contains(movie.getId()))
                .map(movie -> CollectionMovie.of(collection, movie))
                .toList();

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
     */
    public Page<CollectionMovieListItemResponse> getCollectionMovies(Long viewerId, Long collectionId, Pageable pageable) {
        Long ownerId = collectionRepository.findOwnerIdById(collectionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COLLECTION_NOT_FOUND));
        userAccessPolicy.validateCanView(viewerId, ownerId);

        Page<CollectionMovie> collectionMoviePage = collectionMovieRepository.findByCollectionId(collectionId, pageable);
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

    private Collection getOwnedCollectionOrThrow(Long userId, Long collectionId) {
        Collection collection = collectionRepository.findById(collectionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COLLECTION_NOT_FOUND));
        if (!collection.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.COLLECTION_ACCESS_DENIED);
        }
        return collection;
    }
}
