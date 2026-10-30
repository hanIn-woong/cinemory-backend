package com.project.cinemory.global.infra.media;

import org.springframework.stereotype.Component;

/**
 * DB에 저장된 {@code profile_image}를 응답용 URL로 바꾼다 (account-integrity-spec P-2·P-3).
 * 프로필 이미지를 내보내는 DTO를 만드는 곳은 <b>모두</b> 이 변환을 거친다 — 거치지 않으면 S3 키가 그대로 응답에 샌다(B-5-1).
 *
 * <p>{@code http} 분기(제공자 URL 통과)는 P-1이 V37로 제공자 사진을 지운 뒤 제거한다(B-5-3).
 */
@Component
public class ProfileImageUrlResolver {

    private final MediaProperties properties;

    public ProfileImageUrlResolver(MediaProperties properties) {
        this.properties = properties;
    }

    public String resolve(String stored) {
        if (stored == null) {
            return null;
        }
        if (stored.startsWith("http")) {
            return stored;
        }
        return properties.publicBaseUrl() + "/" + stored;
    }
}
