package com.project.cinemory.domain.user.nickname;

import com.project.cinemory.domain.user.entity.PrivacySetting;
import com.project.cinemory.domain.user.entity.RoleType;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.global.security.JwtTokenProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 닉네임 규칙의 API 경로 (profile-spec PT-2 · PT-3 · PT-5, PT-17은 PATCH 쪽만 — PUT /profile은 P-2). */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NicknameApiTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);
    private static final String[] INVALID = {"가", "가".repeat(21), "   ", "CineMory팬", "영화\n광", "관리자", "ADMIN"};

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private User user(String nickname) {
        User user = User.createOAuth("nick-" + SEQ.incrementAndGet() + "@nick.test", nickname, null);
        em.persist(user);
        em.flush();
        return user;
    }

    private ResultActions signUp(String nickname) throws Exception {
        String body = "{\"email\":\"local" + SEQ.incrementAndGet() + "@nick.test\",\"rawPassword\":\"Passw0rd!x1\",\"nickname\":\""
                + nickname.replace("\n", "\\n") + "\",\"agreeToTerms\":true}";
        return mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions changeNickname(User user, String nickname) throws Exception {
        return mockMvc.perform(patch("/api/users/me/nickname")
                .header("Authorization", "Bearer " + jwtTokenProvider.createAccessToken(user.getId(), RoleType.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"" + nickname.replace("\n", "\\n") + "\"}"));
    }

    private static void expectInvalid(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"))
                .andExpect(jsonPath("$.errors[0].field").value("nickname"))
                .andExpect(jsonPath("$.errors[0].reason").isNotEmpty());
    }

    private String nicknameOf(User user) {
        em.flush();
        em.clear();
        return em.find(User.class, user.getId()).getNickname();
    }

    // ---- PT-2 -----------------------------------------------------------------------------------

    @Test
    void PT2_가입은_규칙_위반을_400으로_거부한다() throws Exception {
        for (String nickname : INVALID) {
            expectInvalid(signUp(nickname));
        }
    }

    @Test
    void PT2_닉네임_변경은_규칙_위반을_400으로_거부하고_값을_바꾸지_않는다() throws Exception {
        User me = user("원래이름");
        for (String nickname : INVALID) {
            expectInvalid(changeNickname(me, nickname));
        }
        assertThat(nicknameOf(me)).isEqualTo("원래이름");
    }

    @Test
    void PT2_가입은_이전_한도_50자_안쪽의_30자도_이제_거부하고_20자는_통과한다() throws Exception {
        expectInvalid(signUp("가".repeat(30)));
        signUp("가".repeat(20)).andExpect(status().isCreated()).andExpect(jsonPath("$.nickname").value("가".repeat(20)));
    }

    @Test
    void 가입과_변경은_앞뒤_공백을_제거해_저장한다() throws Exception {
        signUp("  공백이름  ").andExpect(status().isCreated()).andExpect(jsonPath("$.nickname").value("공백이름"));
        User me = user("원래이름");
        changeNickname(me, "  바뀐이름  ").andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("바뀐이름"));
    }

    // ---- PT-3: 중복 허용 ---------------------------------------------------------------------------

    @Test
    void PT3_다른_사용자와_같은_닉네임도_가입과_변경이_모두_성공한다() throws Exception {
        User other = user("같은이름");
        signUp(other.getNickname()).andExpect(status().isCreated());
        signUp(other.getNickname()).andExpect(status().isCreated());
        User me = user("내이름");
        changeNickname(me, other.getNickname()).andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("같은이름"));
        em.flush();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE nickname = ?", Long.class, "같은이름")).isEqualTo(4L);
    }

    // ---- PT-5: 기존 긴 닉네임 ----------------------------------------------------------------------

    @Test
    void PT5_기존_21자_이상_닉네임이_그대로면_규칙을_다시_적용하지_않는다() throws Exception {
        String legacy = "가".repeat(30);
        User me = user(legacy);

        changeNickname(me, legacy).andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value(legacy));
        changeNickname(me, "  " + legacy + "  ").andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value(legacy));
    }

    @Test
    void PT5_기존_긴_닉네임이어도_다른_값으로_바꿀_때는_새_규칙이다() throws Exception {
        User me = user("가".repeat(30));

        expectInvalid(changeNickname(me, "가".repeat(25)));
        changeNickname(me, "새이름").andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("새이름"));
    }

    // ---- PT-17 (이 단계에서는 PATCH /me/privacy 쪽 — PUT /profile은 P-2) -----------------------------

    @Test
    void PT17_PATCH_privacy는_그대로_공개_범위를_바꾼다() throws Exception {
        User me = user("공개설정");
        mockMvc.perform(patch("/api/users/me/privacy")
                        .header("Authorization", "Bearer " + jwtTokenProvider.createAccessToken(me.getId(), RoleType.USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"privacySetting\":\"PUBLIC\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.privacySetting").value("PUBLIC"));
        em.flush();
        em.clear();
        assertThat(em.find(User.class, me.getId()).getPrivacySetting()).isEqualTo(PrivacySetting.PUBLIC);
    }
}
