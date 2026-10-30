package com.project.cinemory.domain.user.nickname;

import com.project.cinemory.global.infra.media.ProfileImageUrlResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** V37 — 기존 제공자 사진 일괄 제거 (profile-spec PT-1 · PR-11). 실제 마이그레이션 파일을 읽어 그대로 실행한다. */
@SpringBootTest
@Transactional
class ProviderPhotoRemovalTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ProfileImageUrlResolver resolver;

    private Long insertUser(String profileImage) {
        jdbc.update("INSERT INTO `user` (email, nickname, profile_image, privacy_setting, role) VALUES (?, ?, ?, 'FRIENDS', 'USER')",
                "v37-" + SEQ.incrementAndGet() + "@v37.test", "v37", profileImage);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private String imageOf(Long id) {
        return jdbc.queryForObject("SELECT profile_image FROM `user` WHERE id = ?", String.class, id);
    }

    private void runV37() throws Exception {
        String sql = new ClassPathResource("db/migration/V37__user_profile_image_clear_provider_urls.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        for (String line : sql.split("\n")) {
            if (!line.startsWith("--") && !line.isBlank()) {
                jdbc.execute(line.strip().replaceAll(";$", ""));
            }
        }
    }

    @Test
    void PT1_카카오_구글_URL만_NULL이_되고_우리_키와_NULL은_그대로다() throws Exception {
        Long kakao = insertUser("http://k.kakaocdn.net/dn/abc/img_640x640.jpg");
        Long kakaoHttps = insertUser("https://k.kakaocdn.net/dn/abc/img_640x640.jpg");
        Long google = insertUser("https://lh3.googleusercontent.com/a/p.jpg");
        Long ours = insertUser("profile/7/eb08257b-5e86-46f3-bd16-ceace96fe9f0.jpg");
        Long none = insertUser(null);

        runV37();

        assertThat(imageOf(kakao)).isNull();
        assertThat(imageOf(kakaoHttps)).isNull();
        assertThat(imageOf(google)).isNull();
        assertThat(imageOf(ours)).isEqualTo("profile/7/eb08257b-5e86-46f3-bd16-ceace96fe9f0.jpg");
        assertThat(imageOf(none)).isNull();
    }

    /** 이 단계가 resolver의 http 분기를 지우는 전제 — V37 이후 저장값은 NULL 또는 우리 키뿐이다. */
    @Test
    void V37_이후_http로_시작하는_값은_0건이다() throws Exception {
        insertUser("https://lh3.googleusercontent.com/a/p.jpg");
        insertUser("profile/7/a.jpg");

        runV37();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE profile_image LIKE 'http%'", Long.class)).isZero();
    }

    @Test
    void resolver는_null은_null로_키는_공개_주소로_바꾸고_http를_통과시키지_않는다() {
        assertThat(resolver.resolve(null)).isNull();
        assertThat(resolver.resolve("profile/7/a.jpg")).isEqualTo("https://media.test/profile/7/a.jpg");
        // 분기가 없으므로 제공자 URL이 남아 있었다면 그대로 나가지 않고 키로 취급된다 — V37이 먼저 지우는 이유
        assertThat(resolver.resolve("http://k.kakaocdn.net/x.jpg")).isEqualTo("https://media.test/http://k.kakaocdn.net/x.jpg");
    }
}
