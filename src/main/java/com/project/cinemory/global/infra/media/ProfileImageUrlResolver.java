package com.project.cinemory.global.infra.media;

import org.springframework.stereotype.Component;

/**
 * DB에 저장된 {@code profile_image}를 응답용 URL로 바꾼다 (account-integrity-spec P-2·P-3).
 * 프로필 이미지를 내보내는 DTO를 만드는 곳은 <b>모두</b> 이 변환을 거친다 — 거치지 않으면 S3 키가 그대로 응답에 샌다(B-5-1).
 *
 * <p>V37이 제공자 사진을 지웠고 가입도 더는 가져오지 않으므로(PR-9 · PR-11) 저장값은 {@code null} 또는 우리 키뿐이다 —
 * 제공자 URL을 그대로 통과시키던 {@code http} 분기는 없앴다(B-5-3).
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
        return properties.publicBaseUrl() + "/" + stored;
    }
}
