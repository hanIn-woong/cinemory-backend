package com.project.cinemory.global.schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 스키마 무결성 제약 V18~V22 (account-integrity-spec A-3 ⑤) + V24 소셜 계정 연결(Part D S-2).
 *
 * <p>엔티티·서비스 검증을 거치지 않는 경로(관리자 SQL 등)에서 DB가 직접 막는지를 본다 — 그래서 JPA가 아니라
 * 네이티브 SQL로 실 DB({@code cinemory_test})에 쓴다. 위반 판정은 예외 메시지의 <b>제약 이름</b>으로 한다
 * — 다른 제약에 우연히 걸려 통과하는 것을 막기 위해서다. 각 테스트는 트랜잭션 롤백으로 정리된다.
 */
@SpringBootTest
@Transactional
class SchemaConstraintTest {

    @Autowired
    private JdbcTemplate jdbc;

    private long userId;
    private long movieId;

    @BeforeEach
    void setUp() {
        userId = insert("INSERT INTO user (email, password_hash, nickname) VALUES (?, ?, ?)",
                "schema-constraint@test.com", "{noop}password", "제약유저");
        movieId = insert("INSERT INTO movie (tmdb_id, title) VALUES (?, ?)", 97_001L, "제약 테스트 영화");
    }

    private long insert(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private long insertOttPlatform() {
        return insert("INSERT INTO ott_platform (name) VALUES (?)", "SCHEMA-TEST-OTT");
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    // ── V18 ────────────────────────────────────────────────────────────────

    @Test
    void V18_영화가_담긴_컬렉션을_지우면_collection_movie도_함께_지워진다() {
        long collectionId = insert("INSERT INTO collection (user_id, name) VALUES (?, ?)", userId, "담긴 컬렉션");
        insert("INSERT INTO collection_movie (collection_id, movie_id) VALUES (?, ?)", collectionId, movieId);

        jdbc.update("DELETE FROM collection WHERE id = ?", collectionId);

        assertThat(count("SELECT COUNT(*) FROM collection_movie WHERE collection_id = ?", collectionId)).isZero();
    }

    // ── V19 ────────────────────────────────────────────────────────────────

    @Test
    void V19_참조_중인_ott_platform은_지울_수_없다() {
        long ottId = insertOttPlatform();
        insert("INSERT INTO watch_record (user_id, movie_id, watch_type, ott_platform_id) VALUES (?, ?, 'OTT', ?)",
                userId, movieId, ottId);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM ott_platform WHERE id = ?", ottId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_watch_record_ott");
    }

    @Test
    void V19_극장_기록에_플랫폼을_지정하면_CHECK_위반이다() {
        long ottId = insertOttPlatform();

        assertThatThrownBy(() -> insert(
                "INSERT INTO watch_record (user_id, movie_id, watch_type, ott_platform_id) VALUES (?, ?, 'THEATER', ?)",
                userId, movieId, ottId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_watch_record_ott");
    }

    @Test
    void V22_관람_방식이_NULL인데_플랫폼을_지정하면_CHECK_위반이다() {
        // NULL 구멍 — V19 조건(watch_type = 'OTT' …)은 NULL을 퍼뜨려 여기서 통과해 버렸다. V22의 <=> 로 막힌다
        long ottId = insertOttPlatform();

        assertThatThrownBy(() -> insert(
                "INSERT INTO watch_record (user_id, movie_id, watch_type, ott_platform_id) VALUES (?, ?, NULL, ?)",
                userId, movieId, ottId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_watch_record_ott");
    }

    @Test
    void V19_OTT_기록에_플랫폼이_없으면_CHECK_위반이다() {
        assertThatThrownBy(() -> insert(
                "INSERT INTO watch_record (user_id, movie_id, watch_type, ott_platform_id) VALUES (?, ?, 'OTT', NULL)",
                userId, movieId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_watch_record_ott");
    }

    // ── V20 ────────────────────────────────────────────────────────────────

    @Test
    void V20_참조_중인_genre는_지울_수_없다() {
        long genreId = insert("INSERT INTO genre (tmdb_genre_id, name) VALUES (?, ?)", 990_001, "제약테스트장르");
        insert("INSERT INTO movie_genre (movie_id, genre_id) VALUES (?, ?)", movieId, genreId);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM genre WHERE id = ?", genreId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_movie_genre_genre");
    }

    // ── V21 ────────────────────────────────────────────────────────────────

    @Test
    void V21_별점이_정수_1에서_10이_아니면_CHECK_위반이다() {
        for (String invalid : new String[]{"7.5", "0", "11"}) {
            assertThatThrownBy(() -> insert(
                    "INSERT INTO watch_record (user_id, movie_id, rating) VALUES (?, ?, ?)",
                    userId, movieId, new BigDecimal(invalid)))
                    .as("rating = %s", invalid)
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("chk_watch_record_rating");
        }
    }

    @Test
    void V21_정수_별점과_미평가는_허용된다() {
        insert("INSERT INTO watch_record (user_id, movie_id, rating) VALUES (?, ?, ?)",
                userId, movieId, new BigDecimal("10"));
        insert("INSERT INTO watch_record (user_id, movie_id, rating) VALUES (?, ?, NULL)", userId, movieId);

        assertThat(count("SELECT COUNT(*) FROM watch_record WHERE user_id = ?", userId)).isEqualTo(2);
    }

    // ── V24 ────────────────────────────────────────────────────────────────

    private long insertSocialAccount(long ownerId, String provider, String providerId) {
        return insert("INSERT INTO user_social_account (user_id, provider, provider_id) VALUES (?, ?, ?)",
                ownerId, provider, providerId);
    }

    @Test
    void V24_같은_소셜_계정은_두_사용자에게_연결될_수_없다() {
        long otherUserId = insert("INSERT INTO user (email, nickname) VALUES (?, ?)", "schema-social@test.com", "소셜유저");
        insertSocialAccount(userId, "KAKAO", "schema-3000000001");

        assertThatThrownBy(() -> insertSocialAccount(otherUserId, "KAKAO", "schema-3000000001"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_user_social_account_provider");
    }

    @Test
    void V24_한_사용자에게_같은_제공자는_하나만_연결된다() {
        insertSocialAccount(userId, "KAKAO", "schema-3000000001");

        assertThatThrownBy(() -> insertSocialAccount(userId, "KAKAO", "schema-3000000002"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_user_social_account_user_provider");
    }

    @Test
    void V24_사용자를_지우면_연결된_소셜_계정도_함께_지워진다() {
        insertSocialAccount(userId, "KAKAO", "schema-3000000001");

        jdbc.update("DELETE FROM user WHERE id = ?", userId);

        assertThat(count("SELECT COUNT(*) FROM user_social_account WHERE user_id = ?", userId)).isZero();
    }

    /**
     * V24는 확장 전용이다(deploy-spec D-3 조건 6) — 옛 코드(V23까지의 {@code User})가 매핑하는 컬럼과 그 조회 인덱스가 남아 있어야
     * CI 자동 롤백·로컬 브랜치 전환 때 옛 코드가 기동한다. 이 테스트가 깨졌다면 축소가 섞인 것이다 — 축소는 다음 릴리스의 V에서만 한다.
     * (축소 마이그레이션을 추가할 때 이 테스트를 함께 지운다.)
     */
    @Test
    void V24_확장_전용이라_옛_코드의_provider_컬럼과_uk_user_provider가_남아_있다() {
        assertThat(count("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME IN ('provider', 'provider_id')
                """)).isEqualTo(2);
        assertThat(count("""
                SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND INDEX_NAME = 'uk_user_provider'
                """)).isEqualTo(1);
    }

    @Test
    void V24_chk_user_auth_method가_사라져_비밀번호와_소셜_연결을_함께_가질_수_있다() {
        // setUp의 userId는 비밀번호가 있는 로컬 가입자다 — V24 이전에는 소셜을 겸할 수 없었다
        insertSocialAccount(userId, "KAKAO", "schema-3000000001");

        assertThat(count("SELECT COUNT(*) FROM user_social_account WHERE user_id = ?", userId)).isEqualTo(1);
        assertThat(count("""
                SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND CONSTRAINT_NAME = 'chk_user_auth_method'
                """)).isZero();
    }
}
