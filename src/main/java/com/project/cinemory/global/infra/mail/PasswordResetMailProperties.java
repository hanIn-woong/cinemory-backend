package com.project.cinemory.global.infra.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 비밀번호 재설정 메일의 표현 설정.
 *
 * <p>SMTP 접속 정보({@code spring.mail.*})와 분리한 이유 — 그쪽은 Spring Boot가 자동 구성하는
 * <b>전송 계층</b>이고, 여기는 "무엇을 어떤 링크로 보내는가"라는 <b>우리 도메인의 표현</b>이다.
 * 발신 주소나 딥링크 스킴이 바뀌어도 전송 설정은 그대로다.
 *
 * @param from          발신 주소
 * @param subject       메일 제목
 * @param deepLinkBase  재설정 화면 딥링크. 여기에 {@code ?token=원문}이 붙는다
 */
@ConfigurationProperties(prefix = "mail.password-reset")
public record PasswordResetMailProperties(
        String from,
        String subject,
        String deepLinkBase
) {

    public PasswordResetMailProperties {
        requireText(from, "mail.password-reset.from");
        requireText(subject, "mail.password-reset.subject");
        requireText(deepLinkBase, "mail.password-reset.deep-link-base");
    }

    /**
     * 원문 토큰은 Base64URL(패딩 없음)이라 {@code A-Za-z0-9-_}로만 구성된다 —
     * URL 인코딩이 필요한 문자가 없어 그대로 붙인다.
     */
    public String resetLink(String rawToken) {
        return deepLinkBase + "?token=" + rawToken;
    }

    private static void requireText(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + "은(는) 필수입니다.");
        }
    }
}
