package com.project.cinemory.domain.user.listener;

import com.project.cinemory.domain.user.event.ProfileImageReplacedEvent;
import com.project.cinemory.domain.user.event.UserWithdrawnEvent;
import com.project.cinemory.global.infra.media.MediaStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 커밋 뒤 S3 정리 (account-integrity-spec P-7 · B-5-2 · B-5-8). 이미 DB는 끝났으므로 실패해도 응답에 영향을 주지 않고 {@code WARN}만 남긴다 —
 * 남은 파일은 아무도 참조하지 않는 고아일 뿐이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileImageCleanupListener {

    private final MediaStorage mediaStorage;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProfileImageReplaced(ProfileImageReplacedEvent event) {
        try {
            mediaStorage.delete(event.oldKey());
        } catch (RuntimeException e) {
            log.warn("이전 프로필 사진 삭제에 실패했습니다. userId={}, key={}", event.userId(), event.oldKey(), e);
        }
    }

    /** 접두 삭제라 우리 키가 하나도 없으면 아무것도 지우지 않는다 — 이전 이미지를 기억해 둘 필요가 없다(B-5-2). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserWithdrawn(UserWithdrawnEvent event) {
        deleteQuietly("profile/" + event.userId() + "/", event.userId());
        deleteQuietly("cover/" + event.userId() + "/", event.userId());
    }

    /** 접두 하나가 실패해도 다른 접두 정리는 이어간다 — 둘은 독립이다. */
    private void deleteQuietly(String prefix, Long userId) {
        try {
            mediaStorage.deletePrefix(prefix);
        } catch (RuntimeException e) {
            log.warn("탈퇴 후 사진 정리에 실패했습니다. userId={}, prefix={}", userId, prefix, e);
        }
    }
}
