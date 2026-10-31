package com.project.cinemory.domain.ott.dto;

import com.project.cinemory.domain.ott.entity.OttPlatform;

/**
 * OTT 플랫폼 응답 — 목록 API와 시청 기록 응답이 함께 쓴다(controller 잔여 #20: {@code domain/watch/dto}의 같은 이름 복사본을 4-3-B에서 지웠다).
 * 같은 이름의 DTO가 두 벌이면 Springdoc이 스키마를 하나로 합쳐 필드를 한쪽에만 넣을 때 계약이 틀어진다.
 *
 * @param custom 직접 입력 플랫폼('기타')이면 true — 앱이 서비스 이름 입력칸을 보이고, 이름은 {@code placeDetail}에 저장한다
 */
public record OttPlatformResponse(Long id, String name, boolean custom) {

    public static OttPlatformResponse from(OttPlatform ottPlatform) {
        return new OttPlatformResponse(ottPlatform.getId(), ottPlatform.getName(), ottPlatform.isCustom());
    }
}
