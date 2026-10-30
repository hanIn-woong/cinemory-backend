package com.project.cinemory.domain.user.profileimage;

import com.project.cinemory.domain.user.entity.RoleType;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.global.infra.media.InMemoryMediaStorage;
import com.project.cinemory.global.security.JwtTokenProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 프로필 사진 API 3종 (account-integrity-spec B-3 · B-5-4). 저장소는 인메모리 가짜다. 이벤트(커밋 후 삭제)는 {@code ProfileImageCleanupTest}. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProfileImageApiTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);
    private static final String BASE = "https://media.test/";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private EntityManager em;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private InMemoryMediaStorage storage;

    @AfterEach
    void clean() {
        storage.clear();
    }

    private User user(String profileImage) {
        User user = User.createOAuth("img-" + SEQ.incrementAndGet() + "@img.test", "사진", profileImage);
        em.persist(user);
        em.flush();
        return user;
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, User user) {
        return request.header("Authorization", "Bearer " + jwtTokenProvider.createAccessToken(user.getId(), RoleType.USER));
    }

    private MockHttpServletRequestBuilder confirm(User user, String key) {
        return auth(put("/api/users/me/profile-image"), user)
                .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"" + key + "\"}");
    }

    private String ownKey(User user) {
        return "profile/" + user.getId() + "/" + SEQ.incrementAndGet() + ".jpg";
    }

    @Test
    void 업로드_URL은_내_경로의_jpg_키와_URL을_준다() throws Exception {
        User me = user(null);

        mockMvc.perform(auth(post("/api/users/me/profile-image/upload-url"), me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(org.hamcrest.Matchers.matchesPattern("profile/" + me.getId() + "/[0-9a-f-]{36}\\.jpg")))
                .andExpect(jsonPath("$.uploadUrl").value(org.hamcrest.Matchers.containsString("image/jpeg")))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    void 인증_없이는_세_API_모두_거부한다() throws Exception {
        mockMvc.perform(post("/api/users/me/profile-image/upload-url")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/users/me/profile-image").contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/users/me/profile-image")).andExpect(status().isUnauthorized());
    }

    @Test
    void 확정하면_키를_저장하고_응답은_완성된_URL이다() throws Exception {
        User me = user(null);
        String key = ownKey(me);
        storage.put(key, 150_000, "image/jpeg");

        mockMvc.perform(confirm(me, key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(me.getId()))
                .andExpect(jsonPath("$.profileImage").value(BASE + key));

        em.flush();
        em.clear();
        assertThat(em.find(User.class, me.getId()).getProfileImage()).isEqualTo(key);
    }

    @Test
    void 정확히_2MB는_통과하고_초과는_거부한다() throws Exception {
        User me = user(null);
        String ok = ownKey(me);
        String big = ownKey(me);
        storage.put(ok, 2_097_152, "image/jpeg");
        storage.put(big, 2_097_153, "image/jpeg");

        mockMvc.perform(confirm(me, ok)).andExpect(status().isOk());
        mockMvc.perform(confirm(me, big)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROFILE_IMAGE"));
    }

    @Test
    void 남의_경로_없는_객체_다른_Content_Type_접두_우회는_모두_INVALID_PROFILE_IMAGE() throws Exception {
        User me = user(null);
        User other = user(null);
        String othersKey = ownKey(other);
        storage.put(othersKey, 1000, "image/jpeg");
        String png = ownKey(me);
        storage.put(png, 1000, "image/png");
        // 접두 우회: profile/1/ 이 profile/12/ 를 가리키면 안 된다 — 접두는 슬래시까지 비교한다
        String prefixTrick = "profile/" + me.getId() + "9/a.jpg";
        storage.put(prefixTrick, 1000, "image/jpeg");

        for (String key : new String[]{othersKey, ownKey(me), png, prefixTrick, "cover/" + me.getId() + "/a.jpg",
                "https://evil.test/a.jpg"}) {
            mockMvc.perform(confirm(me, key)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PROFILE_IMAGE"));
        }
        mockMvc.perform(auth(put("/api/users/me/profile-image"), me).contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"\"}"))
                .andExpect(status().isBadRequest());

        em.flush();
        em.clear();
        assertThat(em.find(User.class, me.getId()).getProfileImage()).isNull();
    }

    @Test
    void 삭제하면_기본_아바타로_돌아간다() throws Exception {
        User me = user("profile/1/old.jpg");

        mockMvc.perform(auth(delete("/api/users/me/profile-image"), me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileImage").doesNotExist());

        em.flush();
        em.clear();
        assertThat(em.find(User.class, me.getId()).getProfileImage()).isNull();
    }

    @Test
    void 기존_제공자_URL_사용자도_교체할_수_있다() throws Exception {
        User me = user("https://k.kakaocdn.net/p.jpg");
        String key = ownKey(me);
        storage.put(key, 1000, "image/jpeg");

        mockMvc.perform(confirm(me, key)).andExpect(status().isOk())
                .andExpect(jsonPath("$.profileImage").value(BASE + key));
    }
}
