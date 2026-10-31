package com.project.cinemory.global.infra.mail;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;

/**
 * 발송 실패 로그에 수신자 이메일이 남지 않는다 (settings-spec ST-10 ③, 방침 2절).
 * SMTP 오류 메시지에는 수신자 주소가 실려 오는 일이 흔하다 — 로그 문장의 {@code to=}만 지워서는 부족하고,
 * 예외 객체(메시지·스택트레이스)를 로그에 넘기지 않아야 한다.
 */
@ExtendWith(MockitoExtension.class)
class PasswordResetMailSenderLogTest {

    private static final String TO = "private-user@cinemory.com";

    @Mock
    private JavaMailSender javaMailSender;

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(PasswordResetMailSender.class);

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    void 발송_실패_로그에는_수신자_주소가_메시지에도_예외에도_없다() {
        // 실제 SMTP 오류처럼 원인 예외의 메시지에 수신자 주소가 들어 있다
        MailSendException failure = new MailSendException("Invalid Addresses",
                new RuntimeException("550 5.1.1 <" + TO + "> user unknown"));
        willThrow(failure).given(javaMailSender).send(any(SimpleMailMessage.class));
        PasswordResetMailSender sender = new PasswordResetMailSender(javaMailSender, new PasswordResetMailProperties(
                "noreply@cinemory.com", "[CineMory] 비밀번호 재설정 안내", "cinemory://reset-password"));

        assertThatThrownBy(() -> sender.send(TO, "raw-token-abc", Duration.ofMinutes(30)))
                .isInstanceOf(com.project.cinemory.global.exception.BusinessException.class);

        List<ILoggingEvent> errors = appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
        assertThat(errors).hasSize(1);
        ILoggingEvent event = errors.get(0);
        // 메시지, 파라미터 치환 후 문장, 예외 객체(스택트레이스 포함) 어디에도 주소·토큰이 없다
        assertThat(event.getFormattedMessage()).doesNotContain(TO).doesNotContain("raw-token-abc");
        assertThat(event.getThrowableProxy()).as("예외 객체를 로그에 넘기지 않는다").isNull();
        // 원인은 예외 종류로 남는다 — 장애 때 Gmail 인증 실패인지 발송 실패인지는 구분할 수 있어야 한다
        assertThat(event.getFormattedMessage()).contains("MailSendException").contains("RuntimeException");
    }
}
