package com.project.cinemory.domain.theater.service;

import com.project.cinemory.domain.theater.dto.AdminTheaterResponse;
import com.project.cinemory.domain.theater.repository.TheaterRepository;
import com.project.cinemory.global.dto.LikePattern;
import com.project.cinemory.global.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 극장 목록(cinemap-spec C-8-2) — 이름·주소 검색 + 체인·예매 링크·폐업 필터. 숨긴 극장도 포함한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminTheaterQueryService {

    private final TheaterRepository theaterRepository;

    /**
     * @param chain         null·빈 문자열이면 전체
     * @param hasBookingUrl null이면 전체, true면 링크 있음, false면 없음
     * @param closed        null이면 전체, true면 숨긴 극장만, false면 영업 중만
     */
    public PageResponse<AdminTheaterResponse> getTheaters(String query, String chain, Boolean hasBookingUrl,
                                                          Boolean closed, int page, int size) {
        String chainFilter = chain == null || chain.isBlank() ? null : chain.trim();
        return PageResponse.from(theaterRepository
                .searchForAdmin(LikePattern.contains(query), chainFilter, mode(hasBookingUrl), mode(closed),
                        PageRequest.of(page, size, Sort.by("name").ascending().and(Sort.by("id"))))
                .map(AdminTheaterResponse::from));
    }

    /** null → 0(전체), true → 1, false → 2 */
    private static int mode(Boolean flag) {
        return flag == null ? 0 : (flag ? 1 : 2);
    }
}
