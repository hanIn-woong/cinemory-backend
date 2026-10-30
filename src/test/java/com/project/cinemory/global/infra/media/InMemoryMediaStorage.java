package com.project.cinemory.global.infra.media;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 테스트용 가짜 저장소 — S3에 의존하지 않는다(CI에는 AWS 자격 증명이 없다, account-integrity-spec B-5-6). */
public class InMemoryMediaStorage implements MediaStorage {

    private final Map<String, ObjectMeta> objects = new ConcurrentHashMap<>();
    private final List<String> deleteCalls = new CopyOnWriteArrayList<>();
    private final List<String> deletePrefixCalls = new CopyOnWriteArrayList<>();
    private volatile boolean failOnDelete;

    /** 앱이 presigned URL로 올린 것을 흉내 낸다. */
    public void put(String key, long size, String contentType) {
        objects.put(key, new ObjectMeta(size, contentType));
    }

    public boolean contains(String key) {
        return objects.containsKey(key);
    }

    public void clear() {
        objects.clear();
        deleteCalls.clear();
        deletePrefixCalls.clear();
        failOnDelete = false;
    }

    /** {@code delete}가 불린 키(실패한 호출 포함). */
    public List<String> deleteCalls() {
        return deleteCalls;
    }

    /** {@code deletePrefix}가 불린 접두사(실패한 호출 포함). */
    public List<String> deletePrefixCalls() {
        return deletePrefixCalls;
    }

    /** 삭제 실패(S3 장애)를 흉내 낸다 — 리스너가 예외를 삼키고 WARN만 남기는지 확인한다. */
    public void failOnDelete(boolean fail) {
        this.failOnDelete = fail;
    }

    @Override
    public PresignedUpload presignPut(String key, String contentType, Duration ttl) {
        return new PresignedUpload("https://fake-s3.test/" + key + "?contentType=" + contentType,
                Instant.now().plus(ttl));
    }

    @Override
    public Optional<ObjectMeta> head(String key) {
        return Optional.ofNullable(objects.get(key));
    }

    @Override
    public void delete(String key) {
        deleteCalls.add(key);
        if (failOnDelete) {
            throw new IllegalStateException("S3 장애(가짜)");
        }
        objects.remove(key);
    }

    @Override
    public void deletePrefix(String prefix) {
        deletePrefixCalls.add(prefix);
        if (failOnDelete) {
            throw new IllegalStateException("S3 장애(가짜)");
        }
        objects.keySet().removeIf(key -> key.startsWith(prefix));
    }
}
