package com.project.cinemory.domain.user.event;

/**
 * 회원 탈퇴가 DB에서 끝난 뒤 정리 작업을 붙이는 단일 확장 지점 (account-integrity-spec W-10).
 * {@code UserService.withdraw}가 발행하고, 리스너는 {@code @TransactionalEventListener(AFTER_COMMIT)}로 받는다 —
 * 롤백되면 리스너는 호출되지 않는다. 발행자(user)가 리스너(recommendation 등)를 알지 못하도록 이벤트는 user 패키지에 둔다.
 */
public record UserWithdrawnEvent(Long userId) {
}
