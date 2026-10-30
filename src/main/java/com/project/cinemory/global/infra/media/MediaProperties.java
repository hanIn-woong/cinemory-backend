package com.project.cinemory.global.infra.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 미디어(프로필 사진) 저장소 설정 — account-integrity-spec B-3. 비밀이 없다: 버킷 이름과 공개 주소뿐이고,
 * 자격 증명은 SDK 기본 체인(운영 EC2 인스턴스 역할 · 로컬 {@code ~/.aws})이 맡는다(P-10).
 *
 * @param publicBaseUrl CloudFront 주소. 끝 슬래시는 없앤다. 비어 있으면 우리 키를 URL로 바꿀 수 없다
 */
@ConfigurationProperties(prefix = "cinemory.media")
public record MediaProperties(String region, String bucket, String publicBaseUrl, Duration uploadUrlTtl,
                              long profileMaxBytes) {

    public MediaProperties {
        publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.strip().replaceAll("/+$", "");
        bucket = bucket == null ? "" : bucket.strip();
    }

    public boolean isConfigured() {
        return !bucket.isEmpty();
    }
}
