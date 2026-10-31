package com.project.cinemory.domain.settings;

import com.project.cinemory.domain.collection.entity.Collection;
import com.project.cinemory.domain.collection.entity.CollectionMovie;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.ott.entity.OttPlatform;
import com.project.cinemory.domain.review.entity.Review;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.watch.entity.WatchRecord;
import com.project.cinemory.domain.watch.entity.WatchType;
import com.project.cinemory.domain.wish.entity.WishMovie;
import com.project.cinemory.global.security.JwtTokenProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * 내 기록 내보내기 (settings-spec ST-5, 테스트 ST-T2~T7). 실 DB({@code cinemory_test})를 쓰고 테스트마다 롤백한다.
 * ZIP은 응답 바이트를 그대로 풀어 검증한다 — 구현의 CSV 쓰기와 별개인 RFC 4180 파서로 되읽는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ExportApiTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private EntityManager em;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    // ---- 픽스처 -------------------------------------------------------------------------------------

    private User user() {
        User user = User.createOAuth("export" + SEQ.incrementAndGet() + "@export.test", "내보내기" + SEQ.get() % 10_000, null);
        em.persist(user);
        return user;
    }

    private Movie movie(String title, int year) {
        Movie movie = Movie.builder().tmdbId(800_000_000L + SEQ.incrementAndGet() % 90_000_000L)
                .title(title).originalTitle(title + " (original)").releaseDate(LocalDate.of(year, 5, 1)).build();
        em.persist(movie);
        return movie;
    }

    private WatchRecord watch(User user, Movie movie, LocalDate date, String rating) {
        WatchRecord record = WatchRecord.builder().user(user).movie(movie).watchDate(date)
                .watchType(WatchType.THEATER).placeDetail("용산 CGV")
                .rating(rating == null ? null : new BigDecimal(rating)).privateReview("메모").build();
        em.persist(record);
        return record;
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenProvider.createAccessToken(user.getId(), user.getRole());
    }

    /** 링크를 발급받아 token 값만 돌려준다. */
    private String issueToken(User user) throws Exception {
        em.flush();
        MvcResult result = mockMvc.perform(post("/api/users/me/export").header("Authorization", bearer(user))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String url = body.get("downloadUrl").asString();
        assertThat(url).contains("/api/export/download?token=");
        assertThat(body.get("expiresAt").asString()).isNotBlank();
        return url.substring(url.indexOf("token=") + "token=".length());
    }

    private MvcResult download(String token) throws Exception {
        return fetch("/api/export/download?token=" + token);
    }

    /**
     * 성공(ZIP)도 실패(410 HTML)도 응답 본문이 {@code StreamingResponseBody}라 비동기로 쓰인다 — 완료를 기다리지 않고
     * 본문을 읽으면 스레드 타이밍에 따라 빈 문자열이 읽힌다(토큰 누락 케이스에서 CI가 간헐적으로 실패했다).
     */
    private MvcResult fetch(String uri) throws Exception {
        MvcResult started = mockMvc.perform(get(URI.create(uri))).andReturn();
        if (started.getRequest().isAsyncStarted()) {
            started.getAsyncResult();
        }
        return started;
    }

    /** ZIP 안의 파일 → 바이트(BOM 포함 원본). 파일 순서를 유지한다. */
    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                files.put(entry.getName(), in.readAllBytes());
            }
        }
        return files;
    }

    private static List<List<String>> csv(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertThat(text).startsWith("﻿");
        return parseCsv(text.substring(1));
    }

    private static Map<String, byte[]> zipOf(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return unzip(result.getResponse().getContentAsByteArray());
    }

    /** 구현과 별개인 RFC 4180 파서 — 큰따옴표 안의 쉼표·줄바꿈·"" 이스케이프를 푼다. */
    static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                i++;
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    // ---- ST-T2 --------------------------------------------------------------------------------------

    @Test
    void T2_발급_후_다운로드는_ZIP_4개이고_같은_링크_재사용은_410이다() throws Exception {
        User me = user();
        Movie movie = movie("기생충", 2019);
        watch(me, movie, LocalDate.of(2024, 3, 1), "9.0");
        em.persist(Review.of(me, movie, "좋았다"));
        em.persist(WishMovie.of(me, movie("괴물", 2006)));
        Collection collection = Collection.builder().user(me).name("봉준호").description("모음").position(0).build();
        em.persist(collection);
        em.persist(CollectionMovie.of(collection, movie, 0));

        String token = issueToken(me);
        MvcResult first = download(token);

        assertThat(first.getResponse().getHeader("Content-Type")).startsWith("application/zip");
        assertThat(first.getResponse().getHeader("Content-Disposition"))
                .startsWith("attachment").contains("cinemory-export-").contains(".zip");
        assertThat(first.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(first.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        Map<String, byte[]> files = zipOf(first);
        assertThat(files.keySet()).containsExactly("watch_records.csv", "reviews.csv", "wishes.csv", "collections.csv");

        // 4개 모두 BOM으로 시작하고 열 이름이 2-2 표 그대로다
        assertThat(csv(files.get("watch_records.csv")).get(0)).containsExactly("WatchedDate", "Title", "OriginalTitle",
                "Year", "tmdbID", "Rating10", "Rating5", "WatchType", "Place", "OttPlatform", "PrivateMemo", "Rewatch", "CreatedAt");
        assertThat(csv(files.get("reviews.csv")).get(0)).containsExactly("Title", "Year", "tmdbID", "Review", "CreatedAt", "UpdatedAt");
        assertThat(csv(files.get("wishes.csv")).get(0)).containsExactly("Title", "Year", "tmdbID", "AddedAt");
        assertThat(csv(files.get("collections.csv")).get(0)).containsExactly("Collection", "Description", "CollectionOrder",
                "Title", "Year", "tmdbID", "MovieOrder");

        List<String> watchRow = csv(files.get("watch_records.csv")).get(1);
        assertThat(watchRow.subList(0, 12)).containsExactly("2024-03-01", "기생충", "기생충 (original)", "2019",
                String.valueOf(movie.getTmdbId()), "9", "4.5", "THEATER", "용산 CGV", "", "메모", "false");
        assertThat(csv(files.get("wishes.csv"))).hasSize(2);
        assertThat(csv(files.get("collections.csv")).get(1).subList(0, 3)).containsExactly("봉준호", "모음", "0");

        // 같은 링크를 다시 열면 410 + HTML
        MvcResult second = download(token);
        assertThat(second.getResponse().getStatus()).isEqualTo(410);
        assertThat(second.getResponse().getContentType()).startsWith("text/html");
        assertThat(second.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("링크가 만료됐어요");
        assertThat(second.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    }

    @Test
    void T2_빈_컬렉션도_한_줄은_남고_평점이_없으면_빈_칸이다() throws Exception {
        User me = user();
        watch(me, movie("무평점", 2020), null, null);
        em.persist(Collection.builder().user(me).name("빈 컬렉션").position(0).build());

        Map<String, byte[]> files = zipOf(download(issueToken(me)));

        List<String> watchRow = csv(files.get("watch_records.csv")).get(1);
        assertThat(watchRow.get(0)).isEmpty();   // WatchedDate
        assertThat(watchRow.get(5)).isEmpty();   // Rating10
        assertThat(watchRow.get(6)).isEmpty();   // Rating5
        assertThat(csv(files.get("collections.csv")).get(1)).containsExactly("빈 컬렉션", "", "0", "", "", "", "");
    }

    // ---- ST-T3 --------------------------------------------------------------------------------------

    @Test
    void T3_잘못된_토큰_빈_토큰_토큰_누락은_모두_같은_410이다() throws Exception {
        MvcResult wrong = download("not-a-real-token");
        MvcResult blank = download("");
        MvcResult missing = fetch("/api/export/download");

        for (MvcResult result : List.of(wrong, blank, missing)) {
            assertThat(result.getResponse().getStatus()).isEqualTo(410);
            assertThat(result.getResponse().getContentType()).startsWith("text/html");
        }
        String body = wrong.getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 빈 문자열끼리 같아 보이는 것을 막는다
        assertThat(body).contains("링크가 만료됐어요");
        assertThat(blank.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(body);
        assertThat(missing.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(body);
    }

    // ---- ST-T4 --------------------------------------------------------------------------------------

    @Test
    void T4_1분_안에_두_번_발급하면_두_번째는_429다() throws Exception {
        User me = user();
        em.flush();

        MvcResult first = mockMvc.perform(post("/api/users/me/export").header("Authorization", bearer(me))).andReturn();
        MvcResult second = mockMvc.perform(post("/api/users/me/export").header("Authorization", bearer(me))).andReturn();

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getStatus()).isEqualTo(429);
        JsonNode body = JSON.readTree(second.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.get("code").asString()).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(Long.parseLong(second.getResponse().getHeader("Retry-After"))).isBetween(1L, 60L);

        // 다른 사용자는 영향이 없다
        User other = user();
        em.flush();
        assertThat(mockMvc.perform(post("/api/users/me/export").header("Authorization", bearer(other)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void 인증_없이_발급을_요청하면_401이다() throws Exception {
        assertThat(mockMvc.perform(post("/api/users/me/export")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    // ---- ST-T5 --------------------------------------------------------------------------------------

    @Test
    void T5_남의_데이터가_섞이지_않는다() throws Exception {
        User a = user();
        User b = user();
        Movie shared = movie("공유 영화", 2021);
        Movie onlyA = movie("에이만 본 영화", 2020);
        Movie onlyB = movie("비만 본 영화", 2022);
        watch(a, shared, LocalDate.of(2024, 1, 1), "8.0");
        watch(a, onlyA, LocalDate.of(2024, 1, 2), "7.0");
        watch(b, shared, LocalDate.of(2024, 2, 1), "5.0");
        watch(b, onlyB, LocalDate.of(2024, 2, 2), "6.0");
        em.persist(Review.of(a, onlyA, "A의 리뷰"));
        em.persist(Review.of(b, onlyB, "B의 리뷰"));
        em.persist(WishMovie.of(b, onlyB));
        Collection bCollection = Collection.builder().user(b).name("B의 컬렉션").position(0).build();
        em.persist(bCollection);
        em.persist(CollectionMovie.of(bCollection, onlyB, 0));

        Map<String, byte[]> aFiles = zipOf(download(issueToken(a)));
        Map<String, byte[]> bFiles = zipOf(download(issueToken(b)));

        List<List<String>> aWatch = csv(aFiles.get("watch_records.csv"));
        assertThat(aWatch).hasSize(3);
        assertThat(aWatch.stream().skip(1).map(r -> r.get(1))).containsExactlyInAnyOrder("공유 영화", "에이만 본 영화");
        assertThat(csv(aFiles.get("reviews.csv")).stream().skip(1).map(r -> r.get(3))).containsExactly("A의 리뷰");
        assertThat(csv(aFiles.get("wishes.csv"))).hasSize(1);       // 머리글뿐
        assertThat(csv(aFiles.get("collections.csv"))).hasSize(1);  // 머리글뿐

        List<List<String>> bWatch = csv(bFiles.get("watch_records.csv"));
        assertThat(bWatch.stream().skip(1).map(r -> r.get(1))).containsExactlyInAnyOrder("공유 영화", "비만 본 영화");
        assertThat(csv(bFiles.get("reviews.csv")).stream().skip(1).map(r -> r.get(3))).containsExactly("B의 리뷰");
        assertThat(csv(bFiles.get("wishes.csv"))).hasSize(2);
        assertThat(csv(bFiles.get("collections.csv")).get(1).get(0)).isEqualTo("B의 컬렉션");
    }

    // ---- ST-T6 --------------------------------------------------------------------------------------

    @Test
    void T6_재관람은_관람일_오름차순_기준으로_두_번째부터_true다() throws Exception {
        User me = user();
        Movie movie = movie("세 번 본 영화", 2018);
        // 삽입 순서를 관람일과 어긋나게 한다 — 정렬 기준이 id가 아니라 관람일임을 보인다
        watch(me, movie, LocalDate.of(2024, 6, 1), "8.0");
        watch(me, movie, LocalDate.of(2024, 1, 1), "6.0");
        watch(me, movie, LocalDate.of(2024, 3, 1), "7.0");
        watch(me, movie("한 번 본 영화", 2017), LocalDate.of(2024, 2, 1), "5.0");

        List<List<String>> rows = csv(zipOf(download(issueToken(me))).get("watch_records.csv"));

        assertThat(rows.stream().skip(1).map(r -> r.get(0) + ":" + r.get(11)))
                .containsExactly("2024-01-01:false", "2024-02-01:false", "2024-03-01:true", "2024-06-01:true");
    }

    // ---- ST-T7 --------------------------------------------------------------------------------------

    @Test
    void T7_리뷰의_쉼표_따옴표_줄바꿈은_파서로_원문이_복원된다() throws Exception {
        User me = user();
        String original = "좋았다, 정말로 \"최고\"였다\n둘째 줄\r\n셋째 줄,끝";
        em.persist(Review.of(me, movie("따옴표, \"영화\"", 2019), original));

        byte[] reviewsCsv = zipOf(download(issueToken(me))).get("reviews.csv");
        List<List<String>> rows = csv(reviewsCsv);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).get(0)).isEqualTo("따옴표, \"영화\"");
        assertThat(rows.get(1).get(3)).isEqualTo(original);
        // 원본 바이트도 RFC 4180 모양이다 — 줄 끝은 CRLF, 내부 큰따옴표는 두 번
        String raw = new String(reviewsCsv, StandardCharsets.UTF_8);
        assertThat(raw).contains("\"좋았다, 정말로 \"\"최고\"\"였다");
        assertThat(raw).endsWith("\r\n");
    }
}
