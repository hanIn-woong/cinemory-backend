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
import com.project.cinemory.domain.watch.dto.RecordRatingFilter;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
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

        // 회차 관람일 순서(4-3-A) — 대표 처리·찜 삭제보다 먼저. 실패하면 부수효과가 없어야 한다. 날짜가 없으면 쿼리 없이 건너뛴다.
        if (request.watchDate() != null) {
            validateEpisodeDateOrder(
                    watchRecordRepository.findByUserIdAndMovieIdOrderByIdDesc(userId, movie.getId()),
                    null, request.watchDate());
        }

        // 대표는 "처음 기록"이다(2026-10-17, service-layer-spec 4-3) — 영화에 대표가 아직 없을 때만 새 기록이 대표가 된다.
        // 이미 있으면 그대로 두고 새 기록은 비대표로 저장한다. 대표를 바꾸는 방법은 setRepresentative뿐이다.
        // (예전엔 "가장 최근 기록이 대표"라 기존 대표를 해제하고 새 기록을 승격했다.)
        boolean hasRepresentative = watchRecordRepository
                .findByUserIdAndMovieIdAndRepresentativeTrue(userId, movie.getId())
                .isPresent();

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
        if (!hasRepresentative) {
            watchRecord.markAsRepresentative();
        }
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

        // 날짜를 바꿀 때만 판정한다(4-3-A) — 이미 어긋난 기록의 다른 필드(별점·메모 등) 수정을 막지 않는다.
        if (request.watchDate() != null && !request.watchDate().equals(watchRecord.getWatchDate())) {
            validateEpisodeDateOrder(
                    watchRecordRepository.findByUserIdAndMovieIdOrderByIdDesc(userId, watchRecord.getMovie().getId()),
                    watchRecord.getId(), request.watchDate());
        }

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

        // 대표를 지우면 남은 기록 중 **가장 오래된 기록**이 대표가 된다(2026-10-17 — "처음 기록이 대표" 정책과 일치.
        // 예전엔 id 최신이었다). 남은 기록이 없으면 아무 일도 하지 않는다.
        if (wasRepresentative) {
            watchRecordRepository.findFirstByUserIdAndMovieIdOrderByIdAsc(userId, movieId)
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
     * <p>
     * {@code filter}는 대표 기록의 별점 범위·미평가만 WHERE로 더한다(5B.1) — 정렬·페이징·공개범위는 그대로.
     */
    public Page<UserMovieListItemResponse> getUserMovieList(Long viewerId, Long targetUserId, Pageable pageable,
                                                            RecordSort sort, RecordRatingFilter filter) {
        userAccessPolicy.validateCanView(viewerId, targetUserId);

        Page<WatchRecord> watchRecordPage;
        if (sort.isByWatchDate()) {
            // 영화별 관람일 정렬은 쿼리에 박혀 있다 — 정렬 없는 Pageable을 넘긴다(Repository 주석).
            Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
            BigDecimal min = filter.min() == null ? null : BigDecimal.valueOf(filter.min());
            BigDecimal max = filter.max() == null ? null : BigDecimal.valueOf(filter.max());
            watchRecordPage = sort == RecordSort.WATCH_DATE_DESC
                    ? watchRecordRepository.findRepresentativesOrderByLatestWatchDate(targetUserId, min, max, filter.unrated(), unsorted)
                    : watchRecordRepository.findRepresentativesOrderByEarliestWatchDate(targetUserId, min, max, filter.unrated(), unsorted);
        } else {
            Pageable sortedPageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort.toSort());
            if (filter.isEmpty()) {
                watchRecordPage = watchRecordRepository.findByUserIdAndRepresentativeTrue(targetUserId, sortedPageable);
            } else if (filter.unrated()) {
                watchRecordPage = watchRecordRepository.findByUserIdAndRepresentativeTrueAndRatingIsNull(targetUserId, sortedPageable);
            } else {
                watchRecordPage = watchRecordRepository.findByUserIdAndRepresentativeTrueAndRatingBetween(
                        targetUserId, BigDecimal.valueOf(filter.min()), BigDecimal.valueOf(filter.max()), sortedPageable);
            }
        }
        List<Long> movieIds = watchRecordPage.getContent().stream()
                .map(watchRecord -> watchRecord.getMovie().getId())
                .toList();

        // ⚠️ 관람일순일 때만 카드 날짜를 정렬 기준이 된 기록에서 가져온다(2026-10-18) — 정렬 기준과 카드에 보이는 날짜가 같아야 하기 때문이다.
        // 최근 관람일순은 관람일이 가장 늦은 기록, 오래된 관람일순은 가장 이른 기록(같으면 각각 id가 큰/작은 쪽, 관람일이 모두 비면 id가 큰/작은 쪽 —
        // 정렬의 MAX/MIN(watchDate)와 같은 날짜). 다른 정렬에서는 대표 기록의 날짜 그대로이고 이 조회도 하지 않는다.
        // 별점은 어느 정렬이든 대표 기록 그대로. 페이지분만 조회한다.
        Map<Long, WatchRecord> latestByMovieId = !sort.isByWatchDate() ? Map.of()
                : watchRecordRepository.findByUserIdAndMovieIdIn(targetUserId, movieIds).stream()
                        .collect(Collectors.toMap(
                                record -> record.getMovie().getId(),
                                record -> record,
                                sort == RecordSort.WATCH_DATE_DESC ? WatchRecordService::laterRecord : WatchRecordService::earlierRecord));

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
                countriesByMovieId.getOrDefault(watchRecord.getMovie().getId(), List.of()),
                latestByMovieId.getOrDefault(watchRecord.getMovie().getId(), watchRecord)
        ));
    }

    /** 가장 이른 기록 — 관람일이 이른 쪽(NULL은 가장 늦은 것으로 본다), 같으면 id가 작은 쪽. */
    private static WatchRecord earlierRecord(WatchRecord a, WatchRecord b) {
        LocalDate dateA = a.getWatchDate();
        LocalDate dateB = b.getWatchDate();
        if (dateA != null && dateB != null && !dateA.equals(dateB)) return dateA.isBefore(dateB) ? a : b;
        if (dateA == null && dateB != null) return b;
        if (dateA != null && dateB == null) return a;
        return a.getId() < b.getId() ? a : b;
    }

    /** 최근 기록 — 관람일이 늦은 쪽(NULL은 가장 이른 것으로 본다), 같으면 id가 큰 쪽. */
    private static WatchRecord laterRecord(WatchRecord a, WatchRecord b) {
        LocalDate dateA = a.getWatchDate();
        LocalDate dateB = b.getWatchDate();
        if (dateA != null && dateB != null && !dateA.equals(dateB)) return dateA.isAfter(dateB) ? a : b;
        if (dateA == null && dateB != null) return b;
        if (dateA != null && dateB == null) return a;
        return a.getId() > b.getId() ? a : b;
    }

    /** 특정 영화의 회차별 시청 기록. 타인 조회 가능하므로 공개범위 검증 선행. */
    public List<WatchRecordResponse> getWatchLog(Long viewerId, Long targetUserId, Long movieId) {
        userAccessPolicy.validateCanView(viewerId, targetUserId);

        return watchRecordRepository.findByUserIdAndMovieIdOrderByIdDesc(targetUserId, movieId).stream()
                .map(WatchRecordResponse::from)
                .toList();
    }

    /**
     * 같은 사용자·같은 영화의 회차를 {@code id} 순서로 놓았을 때 날짜 있는 회차끼리 관람일이 감소하지 않아야 한다
     * (같은 날 허용, 날짜 없는 회차는 건너뜀 — service-layer-spec 4-3-A). 회차 번호를 id 순서로 세므로 이 규칙은 데이터 의미에 속한다.
     *
     * <p>{@code targetId == null}이면 생성이다: {@code watchDate >= 기존 회차의 MAX(관람일)}.
     * 아니면 수정이다: 앞 id 중 날짜 있는 가장 가까운 회차 {@code <= watchDate <= } 뒤 id 중 날짜 있는 가장 가까운 회차.
     * 두 경로의 기준이 다른 것은 명세 그대로다 — 이미 어긋난 기존 데이터가 있어도 수정은 바로 이웃만 본다.
     */
    private static void validateEpisodeDateOrder(List<WatchRecord> records, Long targetId, LocalDate watchDate) {
        List<WatchRecord> dated = records.stream()
                .filter(r -> r.getWatchDate() != null)
                .sorted(Comparator.comparing(WatchRecord::getId))
                .toList();

        boolean valid;
        if (targetId == null) {
            valid = dated.stream().noneMatch(r -> r.getWatchDate().isAfter(watchDate));
        } else {
            LocalDate previous = dated.stream().filter(r -> r.getId() < targetId)
                    .reduce((first, second) -> second).map(WatchRecord::getWatchDate).orElse(null);
            LocalDate next = dated.stream().filter(r -> r.getId() > targetId)
                    .findFirst().map(WatchRecord::getWatchDate).orElse(null);
            valid = (previous == null || !watchDate.isBefore(previous)) && (next == null || !watchDate.isAfter(next));
        }
        if (!valid) {
            throw new BusinessException(ErrorCode.WATCH_DATE_OUT_OF_ORDER);
        }
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
