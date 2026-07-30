package com.project.cinemory.global.infra.kofic.dto;

import java.util.List;

/**
 * KOBIS 일별 박스오피스 응답.
 *
 * <p>KOBIS는 숫자 항목도 전부 문자열로 내려주므로 원본 그대로 String으로 받고,
 * 변환은 수집 서비스에서 수행한다(파싱 실패를 한 곳에서 통제하기 위함).
 * 응답에 정의되지 않은 필드가 추가돼도 Spring Boot 기본 설정이
 * FAIL_ON_UNKNOWN_PROPERTIES를 끄므로 역직렬화가 깨지지 않는다.
 */
public record KoficDailyBoxOfficeResponse(BoxOfficeResult boxOfficeResult) {

    public record BoxOfficeResult(
            String boxofficeType,
            String showRange,
            List<DailyBoxOffice> dailyBoxOfficeList
    ) {
    }

    public record DailyBoxOffice(
            String rank,
            String rankInten,
            String rankOldAndNew,
            String movieCd,
            String movieNm,
            String openDt,
            String salesAmt,
            String salesShare,
            String audiCnt,
            String audiAcc,
            String scrnCnt,
            String showCnt
    ) {
    }

    public List<DailyBoxOffice> items() {
        if (boxOfficeResult == null || boxOfficeResult.dailyBoxOfficeList() == null) {
            return List.of();
        }
        return boxOfficeResult.dailyBoxOfficeList();
    }
}
