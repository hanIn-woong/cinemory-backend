package com.project.cinemory.domain.user.profilefields;

import com.project.cinemory.domain.follow.entity.Follow;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.user.entity.PrivacySetting;
import com.project.cinemory.domain.user.entity.RoleType;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 프로필 필드 통합 저장·조회 (profile-spec PT-2·5·7~11·13·15·17). 실 DB({@code cinemory_test})를 쓰고 테스트마다 롤백한다.
 * 커밋 뒤 동작(PT-12·14·16)은 {@code ProfileCoverCommitTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProfileFieldsApiTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    // ---- 픽스처 -------------------------------------------------------------------------------------

    private User user(String nickname, PrivacySetting privacy) {
        User user = User.createOAuth("pf-" + SEQ.incrementAndGet() + "@pf.test", nickname, null);
        user.changePrivacySetting(privacy);
        em.persist(user);
        em.flush();
        return user;
    }

    private Movie movie(String backdropPath) {
        Movie movie = Movie.builder().tmdbId(900_000_000L + SEQ.incrementAndGet() % 90_000_000L).title("프로필 테스트 영화")
                .posterPath("/poster.jpg").backdropPath(backdropPath).build();
        em.persist(movie);
        em.flush();
        return movie;
    }

    private String token(User user) {
        return "Bearer " + jwtTokenProvider.createAccessToken(user.getId(), RoleType.USER);
    }

    private static String body(String nickname, String bio, String privacy, List<Long> favoriteIds, Long coverMovieId) {
        String ids = favoriteIds == null ? "null" : favoriteIds.toString();
        return "{\"nickname\":" + JSON.writeValueAsString(nickname)
                + ",\"bio\":" + (bio == null ? "null" : JSON.writeValueAsString(bio))
                + ",\"privacySetting\":\"" + privacy + "\""
                + ",\"favoriteMovieIds\":" + ids
                + ",\"coverMovieId\":" + coverMovieId + "}";
    }

    private ResultActions saveProfile(User user, String body) throws Exception {
        return mockMvc.perform(put("/api/users/me/profile").header("Authorization", token(user))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions save(User user, List<Long> favoriteIds, Long coverMovieId) throws Exception {
        return saveProfile(user, body(user.getNickname(), null, "FRIENDS", favoriteIds, coverMovieId));
    }

    private JsonNode profileAs(User viewer, User target) throws Exception {
        var request = get("/api/users/" + target.getId() + "/profile");
        if (viewer != null) {
            request = request.header("Authorization", token(viewer));
        }
        var response = mockMvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private List<Long> favoriteMovieIdsInDb(User user) {
        em.flush();
        return jdbc.queryForList("SELECT movie_id FROM user_favorite_movie WHERE user_id = ? ORDER BY position",
                Long.class, user.getId());
    }

    private static List<Long> ids(JsonNode favorites) {
        List<Long> result = new ArrayList<>();
        favorites.forEach(node -> result.add(node.get("movieId").asLong()));
        return result;
    }

    // ---- PT-7 인생 영화 교체 ------------------------------------------------------------------------

    @Test
    void PT7_인생_영화_ABC_에서_CA_로_다시_빈_목록으로_교체해도_제약을_위반하지_않는다() throws Exception {
        User me = user("내이름", PrivacySetting.FRIENDS);
        Movie a = movie("/a.jpg");
        Movie b = movie("/b.jpg");
        Movie c = movie("/c.jpg");

        JsonNode first = JSON.readTree(save(me, List.of(a.getId(), b.getId(), c.getId()), null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(ids(first.get("favoriteMovies"))).containsExactly(a.getId(), b.getId(), c.getId());
        assertThat(favoriteMovieIdsInDb(me)).containsExactly(a.getId(), b.getId(), c.getId());
        // 같은 영화가 다른 칸으로 옮겨 가는 교체가 UNIQUE를 위반하지 않는지(벌크 삭제 → 삽입 순서) — C가 1칸으로, A가 2칸으로
        JsonNode second = JSON.readTree(save(me, List.of(c.getId(), a.getId()), null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(ids(second.get("favoriteMovies"))).containsExactly(c.getId(), a.getId());
        assertThat(favoriteMovieIdsInDb(me)).containsExactly(c.getId(), a.getId());

        save(me, List.of(), null).andExpect(status().isOk()).andExpect(jsonPath("$.favoriteMovies").isEmpty());
        assertThat(favoriteMovieIdsInDb(me)).isEmpty();
    }

    @Test
    void PT7_같은_영화의_순서만_바꿔도_된다() throws Exception {
        User me = user("내이름", PrivacySetting.FRIENDS);
        Movie a = movie("/a.jpg");
        Movie b = movie("/b.jpg");
        save(me, List.of(a.getId(), b.getId()), null).andExpect(status().isOk());

        save(me, List.of(b.getId(), a.getId()), null).andExpect(status().isOk());

        assertThat(favoriteMovieIdsInDb(me)).containsExactly(b.getId(), a.getId());
    }

    // ---- PT-8 검증 ---------------------------------------------------------------------------------

    @Test
    void PT8_네_편_중복_없는_영화는_각각_400_400_404이고_기존_목록을_바꾸지_않는다() throws Exception {
        User me = user("내이름", PrivacySetting.FRIENDS);
        Movie a = movie("/a.jpg");
        Movie b = movie("/b.jpg");
        Movie c = movie("/c.jpg");
        Movie d = movie("/d.jpg");
        save(me, List.of(a.getId()), null).andExpect(status().isOk());

        save(me, List.of(a.getId(), b.getId(), c.getId(), d.getId()), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"))
                .andExpect(jsonPath("$.errors[0].field").value("favoriteMovieIds"));
        save(me, List.of(a.getId(), a.getId()), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("favoriteMovieIds"));
        save(me, List.of(a.getId(), 999_999_999L), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MOVIE_NOT_FOUND"));

        assertThat(favoriteMovieIdsInDb(me)).containsExactly(a.getId());
    }

    @Test
    void 인생_영화_목록이_빠지면_400이다_전체_치환이라_빈_배열을_명시한다() throws Exception {
        User me = user("내이름", PrivacySetting.FRIENDS);
        saveProfile(me, body("내이름", null, "FRIENDS", null, null)).andExpect(status().isBadRequest());
    }

    // ---- PT-9 영화 커버 ----------------------------------------------------------------------------

    @Test
    void PT9_커버_영화가_목록_밖이거나_배경이_없으면_400이고_null은_200이다() throws Exception {
        User me = user("내이름", PrivacySetting.FRIENDS);
        Movie withBackdrop = movie("/back.jpg");
        Movie outside = movie("/outside.jpg");
        Movie noBackdrop = movie(null);
        Movie blankBackdrop = movie("  ");

        save(me, List.of(withBackdrop.getId()), outside.getId())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_COVER_MOVIE"));
        save(me, List.of(noBackdrop.getId()), noBackdrop.getId())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_COVER_MOVIE"));
        save(me, List.of(blankBackdrop.getId()), blankBackdrop.getId())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_COVER_MOVIE"));
        save(me, List.of(withBackdrop.getId()), withBackdrop.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cover.type").value("MOVIE"))
                .andExpect(jsonPath("$.cover.movieId").value(withBackdrop.getId()))
                .andExpect(jsonPath("$.cover.backdropPath").value("/back.jpg"))
                .andExpect(jsonPath("$.cover.title").value("프로필 테스트 영화"))
                .andExpect(jsonPath("$.cover.imageUrl").doesNotExist());
        save(me, List.of(withBackdrop.getId()), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cover").doesNotExist());
    }

    // ---- PT-13 커버 영화 삭제 ----------------------------------------------------------------------

    @Test
    void PT13_커버_영화를_삭제하면_cover_movie_id가_NULL이_된다() throws Exception {
        User me = user("내이름", PrivacySetting.FRIENDS);
        Movie cover = movie("/cover.jpg");
        // 인생 영화에 담긴 영화는 RESTRICT로 지울 수 없다 — 관리 SQL이 지우는 상황을 만들려고 커버만 직접 지정한다
        jdbc.update("UPDATE `user` SET cover_movie_id = ? WHERE id = ?", cover.getId(), me.getId());
        em.clear();

        jdbc.update("DELETE FROM movie WHERE id = ?", cover.getId());

        assertThat(jdbc.queryForObject("SELECT cover_movie_id FROM `user` WHERE id = ?", Long.class, me.getId())).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE id = ?", Long.class, me.getId())).isEqualTo(1L);
    }

    @Test
    void 인생_영화로_담긴_영화는_삭제할_수_없다_RESTRICT() throws Exception {
        User me = user("내이름", PrivacySetting.FRIENDS);
        Movie a = movie("/a.jpg");
        save(me, List.of(a.getId()), null).andExpect(status().isOk());
        em.flush();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("DELETE FROM movie WHERE id = ?", a.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // ---- PT-2 / PT-5 / PT-3: PUT /profile의 닉네임 -------------------------------------------------

    @Test
    void PT2_PUT_profile도_닉네임_규칙_위반을_400으로_거부한다() throws Exception {
        User me = user("원래이름", PrivacySetting.FRIENDS);
        for (String nickname : new String[]{"가", "가".repeat(21), "   ", "CineMory팬", "영화\n광"}) {
            saveProfile(me, body(nickname, null, "FRIENDS", List.of(), null))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"))
                    .andExpect(jsonPath("$.errors[0].field").value("nickname"));
        }
    }

    @Test
    void PT3_PUT_profile은_다른_사용자와_같은_닉네임도_허용한다() throws Exception {
        User other = user("같은이름", PrivacySetting.FRIENDS);
        User me = user("내이름", PrivacySetting.FRIENDS);

        saveProfile(me, body(other.getNickname(), null, "FRIENDS", List.of(), null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("같은이름"));
    }

    @Test
    void PT5_21자_이상_기존_닉네임_사용자가_bio만_바꿔도_저장된다() throws Exception {
        String legacy = "가".repeat(30);
        User me = user(legacy, PrivacySetting.FRIENDS);

        saveProfile(me, body(legacy, "소개만 바꿉니다", "FRIENDS", List.of(), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value(legacy))
                .andExpect(jsonPath("$.bio").value("소개만 바꿉니다"));
        // 닉네임을 다른 값으로 바꿀 때는 새 규칙이다
        saveProfile(me, body("가".repeat(25), "소개", "FRIENDS", List.of(), null)).andExpect(status().isBadRequest());
    }

    // ---- 자기소개 (PR-2) ---------------------------------------------------------------------------

    @Test
    void 자기소개는_앞뒤_공백을_제거하고_빈_문자열은_null이며_150자_줄바꿈_2개까지다() throws Exception {
        User me = user("내이름", PrivacySetting.FRIENDS);

        saveProfile(me, body("내이름", "  안녕하세요  ", "FRIENDS", List.of(), null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bio").value("안녕하세요"));
        saveProfile(me, body("내이름", "   ", "FRIENDS", List.of(), null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bio").doesNotExist());
        saveProfile(me, body("내이름", "1\n2\n3", "FRIENDS", List.of(), null)).andExpect(status().isOk());
        saveProfile(me, body("내이름", "1\r\n2\r\n3", "FRIENDS", List.of(), null)).andExpect(status().isOk());
        saveProfile(me, body("내이름", "가".repeat(150), "FRIENDS", List.of(), null)).andExpect(status().isOk());

        for (String tooMuch : new String[]{"1\n2\n3\n4", "가".repeat(151), "😀".repeat(151)}) {
            saveProfile(me, body("내이름", tooMuch, "FRIENDS", List.of(), null))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"))
                    .andExpect(jsonPath("$.errors[0].field").value("bio"));
        }
        saveProfile(me, body("내이름", "😀".repeat(150), "FRIENDS", List.of(), null)).andExpect(status().isOk());
    }

    // ---- PT-10 / PT-11 프로필 조회 -----------------------------------------------------------------

    @Test
    void PT10_PRIVATE_프로필을_다른_사람이_보면_제한_응답이고_본인은_전부_본다() throws Exception {
        User target = user("비공개", PrivacySetting.FRIENDS);
        Movie a = movie("/a.jpg");
        saveProfile(target, body("비공개", "비밀 소개", "PRIVATE", List.of(a.getId()), a.getId())).andExpect(status().isOk());
        User viewer = user("조회자", PrivacySetting.FRIENDS);
        em.persist(Follow.of(viewer, target));
        em.persist(Follow.of(target, viewer));
        em.flush();
        em.clear();

        for (User as : new User[]{viewer, null}) {
            JsonNode restricted = profileAs(as, target);
            assertThat(restricted.get("restricted").asBoolean()).isTrue();
            assertThat(restricted.get("nickname").asString()).isEqualTo("비공개");
            assertThat(restricted.get("privacySetting").asString()).isEqualTo("PRIVATE");
            for (String hidden : new String[]{"bio", "favoriteMovies", "cover", "followerCount", "followingCount"}) {
                assertThat(restricted.path(hidden).isNull() || restricted.path(hidden).isMissingNode())
                        .as(hidden + " 은 null이어야 한다").isTrue();
            }
        }
        // 맞팔로우여도 PRIVATE 헤더는 제한이다 — 헤더 규칙은 FRIENDS 본문 규칙과 별개다
        assertThat(profileAs(viewer, target).get("following").asBoolean()).isTrue();

        JsonNode mine = profileAs(target, target);
        assertThat(mine.get("restricted").asBoolean()).isFalse();
        assertThat(mine.get("me").asBoolean()).isTrue();
        assertThat(mine.get("bio").asString()).isEqualTo("비밀 소개");
        assertThat(ids(mine.get("favoriteMovies"))).containsExactly(a.getId());
        assertThat(mine.get("cover").get("movieId").asLong()).isEqualTo(a.getId());
        assertThat(mine.get("followerCount").asLong()).isEqualTo(1L);
        assertThat(mine.get("followingCount").asLong()).isEqualTo(1L);
    }

    @Test
    void PT11_PUBLIC과_맞팔_아닌_FRIENDS는_헤더_전부를_본다() throws Exception {
        Movie a = movie("/a.jpg");
        Movie b = movie("/b.jpg");
        User publicUser = user("공개", PrivacySetting.FRIENDS);
        saveProfile(publicUser, body("공개", "공개 소개", "PUBLIC", List.of(a.getId(), b.getId()), b.getId())).andExpect(status().isOk());
        User friendsUser = user("친구", PrivacySetting.FRIENDS);
        saveProfile(friendsUser, body("친구", "친구 소개", "FRIENDS", List.of(a.getId()), null)).andExpect(status().isOk());
        User stranger = user("남", PrivacySetting.FRIENDS);
        em.flush();
        em.clear();

        JsonNode publicProfile = profileAs(stranger, publicUser);
        assertThat(publicProfile.get("restricted").asBoolean()).isFalse();
        assertThat(publicProfile.get("bio").asString()).isEqualTo("공개 소개");
        assertThat(ids(publicProfile.get("favoriteMovies"))).containsExactly(a.getId(), b.getId());
        assertThat(publicProfile.get("cover").get("movieId").asLong()).isEqualTo(b.getId());
        assertThat(publicProfile.get("followerCount").asLong()).isZero();

        JsonNode friendsProfile = profileAs(stranger, friendsUser); // 비맞팔 FRIENDS도 헤더는 보인다
        assertThat(friendsProfile.get("restricted").asBoolean()).isFalse();
        assertThat(friendsProfile.get("bio").asString()).isEqualTo("친구 소개");
        assertThat(friendsProfile.path("cover").isNull() || friendsProfile.path("cover").isMissingNode()).isTrue();
    }

    @Test
    void GET_me는_인생_영화와_커버와_자기소개를_싣는다() throws Exception {
        User me = user("내이름", PrivacySetting.FRIENDS);
        Movie a = movie("/a.jpg");
        saveProfile(me, body("내이름", "내 소개", "FRIENDS", List.of(a.getId()), a.getId())).andExpect(status().isOk());
        em.flush();
        em.clear();

        mockMvc.perform(get("/api/users/me").header("Authorization", token(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bio").value("내 소개"))
                .andExpect(jsonPath("$.favoriteMovies[0].movieId").value(a.getId()))
                .andExpect(jsonPath("$.favoriteMovies[0].posterPath").value("/poster.jpg"))
                .andExpect(jsonPath("$.favoriteMovies[0].backdropPath").value("/a.jpg"))
                .andExpect(jsonPath("$.cover.type").value("MOVIE"));
    }

    // ---- PT-15 / PT-17 ----------------------------------------------------------------------------

    @Test
    void PT15_업로드_커버가_있는_상태에서_coverMovieId_null을_보내도_업로드_커버는_유지된다() throws Exception {
        User me = user("내이름", PrivacySetting.FRIENDS);
        jdbc.update("UPDATE `user` SET cover_image = ? WHERE id = ?", "cover/" + me.getId() + "/x.jpg", me.getId());
        em.clear();

        save(me, List.of(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cover.type").value("IMAGE"))
                .andExpect(jsonPath("$.cover.imageUrl").value("https://media.test/cover/" + me.getId() + "/x.jpg"));
        assertThat(jdbc.queryForObject("SELECT cover_image FROM `user` WHERE id = ?", String.class, me.getId()))
                .isEqualTo("cover/" + me.getId() + "/x.jpg");
    }

    @Test
    void 영화_커버를_고르면_업로드_커버는_해제되고_영화_커버만_해제하는_null은_영화를_지운다() throws Exception {
        User me = user("내이름", PrivacySetting.FRIENDS);
        Movie a = movie("/a.jpg");
        jdbc.update("UPDATE `user` SET cover_image = ? WHERE id = ?", "cover/" + me.getId() + "/x.jpg", me.getId());
        em.clear();

        save(me, List.of(a.getId()), a.getId()).andExpect(status().isOk()).andExpect(jsonPath("$.cover.type").value("MOVIE"));
        em.flush();
        assertThat(jdbc.queryForObject("SELECT cover_image FROM `user` WHERE id = ?", String.class, me.getId())).isNull();

        save(me, List.of(a.getId()), null).andExpect(status().isOk()).andExpect(jsonPath("$.cover").doesNotExist());
        em.flush();
        assertThat(jdbc.queryForObject("SELECT cover_movie_id FROM `user` WHERE id = ?", Long.class, me.getId())).isNull();
    }

    @Test
    void PT17_PATCH_privacy와_PUT_profile의_공개_범위_변경은_결과가_같다() throws Exception {
        User viaPatch = user("패치", PrivacySetting.FRIENDS);
        User viaPut = user("풋", PrivacySetting.FRIENDS);

        mockMvc.perform(patch("/api/users/me/privacy").header("Authorization", token(viaPatch))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"privacySetting\":\"PRIVATE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.privacySetting").value("PRIVATE"));
        saveProfile(viaPut, body("풋", null, "PRIVATE", List.of(), null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.privacySetting").value("PRIVATE"));
        em.flush();
        em.clear();

        assertThat(em.find(User.class, viaPatch.getId()).getPrivacySetting()).isEqualTo(PrivacySetting.PRIVATE);
        assertThat(em.find(User.class, viaPut.getId()).getPrivacySetting()).isEqualTo(PrivacySetting.PRIVATE);
    }

    // ---- PATCH 유지 (PR-12) ------------------------------------------------------------------------

    @Test
    void PATCH_nickname과_privacy는_유지되고_응답에_새_필드가_있다() throws Exception {
        User me = user("원래", PrivacySetting.FRIENDS);

        mockMvc.perform(patch("/api/users/me/nickname").header("Authorization", token(me))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"바뀜\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("바뀜"))
                .andExpect(jsonPath("$.favoriteMovies").isArray());
    }
}
