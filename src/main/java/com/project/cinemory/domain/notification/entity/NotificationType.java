package com.project.cinemory.domain.notification.entity;

/** 알림 종류. DB {@code notification.type} ENUM과 값이 일치해야 한다. */
public enum NotificationType {

    FOLLOW,
    COMMENT_ON_COLLECTION,
    COMMENT_ON_REVIEW
}
