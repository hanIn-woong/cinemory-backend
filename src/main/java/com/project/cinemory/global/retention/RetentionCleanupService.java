package com.project.cinemory.global.retention;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 보관 기간 정리 (settings-spec ST-10 ④). 개인정보처리방침에 적는 보관 기간을 실제로 지키는 장치다.
 *
 * <ul>
 *   <li>{@code admin_action_log} — {@code created_at}이 <b>1년</b> 지난 행</li>
 *   <li>{@code refresh_token} — {@code expires_at}이 <b>90일</b> 지난 행(만료 후 90일. BD-9: 대시보드 최대 기간 90일 이상 보관 조건 충족)</li>
 * </ul>
 *
 * <p>한 번에 지우는 행은 <b>5,000개</b>씩 나눠 반복한다(긴 잠금·큰 트랜잭션 방지). 메서드에 {@code @Transactional}을 두지 않아
 * 각 DELETE가 따로 커밋된다 — 중간에 실패해도 지운 만큼은 남고, 다음 날 이어서 지운다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetentionCleanupService {

    static final int BATCH_SIZE = 5_000;
    static final int ADMIN_ACTION_LOG_RETENTION_YEARS = 1;
    static final int REFRESH_TOKEN_RETENTION_DAYS = 90;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public record Result(int adminActionLogs, int refreshTokens) {
    }

    public Result cleanup() {
        return cleanup(BATCH_SIZE);
    }

    /** 배치 크기를 바꿔 반복 삭제를 검증하는 테스트용. */
    Result cleanup(int batchSize) {
        LocalDateTime now = LocalDateTime.now(clock);
        int logs = deleteInBatches("admin_action_log", "created_at",
                now.minusYears(ADMIN_ACTION_LOG_RETENTION_YEARS), batchSize);
        int tokens = deleteInBatches("refresh_token", "expires_at",
                now.minusDays(REFRESH_TOKEN_RETENTION_DAYS), batchSize);
        log.info("보관 기간 정리 완료. admin_action_log={}행, refresh_token={}행", logs, tokens);
        return new Result(logs, tokens);
    }

    /** 테이블·열 이름은 이 클래스 안의 상수만 넘어온다(바인딩할 수 없는 식별자라 문자열로 조립). */
    private int deleteInBatches(String table, String column, LocalDateTime cutoff, int batchSize) {
        String sql = "DELETE FROM " + table + " WHERE " + column + " < ? ORDER BY id LIMIT ?";
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.update(sql, Timestamp.valueOf(cutoff), batchSize);
            total += deleted;
        } while (deleted == batchSize);
        return total;
    }
}
