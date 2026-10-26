package com.project.cinemory.domain.recommendation.dto;

/**
 * 질의에서 적용한 조건 (5-14-C) — 앱이 칩으로 보여 준다.
 *
 * @param value 런타임 분({@code RUNTIME_MAX})만, 그 외는 null
 * @param label 짧은 이름 — "한국" · "1990년대" · "최근 5년" · "공포" · "잔인하다" 등
 * @param text  서버가 만든 한국어 문구(변경에 앱 배포가 필요 없다)
 */
public record AppliedConditionResponse(ConditionType type, Integer value, String label, String text) {
}
