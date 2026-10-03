package com.project.cinemory.domain.watch.service;

import com.project.cinemory.domain.movie.dto.CountryResponse;
import com.project.cinemory.domain.movie.dto.GenreResponse;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieCountryRepository;
import com.project.cinemory.domain.movie.repository.MovieGenreRepository;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.ott.entity.OttPlatform;
import com.project.cinemory.domain.ott.repository.OttPlatformRepository;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.domain.watch.dto.RecordSort;
import com.project.cinemory.domain.watch.dto.UserMovieListItemResponse;
import com.project.cinemory.domain.watch.dto.WatchRecordCreateRequest;
import com.project.cinemory.domain.watch.dto.WatchRecordResponse;
import com.project.cinemory.domain.watch.dto.WatchRecordUpdateRequest;
import com.project.cinemory.domain.watch.entity.WatchRecord;
import com.project.cinemory.domain.watch.entity.WatchType;
import com.project.cinemory.domain.watch.repository.WatchRecordRepository;
import com.project.cinemory.domain.wish.repository.WishMovieRepository;
import com.project.cinemory.global.access.UserAccessPolicy;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WatchRecordService {

    private final WatchRecordRepository watchRecordRepository;
    private final UserRepository userRepository;
    private final MovieRepository movieRepository;
    private final OttPlatformRepository ottPlatformRepository;
    private final MovieGenreRepository movieGenreRepository;
    private final MovieCountryRepository movieCountryRepository;
    private final UserAccessPolicy userAccessPolicy;
    private final WishMovieRepository wishMovieRepository;

    @Transactional
    public WatchRecordResponse addWatchRecord(Long userId, WatchRecordCreateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        Movie movie = movieRepository.findById(request.movieId())
                .orElseThrow(() -> new BusinessException(ErrorCode.MOVIE_NOT_FOUND));

        validateWatchTypeConsistency(request.watchType(), request.ottPlatformId());

        OttPlatform ottPlatform = request.ottPlatformId() != null
                ? ottPlatformRepository.findById(request.ottPlatformId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.OTT_PLATFORM_NOT_FOUND))
                : null;

        unmarkCurrentRepresentative(userId, movie.getId());

        WatchRecord watchRecord = WatchRecord.builder()
                .user(user)
                .movie(movie)
                .watchDate(request.watchDate())
                .watchType(request.watchType())
                .placeDetail(request.placeDetail())
                .ottPlatform(ottPlatform)
                .rating(request.rating())
                .privateReview(request.privateReview())
                .build();
        watchRecord.markAsRepresentative();
        WatchRecord saved = watchRecordRepository.save(watchRecord);

        // 봤으니 찜에서 뺀다(2026-10-03, service-layer-spec 4-3) — 같은 트랜잭션이라 기록만 남고 찜이 남는 일이 없다.
        // 찜이 없으면 아무 일도 하지 않는다. 기록이 있는 영화를 나중에 찜하는 것은 막지 않는다 — 다음 기록이 생기면
        // 그때 다시 빠진다. 수정·삭제·대표 변경은 "새로 봤다"가 아니므로 찜을 건드리지 않는다.
        wishMovieRepository.findByUserIdAndMovieId(userId, movie.getId())
                .ifPresent(wishMovieRepository::delete);

        return WatchRecordResponse.from(saved);
    }

    @Transactional
    public WatchRecordResponse updateWatchRecord(Long userId, Long watchRecordId, WatchRecordUpdateRequest request) {
        WatchRecord watchRecord = findWatchRecordOrThrow(watchRecordId);
        validateOwner(watchRecord, userId);

        validateWatchTypeConsistency(request.watchType(), request.ottPlatformId());

        OttPlatform ottPlatform = request.ottPlatformId() != null
                ? ottPlatformRepository.findById(request.ottPlatformId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.OTT_PLATFORM_NOT_FOUND))
                : null;

        watchRecord.update(request.watchDate(), request.watchType(), request.placeDetail(),
                ottPlatform, request.rating(), request.privateReview());

        return WatchRecordResponse.from(watchRecord);
    }

    @Transactional
    public void deleteWatchRecord(Long userId, Long watchRecordId) {
        WatchRecord watchRecord = findWatchRecordOrThrow(watchRecordId);
        validateOwner(watchRecord, userId);

        boolean wasRepresentative = watchRecord.isRepresentative();
        Long movieId = watchRecord.getMovie().getId();

        watchRecordRepository.delete(watchRecord);

        if (wasRepresentative) {
            watchRecordRepository.findByUserIdAndMovieIdOrderByIdDesc(userId, movieId).stream()
                    .findFirst()
                    .ifPresent(WatchRecord::markAsRepresentative);
        }
    }

    @Transactional
    public void setRepresentative(Long userId, Long watchRecordId) {
        WatchRecord target = findWatchRecordOrThrow(watchRecordId);
        validateOwner(target, userId);

        if (target.isRepresentative()) {
            return;
        }

        unmarkCurrentRepresentative(userId, target.getMovie().getId());
        target.markAsRepresentative();
    }

    /**
     * "내 영화" 목록 — 타인의 프로필에서도 호출되므로 공개범위 검증이 선행된다.
     * (viewerId == null인 비로그인 조회도 허용, PUBLIC 대상만 통과)
     * <p>
     * 정렬은 {@code sort}만 따른다 — {@code pageable}에 실려 온 클라이언트 자유 {@code sort}는
     * 페이지 번호·크기만 남기고 버린다(5-0-D).
     */
    public Page<UserMovieListItemResponse> getUserMovieList(Long viewerId, Long targetUserId, Pageable pageable,
                                                            RecordSort sort) {
        userAccessPolicy.validateCanView(viewerId, targetUserId);

        Pageable sortedPageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort.toSort());
        Page<WatchRecord> watchRecordPage = watchRecordRepository.findByUserIdAndRepresentativeTrue(targetUserId, sortedPageable);
        List<Long> movieIds = watchRecordPage.getContent().stream()
                .map(watchRecord -> watchRecord.getMovie().getId())
                .toList();

        Map<Long, List<GenreResponse>> genresByMovieId = movieGenreRepository.findByMovieIdIn(movieIds).stream()
                .collect(Collectors.groupingBy(
                        movieGenre -> movieGenre.getMovie().getId(),
                        Collectors.mapping(movieGenre -> GenreResponse.from(movieGenre.getGenre()), Collectors.toList())
                ));

        Map<Long, List<CountryResponse>> countriesByMovieId = movieCountryRepository.findByMovieIdIn(movieIds).stream()
                .collect(Collectors.groupingBy(
                        movieCountry -> movieCountry.getMovie().getId(),
                        Collectors.mapping(movieCountry -> CountryResponse.from(movieCountry.getCountry()), Collectors.toList())
                ));

        return watchRecordPage.map(watchRecord -> UserMovieListItemResponse.from(
                watchRecord,
                genresByMovieId.getOrDefault(watchRecord.getMovie().getId(), List.of()),
                countriesByMovieId.getOrDefault(watchRecord.getMovie().getId(), List.of())
        ));
    }

    /** 특정 영화의 회차별 시청 기록. 타인 조회 가능하므로 공개범위 검증 선행. */
    public List<WatchRecordResponse> getWatchLog(Long viewerId, Long targetUserId, Long movieId) {
        userAccessPolicy.validateCanView(viewerId, targetUserId);

        return watchRecordRepository.findByUserIdAndMovieIdOrderByIdDesc(targetUserId, movieId).stream()
                .map(WatchRecordResponse::from)
                .toList();
    }

    private void unmarkCurrentRepresentative(Long userId, Long movieId) {
        watchRecordRepository.findByUserIdAndMovieIdAndRepresentativeTrue(userId, movieId)
                .ifPresent(WatchRecord::unmarkAsRepresentative);
    }

    private void validateWatchTypeConsistency(WatchType watchType, Long ottPlatformId) {
        if (watchType == WatchType.OTT) {
            if (ottPlatformId == null) {
                throw new BusinessException(ErrorCode.INVALID_WATCH_TYPE_OTT_COMBINATION);
            }
            return;
        }
        // THEATER, ETC, null 모두 ottPlatformId가 있으면 안 됨
        if (ottPlatformId != null) {
            throw new BusinessException(ErrorCode.INVALID_WATCH_TYPE_OTT_COMBINATION);
        }
    }

    private WatchRecord findWatchRecordOrThrow(Long watchRecordId) {
        return watchRecordRepository.findById(watchRecordId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WATCH_RECORD_NOT_FOUND));
    }

    private void validateOwner(WatchRecord watchRecord, Long userId) {
        if (!watchRecord.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.WATCH_RECORD_ACCESS_DENIED);
        }
    }
}
