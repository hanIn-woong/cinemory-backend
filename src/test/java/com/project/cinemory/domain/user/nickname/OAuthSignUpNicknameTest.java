package com.project.cinemory.domain.user.nickname;

import com.project.cinemory.domain.auth.entity.OAuthProvider;
import com.project.cinemory.domain.auth.service.OAuthVerificationService;
import com.project.cinemory.domain.auth.service.oauth.OAuthUserInfo;
import com.project.cinemory.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 소셜 가입 (profile-spec PT-6 · PR-9 · PR-10). 토큰 검증은 가짜로 두고(검증기 자체는 각 검증기 테스트가 지킨다)
 * 가입 이후 서버가 만드는 값과 응답을 실제 엔드포인트로 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OAuthSignUpNicknameTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private EntityManager em;

    @MockitoBean
    private OAuthVerificationService oauthVerificationService;

    private JsonNode login(OAuthProvider provider, String providerId, String email) throws Exception {
        given(oauthVerificationService.verify(eq(provider), any(), any())).willReturn(new OAuthUserInfo(providerId, email));
        var response = mockMvc.perform(post("/api/auth/oauth/" + provider.name().toLowerCase(Locale.ROOT))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"idToken\":\"t\",\"nonce\":\"n\"}"))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode me(String accessToken) throws Exception {
        return JSON.readTree(mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void assertNewUserFlow(OAuthProvider provider) throws Exception {
        String providerId = provider + "-" + SEQ.incrementAndGet();
        String email = "oauth" + SEQ.incrementAndGet() + "@oauth.test";

        JsonNode first = login(provider, providerId, email);
        assertThat(first.get("newUser").asBoolean()).isTrue();
        assertThat(first.get("accessToken").asString()).isNotBlank();
        assertThat(first.get("accessTokenExpiresIn").asLong()).isPositive();

        JsonNode profile = me(first.get("accessToken").asString());
        assertThat(profile.get("nickname").asString()).matches("관객\\d{6}");
        assertThat(profile.path("profileImage").isNull() || profile.path("profileImage").isMissingNode())
                .as("제공자 사진을 가져오지 않는다").isTrue();

        em.flush();
        em.clear();
        User saved = em.createQuery("select u from User u where u.email = :e", User.class)
                .setParameter("e", email).getSingleResult();
        assertThat(saved.getProfileImage()).isNull();

        JsonNode second = login(provider, providerId, email);
        assertThat(second.get("newUser").asBoolean()).isFalse();
        assertThat(me(second.get("accessToken").asString()).get("nickname").asString()).isEqualTo(saved.getNickname());
    }

    @Test
    void PT6_카카오_신규_가입은_자동_닉네임_사진_없음_newUser_true이고_재로그인은_false다() throws Exception {
        assertNewUserFlow(OAuthProvider.KAKAO);
    }

    @Test
    void PT6_구글_신규_가입도_같다() throws Exception {
        assertNewUserFlow(OAuthProvider.GOOGLE);
    }

    /** 일반 로그인·재발급의 TokenResponse는 그대로다 — newUser 필드가 없다(앱의 재발급 single-flight 경로를 건드리지 않는다). */
    @Test
    void 로컬_로그인_응답에는_newUser가_없다() throws Exception {
        String email = "tr" + SEQ.incrementAndGet() + "@oauth.test";
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"rawPassword\":\"Passw0rd!x1\",\"nickname\":\"토큰확인\",\"agreeToTerms\":true}"));
        var login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"Passw0rd!x1\"}")).andReturn().getResponse();

        assertThat(login.getStatus()).isEqualTo(200);
        String body = login.getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("accessToken").doesNotContain("newUser");
    }
}
