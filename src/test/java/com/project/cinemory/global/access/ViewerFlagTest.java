package com.project.cinemory.global.access;

import com.project.cinemory.domain.collection.entity.Collection;
import com.project.cinemory.domain.collection.repository.CollectionRepository;
import com.project.cinemory.domain.comment.entity.Comment;
import com.project.cinemory.domain.comment.entity.TargetType;
import com.project.cinemory.domain.comment.repository.CommentRepository;
import com.project.cinemory.domain.follow.entity.Follow;
import com.project.cinemory.domain.follow.repository.FollowRepository;
import com.project.cinemory.domain.user.entity.PrivacySetting;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 5-7 C-2 — 응답 DTO에 viewer 계산값을 담는 3개 엔드포인트가 비로그인 호출에서 전부 {@code false}를
 * 내려주는지 확인한다({@code controller-layer-spec.md} 5-7-C 재개정판 기준). 대상은
 * {@code FollowUserResponse.following}, {@code UserProfileResponse.following/me},
 * {@code CommentResponse.editable/deletable}이 전부다 — 나머지 도메인은 viewerId를 응답 필드가
 * 아니라 접근 제어에만 쓰므로 C-3 소관이다.
 *
 * <p><b>{@code @SpringBootTest} + {@code MockMvc} + {@code @Transactional}을 쓰는 이유</b> —
 * 실제 팔로우 관계·댓글 행이 DB에 있어야 의미가 있는 검증인데, {@code RANDOM_PORT}는 톰캣이
 * 별도 스레드·트랜잭션으로 요청을 처리해 테스트 트랜잭션 롤백이 닿지 않는다. MockMvc는 같은
 * 스레드에서 {@code DispatcherServlet}을 직접 호출하므로 시딩한 데이터가 그대로 보이고,
 * 테스트가 끝나면 롤백돼 잔여 행이 남지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ViewerFlagTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FollowRepository followRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private CollectionRepository collectionRepository;

    private User createPublicUser(String email, String nickname) {
        User user = User.createLocal(email, "{noop}password", nickname);
        user.changePrivacySetting(PrivacySetting.PUBLIC);
        return userRepository.save(user);
    }

    @Test
    void 팔로워_목록_비로그인_조회는_following이_false다() throws Exception {
        User target = createPublicUser("viewerflag-follow-target@test.com", "타깃");
        User follower = createPublicUser("viewerflag-follow-follower@test.com", "팔로워");
        followRepository.save(Follow.of(follower, target));

        mockMvc.perform(get("/api/users/{userId}/followers", target.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].userId").value(follower.getId()))
                .andExpect(jsonPath("$.content[0].following").value(false));
    }

    @Test
    void 프로필_비로그인_조회는_following과_me가_false다() throws Exception {
        User target = createPublicUser("viewerflag-profile-target@test.com", "프로필타깃");

        mockMvc.perform(get("/api/users/{userId}/profile", target.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.following").value(false))
                .andExpect(jsonPath("$.me").value(false));
    }

    @Test
    void 댓글_목록_비로그인_조회는_editable_deletable이_false다() throws Exception {
        User owner = createPublicUser("viewerflag-comment-owner@test.com", "컬렉션주인");
        Collection collection = collectionRepository.save(Collection.of(owner, "테스트 컬렉션", null));

        User author = createPublicUser("viewerflag-comment-author@test.com", "댓글작성자");
        Comment comment = Comment.builder()
                .user(author)
                .targetType(TargetType.COLLECTION)
                .targetId(collection.getId())
                .content("좋은 컬렉션이네요")
                .build();
        commentRepository.save(comment);

        mockMvc.perform(get("/api/comments")
                        .param("targetType", "COLLECTION")
                        .param("targetId", collection.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].content").value("좋은 컬렉션이네요"))
                .andExpect(jsonPath("$.content[0].editable").value(false))
                .andExpect(jsonPath("$.content[0].deletable").value(false));
    }
}
