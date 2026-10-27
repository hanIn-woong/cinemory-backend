package com.project.cinemory.domain.theater.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 극장 시드 결과 보고(cinemap-spec C-4-6). dryRun이든 아니든 같은 모양이며, 저장 건수({@code inserted}/{@code updated})는
 * dryRun이면 {@code null}이다. 재동기화 전 검증 도구로 계속 쓴다.
 */
public record TheaterSeedReport(
        boolean dryRun,
        int totalRows,                              // 원천 전체 행(폐업 포함, 중복 제거 후)
        int duplicateRows,                          // 같은 OPN_ATMY_GRP_CD+MNG_NO가 또 온 행
        Map<String, Integer> salesStatusCounts,     // SALS_STTS_CD별
        int activeRows,                             // 영업 중(01) 행
        Map<String, Integer> shapeCounts,           // 영업 중 행의 PRFRPLC_SHP_SE_NM별
        Map<String, List<String>> shapeExamples,    // 영업 형태별 사업장명 예시(최대 5) — 빈 값이 무엇인지 보려고
        int excludedByShape,                        // 영업 형태 허용 목록에 걸려 빠진 행
        int theaterCount,                           // 묶은 극장 수
        Map<String, Integer> chainCounts,           // 체인별 극장 수
        List<String> otherChainNames,               // 체인 '기타'로 판정된 극장 상위 100(관 수 순) — 사전 보완용
        List<SplitNameGroup> splitNameGroups,       // 정규화 후에도 이름이 갈린 그룹 상위 50
        int splitNameGroupTotal,
        List<MultiChainGroup> multiChainGroups,     // 체인이 둘 이상인 그룹
        Map<Integer, Integer> screenCountDistribution, // 관 수 → 극장 수
        Coordinates coordinates,
        List<SampleTheater> samples,                // 샘플 20개
        // ── C-5-4 동기화 보고. dryRun이면 "실행하면 이렇게 된다"는 예상 수다
        int newTheaters,                            // 새로 생긴 극장
        int closedTheaters,                         // 숨긴(숨길) 극장
        int reopenedTheaters,                       // 복구한(복구할) 극장 — 원천 흔들림 감지
        int massCloseHeld,                          // 대량 숨김 방지로 보류한 건수(0이면 장치 미발동)
        List<ClosedWithBooking> closedWithBookingUrl, // 예매 링크가 있는데 숨겨진 극장 — 관리자 확인 대상
        boolean aborted,                            // 원천 수신이 불완전해 아무것도 반영하지 않았다
        String abortReason,
        int geocodeReused,                          // 저장된 좌표를 재사용해 카카오를 부르지 않은 극장 수
        int geocodeCalled,                          // 카카오 주소 검색을 부른 극장 수
        Integer inserted,                           // dryRun이면 null
        Integer updated
) {

    /** 수신이 불완전할 때의 보고서 — 분석 없이 수신 상태만 담는다(C-5-2 ①). */
    public static TheaterSeedReport aborted(boolean dryRun, int totalRows, int duplicateRows, String reason) {
        return new TheaterSeedReport(dryRun, totalRows, duplicateRows, Map.of(), 0, Map.of(), Map.of(), 0, 0, Map.of(),
                List.of(), List.of(), 0, List.of(), Map.of(),
                new Coordinates(0, 0, 0, 0, 0, new TmCheck(0, 0, 0, 0, List.of()), List.of()),
                List.of(), 0, 0, 0, 0, List.of(), true, reason, 0, 0, null, null);
    }

    public record ClosedWithBooking(Long theaterId, String sourceCode, String name, String bookingUrl) {
    }

    public record SplitNameGroup(String sourceCode, int rows, Map<String, Integer> names, String chosen) {
    }

    public record MultiChainGroup(String sourceCode, Map<String, Integer> chains, List<String> names) {
    }

    public record Coordinates(
            int address,                            // 도로명 주소 검색
            int lot,                                // 지번 주소 검색
            int tm,                                 // EPSG:5174 변환
            int failed,
            int kakaoErrors,                        // 호출 자체가 실패한 횟수(결과 없음과 구분)
            TmCheck tmCheck,
            List<FailedGroup> failures
    ) {
    }

    /** 주소 검색으로 정한 좌표와 TM 변환 좌표의 차이 — 변환 파라미터가 맞는지 대량으로 확인한다. */
    public record TmCheck(int compared, double avgMeters, double maxMeters, int over100m, List<Outlier> outliers) {
    }

    /** 차이가 큰 상위 극장 — 원천 TM 좌표가 틀렸는지, 주소 검색이 다른 건물을 잡았는지 눈으로 가리는 용도. */
    public record Outlier(String sourceCode, String name, double meters) {
    }

    public record FailedGroup(String sourceCode, String name, String roadAddress, String lotAddress) {
    }

    public record SampleTheater(String sourceCode, String name, String chainName, String address,
                                BigDecimal latitude, BigDecimal longitude, int screenCount, String coordinateSource) {
    }
}
