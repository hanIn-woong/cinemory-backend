package com.project.cinemory.global.dto;

/**
 * 부분 일치 검색어를 {@code LIKE} 패턴으로 만든다 — 쿼리는 {@code like :pattern escape '!'}로 받는다.
 * 사용자가 친 {@code %}·{@code _}가 와일드카드로 해석되지 않게 이스케이프한다.
 */
public final class LikePattern {

    private LikePattern() {
    }

    /** 앞부분 일치 패턴 — 사용자 검색의 정렬(완전 일치 → 앞부분 → 포함)에 쓴다. */
    public static String startsWith(String q) {
        return escape(q) + "%";
    }

    private static String escape(String q) {
        return q.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /** trim 후 비어 있으면 전체 일치({@code %}). */
    public static String contains(String q) {
        if (q == null || q.isBlank()) {
            return "%";
        }
        return "%" + escape(q) + "%";
    }
}
