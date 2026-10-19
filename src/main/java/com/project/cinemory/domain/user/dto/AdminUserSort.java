package com.project.cinemory.domain.user.dto;

import org.springframework.data.domain.Sort;

/** 관리자 회원 목록 정렬 화이트리스트(admin-spec 3-2) — 모두 마지막에 {@code id}로 전순서를 만든다. */
public enum AdminUserSort {

    CREATED_AT_DESC(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))),
    CREATED_AT_ASC(Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"))),
    NICKNAME_ASC(Sort.by(Sort.Order.asc("nickname"), Sort.Order.asc("id")));

    private final Sort sort;

    AdminUserSort(Sort sort) {
        this.sort = sort;
    }

    public Sort toSort() {
        return sort;
    }
}
