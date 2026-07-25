package com.project.cinemory.domain.watch.dto;

import com.project.cinemory.domain.ott.entity.OttPlatform;

public record OttPlatformResponse(Long id, String name) {

    public static OttPlatformResponse from(OttPlatform ottPlatform) {
        return new OttPlatformResponse(ottPlatform.getId(), ottPlatform.getName());
    }
}
