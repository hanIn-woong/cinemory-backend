package com.project.cinemory.domain.theater.dto;

/**
 * 극장 예매 링크 변경 요청(cinemap-spec C-8-2). {@code null} = 삭제. 형식 검증은 서비스가 {@code INVALID_BOOKING_URL}(400)로 한다 —
 * Bean Validation을 쓰면 {@code INVALID_INPUT_VALUE}로 나가 계약의 에러 코드와 달라진다. 빈 문자열은 삭제가 아니라 위반이다.
 */
public record AdminTheaterBookingUrlRequest(String bookingUrl) {
}
