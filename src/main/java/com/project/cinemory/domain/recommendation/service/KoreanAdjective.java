package com.project.cinemory.domain.recommendation.service;

/**
 * 형용사 기본형 → 관형형 변환 — 추천 칩 문구용("잔인하다" → "잔인한", "무겁다" → "무거운").
 *
 * <p>질의의 "무겁지 않은"에서 <b>어간 + "다"</b>(기본형)는 항상 맞게 복원되지만(M3b Q-2-3), 사람이 읽는 문구는 관형형이 자연스럽다.
 * 관형형 복원 자체는 불규칙 활용 때문에 어렵다 — 그래서 <b>검색에는 기본형</b>을 쓰고 이 변환은 문구에만 쓴다. 틀려도 칩 문구가 어색할 뿐
 * 검색 결과에는 영향이 없다.
 *
 * <p>다루는 규칙: 받침 없음(슬프다→슬픈, 하다→한, 크다→큰) · ㅂ 불규칙(무겁다→무거운, 어렵다→어려운) · ㄹ 탈락(길다→긴, 멀다→먼) ·
 * 있다/없다(재미있다→재미있는) · 그 밖의 받침(좋다→좋은, 많다→많은). 기본형이 아니면 그대로 돌려준다.
 */
final class KoreanAdjective {

    private static final int HANGUL_BASE = 0xAC00;
    private static final int HANGUL_LAST = 0xD7A3;
    private static final int JONG_COUNT = 28;
    private static final int JONG_NIEUN = 4;
    private static final int JONG_RIEUL = 8;
    private static final int JONG_BIEUP = 17;

    // ㅂ 받침이지만 규칙 활용인 형용사 어간 (좁다→좁은).
    private static final java.util.Set<String> REGULAR_BIEUP = java.util.Set.of("좁");

    private KoreanAdjective() {
    }

    /** {@code 잔인하다 → 잔인한}. 기본형(…다)이 아니거나 한글이 아니면 입력을 그대로 돌려준다. */
    static String toAdnominal(String dictionaryForm) {
        if (dictionaryForm == null || dictionaryForm.length() < 2 || !dictionaryForm.endsWith("다")) {
            return dictionaryForm;
        }
        String stem = dictionaryForm.substring(0, dictionaryForm.length() - 1);
        if (stem.endsWith("있") || stem.endsWith("없")) {
            return stem + "는";
        }
        char last = stem.charAt(stem.length() - 1);
        if (last < HANGUL_BASE || last > HANGUL_LAST) {
            return dictionaryForm;
        }
        String head = stem.substring(0, stem.length() - 1);
        int offset = last - HANGUL_BASE;
        int jong = offset % JONG_COUNT;
        int syllableWithoutJong = offset - jong;

        if (jong == 0) {
            return head + compose(syllableWithoutJong, JONG_NIEUN);                     // 슬프 → 슬픈
        }
        if (jong == JONG_RIEUL) {
            return head + compose(syllableWithoutJong, JONG_NIEUN);                     // 길 → 긴 (ㄹ 탈락)
        }
        if (jong == JONG_BIEUP && !REGULAR_BIEUP.contains(String.valueOf(last))) {
            return head + compose(syllableWithoutJong, 0) + "운";                       // 무겁 → 무거 + 운
        }
        return stem + "은";                                                            // 좋 → 좋은
    }

    private static char compose(int syllableWithoutJong, int jong) {
        return (char) (HANGUL_BASE + syllableWithoutJong + jong);
    }
}
