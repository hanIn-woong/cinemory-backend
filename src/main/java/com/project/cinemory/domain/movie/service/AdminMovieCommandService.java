package com.project.cinemory.domain.movie.service;

import com.project.cinemory.domain.admin.entity.AdminAction;
import com.project.cinemory.domain.admin.entity.AdminTargetType;
import com.project.cinemory.domain.admin.service.AdminAuditService;
import com.project.cinemory.domain.movie.dto.AdminMovieDetailResponse;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.security.resolver.AuditActor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 영화 한글 제목 변경(admin-spec 12-B-3). DB 쓰기가 있는 동작이라 <b>한 트랜잭션</b>에서 변경과 감사 로그를 함께 남긴다 —
 * 둘 다 커밋되거나 둘 다 롤백된다(11-3). 값이 같으면 저장도 기록도 하지 않고 그대로 돌려준다(멱등).
 * 응답은 조회 서비스({@link AdminMovieQueryService#getMovie})로 다시 읽어 상세 화면이 그대로 상태를 바꿀 수 있게 한다.
 */
@Service
@RequiredArgsConstructor
public class AdminMovieCommandService {

    private final MovieRepository movieRepository;
    private final AdminMovieQueryService adminMovieQueryService;
    private final AdminAuditService adminAuditService;

    @Transactional
    public AdminMovieDetailResponse changeTitleKo(Long movieId, String titleKo, AuditActor actor) {
        Movie movie = movieRepository.findById(movieId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MOVIE_NOT_FOUND));

        String before = movie.getTitleKo();
        movie.changeTitleKo(titleKo);
        String after = movie.getTitleKo();

        if (!Objects.equals(before, after)) {
            // before/after를 null까지 명시한다 — "처음 입력"(before=null)과 "해제"(after=null)를 로그에서 구별해야 한다
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("before", before);
            detail.put("after", after);
            adminAuditService.record(actor, AdminAction.MOVIE_TITLE_KO_UPDATE, AdminTargetType.MOVIE, movieId, detail);
        }
        // 같은 트랜잭션 안이라 방금 바꾼 값이 보인다(조회 서비스는 readOnly 트랜잭션에 합류)
        return adminMovieQueryService.getMovie(movieId);
    }
}
