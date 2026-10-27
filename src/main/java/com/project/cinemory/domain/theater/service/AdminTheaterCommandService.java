package com.project.cinemory.domain.theater.service;

import com.project.cinemory.domain.admin.entity.AdminAction;
import com.project.cinemory.domain.admin.entity.AdminTargetType;
import com.project.cinemory.domain.admin.service.AdminAuditService;
import com.project.cinemory.domain.theater.dto.AdminTheaterResponse;
import com.project.cinemory.domain.theater.entity.Theater;
import com.project.cinemory.domain.theater.repository.TheaterRepository;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.security.resolver.AuditActor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 극장 예매 링크 변경(cinemap-spec C-8-2). DB 쓰기라 변경과 감사 로그를 <b>한 트랜잭션</b>에서 남긴다(admin-spec 11-3).
 * 값이 같으면 저장도 기록도 하지 않고 그대로 돌려준다(멱등).
 */
@Service
@RequiredArgsConstructor
public class AdminTheaterCommandService {

    public static final int BOOKING_URL_MAX_LENGTH = 500;

    private final TheaterRepository theaterRepository;
    private final AdminAuditService adminAuditService;

    @Transactional
    public AdminTheaterResponse changeBookingUrl(Long theaterId, String bookingUrl, AuditActor actor) {
        Theater theater = theaterRepository.findById(theaterId)
                .orElseThrow(() -> new BusinessException(ErrorCode.THEATER_NOT_FOUND));
        if (bookingUrl != null) {
            validate(bookingUrl);
        }

        String before = theater.getBookingUrl();
        if (!Objects.equals(before, bookingUrl)) {
            theater.updateBookingUrl(bookingUrl);
            // null까지 명시한다 — "처음 입력"(before=null)과 "삭제"(after=null)를 로그에서 구별해야 한다
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("before", before);
            detail.put("after", bookingUrl);
            adminAuditService.record(actor, AdminAction.THEATER_BOOKING_URL_UPDATE, AdminTargetType.THEATER, theaterId, detail);
        }
        return AdminTheaterResponse.from(theater);
    }

    /** https:// 로 시작 · 최대 500자 · 공백(앞뒤·중간 모두) 불가 · 호스트가 있는 URI. 위반은 {@code INVALID_BOOKING_URL}(400). */
    static void validate(String url) {
        if (url.length() > BOOKING_URL_MAX_LENGTH || !url.startsWith("https://") || url.chars().anyMatch(Character::isWhitespace)) {
            throw new BusinessException(ErrorCode.INVALID_BOOKING_URL);
        }
        try {
            String host = URI.create(url).getHost();
            if (host == null || host.isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_BOOKING_URL);
            }
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_BOOKING_URL);
        }
    }
}
