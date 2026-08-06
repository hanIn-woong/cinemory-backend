package com.project.cinemory.global.infra.mail;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 메일 연동 설정. {@code global/infra/kofic}·{@code global/infra/kakao}와 같은 구조를 따른다.
 *
 * <p>{@code JavaMailSender} 빈을 직접 만들지 않는 이유 — Spring Boot가
 * {@code spring.mail.host}가 있을 때 자동 구성한다. 직접 만들면 타임아웃 같은 설정이
 * 자동 구성본과 갈라진다({@code JsonMapper}를 새로 만들지 않는 것과 같은 판단).
 */
@Configuration
@EnableConfigurationProperties(PasswordResetMailProperties.class)
public class MailConfig {
}
