package com.project.cinemory.global.retention;

import com.project.cinemory.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보관 기간 정리 (settings-spec ST-10 ④, 테스트 ST-T10). 실 DB({@code cinemory_test})를 쓰고 테스트마다 롤백한다 —
 * 정리 작업의 DELETE도 테스트 트랜잭션에 참여하므로 롤백되고, 기준 시각 밖의 기존 행에는 의존하지 않는다(id로만 확인).
 */
@SpringBootTest
@Transactional
class RetentionCleanupServiceTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @Autowired
    private RetentionCleanupService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager em;

    private long adminLog(LocalDateTime createdAt) {
        jdbc.update("INSERT INTO admin_action_log (admin_user_id, admin_email_snapshot, action, created_at) VALUES (?, ?, ?, ?)",
                1L, "retention@test", "USER_LIST_VIEW", Timestamp.valueOf(createdAt));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private User user() {
        User user = User.createOAuth("retention" + SEQ.incrementAndGet() + "@retention.test", "보관정리", null);
        em.persist(user);
        em.flush();
        return user;
    }

    private long refreshToken(User user, LocalDateTime expiresAt) {
        jdbc.update("INSERT INTO refresh_token (user_id, token_hash, expires_at) VALUES (?, ?, ?)",
                user.getId(), String.format("%064d", SEQ.incrementAndGet()), Timestamp.valueOf(expiresAt));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private boolean logExists(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM admin_action_log WHERE id = ?", Integer.class, id) > 0;
    }

    private boolean tokenExists(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE id = ?", Integer.class, id) > 0;
    }

    @Test
    void T10_1년_1일_전_감사_로그와_91일_전_만료_토큰만_지워진다() {
        LocalDateTime now = LocalDateTime.now();
        User user = user();
        long oldLog = adminLog(now.minusYears(1).minusDays(1));
        long recentLog = adminLog(now.minusYears(1).plusDays(1));
        long freshLog = adminLog(now.minusDays(1));
        long expired91 = refreshToken(user, now.minusDays(91));
        long expired89 = refreshToken(user, now.minusDays(89));
        long stillValid = refreshToken(user, now.plusDays(10));

        RetentionCleanupService.Result result = service.cleanup();

        assertThat(logExists(oldLog)).isFalse();
        assertThat(logExists(recentLog)).isTrue();
        assertThat(logExists(freshLog)).isTrue();
        assertThat(tokenExists(expired91)).isFalse();
        assertThat(tokenExists(expired89)).isTrue();
        assertThat(tokenExists(stillValid)).isTrue();
        assertThat(result.adminActionLogs()).isGreaterThanOrEqualTo(1);
        assertThat(result.refreshTokens()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void 배치_크기보다_많은_행도_반복해서_모두_지운다() {
        LocalDateTime now = LocalDateTime.now();
        User user = user();
        List<Long> oldLogs = new ArrayList<>();
        List<Long> oldTokens = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            oldLogs.add(adminLog(now.minusYears(2)));
            oldTokens.add(refreshToken(user, now.minusDays(200)));
        }
        long keptLog = adminLog(now.minusDays(1));
        long keptToken = refreshToken(user, now.minusDays(1));

        service.cleanup(2); // 2행씩 → 3번 돌아야 5행(+기존 오래된 행)을 지운다

        assertThat(oldLogs).noneMatch(this::logExists);
        assertThat(oldTokens).noneMatch(this::tokenExists);
        assertThat(logExists(keptLog)).isTrue();
        assertThat(tokenExists(keptToken)).isTrue();
    }
}
