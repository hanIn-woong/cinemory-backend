package com.project.cinemory.global.infra.publicdata;

import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.publicdata.dto.TheaterSourcePage;
import com.project.cinemory.global.infra.publicdata.dto.TheaterSourceRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 행정안전부 영화상영관 조회서비스(공공데이터 15154848) 호출.
 *
 * <p>응답은 {@code response.body.items.item}(배열 또는 단일 객체) 형태를 기본으로 하되, 포털 API마다 층이 조금씩 달라
 * {@code items}가 곧 배열인 경우도 받아 준다. 게이트웨이 오류(키 오류 등)는 JSON이 아닌 XML로 내려오는 일이 있어
 * 본문을 문자열로 받아 직접 파싱한다 — 파싱 실패 시 본문 앞부분을 로그에 남긴다.
 */
@Slf4j
@Component
public class PublicDataTheaterClient {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final RestClient restClient;
    private final PublicDataProperties properties;

    public PublicDataTheaterClient(RestClient publicDataRestClient, PublicDataProperties properties) {
        this.restClient = publicDataRestClient;
        this.properties = properties;
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    /** 한 페이지 조회. 1-based {@code pageNo}. */
    public TheaterSourcePage fetchPage(int pageNo, int numOfRows) {
        String body;
        try {
            // {key} 템플릿 변수로 넘기면 값이 한 번만 인코딩된다(Decoding 키 전제)
            body = restClient.get()
                    .uri(b -> b.path(properties.theaterPath())
                            .queryParam("serviceKey", "{key}")
                            .queryParam("pageNo", pageNo)
                            .queryParam("numOfRows", numOfRows)
                            .queryParam("returnType", "json")
                            .build(Map.of("key", properties.serviceKey())))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            // 예외 메시지에 키가 든 URL이 섞일 수 있어 메시지 대신 종류만 남긴다
            log.error("공공데이터 영화상영관 조회 실패. pageNo={}, cause={}", pageNo, e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR, "공공데이터 영화상영관 조회에 실패했습니다.");
        }
        return parse(body, pageNo);
    }

    static TheaterSourcePage parse(String body, int pageNo) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body == null ? "" : body);
        } catch (RuntimeException e) {
            log.error("공공데이터 응답이 JSON이 아닙니다. pageNo={}, body={}", pageNo, head(body));
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR, "공공데이터 응답을 해석하지 못했습니다.");
        }

        JsonNode response = root.has("response") ? root.get("response") : root;
        JsonNode header = response.path("header");
        String resultCode = text(header, "resultCode");
        if (!resultCode.isEmpty() && !"00".equals(resultCode) && !"0".equals(resultCode)) {
            log.error("공공데이터 오류 응답. pageNo={}, code={}, msg={}", pageNo, resultCode, text(header, "resultMsg"));
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR, "공공데이터 오류 응답: " + resultCode);
        }

        JsonNode bodyNode = response.path("body");
        JsonNode items = bodyNode.path("items");
        JsonNode itemNode = items.has("item") ? items.get("item") : items;

        List<TheaterSourceRow> rows = new ArrayList<>();
        if (itemNode.isArray()) {
            itemNode.forEach(n -> rows.add(toRow(n)));
        } else if (itemNode.isObject() && itemNode.size() > 0) {
            rows.add(toRow(itemNode));
        }
        if (rows.isEmpty() && !bodyNode.has("totalCount")) {
            // 구조를 못 찾은 것과 "정말 0건"을 구분한다
            log.error("공공데이터 응답 구조를 알 수 없습니다. pageNo={}, body={}", pageNo, head(body));
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR, "공공데이터 응답 구조를 알 수 없습니다.");
        }
        return new TheaterSourcePage(parseInt(text(bodyNode, "totalCount")), rows);
    }

    private static TheaterSourceRow toRow(JsonNode n) {
        return new TheaterSourceRow(
                text(n, "OPN_ATMY_GRP_CD"), text(n, "MNG_NO"), text(n, "BPLC_NM"),
                text(n, "SALS_STTS_CD"), text(n, "PRFRPLC_SHP_SE_NM"),
                text(n, "ROAD_NM_ADDR"), text(n, "LOTNO_ADDR"),
                text(n, "CRD_INFO_X"), text(n, "CRD_INFO_Y"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asString("").trim();
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String head(String body) {
        if (body == null) return "";
        return body.length() > 300 ? body.substring(0, 300) : body;
    }
}
