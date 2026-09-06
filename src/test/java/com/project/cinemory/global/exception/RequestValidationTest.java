package com.project.cinemory.global.exception;

import com.project.cinemory.domain.user.entity.RoleType;
import com.project.cinemory.global.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 5-7 C-1 — {@code @Valid} 위반이 400 {@code INVALID_INPUT_VALUE} + {@code errors[]}로 나가는지
 * 요청 바디에 제약이 있는 5개 도메인(User·WatchRecord·Review·Collection·Comment)에서 각각 확인한다.
 *
 * <p><b>{@code @SpringBootTest} + {@code MockMvc}(슬라이스 아님)를 쓰는 이유</b> — 우리
 * {@code SecurityConfig}·{@code JwtAuthenticationFilter}가 실제로 로드돼야 {@code @AuthUser}가
 * 정상 동작한다. {@code @WebMvcTest}는 이걸 자동 로드하지 않아 인증이 통과하면서 아무것도
 * 검증하지 않는 상태가 된다. {@code MockMvc}는 같은 스레드에서 {@code DispatcherServlet}을
 * 직접 호출하므로 {@code @Transactional} 롤백이 실제로 적용된다({@code RANDOM_PORT}와 다른 점).
 *
 * <p>모든 케이스가 인증 토큰을 필요로 하는 이유 — 대상 5개 엔드포인트가 전부 인증 필수이고,
 * {@code @Valid} 실패를 보려면 {@code @AuthUser(required = true)} 단계를 먼저 통과해야 한다.
 * {@code @Valid} 실패는 컨트롤러 본문 진입 전에 인자 바인딩 단계에서 일어나므로 DB에 아무것도
 * 쓰지 않는다 — 실제 User/Movie/Collection 행이 없어도 안전하게 검증할 수 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RequestValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private String token;

    @BeforeEach
    void setUp() {
        token = jwtTokenProvider.createAccessToken(1L, RoleType.USER);
    }

    /**
     * 실제 필터체인이 도는지 확인하는 회귀 방지용 카나리아 — 인증 헤더 없이 호출하면 400이 아니라
     * 401이어야 한다. 이게 깨지면 아래 5개 테스트 전부가 "인증이 통과하는 척"하며 의미 없이
     * 초록불을 켜는 상태(5-0-F/5-7-C가 경계하는 실패 유형)라는 뜻이다.
     */
    @Test
    void 인증_토큰_없이_호출하면_401이다() throws Exception {
        mockMvc.perform(post("/api/collections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 닉네임_빈값은_400이다() throws Exception {
        mockMvc.perform(patch("/api/users/me/nickname")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"))
                .andExpect(jsonPath("$.errors[0].field").value("nickname"));
    }

    /** {@code watchDate}는 nullable이라 제약이 없다 — {@code movieId} 누락만 걸려야 한다. */
    @Test
    void 시청기록_movieId_누락은_400이다() throws Exception {
        mockMvc.perform(post("/api/records")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"))
                .andExpect(jsonPath("$.errors[0].field").value("movieId"));
    }

    /** v15에서 review.rating 컬럼이 제거되며 {@code ReviewWriteRequest}의 제약이 content 하나로 줄었다. */
    @Test
    void 리뷰_content_빈값은_400이다() throws Exception {
        mockMvc.perform(put("/api/movies/1/review")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"))
                .andExpect(jsonPath("$.errors[0].field").value("content"));
    }

    @Test
    void 컬렉션_이름_빈값은_400이다() throws Exception {
        mockMvc.perform(post("/api/collections")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void 댓글_targetType_누락은_400이다() throws Exception {
        mockMvc.perform(post("/api/comments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetId\":1,\"content\":\"좋아요\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"))
                .andExpect(jsonPath("$.errors[0].field").value("targetType"));
    }
}
