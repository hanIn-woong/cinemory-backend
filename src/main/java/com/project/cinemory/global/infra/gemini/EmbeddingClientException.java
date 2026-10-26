package com.project.cinemory.global.infra.gemini;

/** 임베딩 호출 실패(네트워크·타임아웃·4xx/5xx·응답 형식 불일치). 메시지에 요청·응답 본문이나 키를 넣지 않는다. */
public class EmbeddingClientException extends RuntimeException {

    public EmbeddingClientException(String message) {
        super(message);
    }

    public EmbeddingClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
