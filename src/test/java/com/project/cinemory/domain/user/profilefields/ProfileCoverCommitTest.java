package com.project.cinemory.domain.user.profilefields;

import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.user.dto.ProfileUpdateRequest;
import com.project.cinemory.domain.user.dto.SignUpLocalRequest;
import com.project.cinemory.domain.user.entity.PrivacySetting;
import com.project.cinemory.domain.user.entity.RoleType;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.domain.user.service.CoverImageService;
import com.project.cinemory.domain.user.service.ProfileImageService;
import com.project.cinemory.domain.user.service.ProfileService;
import com.project.cinemory.domain.user.service.UserService;
import com.project.cinemory.global.infra.media.InMemoryMediaStorage;
import com.project.cinemory.global.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 커밋 뒤에야 보이는 동작 (profile-spec PT-12 · PT-14 · PT-16). {@code AFTER_COMMIT}을 보려면 롤백 트랜잭션 밖에서 직접 커밋해야 하므로
 * 만든 사용자·영화는 {@code @AfterEach}에서 지운다(사용자 먼저 — 인생 영화가 영화를 RESTRICT로 잡고 있다).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProfileCoverCommitTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);
    private static final String PASSWORD = "Passw0rd!x1";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserService userService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MovieRepository movieRepository;
    @Autowired
    private ProfileService profileService;
    @Autowired
    private CoverImageService coverImageService;
    @Autowired
    private ProfileImageService profileImageService;
    @Autowired
    private InMemoryMediaStorage storage;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private final List<Long> users = new ArrayList<>();
    private final List<Movie> movies = new ArrayList<>();

    @AfterEach
    void clean() {
        storage.clear();
        users.forEach(userRepository::deleteById);
        movieRepository.deleteAll(movies);
    }

    private Long newUser() {
        Long id = userService.signUpLocal(new SignUpLocalRequest("cv-" + SEQ.incrementAndGet() + "@cv.test", PASSWORD, "커버")).id();
        users.add(id);
        return id;
    }

    private Movie newMovie(String backdropPath) {
        Movie movie = movieRepository.save(Movie.builder().tmdbId(900_000_000L + SEQ.incrementAndGet() % 90_000_000L)
                .title("커버 테스트").backdropPath(backdropPath).build());
        movies.add(movie);
        return movie;
    }

    private String uploadCover(Long userId, String name) {
        String key = "cover/" + userId + "/" + name + ".jpg";
        storage.put(key, 1000, "image/jpeg");
        return key;
    }

    private String uploadPhoto(Long userId, String name) {
        String key = "profile/" + userId + "/" + name + ".jpg";
        storage.put(key, 1000, "image/jpeg");
        return key;
    }

    private ProfileUpdateRequest request(Long userId, List<Long> favorites, Long coverMovieId) {
        return new ProfileUpdateRequest("커버", null, PrivacySetting.FRIENDS, favorites, coverMovieId);
    }

    private String coverImageOf(Long userId) {
        return jdbc.queryForObject("SELECT cover_image FROM `user` WHERE id = ?", String.class, userId);
    }

    private Long coverMovieOf(Long userId) {
        return jdbc.queryForObject("SELECT cover_movie_id FROM `user` WHERE id = ?", Long.class, userId);
    }

    // ---- PT-14 -------------------------------------------------------------------------------------

    @Test
    void PT14_업로드_커버에서_영화_커버로_바꾸면_업로드_키가_해제되고_커밋_뒤_파일이_지워진다() {
        Long me = newUser();
        Movie movie = newMovie("/back.jpg");
        String uploaded = uploadCover(me, "a");
        coverImageService.confirm(me, uploaded);
        assertThat(coverImageOf(me)).isEqualTo(uploaded);

        profileService.updateProfile(me, request(me, List.of(movie.getId()), movie.getId()));

        assertThat(coverImageOf(me)).isNull();
        assertThat(coverMovieOf(me)).isEqualTo(movie.getId());
        assertThat(storage.contains(uploaded)).isFalse();
    }

    @Test
    void PT14_영화_커버에서_업로드_커버로_바꾸면_영화_커버가_해제된다() {
        Long me = newUser();
        Movie movie = newMovie("/back.jpg");
        profileService.updateProfile(me, request(me, List.of(movie.getId()), movie.getId()));
        assertThat(coverMovieOf(me)).isEqualTo(movie.getId());

        String uploaded = uploadCover(me, "a");
        var response = coverImageService.confirm(me, uploaded);

        assertThat(coverMovieOf(me)).isNull();
        assertThat(coverImageOf(me)).isEqualTo(uploaded);
        assertThat(response.cover().type().name()).isEqualTo("IMAGE");
        // 인생 영화 목록은 그대로다 — 커버만 바뀐다
        assertThat(response.favoriteMovies()).hasSize(1);
    }

    @Test
    void 업로드_커버를_새_업로드로_교체하면_이전_파일이_지워지고_해제하면_컬럼이_NULL이다() {
        Long me = newUser();
        String first = uploadCover(me, "a");
        String second = uploadCover(me, "b");
        coverImageService.confirm(me, first);

        coverImageService.confirm(me, second);
        assertThat(storage.contains(first)).isFalse();
        assertThat(storage.contains(second)).isTrue();

        coverImageService.remove(me);
        assertThat(coverImageOf(me)).isNull();
        assertThat(storage.contains(second)).isFalse();
    }

    @Test
    void 업로드_커버_해제는_영화_커버를_건드리지_않는다() {
        Long me = newUser();
        Movie movie = newMovie("/back.jpg");
        profileService.updateProfile(me, request(me, List.of(movie.getId()), movie.getId()));

        coverImageService.remove(me);

        assertThat(coverMovieOf(me)).isEqualTo(movie.getId());
    }

    // ---- PT-16 -------------------------------------------------------------------------------------

    @Test
    void PT16_커버_확정은_남의_경로_2MB_초과_PNG_없는_객체를_INVALID_COVER_IMAGE로_거부한다() throws Exception {
        Long me = newUser();
        Long other = newUser();
        String othersKey = uploadCover(other, "a");
        String big = "cover/" + me + "/big.jpg";
        storage.put(big, 2_097_153, "image/jpeg");
        String png = "cover/" + me + "/png.jpg";
        storage.put(png, 1000, "image/png");
        String profilePath = uploadPhoto(me, "photo"); // 사진 경로로는 커버를 확정할 수 없다
        String token = "Bearer " + jwtTokenProvider.createAccessToken(me, RoleType.USER);

        for (String key : new String[]{othersKey, big, png, "cover/" + me + "/none.jpg", profilePath, "cover/" + me + "9/x.jpg"}) {
            mockMvc.perform(put("/api/users/me/cover-image").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"" + key + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_COVER_IMAGE"));
        }
        assertThat(coverImageOf(me)).isNull();

        String ok = "cover/" + me + "/ok.jpg";
        storage.put(ok, 2_097_152, "image/jpeg"); // 정확히 2MB는 통과
        mockMvc.perform(put("/api/users/me/cover-image").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"" + ok + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 커버_업로드_URL은_cover_접두의_내_경로를_준다() throws Exception {
        Long me = newUser();
        String token = "Bearer " + jwtTokenProvider.createAccessToken(me, RoleType.USER);

        mockMvc.perform(post("/api/users/me/cover-image/upload-url").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(org.hamcrest.Matchers.matchesPattern("cover/" + me + "/[0-9a-f-]{36}\\.jpg")))
                .andExpect(jsonPath("$.uploadUrl").value(org.hamcrest.Matchers.containsString("image/jpeg")));
        mockMvc.perform(post("/api/users/me/cover-image/upload-url")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/users/me/cover-image")).andExpect(status().isUnauthorized());
    }

    // ---- PT-12 -------------------------------------------------------------------------------------

    @Test
    void PT12_인생_영화와_업로드_커버가_있는_사용자가_탈퇴하면_인생_영화_행이_사라지고_두_접두가_지워진다() {
        Long me = newUser();
        Long other = newUser();
        Movie a = newMovie("/a.jpg");
        Movie b = newMovie("/b.jpg");
        profileService.updateProfile(me, request(me, List.of(a.getId(), b.getId()), null));
        profileService.updateProfile(other, request(other, List.of(a.getId()), null));
        String photo = uploadPhoto(me, "p");
        String cover = uploadCover(me, "c");
        profileImageService.confirm(me, photo);
        coverImageService.confirm(me, cover);
        String othersCover = uploadCover(other, "c");
        String neighbor = "cover/" + me + "9/c.jpg"; // 접두는 슬래시까지 — 이웃 번호 경로를 지우면 안 된다
        storage.put(neighbor, 1000, "image/jpeg");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_favorite_movie WHERE user_id = ?", Long.class, me)).isEqualTo(2L);

        userService.withdraw(me, PASSWORD);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_favorite_movie WHERE user_id = ?", Long.class, me)).isZero();
        assertThat(storage.contains(photo)).isFalse();
        assertThat(storage.contains(cover)).isFalse();
        assertThat(storage.contains(othersCover)).isTrue();
        assertThat(storage.contains(neighbor)).isTrue();
        // 다른 사용자의 인생 영화는 그대로
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_favorite_movie WHERE user_id = ?", Long.class, other)).isEqualTo(1L);
    }

    @Test
    void 탈퇴_때_프로필_접두_정리가_실패해도_커버_접두_정리를_시도한다() {
        Long me = newUser();
        uploadPhoto(me, "p");
        uploadCover(me, "c");
        storage.failOnDelete(true);

        userService.withdraw(me, PASSWORD);

        // 실패해도 예외가 새지 않고 탈퇴는 끝난다 — 두 접두를 각각 시도한다(호출 기록으로 확인)
        assertThat(storage.deletePrefixCalls()).containsExactlyInAnyOrder("profile/" + me + "/", "cover/" + me + "/");
    }
}
