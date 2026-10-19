package com.project.cinemory.domain.admin.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 감사 로그 {@code detail}을 만드는 도우미 — {@code Map.of}는 null 값을 허용하지 않는데 요청 파라미터는 대개 선택이라
 * null이 흔하다. <b>null 값은 키째 뺀다</b>(로그에 {@code "limit": null}이 남으면 "기본값을 썼다"와 구별되지 않는다).
 * 값은 JSON으로 직렬화되는 기본형·문자열만 넣는다(날짜는 {@code toString()}).
 */
public final class AuditDetail {

    private AuditDetail() {
    }

    /** {@code of("q", q, "page", 0)} — 키·값 쌍. 쌍이 맞지 않으면 {@code IllegalArgumentException}. */
    public static Map<String, Object> of(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("키·값 쌍이어야 합니다.");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            if (value != null) {
                detail.put((String) keyValues[i], value);
            }
        }
        return detail;
    }
}
