package com.project.cinemory.global.infra.publicdata;

import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.infra.publicdata.dto.TheaterSourcePage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicDataTheaterClientParseTest {

    private static final String ROW = """
            {"OPN_ATMY_GRP_CD":"4231000","MNG_NO":"CDFF4220002026000005","BPLC_NM":"롯데시네마 속초 5관",
             "SALS_STTS_CD":"01","PRFRPLC_SHP_SE_NM":"영화관","ROAD_NM_ADDR":"강원 속초시 중앙로121번길 28",
             "LOTNO_ADDR":"","CRD_INFO_X":"339101.58","CRD_INFO_Y":""}""";

    @Test
    void items_item_배열을_읽는다() {
        String json = "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":5244,"
                + "\"items\":{\"item\":[" + ROW + "," + ROW + "]}}}}";

        TheaterSourcePage page = PublicDataTheaterClient.parse(json, 1);

        assertThat(page.totalCount()).isEqualTo(5244);
        assertThat(page.rows()).hasSize(2);
        assertThat(page.rows().get(0).mngNo()).isEqualTo("CDFF4220002026000005");
        assertThat(page.rows().get(0).crdInfoY()).isEmpty();
    }

    @Test
    void item이_단일_객체여도_읽는다() {
        String json = "{\"response\":{\"body\":{\"totalCount\":1,\"items\":{\"item\":" + ROW + "}}}}";

        assertThat(PublicDataTheaterClient.parse(json, 1).rows()).hasSize(1);
    }

    @Test
    void 오류_코드는_예외() {
        String json = "{\"response\":{\"header\":{\"resultCode\":\"30\",\"resultMsg\":\"SERVICE KEY IS NOT REGISTERED\"}}}";

        assertThatThrownBy(() -> PublicDataTheaterClient.parse(json, 1)).isInstanceOf(BusinessException.class);
    }

    @Test
    void JSON이_아니면_예외() {
        assertThatThrownBy(() -> PublicDataTheaterClient.parse("<OpenAPI_ServiceResponse/>", 1))
                .isInstanceOf(BusinessException.class);
    }
}
