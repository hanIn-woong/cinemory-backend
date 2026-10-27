package com.project.cinemory.domain.theater.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TheaterNormalizerTest {

    @Test
    void 주소_기준부는_첫_쉼표_앞이고_괄호는_제거한다() {
        String road = "강원특별자치도 속초시 중앙로121번길 28, 센텀마크빌딩 제엠-301호 (금호동)";
        assertThat(TheaterNormalizer.addressBase(road, "")).isEqualTo("강원특별자치도 속초시 중앙로121번길 28");
    }

    @Test
    void 같은_건물의_층_차이는_같은_기준부로_묶인다() {
        String a = "인천광역시 서구 검단로 100 (원당동)";
        String b = "인천광역시 서구 검단로 100, 18층 (원당동)";
        assertThat(TheaterNormalizer.addressBase(a, "")).isEqualTo(TheaterNormalizer.addressBase(b, ""));
    }

    @Test
    void 도로명이_없으면_지번에서_번지까지() {
        String lot = "강원특별자치도 속초시 금호동 473-235 센텀마크빌딩";
        assertThat(TheaterNormalizer.addressBase("", lot)).isEqualTo("강원특별자치도 속초시 금호동 473-235");
        assertThat(TheaterNormalizer.addressBase(null, lot)).isEqualTo("강원특별자치도 속초시 금호동 473-235");
    }

    @Test
    void 동_이름의_숫자는_번지로_오인하지_않는다() {
        assertThat(TheaterNormalizer.lotAddressBase("서울특별시 강남구 역삼1동 12-3 어딘가"))
                .isEqualTo("서울특별시 강남구 역삼1동 12-3");
    }

    @Test
    void 묶음_키는_자치단체코드와_기준부() {
        assertThat(TheaterNormalizer.groupKey("4231000", "강원 속초시 중앙로 28")).isEqualTo("4231000|강원 속초시 중앙로 28");
    }

    @Test
    void 이름_끝의_관_표기를_뗀다() {
        assertThat(TheaterNormalizer.normalizeName("롯데시네마 속초 3관")).isEqualTo("롯데시네마 속초");
        assertThat(TheaterNormalizer.normalizeName("  CGV  검단   제5상영관 ")).isEqualTo("CGV 검단");
        assertThat(TheaterNormalizer.normalizeName("메가박스 코엑스 IMAX관")).isEqualTo("메가박스 코엑스");
        assertThat(TheaterNormalizer.normalizeName("CGV 용산 4DX관")).isEqualTo("CGV 용산");
        assertThat(TheaterNormalizer.normalizeName("독립극장")).isEqualTo("독립극장");
    }

    @Test
    void 괄호_안_쉼표에서_잘려도_괄호가_남지_않는다() {
        // dryRun에서 발견: "(삼성동, 코엑스)"를 쉼표로 먼저 자르면 "(삼성동"이 주소에 남았다
        assertThat(TheaterNormalizer.addressBase("서울특별시 강남구 영동대로 513 (삼성동, 코엑스)", ""))
                .isEqualTo("서울특별시 강남구 영동대로 513");
        assertThat(TheaterNormalizer.addressBase("서울특별시 강남구 영동대로 513 (삼성동", ""))
                .isEqualTo("서울특별시 강남구 영동대로 513");
    }

    @Test
    void 번지_뒤에_글자가_붙어도_번지에서_자른다() {
        assertThat(TheaterNormalizer.lotAddressBase("경기도 고양시 일산동구 장항동 867외1필지 지상 3,4,5,6층"))
                .isEqualTo("경기도 고양시 일산동구 장항동 867");
    }

    @Test
    void 시도_접두는_둘째_토큰이_시_군일_때만_뗀다() {
        assertThat(TheaterNormalizer.withoutProvince("전남광주통합특별시 광양시 광양읍 덕례리 702"))
                .isEqualTo("광양시 광양읍 덕례리 702");
        assertThat(TheaterNormalizer.withoutProvince("전남광주통합특별시 남구 주월동 126620")).isEmpty();
    }

    @Test
    void 괄호_안_관_표기를_뗀다() {
        assertThat(TheaterNormalizer.normalizeName("롯데시네마 월드타워(제10관)")).isEqualTo("롯데시네마 월드타워");
        assertThat(TheaterNormalizer.normalizeName("롯데시네마 건대입구관 (샤롯데관)")).isEqualTo("롯데시네마 건대입구관");
        assertThat(TheaterNormalizer.normalizeName("CGV인천14 제1관[CGV아트하우스]")).isEqualTo("CGV인천14");
        assertThat(TheaterNormalizer.normalizeName("CGV강변11 4DX")).isEqualTo("CGV강변11");
        assertThat(TheaterNormalizer.normalizeName("메가박스중앙(주) 목동지점 COMFORT")).isEqualTo("메가박스 목동지점");
        assertThat(TheaterNormalizer.normalizeName("롯데컬처웍스(주) 롯데시네마 성남중앙")).isEqualTo("롯데시네마 성남중앙");
        assertThat(TheaterNormalizer.normalizeName("롯데컬처웍스(주) 도곡")).isEqualTo("롯데시네마 도곡");
        assertThat(TheaterNormalizer.normalizeName("(주)씨지브이 화정")).isEqualTo("씨지브이 화정");
        assertThat(TheaterNormalizer.normalizeName("주식회사")).isEqualTo("주식회사");
        assertThat(TheaterNormalizer.normalizeName("CGV센텀시티- IMAX")).isEqualTo("CGV센텀시티");
        // 이름 끝이 우연히 특별관 단어로 끝나는 경우는 건드리지 않는다
        assertThat(TheaterNormalizer.normalizeName("스마트")).isEqualTo("스마트");
        assertThat(TheaterNormalizer.normalizeName("Smart")).isEqualTo("Smart");
        assertThat(TheaterNormalizer.normalizeName("Superstar")).isEqualTo("Superstar");
    }

    @Test
    void 체인_판정() {
        // 법인명이 앞에 붙는 표기 — 첫 dryRun에서 전부 '기타'로 떨어졌던 이름들
        assertThat(TheaterNormalizer.chainOf("CJ CGV 광주용봉")).isEqualTo("CGV");
        assertThat(TheaterNormalizer.chainOf("(주)씨지브이 화정")).isEqualTo("CGV");
        assertThat(TheaterNormalizer.chainOf("롯데컬처웍스(주) 롯데시네마 성남중앙")).isEqualTo("롯데시네마");
        assertThat(TheaterNormalizer.chainOf("주식회사 트리스타 메가박스 천안")).isEqualTo("메가박스");
        assertThat(TheaterNormalizer.chainOf("CINE Q 신도림점")).isEqualTo("씨네Q");
        // 운영 확인에서 발견: 씨네큐브광화문은 독립 예술영화관이라 씨네Q 체인이 아니다
        assertThat(TheaterNormalizer.chainOf("씨네큐브광화문")).isEqualTo("기타");
        assertThat(TheaterNormalizer.chainOf("씨네큐브 광화문")).isEqualTo("기타");
        assertThat(TheaterNormalizer.chainOf("MEGA｜MX4D관")).isEqualTo("메가박스");
        assertThat(TheaterNormalizer.chainOf("시.지.브이오리11")).isEqualTo("CGV");
        assertThat(TheaterNormalizer.chainOf("CGV 속초")).isEqualTo("CGV");
        assertThat(TheaterNormalizer.chainOf("씨지브이 속초")).isEqualTo("CGV");
        assertThat(TheaterNormalizer.chainOf("롯데시네마 속초")).isEqualTo("롯데시네마");
        assertThat(TheaterNormalizer.chainOf("메가박스 코엑스")).isEqualTo("메가박스");
        assertThat(TheaterNormalizer.chainOf("MEGABOX 코엑스")).isEqualTo("메가박스");
        assertThat(TheaterNormalizer.chainOf("씨네큐 신도림")).isEqualTo("씨네Q");
        assertThat(TheaterNormalizer.chainOf("서울극장")).isEqualTo("기타");
    }
}
