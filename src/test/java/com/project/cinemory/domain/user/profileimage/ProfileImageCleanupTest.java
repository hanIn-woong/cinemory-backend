package com.project.cinemory.domain.user.profileimage;

import com.project.cinemory.domain.user.dto.SignUpLocalRequest;
import com.project.cinemory.domain.user.event.UserWithdrawnEvent;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.domain.user.service.ProfileImageService;
import com.project.cinemory.domain.user.service.UserService;
import com.project.cinemory.global.infra.media.InMemoryMediaStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 커밋 뒤 정리 (P-7 · B-5-2). {@code AFTER_COMMIT}을 보려면 테스트가 롤백 트랜잭션 안에 있으면 안 되므로 직접 커밋하고,
 * 만든 사용자는 {@code @AfterEach}에서 지운다.
 */
@SpringBootTest
class ProfileImageCleanupTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @Autowired
    private UserService userService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProfileImageService profileImageService;
    @Autowired
    private InMemoryMediaStorage storage;
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<Long> created = new ArrayList<>();

    @AfterEach
    void clean() {
        storage.clear();
        created.forEach(userRepository::deleteById);
    }

    private Long newUser() {
        Long id = userService.signUpLocal(new SignUpLocalRequest("clean-" + SEQ.incrementAndGet() + "@img.test",
                "Passw0rd!x1", "정리")).id();
        created.add(id);
        return id;
    }

    private String imageOf(Long userId) {
        return userRepository.findById(userId).orElseThrow().getProfileImage();
    }

    private String upload(Long userId, String name) {
        String key = "profile/" + userId + "/" + name + ".jpg";
        storage.put(key, 1000, "image/jpeg");
        return key;
    }

    @Test
    void 교체하면_커밋_뒤_이전_파일을_지운다() {
        Long me = newUser();
        String first = upload(me, "a");
        String second = upload(me, "b");
        profileImageService.confirm(me, first);

        profileImageService.confirm(me, second);

        assertThat(storage.contains(first)).isFalse();
        assertThat(storage.contains(second)).isTrue();
    }

    @Test
    void 같은_키를_다시_확정해도_파일은_남는다() {
        Long me = newUser();
        String key = upload(me, "a");
        profileImageService.confirm(me, key);

        profileImageService.confirm(me, key);

        assertThat(storage.contains(key)).isTrue();
    }

    @Test
    void 삭제하면_이전_파일을_지우고_컬럼은_null() {
        Long me = newUser();
        String key = upload(me, "a");
        profileImageService.confirm(me, key);

        profileImageService.remove(me);

        assertThat(storage.contains(key)).isFalse();
        assertThat(imageOf(me)).isNull();
    }

    @Test
    void 제공자_URL을_교체할_때는_지울_파일이_없어_삭제를_부르지_않는다() {
        Long me = newUser();
        // 제공자 URL 상태를 만든다(JPA 도메인 메서드 사용)
        new TransactionTemplate(transactionManager).executeWithoutResult(s ->
                userRepository.findById(me).orElseThrow().changeProfileImage("https://k.kakaocdn.net/p.jpg"));
        String key = upload(me, "a");

        profileImageService.confirm(me, key);

        assertThat(imageOf(me)).isEqualTo(key);
        assertThat(storage.deleteCalls()).isEmpty();
    }

    @Test
    void 삭제가_실패해도_교체는_성공하고_예외가_새지_않는다() {
        Long me = newUser();
        String first = upload(me, "a");
        String second = upload(me, "b");
        profileImageService.confirm(me, first);
        storage.failOnDelete(true);

        profileImageService.confirm(me, second);

        assertThat(imageOf(me)).isEqualTo(second);
        assertThat(storage.contains(first)).isTrue(); // 고아로 남을 뿐이다(P-7)
    }

    @Test
    void 롤백되면_이전_파일을_지우지_않는다() {
        Long me = newUser();
        String first = upload(me, "a");
        String second = upload(me, "b");
        profileImageService.confirm(me, first);

        new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            profileImageService.confirm(me, second);
            s.setRollbackOnly();
        });

        assertThat(storage.contains(first)).isTrue();
        assertThat(imageOf(me)).isEqualTo(first);
    }

    @Test
    void 탈퇴하면_내_접두만_지우고_남의_파일은_남긴다() {
        Long me = newUser();
        Long other = newUser();
        String mine = upload(me, "a");
        String mine2 = upload(me, "b");
        String others = upload(other, "a");
        // 접두 비교는 슬래시까지 — profile/1/ 이 profile/12/ 를 지우면 안 된다
        String prefixNeighbor = "profile/" + me + "9/a.jpg";
        storage.put(prefixNeighbor, 1000, "image/jpeg");

        new TransactionTemplate(transactionManager).executeWithoutResult(s ->
                eventPublisher.publishEvent(new UserWithdrawnEvent(me)));

        assertThat(storage.contains(mine)).isFalse();
        assertThat(storage.contains(mine2)).isFalse();
        assertThat(storage.contains(others)).isTrue();
        assertThat(storage.contains(prefixNeighbor)).isTrue();
    }

    @Test
    void 탈퇴_정리가_실패해도_예외가_새지_않는다() {
        Long me = newUser();
        upload(me, "a");
        storage.failOnDelete(true);

        new TransactionTemplate(transactionManager).executeWithoutResult(s ->
                eventPublisher.publishEvent(new UserWithdrawnEvent(me)));
    }
}
