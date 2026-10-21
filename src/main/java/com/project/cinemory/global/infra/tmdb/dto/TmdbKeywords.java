package com.project.cinemory.global.infra.tmdb.dto;

import java.util.List;

/**
 * {@code append_to_response=keywords}로 실려 오는 {@code keywords} 객체.
 * 목록은 {@code keywords.keywords[]}로 한 겹 더 들어 있다 (tmdb-sync-spec 6-11, 실응답 확인).
 */
public record TmdbKeywords(List<Item> keywords) {

    public record Item(Long id, String name) {
    }

    public List<Item> keywordsOrEmpty() {
        return keywords == null ? List.of() : keywords;
    }
}
