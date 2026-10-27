package com.project.cinemory.global.infra.publicdata.dto;

/**
 * 영화상영관 조회서비스 1행 = 상영관(관) 하나(cinemap-spec C-3-1 ①). 쓰는 필드만 담는다(C-3-2).
 * 좌표는 EPSG:5174 원본 문자열 그대로 — 빈 값("")이 있다.
 */
public record TheaterSourceRow(
        String opnAtmyGrpCd,
        String mngNo,
        String bplcNm,
        String salsSttsCd,
        String prfrplcShpSeNm,
        String roadNmAddr,
        String lotnoAddr,
        String crdInfoX,
        String crdInfoY
) {
}
