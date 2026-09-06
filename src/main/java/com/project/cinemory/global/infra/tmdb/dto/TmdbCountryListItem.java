package com.project.cinemory.global.infra.tmdb.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code GET /configuration/countries} 응답의 항목.
 *
 * <p>⚠️ 이 엔드포인트는 <b>루트 레벨 JSON 배열</b>을 반환한다. 래퍼 객체가 없으므로
 * {@code ParameterizedTypeReference<List<TmdbCountryListItem>>}로 받아야 한다.
 *
 * <p>필드가 snake_case인데 이 프로젝트는 Jackson 네이밍 전략을 바꾸지 않았으므로
 * ({@code KoficDailyBoxOfficeResponse}는 KOBIS가 camelCase라 문제가 없었다)
 * {@code @JsonProperty}로 명시 매핑한다.
 *
 * @param iso31661    ISO 3166-1 alpha-2 코드 — {@code country.code}에 대응하는 자연키
 * @param englishName 영문 국가명
 * @param nativeName  {@code language} 파라미터 기준 지역화 국가명
 */
public record TmdbCountryListItem(
        @JsonProperty("iso_3166_1") String iso31661,
        @JsonProperty("english_name") String englishName,
        @JsonProperty("native_name") String nativeName
) {

    /**
     * 저장할 국가명. {@code language=ko-KR}로 요청해도 TMDB의 국가명 지역화 범위가
     * 완전하지 않아 {@code nativeName}이 비어 오거나 영문 그대로인 경우가 있다.
     * 비면 영문명으로 폴백한다 — {@code country.name}이 not null이라 비우면 저장이 실패한다.
     */
    public String resolveName() {
        return (nativeName == null || nativeName.isBlank()) ? englishName : nativeName;
    }
}
