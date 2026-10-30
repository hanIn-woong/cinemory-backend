package com.project.cinemory.global.infra.media;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 객체 저장소 추상 (account-integrity-spec B-3). 운영은 {@link S3MediaStorage}, 테스트는 인메모리 구현을 쓴다 —
 * CI에는 AWS 자격 증명이 없다(B-5-6).
 */
public interface MediaStorage {

    /** {@code Content-Type}을 서명에 포함한 PUT URL. 다른 Content-Type으로 올리면 S3가 거부한다. */
    PresignedUpload presignPut(String key, String contentType, Duration ttl);

    /** 객체 메타데이터. 객체가 없으면 빈 값. */
    Optional<ObjectMeta> head(String key);

    /** 없는 키를 지우는 것은 오류가 아니다. */
    void delete(String key);

    /** {@code prefix}(예: {@code profile/12/})로 시작하는 객체를 모두 지운다. 접두사는 {@code /}로 끝나야 한다. */
    void deletePrefix(String prefix);

    record PresignedUpload(String url, Instant expiresAt) {
    }

    record ObjectMeta(long size, String contentType) {
    }
}
