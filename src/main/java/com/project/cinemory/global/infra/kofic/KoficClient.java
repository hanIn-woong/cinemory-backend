package com.project.cinemory.global.infra.kofic;

import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.kofic.dto.KoficDailyBoxOfficeResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Component
public class KoficClient {

    private static final DateTimeFormatter TARGET_DT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final RestClient restClient;
    private final KoficProperties properties;

    public KoficClient(RestClient koficRestClient, KoficProperties properties) {
        this.restClient = koficRestClient;
        this.properties = properties;
    }

    /**
     * 일별 박스오피스 조회.
     * 호출 실패는 {@link ErrorCode#EXTERNAL_API_ERROR}로 감싸 던진다.
     * (스케줄러 진입점에서 잡아 로깅만 하고 삼킨다 — 수집 실패가 전체로 전파되면 안 되므로)
     */
    public List<KoficDailyBoxOfficeResponse.DailyBoxOffice> fetchDailyBoxOffice(LocalDate targetDate) {
        try {
            KoficDailyBoxOfficeResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/boxoffice/searchDailyBoxOfficeList.json")
                            .queryParam("key", properties.apiKey())
                            .queryParam("targetDt", targetDate.format(TARGET_DT))
                            .build())
                    .retrieve()
                    .body(KoficDailyBoxOfficeResponse.class);

            if (response == null) {
                throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR, "KOFIC 응답이 비어 있습니다.");
            }
            return response.items();

        } catch (RestClientException e) {
            log.error("KOFIC 일별 박스오피스 조회 실패. targetDate={}", targetDate, e);
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR, "KOFIC 박스오피스 조회에 실패했습니다.");
        }
    }
}
