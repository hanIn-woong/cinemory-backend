package com.project.cinemory.global.infra.mail;

import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

/**
 * 메일 발송의 두 가지 계약을 고정한다.
 *
 * <ol>
 *   <li><b>딥링크에 원문 토큰이 그대로 실린다</b> — 우리가 DB에 남기는 것은 해시뿐이라,
 *       이 메일이 원문이 존재하는 유일한 경로다. 여기서 값이 어긋나면 링크가 전부 무효가 된다.</li>
 *   <li><b>발송 실패를 삼키지 않는다</b>(S-9 F-2) — 삼키면 호출부 트랜잭션이 커밋돼
 *       토큰만 남고, 사용자는 재요청해도 억제에 걸려 갇힌다.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class PasswordResetMailSenderTest {

    private static final String TO = "user@cinemory.com";
    private static final String RAW_TOKEN = "raw-token-abc";
    private static final Duration TTL = Duration.ofMinutes(30);

    @Mock
    private JavaMailSender javaMailSender;

    private final PasswordResetMailProperties properties = new PasswordResetMailProperties(
            "noreply@cinemory.com", "[CineMory] 비밀번호 재설정 안내", "cinemory://reset-password");

    private PasswordResetMailSender sender() {
        return new PasswordResetMailSender(javaMailSender, properties);
    }

    @Test
    void 본문에_원문_토큰이_담긴_딥링크와_만료_시간을_넣어_보낸다() {
        sender().send(TO, RAW_TOKEN, TTL);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();

        assertThat(message.getFrom()).isEqualTo("noreply@cinemory.com");
        assertThat(message.getTo()).containsExactly(TO);
        assertThat(message.getSubject()).isEqualTo("[CineMory] 비밀번호 재설정 안내");
        assertThat(message.getText())
                .contains("cinemory://reset-password?token=" + RAW_TOKEN)
                .contains("30분");
    }

    /** 발송이 실패하면 호출부가 롤백할 수 있도록 예외를 올린다. */
    @Test
    void 발송에_실패하면_EXTERNAL_API_ERROR로_예외를_올린다() {
        willThrow(new MailSendException("smtp down")).given(javaMailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> sender().send(TO, RAW_TOKEN, TTL))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }

    /**
     * 설정 누락은 기동 시점에 실패시킨다. 발신 주소나 딥링크가 비면 메일은 나가지만
     * 사용자가 재설정 화면에 도달하지 못해, 장애가 <b>발송 시점에야</b> 드러난다.
     */
    @Test
    void 딥링크_설정이_비어_있으면_기동_시점에_실패한다() {
        assertThatThrownBy(() -> new PasswordResetMailProperties("noreply@cinemory.com", "제목", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mail.password-reset.deep-link-base");
    }
}
