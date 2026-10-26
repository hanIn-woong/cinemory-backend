package com.project.cinemory.global.infra.gemini;

/** 임베딩할 영화 문서 한 건. 접두어({@code title: … | text: …}) 조립은 {@link EmbeddingClient} 구현 내부 책임이다. */
public record EmbeddingDocument(String title, String text) {
}
