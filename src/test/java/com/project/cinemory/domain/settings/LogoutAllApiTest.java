package com.project.cinemory.domain.settings;

import com.project.cinemory.domain.auth.dto.LoginRequest;
import com.project.cinemory.domain.auth.dto.TokenResponse;
import com.project.cinemory.domain.auth.service.AuthService;
import com.project.cinemory.domain.user.dto.SignUpLocalRequest;
import com.project.cinemory.domain.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 모든 기기에서 로그아웃 (settings-spec ST-4, 테스트 ST-T1).
 *
 * <p>{@code revokeAllByUserId}가 {@code REQUIRES_NEW}라 테스트 트랜잭션에 묶으면 아직 커밋되지 않은 토큰을 보지 못한다 —
 * 그래서 이 클래스는 트랜잭션 없이 실제로 커밋하고, 끝에서 사용자를 지운다(토큰은 FK CASCADE로 함께 사라진다).
 */
@SpringBootTest
@AutoConfigureMockMvc
class LogoutAllApiTest {

    private static final String PASSWORD = "Passw0rd!x1";
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserService userService;
    @Autowired
    private AuthService authService;
    @Autowired
    private JdbcTemplate jdbc;

    private Long signUp(String email) {
        return userService.signUpLocal(new SignUpLocalRequest(email, PASSWORD, "로그아웃올", true)).id();
    }

    private void reissueExpecting(String refreshToken, int status) throws Exception {
        assertThat(mockMvc.perform(post("/api/auth/reissue").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(status);
    }

    @Test
    void T1_두_기기_로그인_후_logout_all이면_두_토큰_모두_LOGOUT으로_폐기되고_재발급은_401이다() throws Exception {
        String email = "logout-all-" + SEQ.incrementAndGet() + "@settings.test";
        Long userId = signUp(email);
        try {
            TokenResponse deviceA = authService.login(new LoginRequest(email, PASSWORD));
            TokenResponse deviceB = authService.login(new LoginRequest(email, PASSWORD));
            // 다른 사용자의 토큰은 건드리지 않는다
            String otherEmail = "logout-all-other-" + SEQ.incrementAndGet() + "@settings.test";
            Long otherId = signUp(otherEmail);
            try {
                TokenResponse other = authService.login(new LoginRequest(otherEmail, PASSWORD));

                int status = mockMvc.perform(post("/api/auth/logout-all")
                                .header("Authorization", "Bearer " + deviceA.accessToken()))
                        .andReturn().getResponse().getStatus();

                assertThat(status).isEqualTo(204);
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT revoked_at, revoked_reason FROM refresh_token WHERE user_id = ?", userId);
                assertThat(rows).hasSize(2);
                assertThat(rows).allSatisfy(row -> {
                    assertThat(row.get("revoked_at")).isNotNull();
                    assertThat(row.get("revoked_reason")).isEqualTo("LOGOUT");
                });
                reissueExpecting(deviceA.refreshToken(), 401);
                reissueExpecting(deviceB.refreshToken(), 401);
                reissueExpecting(other.refreshToken(), 200);
            } finally {
                jdbc.update("DELETE FROM `user` WHERE id = ?", otherId);
            }
        } finally {
            jdbc.update("DELETE FROM `user` WHERE id = ?", userId);
        }
    }

    @Test
    void 인증_없이_호출하면_401이다() throws Exception {
        assertThat(mockMvc.perform(post("/api/auth/logout-all")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }
}
