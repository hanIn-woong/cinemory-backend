package com.project.cinemory.domain.user.service;

import com.project.cinemory.domain.user.dto.SignUpLocalRequest;
import com.project.cinemory.domain.user.dto.UserResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code UserResponse.createdAt} — 앱 리포트의 "CineMory와 함께한 N일"이 이 값을 쓴다(M3a-report-spec 4-8, 2026-10-12).
 * 감사(auditing)가 저장 시점에 채우므로, 가입 응답에서 이미 null이 아니어야 하고 조회 응답과 같아야 한다.
 */
@SpringBootTest
@Transactional
class UserResponseCreatedAtTest {

    @Autowired
    private UserService userService;

    @Test
    void 가입_응답과_내_정보_조회에_같은_가입일이_실린다() {
        UserResponse signedUp = userService.signUpLocal(
                new SignUpLocalRequest("created-at-test@cinemory.test", "password1234", "가입일테스트"));

        UserResponse me = userService.getUser(signedUp.id());

        assertThat(signedUp.createdAt()).isNotNull();
        assertThat(me.createdAt()).isEqualTo(signedUp.createdAt());
    }
}
