package com.project.cinemory.domain.settings;

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
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 가입 동의 기록 (settings-spec ST-7, 테스트 ST-T8·T9). 실 DB({@code cinemory_test})를 쓰고 테스트마다 롤백한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TermsAgreementApiTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private int signUp(String email, String agreeFragment) throws Exception {
        String body = "{\"email\":\"" + email + "\",\"rawPassword\":\"Passw0rd!x1\",\"nickname\":\"동의테스트\"" + agreeFragment + "}";
        return mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
    }

    private LocalDateTime termsAgreedAt(String email) {
        em.flush();
        Timestamp value = jdbc.queryForObject("SELECT terms_agreed_at FROM `user` WHERE email = ?", Timestamp.class, email);
        return value == null ? null : value.toLocalDateTime();
    }

    private boolean exists(String email) {
        em.flush();
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE email = ?", Integer.class, email);
        return count != null && count > 0;
    }

    @Test
    void T8_agreeToTerms가_false이거나_누락이면_400이고_가입되지_않는다() throws Exception {
        String falseEmail = "terms-false-" + SEQ.incrementAndGet() + "@settings.test";
        String missingEmail = "terms-missing-" + SEQ.incrementAndGet() + "@settings.test";

        assertThat(signUp(falseEmail, ",\"agreeToTerms\":false")).isEqualTo(400);
        assertThat(signUp(missingEmail, "")).isEqualTo(400);

        assertThat(exists(falseEmail)).isFalse();
        assertThat(exists(missingEmail)).isFalse();
    }

    @Test
    void T8_agreeToTerms가_true이면_201이고_terms_agreed_at이_기록된다() throws Exception {
        String email = "terms-true-" + SEQ.incrementAndGet() + "@settings.test";
        LocalDateTime before = LocalDateTime.now().minusSeconds(5);

        assertThat(signUp(email, ",\"agreeToTerms\":true")).isEqualTo(201);

        assertThat(termsAgreedAt(email)).isNotNull().isAfter(before).isBefore(LocalDateTime.now().plusSeconds(5));
    }

    @Test
    void T9_terms_agreement를_두_번_호출해도_처음_값이_유지된다() throws Exception {
        User social = User.createOAuth("terms-social-" + SEQ.incrementAndGet() + "@settings.test", "소셜동의", null);
        em.persist(social);
        em.flush();
        assertThat(termsAgreedAt(social.getEmail())).as("기존 사용자·소셜 가입 직후는 NULL").isNull();
        String bearer = "Bearer " + jwtTokenProvider.createAccessToken(social.getId(), social.getRole());

        assertThat(mockMvc.perform(post("/api/users/me/terms-agreement").header("Authorization", bearer))
                .andReturn().getResponse().getStatus()).isEqualTo(204);
        LocalDateTime first = termsAgreedAt(social.getEmail());
        assertThat(first).isNotNull();

        // 처음 값을 과거로 고정한 뒤 다시 호출 — 덮어쓰면 지금 시각으로 바뀐다
        LocalDateTime pinned = LocalDateTime.of(2026, 10, 31, 9, 0, 0);
        jdbc.update("UPDATE `user` SET terms_agreed_at = ? WHERE id = ?", Timestamp.valueOf(pinned), social.getId());
        em.clear();

        assertThat(mockMvc.perform(post("/api/users/me/terms-agreement").header("Authorization", bearer))
                .andReturn().getResponse().getStatus()).isEqualTo(204);

        assertThat(termsAgreedAt(social.getEmail())).isEqualTo(pinned);
    }

    @Test
    void 인증_없이_terms_agreement를_호출하면_401이다() throws Exception {
        assertThat(mockMvc.perform(post("/api/users/me/terms-agreement")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }
}
