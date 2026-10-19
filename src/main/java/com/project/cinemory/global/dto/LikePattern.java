package com.project.cinemory.global.dto;

/**
 * 부분 일치 검색어를 {@code LIKE} 패턴으로 만든다 — 쿼리는 {@code like :pattern escape '!'}로 받는다.
 * 사용자가 친 {@code %}·{@code _}가 와일드카드로 해석되지 않게 이스케이프한다.
 */
public final class LikePattern {

    private LikePattern() {
    }

    /** trim 후 비어 있으면 전체 일치({@code %}). */
    public static String contains(String q) {
        if (q == null || q.isBlank()) {
            return "%";
        }
        String escaped = q.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }
}
