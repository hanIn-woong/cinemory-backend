package com.project.cinemory.domain.feed;

import com.project.cinemory.domain.feed.dto.ActivityPageResponse;
import com.project.cinemory.domain.feed.dto.FeedScope;
import com.project.cinemory.domain.feed.dto.FeedStripsResponse;
import com.project.cinemory.domain.feed.repository.FeedQueryRepository;
import com.project.cinemory.domain.feed.service.FeedService;
import com.project.cinemory.domain.feed.service.StripOrderCache;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 소셜 피드 부하 측정 ① (social-feed-spec 6절) — 기본 {@code ./gradlew test}에서는 제외되고 {@code ./gradlew perfTest}로만 돈다.
 * 별도 DB {@code cinemory_perf}(Flyway V35까지)에 {@code docs/schema/perf/social-feed-seed.sql}을 넣고(비어 있을 때만) 측정한다.
 *
 * <p>합격: 팔로잉 스트립 첫 페이지·3페이지, 개인 피드(활동 약 5천 건 사용자) 모두 서버 p95 ≤ 200ms(100회 반복).
 * 서비스 호출 전체(쿼리 + 조립)를 잰다. 결과와 {@code EXPLAIN ANALYZE} 전문은 {@code build/perf/social-feed-perf.txt}에 남는다.
 */
@Tag("perf")
@SpringBootTest
class SocialFeedPerfTest {

    private static final long VIEWER_ID = 1L;
    private static final long HEAVY_USER_ID = 5000L;
    private static final int WARMUP = 10;
    private static final int REPEAT = 100;
    private static final double LIMIT_MS = 200.0;
    private static final DateTimeFormatter SQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private FeedService feedService;
    @Autowired
    private StripOrderCache stripOrderCache;
    @Autowired
    private FeedQueryRepository feedQueryRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;

    private final StringBuilder report = new StringBuilder();

    @Test
    void 팔로잉_스트립과_개인_피드가_p95_200ms_이내다() throws IOException {
        seedIfEmpty();
        printDataProfile();

        // 커서 체인: 첫 페이지 → 2페이지 → 3페이지(아래에서 측정하는 요청의 커서)
        FeedStripsResponse page1 = feedService.getStrips(VIEWER_ID, FeedScope.FOLLOWING, null, 10, null);
        FeedStripsResponse page2 = feedService.getStrips(VIEWER_ID, FeedScope.FOLLOWING, page1.nextCursor(), 10, null);
        String cursorOfPage3 = page2.nextCursor();
        assertThat(page1.content()).isNotEmpty();
        assertThat(cursorOfPage3).isNotNull();

        ActivityPageResponse feed1 = feedService.getActivities(VIEWER_ID, HEAVY_USER_ID, null, 20);
        String deepCursor = feed1.nextCursor();
        for (int i = 0; i < 20; i++) {                                                  // 20페이지(400건) 뒤
            deepCursor = feedService.getActivities(VIEWER_ID, HEAVY_USER_ID, deepCursor, 20).nextCursor();
        }
        String deep = deepCursor;

        // 3페이지 캐시 적중을 먼저 잰다 — 아래 첫 페이지 측정이 새 항목을 계속 넣으므로 page1의 항목이 밀리기 전에
        LocalDateTime page1AsOf = page1.asOf();
        assertThat(stripOrderCache.get(VIEWER_ID, page1AsOf)).as("page1이 순서를 캐시에 넣었다").isNotNull();
        double stripsThird = measure("팔로잉 스트립 3페이지 — 캐시 적중 (size=10, cursor)",
                () -> feedService.getStrips(VIEWER_ID, FeedScope.FOLLOWING, cursorOfPage3, 10, null));
        assertThat(stripOrderCache.get(VIEWER_ID, page1AsOf)).as("적중 측정 동안 항목이 유지됐다").isNotNull();
        double stripsFirst = measure("팔로잉 스트립 첫 페이지 (size=10, me 포함)",
                () -> feedService.getStrips(VIEWER_ID, FeedScope.FOLLOWING, null, 10, null));
        double stripsThirdMiss = measure("(참고) 팔로잉 스트립 3페이지 — 캐시 미스, 경계 쿼리 대체 (기준 미적용)", () -> {
            stripOrderCache.clear();
            return feedService.getStrips(VIEWER_ID, FeedScope.FOLLOWING, cursorOfPage3, 10, null);
        });
        double stripsSeen = measure("(참고) 팔로잉 스트립 첫 페이지 + seenAfter",
                () -> feedService.getStrips(VIEWER_ID, FeedScope.FOLLOWING, null, 10, LocalDateTime.now().minusDays(3)));
        double feedFirst = measure("개인 피드 첫 페이지 (size=20, 활동 약 5천 건 사용자)",
                () -> feedService.getActivities(VIEWER_ID, HEAVY_USER_ID, null, 20));
        double feedDeep = measure("(참고) 개인 피드 깊은 페이지 (20페이지 뒤)",
                () -> feedService.getActivities(VIEWER_ID, HEAVY_USER_ID, deep, 20));

        segments(page1);
        explainAll(page1);

        report.append("\n합격 기준: 스트립 첫 페이지·3페이지(캐시 적중) p95 <= ").append(LIMIT_MS).append("ms\n");
        report.append("판정: ").append(stripsFirst <= LIMIT_MS && stripsThird <= LIMIT_MS && feedFirst <= LIMIT_MS ? "합격" : "불합격").append('\n');
        Path out = Path.of("build", "perf", "social-feed-perf.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);

        assertThat(stripsFirst).as("팔로잉 스트립 첫 페이지 p95").isLessThanOrEqualTo(LIMIT_MS);
        assertThat(stripsThird).as("팔로잉 스트립 3페이지 p95").isLessThanOrEqualTo(LIMIT_MS);
        assertThat(feedFirst).as("개인 피드 첫 페이지 p95").isLessThanOrEqualTo(LIMIT_MS);
        assertThat(stripsSeen + feedDeep + stripsThirdMiss).isPositive();                                  // 참고 항목은 판정에 쓰지 않는다
    }

    // ---- 시드 ----------------------------------------------------------------------------------------------

    private void seedIfEmpty() {
        Long users = jdbc.queryForObject("SELECT COUNT(*) FROM `user`", Long.class);
        if (users != null && users > 0) {
            return;
        }
        long started = System.nanoTime();
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new FileSystemResource("docs/schema/perf/social-feed-seed.sql"));
        populator.setSqlScriptEncoding("UTF-8");
        populator.setSeparator(";");
        populator.execute(dataSource);
        System.out.printf("시드 완료: %.0f초%n", (System.nanoTime() - started) / 1e9);
    }

