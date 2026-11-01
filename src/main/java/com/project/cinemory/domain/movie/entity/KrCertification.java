package com.project.cinemory.domain.movie.entity;

import java.util.Locale;

/**
 * 한국 관람등급(tmdb-sync-spec 6-12-2). TMDB 원문 표기가 흔들려도 화면이 영향받지 않게 enum으로 정규화해 저장한다.
 * 매핑되지 않는 값은 {@code null} — 호출부가 원문을 WARN으로 남긴다.
 */
public enum KrCertification {
    ALL, AGE_12, AGE_15, AGE_18, RESTRICTED;

    /** 대소문자·공백을 무시한다. 모르는 값·빈 값은 null. */
    public static KrCertification fromTmdb(String raw) {
        if (raw == null) {
            return null;
        }
        String key = raw.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return switch (key) {
            case "all" -> ALL;
            case "12" -> AGE_12;
            case "15" -> AGE_15;
            // "19"는 2007년 이전의 연소자 관람불가 표기로, 지금의 청불(18)과 같은 뜻이다 - 옛 영화(올드보이 등)에 실제로 남아 있다(실측 2026-11-01)
            case "18", "19" -> AGE_18;
            case "restrictedscreening" -> RESTRICTED;
            default -> null;
        };
    }
}
