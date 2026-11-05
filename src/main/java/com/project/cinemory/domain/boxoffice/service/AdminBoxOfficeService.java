package com.project.cinemory.domain.boxoffice.service;

import com.project.cinemory.domain.admin.entity.AdminAction;
import com.project.cinemory.domain.admin.entity.AdminTargetType;
import com.project.cinemory.domain.admin.service.AdminAuditService;
import com.project.cinemory.domain.admin.service.AuditDetail;
import com.project.cinemory.domain.boxoffice.dto.AdminUnlinkedBoxOfficeResponse;
import com.project.cinemory.domain.boxoffice.dto.BoxOfficeLinkResponse;
import com.project.cinemory.domain.boxoffice.dto.BoxOfficeUnlinkResponse;
import com.project.cinemory.domain.boxoffice.repository.BoxOfficeRecordRepository;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.global.dto.PageResponse;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.security.resolver.AuditActor;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 박스오피스 수동 연결(admin-spec 17, DD-3·DD-4). KOFIC 코드 단위로 모든 날짜 행을 한 번에 잇고 영화에도 코드를 심는다.
 * 자동 배치({@code linkByKoficCode})와 달리 충돌은 조용히 넘기지 않고 409로 알린다. 변경과 감사 로그는 한 트랜잭션.
 */
@Service
@RequiredArgsConstructor
public class AdminBoxOfficeService {

    private final BoxOfficeRecordRepository boxOfficeRecordRepository;
    private final MovieRepository movieRepository;
    private final AdminAuditService adminAuditService;

    @Transactional(readOnly = true)
    public PageResponse<AdminUnlinkedBoxOfficeResponse> getUnlinked(int page, int size) {
        return PageResponse.from(boxOfficeRecordRepository
                .findUnlinkedGroups(PageRequest.of(page, size))
                .map(AdminUnlinkedBoxOfficeResponse::from));
    }

    @Transactional
    public BoxOfficeLinkResponse link(String koficMovieCd, Long movieId, AuditActor actor) {
        if (boxOfficeRecordRepository.countByKoficMovieCdAndMovieIsNull(koficMovieCd) == 0) {
            throw new BusinessException(ErrorCode.BOX_OFFICE_NOT_FOUND);
        }
        Movie movie = movieRepository.findById(movieId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MOVIE_NOT_FOUND));

        // DD-4: 영화에 다른 코드가 있거나, 이 코드가 다른 영화에 붙어 있으면 막는다
        String existing = movie.getKoficMovieCd();
        if (existing != null && !existing.equals(koficMovieCd)) {
            throw new BusinessException(ErrorCode.KOFIC_CODE_CONFLICT);
        }
        movieRepository.findByKoficMovieCd(koficMovieCd)
                .filter(holder -> !holder.getId().equals(movie.getId()))
                .ifPresent(holder -> {
                    throw new BusinessException(ErrorCode.KOFIC_CODE_CONFLICT);
                });

        if (existing == null) {
            movie.linkKoficCode(koficMovieCd);
        }
        int linked = boxOfficeRecordRepository.linkUnlinkedByKoficMovieCd(koficMovieCd, movie);

        adminAuditService.record(actor, AdminAction.BOX_OFFICE_LINK, AdminTargetType.MOVIE, movieId,
                AuditDetail.of("koficMovieCd", koficMovieCd, "movieId", movieId, "linkedRecords", linked));
        return new BoxOfficeLinkResponse(koficMovieCd, movieId, linked);
    }

    @Transactional
    public BoxOfficeUnlinkResponse unlink(String koficMovieCd, AuditActor actor) {
        Long holderId = movieRepository.findByKoficMovieCd(koficMovieCd).map(Movie::getId).orElse(null);
        // 벌크 UPDATE는 영속성 컨텍스트를 비우므로(clearAutomatically) 그 뒤에 영화를 다시 읽어 변경한다
        int unlinked = boxOfficeRecordRepository.unlinkByKoficMovieCd(koficMovieCd);
        if (holderId == null && unlinked == 0) {
            throw new BusinessException(ErrorCode.BOX_OFFICE_NOT_FOUND);
        }
        if (holderId != null) {
            movieRepository.findById(holderId).orElseThrow().unlinkKoficCode();
        }
        adminAuditService.record(actor, AdminAction.BOX_OFFICE_UNLINK,
                holderId == null ? null : AdminTargetType.MOVIE, holderId,
                AuditDetail.of("koficMovieCd", koficMovieCd, "previousMovieId", holderId,
                        "unlinkedRecords", unlinked));
        return new BoxOfficeUnlinkResponse(koficMovieCd, unlinked);
    }
}
