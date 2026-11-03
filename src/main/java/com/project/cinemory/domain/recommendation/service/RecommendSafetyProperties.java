package com.project.cinemory.domain.recommendation.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * {@code cinemory.recommend.safety.*} — 추천 제외 자동 판정 기준값 (M3b-recommendation-spec R-5-1). 값을 바꾸면 재기동만으로 반영된다
 * (판정 결과를 저장하지 않고 특징 인덱스를 만들 때 계산하기 때문). 운영 DB에서 형태 규칙에 걸리는 목록을 뽑아 확인한 뒤 확정한다.
 *
 * @param blockedKeywords   차단 키워드(TMDB 키워드 영문명, 대소문자 무시) — 보조 규칙. 키워드 누락이 흔해 주 방어가 아니다
 * @param shapeMaxRuntime   형태 규칙: 상영시간이 이 값 <b>이하</b>(분). 상영시간 정보가 없으면 걸리지 않는다
 * @param shapeMinVoteCount 형태 규칙: 투표 수가 이 값 <b>미만</b>. 투표 수 정보가 없으면 0으로 본다
 */
@ConfigurationProperties(prefix = "cinemory.recommend.safety")
public record RecommendSafetyProperties(
        @DefaultValue({"erotic movie", "softcore", "sexploitation", "erotica", "pink film"}) List<String> blockedKeywords,
        @DefaultValue("90") int shapeMaxRuntime,
        @DefaultValue("20") int shapeMinVoteCount) {
}
