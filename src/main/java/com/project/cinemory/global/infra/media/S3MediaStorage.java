package com.project.cinemory.global.infra.media;

import jakarta.annotation.PreDestroy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * S3 구현. 클라이언트를 만들 때는 자격 증명을 조회하지 않는다 — 첫 요청에서 SDK 기본 체인이 해석하므로
 * 자격 증명이 없는 환경(CI)에서도 기동한다.
 */
public class S3MediaStorage implements MediaStorage {

    private final MediaProperties properties;
    private final S3Client s3;
    private final S3Presigner presigner;

    public S3MediaStorage(MediaProperties properties) {
        this.properties = properties;
        Region region = Region.of(properties.region());
        this.s3 = S3Client.builder().region(region).build();
        this.presigner = S3Presigner.builder().region(region).build();
    }

    @Override
    public PresignedUpload presignPut(String key, String contentType, Duration ttl) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket()).key(key).contentType(contentType).build();
        var presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(ttl).putObjectRequest(put).build());
        return new PresignedUpload(presigned.url().toString(), presigned.expiration());
    }

    @Override
    public Optional<ObjectMeta> head(String key) {
        try {
            var response = s3.headObject(HeadObjectRequest.builder().bucket(bucket()).key(key).build());
            return Optional.of(new ObjectMeta(response.contentLength(), response.contentType()));
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket()).key(key).build());
    }

    @Override
    public void deletePrefix(String prefix) {
        if (prefix == null || !prefix.endsWith("/") || prefix.length() < 2) {
            throw new IllegalArgumentException("접두사는 비어 있지 않고 '/'로 끝나야 합니다: " + prefix);
        }
        var pages = s3.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket()).prefix(prefix).build());
        for (var page : pages) {
            if (page.contents().isEmpty()) {
                continue;
            }
            List<ObjectIdentifier> ids = page.contents().stream()
                    .map(o -> ObjectIdentifier.builder().key(o.key()).build()).toList();
            s3.deleteObjects(DeleteObjectsRequest.builder().bucket(bucket())
                    .delete(Delete.builder().objects(ids).quiet(true).build()).build());
        }
    }

    private String bucket() {
        if (!properties.isConfigured()) {
            throw new IllegalStateException("cinemory.media.bucket(MEDIA_BUCKET)이 설정되지 않았습니다.");
        }
        return properties.bucket();
    }

    @PreDestroy
    void close() {
        presigner.close();
        s3.close();
    }
}
