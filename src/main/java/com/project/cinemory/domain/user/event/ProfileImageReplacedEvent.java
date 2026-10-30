package com.project.cinemory.domain.user.event;

/**
 * 프로필 사진이 바뀌었거나 지워진 뒤 이전 파일을 정리하는 확장 지점 (account-integrity-spec P-7).
 * {@code oldKey}는 <b>우리 S3 키</b>일 때만 담는다 — 제공자 URL은 지울 파일이 없다.
 * 리스너는 {@code AFTER_COMMIT}에서 받는다: 커밋 전에 지우고 롤백되면 DB는 옛 키를 가리키는데 파일은 없다.
 */
public record ProfileImageReplacedEvent(Long userId, String oldKey) {
}