    private void printDataProfile() {
        report.append("== 데이터 ==\n");
        report.append("MySQL ").append(jdbc.queryForObject("SELECT VERSION()", String.class))
                .append(" · innodb_buffer_pool_size=")
                .append(jdbc.queryForObject("SELECT @@innodb_buffer_pool_size / 1024 / 1024", Double.class).longValue()).append("MB\n");
        for (String table : List.of("user", "movie", "watch_record", "review", "wish_movie", "collection", "collection_movie", "follow")) {
            Long total = jdbc.queryForObject("SELECT COUNT(*) FROM `" + table + "`", Long.class);
            report.append(String.format("  %-17s %,10d", table, total));
            if (!List.of("user", "movie").contains(table)) {
                Long recent = jdbc.queryForObject("SELECT COUNT(*) FROM `" + table + "` WHERE created_at >= NOW() - INTERVAL 30 DAY", Long.class);
                report.append(String.format("  (최근 30일 %,d)", recent));
            }
            report.append('\n');
        }
        report.append("  viewer(user ").append(VIEWER_ID).append(") 팔로잉 ")
                .append(jdbc.queryForObject("SELECT COUNT(*) FROM follow WHERE follower_id = ?", Long.class, VIEWER_ID))
                .append("명, 볼 수 있는 대상 ").append(feedQueryRepository.findViewableFollowingIds(VIEWER_ID).size()).append("명\n");
        report.append("  개인 피드 대상(user ").append(HEAVY_USER_ID).append(") 활동 ").append(jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM watch_record WHERE user_id = ?) + (SELECT COUNT(*) FROM review WHERE user_id = ?)
                     + (SELECT COUNT(*) FROM wish_movie WHERE user_id = ?) + (SELECT COUNT(*) FROM collection WHERE user_id = ?)
                     + (SELECT COUNT(*) FROM follow WHERE follower_id = ?)
                     + (SELECT COUNT(*) FROM collection_movie cm JOIN collection c ON c.id = cm.collection_id WHERE c.user_id = ?)
                """, Long.class, HEAVY_USER_ID, HEAVY_USER_ID, HEAVY_USER_ID, HEAVY_USER_ID, HEAVY_USER_ID, HEAVY_USER_ID))
                .append("건(원시 행)\n\n");
    }

    // ---- 측정 ----------------------------------------------------------------------------------------------

    private double measure(String name, Callable<?> call) {
        try {
            for (int i = 0; i < WARMUP; i++) {
                call.call();
            }
            double[] ms = new double[REPEAT];
            for (int i = 0; i < REPEAT; i++) {
                long t = System.nanoTime();
                call.call();
                ms[i] = (System.nanoTime() - t) / 1e6;
            }
            Arrays.sort(ms);
            double p95 = ms[(int) Math.ceil(REPEAT * 0.95) - 1];
            report.append(String.format("%-52s p50 %7.1fms  p95 %7.1fms  max %7.1fms  (n=%d, warmup=%d)%n",
                    name, ms[REPEAT / 2 - 1], p95, ms[REPEAT - 1], REPEAT, WARMUP));
            return p95;
        } catch (Exception e) {
            throw new IllegalStateException(name, e);
        }
    }

    /** 첫 페이지를 이루는 구간별 시간(참고) — 어디에 시간이 쓰이는지 본다. */
    private void segments(FeedStripsResponse page1) {
        LocalDateTime asOf = LocalDateTime.now().plusSeconds(1);
        LocalDateTime since = asOf.minusDays(30);
        List<Long> targets = feedQueryRepository.findViewableFollowingIds(VIEWER_ID);
        List<Long> pageUsers = page1.content().stream().map(s -> s.user().userId()).toList();
        report.append("\n== 구간별 (참고, 서비스 밖에서 호출) ==\n");
        measure("  ① 대상 조회 findViewableFollowingIds", () -> feedQueryRepository.findViewableFollowingIds(VIEWER_ID));
        measure("  ⑤ 첫 페이지 순위 findStripOrder (대상 전원, 새 쿼리)", () -> feedQueryRepository.findStripOrder(targets, since));
        measure("  ② (대체 경로) 기간 경계 집계 findStrips (11명)", () -> feedQueryRepository.findStrips(targets, since, asOf, null, null, 11));
        measure("  ③ 프레임 findFrames (페이지 10명, 30일)", () -> feedQueryRepository.findFrames(pageUsers, since, asOf, null, 6));
        measure("  ④ me 프레임 findFrames (기간 없음)", () -> feedQueryRepository.findFrames(List.of(VIEWER_ID), null, asOf, null, 6));
    }

    // ---- EXPLAIN -------------------------------------------------------------------------------------------

    private void explainAll(FeedStripsResponse page1) {
        LocalDateTime asOf = LocalDateTime.now();
        LocalDateTime since = asOf.minusDays(30);
        String targets = feedQueryRepository.findViewableFollowingIds(VIEWER_ID).stream().map(String::valueOf).collect(Collectors.joining(","));
        String pageUsers = page1.content().stream().map(s -> String.valueOf(s.user().userId())).collect(Collectors.joining(","));

        report.append("\n== 첫 페이지 순위 집계 (4-2, 새 쿼리) — 대상 ").append(targets.split(",").length).append("명 ==\n");
        explain(bind(FeedQueryRepository.stripOrderSql(), targets, since, asOf));

        report.append("\n== (참고) 기간 경계 집계 — 캐시 미스 대체 경로 ==\n");
        explain(bind(FeedQueryRepository.stripsSql(false), targets, since, asOf));

        report.append("\n== 프레임 (4-2 ③) — 페이지 인원 ").append(pageUsers.split(",").length).append("명 ==\n");
        explain(bind(FeedQueryRepository.framesSql(true, false), pageUsers, since, asOf).replace(":frameCount", "6"));

        report.append("\n== 개인 피드 첫 페이지 (4-3) — user ").append(HEAVY_USER_ID).append(" ==\n");
        explain(bind(FeedQueryRepository.activitiesSql(false), String.valueOf(HEAVY_USER_ID), since, asOf) + "\nLIMIT 21");
    }

    private static String bind(String sql, String users, LocalDateTime since, LocalDateTime asOf) {
        return sql.replace(":users", users)
                .replace(":since", "'" + SQL_TIME.format(since) + "'")
                .replace(":asOf", "'" + SQL_TIME.format(asOf) + "'");
    }

    private void explain(String sql) {
        List<String> plain = new ArrayList<>();
        jdbc.query("EXPLAIN " + sql, rs -> {
            plain.add(String.format("  id=%s select_type=%s table=%s type=%s key=%s rows=%s Extra=%s", rs.getString("id"),
                    rs.getString("select_type"), rs.getString("table"), rs.getString("type"), rs.getString("key"),
                    rs.getString("rows"), rs.getString("Extra")));
        });
        report.append("-- EXPLAIN\n").append(String.join("\n", plain)).append('\n');
        String analyze = jdbc.queryForObject("EXPLAIN ANALYZE " + sql, String.class);
        report.append("-- EXPLAIN ANALYZE\n").append(analyze).append('\n');
        boolean groupBy = plain.stream().anyMatch(line -> line.contains("Using index for group-by")) || analyze.contains("for group-by");
        report.append("-- Using index for group-by: ").append(groupBy ? "예" : "아니오").append('\n');
    }
}
