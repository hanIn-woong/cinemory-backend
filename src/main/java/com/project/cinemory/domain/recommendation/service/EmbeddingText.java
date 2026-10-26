package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.recommendation.entity.OverviewSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 조립된 임베딩 입력. {@code movie_embedding.source_text}에는 {@link #sourceText()}가 그대로 저장된다.
 *
 * @param title 접두어에 들어갈 제목 — {@code 표시제목} 또는 {@code 표시제목 (원제)}
 * @param body  제목 뒤 본문(개봉·국가 줄부터 줄거리까지, 값 없는 줄은 생략됨)
 */
public record EmbeddingText(String title, String body, OverviewSource overviewSource) {

    /** {@code GeminiEmbeddingClient}가 보내는 문서 형식({@code title: … | text: …})과 같다. */
    public String sourceText() {
        return "title: " + title + " | text: " + body;
    }

    /** SHA-256(model_name + source_text) — 모델이 바뀌면 전 행이 불일치해 자동 전량 재임베딩이 된다(E-4-1). */
    public String hash(String modelName) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((modelName + sourceText()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }
}
