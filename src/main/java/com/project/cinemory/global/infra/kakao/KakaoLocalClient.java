package com.project.cinemory.global.infra.kakao;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Optional;

/**
 * 카카오 로컬 주소 검색. 지도와 같은 카카오 데이터라 마커가 지도 위 건물에 맞는다(cinemap-spec C-4-4).
 *
 * <p>호출 실패(네트워크·401·한도)와 "결과 없음"은 다르다 — 전자는 {@link KakaoLocalException}으로 던져
 * 호출 쪽이 시드 전체를 멈추게 하고, 후자만 {@code Optional.empty()}다(다음 좌표 경로로 넘어간다).
 */
@Slf4j
@Component
public class KakaoLocalClient {

    private final RestClient restClient;
    private final KakaoLocalProperties properties;

    public KakaoLocalClient(RestClient kakaoLocalRestClient, KakaoLocalProperties properties) {
        this.restClient = kakaoLocalRestClient;
        this.properties = properties;
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    /** 첫 결과의 WGS84 좌표. */
    public Optional<LatLng> searchAddress(String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        try {
            AddressResponse response = restClient.get()
                    .uri(b -> b.path("/v2/local/search/address.json")
                            .queryParam("query", "{q}")
                            .queryParam("size", 1)
                            .build(query))
                    .retrieve()
                    .body(AddressResponse.class);
            if (response == null || response.documents() == null || response.documents().isEmpty()) {
                return Optional.empty();
            }
            Document d = response.documents().get(0);
            // 카카오: x = 경도, y = 위도 (문자열)
            return Optional.of(new LatLng(Double.parseDouble(d.y()), Double.parseDouble(d.x())));
        } catch (RestClientException | NumberFormatException e) {
            log.error("카카오 주소 검색 실패. query={}, cause={}", query, e.getClass().getSimpleName());
            throw new KakaoLocalException("카카오 주소 검색에 실패했습니다.", e);
        }
    }

    public record LatLng(double latitude, double longitude) {
    }

    public static class KakaoLocalException extends RuntimeException {
        public KakaoLocalException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AddressResponse(List<Document> documents) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Document(String x, String y) {
    }
}
