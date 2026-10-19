package com.project.cinemory.global.security;

import com.project.cinemory.domain.user.entity.RoleType;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리자 조회 API 6개 중 GET 5개의 인가(admin-spec 테스트 7절) — 비로그인 401, USER 403, ADMIN 200. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminApiAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private User user;
    private String userToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.createLocal("admin-authz-test@test.com", "{noop}pw", "인가테스트"));
        userToken = jwtTokenProvider.createAccessToken(user.getId(), RoleType.USER);
        adminToken = jwtTokenProvider.createAccessToken(user.getId(), RoleType.ADMIN);
    }

    private String[] paths() {
        return new String[]{"/api/admin/me", "/api/admin/users", "/api/admin/users/" + user.getId(),
                "/api/admin/movies"};
    }

    @Test
    void 비로그인은_401이다() throws Exception {
        for (String path : paths()) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void 일반_사용자는_403이다() throws Exception {
        for (String path : paths()) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void 관리자는_200이다() throws Exception {
        for (String path : paths()) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void me는_토큰의_사용자를_돌려준다() throws Exception {
        mockMvc.perform(get("/api/admin/me").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.userId").value(user.getId()))
                .andExpect(jsonPath("$.email").value("admin-authz-test@test.com"))
                .andExpect(jsonPath("$.nickname").value("인가테스트"));
    }

    @Test
    void 목록은_PageResponse_형태이고_정렬_리터럴이_틀리면_400이다() throws Exception {
        mockMvc.perform(get("/api/admin/users?q=admin-authz-test").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.content[0].email").value("admin-authz-test@test.com"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.last").value(true));

        mockMvc.perform(get("/api/admin/users?sort=password_hash,asc").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }
}
