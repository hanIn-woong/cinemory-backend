package com.project.cinemory.global.infra.mail;

import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 비밀번호 재설정 링크 메일 발송.
 *
 * <p><b>평문 본문</b>을 쓴다. HTML 메일은 템플릿 엔진과 렌더링 호환성 문제를 함께 들여오는데,
 * 담을 내용이 링크 한 줄이라 얻는 것이 없다.
 *
 * <p><b>⚠️ 이 호출은 트랜잭션 안에서 일어난다</b>(S-9 F-2). 발송이 실패하면 토큰도 함께
 * 롤백돼야 하므로 예외를 <b>삼키지 않는다.</b> 삼키면 메일은 못 받았는데 토큰만 남아,
 * 사용자가 재요청해도 재요청 억제에 걸려 아무것도 할 수 없는 상태가 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PasswordResetMailSender {

    private final JavaMailSender mailSender;
    private final PasswordResetMailProperties properties;

    /**
     * @param rawToken 원문 토큰. DB에는 이 값의 해시만 남고, 원문은 이 메일에만 존재한다
     * @throws BusinessException {@code EXTERNAL_API_ERROR} — 발송 실패. 호출부의 트랜잭션을 롤백시킨다
     */
    public void send(String to, String rawToken, Duration ttl) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(to);
        message.setSubject(properties.subject());
        message.setText(body(rawToken, ttl));

        try {
            mailSender.send(message);
        } catch (MailException e) {
            // 원문 토큰이 로그에 남지 않도록 수신자만 남긴다.
            log.error("비밀번호 재설정 메일 발송 실패 - to={}", to, e);
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR, "메일 발송에 실패했습니다.");
        }
    }

    private String body(String rawToken, Duration ttl) {
        return """
                CineMory 비밀번호 재설정 안내입니다.

                아래 링크를 눌러 새 비밀번호를 설정해 주세요.
                %s

                이 링크는 %d분 후 만료되며, 한 번만 사용할 수 있습니다.
                본인이 요청하지 않았다면 이 메일을 무시하셔도 됩니다. 비밀번호는 변경되지 않습니다.
                """.formatted(properties.resetLink(rawToken), ttl.toMinutes());
    }
}
