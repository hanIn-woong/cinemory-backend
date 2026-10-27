package com.project.cinemory.domain.theater;

import com.project.cinemory.domain.theater.dto.BookingSource;
import com.project.cinemory.domain.theater.entity.Theater;
import com.project.cinemory.domain.theater.service.TheaterBookingResolver;
import com.project.cinemory.domain.theater.service.TheaterNormalizer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 체인 기본 예매 URL 설정이 <b>실제 application.yml에서 네 체인 전부</b> 읽히는지 확인한다.
 * 운영 첫 실행에서 한글 키(롯데시네마·씨네Q)가 읽히지 않아 CGV만 링크가 나왔다 — 링크 판정 단위 테스트가 CGV 하나만 봐서 놓쳤다.
 */
@SpringBootTest
class TheaterChainBookingConfigTest {

    @Autowired
    private TheaterBookingResolver resolver;

    private static Theater theaterOf(String chain) {
        return Theater.builder().sourceCode("x|" + chain).name(chain + " 테스트").chainName(chain).address("서울")
                .latitude(new BigDecimal("37.5")).longitude(new BigDecimal("127.0")).build();
    }

    @Test
    void 판정하는_체인_네_곳_모두_기본_예매_URL이_설정되어_있다() {
        List<String> chains = List.of("CGV", "롯데시네마", "메가박스", "씨네Q");

        for (String chain : chains) {
            TheaterBookingResolver.Booking booking = resolver.resolve(theaterOf(chain));
            assertThat(booking.source()).as("체인 %s", chain).isEqualTo(BookingSource.CHAIN);
            assertThat(booking.url()).as("체인 %s", chain).startsWith("https://");
        }
    }

    @Test
    void 설정_키는_TheaterNormalizer가_판정하는_체인명과_같다() {
        // 체인명 판정이 바뀌어 설정 키와 어긋나면 링크가 조용히 사라진다 — 판정 결과를 키로 그대로 쓴다
        for (String name : List.of("CGV 명동", "롯데시네마 에비뉴엘", "메가박스 코엑스", "씨네Q 신도림")) {
            String chain = TheaterNormalizer.chainOf(name);
            assertThat(resolver.resolve(theaterOf(chain)).source()).as("%s → %s", name, chain).isEqualTo(BookingSource.CHAIN);
        }
    }

    @Test
    void 기타는_체인_링크가_없다() {
        assertThat(resolver.resolve(theaterOf("기타")).source()).isNull();
    }
}
