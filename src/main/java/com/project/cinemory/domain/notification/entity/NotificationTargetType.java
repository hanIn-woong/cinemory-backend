package com.project.cinemory.domain.notification.entity;

/**
 * 알림이 가리키는 대상의 종류.
 *
 * <p>{@code domain.comment.entity.TargetType}과 값이 다르므로 <b>재사용하지 않는다.</b>
 * 알림은 팔로우(대상 = USER)를 포함하는 반면 댓글 대상에는 USER가 없다.
 */
public enum NotificationTargetType {

    USER,
    COLLECTION,
    REVIEW
}
