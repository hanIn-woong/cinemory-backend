package com.project.cinemory.domain.collection.service;

import com.project.cinemory.domain.collection.dto.AddMoviesToCollectionRequest;
import com.project.cinemory.domain.collection.dto.CollectionCreateRequest;
import com.project.cinemory.domain.collection.dto.CollectionMovieListItemResponse;
import com.project.cinemory.domain.collection.dto.CollectionMovieOrderRequest;
import com.project.cinemory.domain.collection.dto.CollectionOrderRequest;
import com.project.cinemory.domain.collection.dto.CollectionResponse;
import com.project.cinemory.domain.collection.entity.Collection;
import com.project.cinemory.domain.collection.repository.CollectionRepository;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.user.entity.PrivacySetting;
import com.project.cinemory.domain.user.entity.User;
import com.project.cinemory.domain.user.repository.UserRepository;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 컬렉션 사용자 지정 순서(position)와 미리보기 포스터 검증(controller-layer-spec 5-4-A, service-layer-spec 4-5-A).
 * 미리보기는 윈도 함수 native query라 실 DB로 고정한다.
 */
@SpringBootTest
@Transactional
class CollectionOrderTest {

    @Autowired
    private CollectionService collectionService;
    @Autowired
    private CollectionRepository collectionRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MovieRepository movieRepository;
    @Autowired
    private EntityManager entityManager;

    private User user;

    @BeforeEach
    void setUp() {
        user = createUser("order-user@test.com", "순서유저");
    }

    private User createUser(String email, String nickname) {
        User created = userRepository.save(User.createLocal(email, "{noop}password", nickname));
        created.changePrivacySetting(PrivacySetting.PUBLIC);
        return created;
    }

    private Movie createMovie(long tmdbId, String posterPath) {
        return movieRepository.save(Movie.builder().tmdbId(tmdbId).title("영화 " + tmdbId).posterPath(posterPath).build());
    }

    private Long createCollection(String name) {
        return collectionService.createCollection(user.getId(), new CollectionCreateRequest(name, null)).id();
    }

    private List<Long> collectionIds() {
        return collectionService.getCollections(user.getId(), user.getId(), PageRequest.of(0, 20))
                .map(CollectionResponse::id).getContent();
    }

    private List<Long> movieIds(Long collectionId) {
        return collectionService.getCollectionMovies(user.getId(), collectionId, PageRequest.of(0, 20))
                .map(CollectionMovieListItemResponse::movieId).getContent();
    }

    /** 재작성은 dirty checking이다 — 영속성 컨텍스트를 비워 DB에 실제로 쓰인 값으로 검증한다. */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void 새_컬렉션은_맨_위에_오고_클라이언트_자유_sort는_무시된다() {
        Long first = createCollection("첫째");
        Long second = createCollection("둘째");
        Long third = createCollection("셋째");

        assertThat(collectionIds()).containsExactly(third, second, first);

        List<Long> withClientSort = collectionService.getCollections(user.getId(), user.getId(),
                        PageRequest.of(0, 20, Sort.by("id")))
                .map(CollectionResponse::id).getContent();
        assertThat(withClientSort).containsExactly(third, second, first);
    }

    @Test
    void 컬렉션_순서를_받은_배열대로_재작성한다() {
        Long a = createCollection("A");
        Long b = createCollection("B");
        Long c = createCollection("C");

        collectionService.reorderCollections(user.getId(), new CollectionOrderRequest(List.of(b, a, c)));
        flushAndClear();

        assertThat(collectionIds()).containsExactly(b, a, c);
    }

    @Test
    void 재작성_집합이_소유_집합과_다르면_400이다() {
        Long a = createCollection("A");
        Long b = createCollection("B");
        User other = createUser("order-other@test.com", "남의유저");
        Long othersId = collectionService.createCollection(other.getId(), new CollectionCreateRequest("남의 것", null)).id();

        // 누락 · 중복(크기는 같다) · 남의 것 섞임
        for (List<Long> invalid : List.of(List.of(a), List.of(a, a), List.of(a, b, othersId))) {
            assertThatThrownBy(() -> collectionService.reorderCollections(user.getId(), new CollectionOrderRequest(invalid)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    @Test
    void 벌크_추가는_요청_배열_순서대로_MIN_1_MIN_2를_받고_재작성할_수_있다() {
        Long collectionId = createCollection("영화들");
        Movie m1 = createMovie(96_001L, "/p1.jpg");
        Movie m2 = createMovie(96_002L, "/p2.jpg");
        Movie m3 = createMovie(96_003L, "/p3.jpg");

        collectionService.addMoviesToCollection(user.getId(), collectionId,
                new AddMoviesToCollectionRequest(List.of(m1.getId(), m2.getId())));
        collectionService.addMoviesToCollection(user.getId(), collectionId,
                new AddMoviesToCollectionRequest(List.of(m3.getId())));

        // m1=-1, m2=-2, m3=-3 → 각 값이 달라 순서가 정해진다
        assertThat(movieIds(collectionId)).containsExactly(m3.getId(), m2.getId(), m1.getId());

        collectionService.reorderCollectionMovies(user.getId(), collectionId,
                new CollectionMovieOrderRequest(List.of(m1.getId(), m3.getId(), m2.getId())));
        flushAndClear();

        assertThat(movieIds(collectionId)).containsExactly(m1.getId(), m3.getId(), m2.getId());

        assertThatThrownBy(() -> collectionService.reorderCollectionMovies(user.getId(), collectionId,
                new CollectionMovieOrderRequest(List.of(m1.getId(), m3.getId()))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 미리보기_포스터는_position_순서로_최대_5장이고_포스터_없는_영화는_빠진다() {
        Long collectionId = createCollection("포스터");
        List<Long> added = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            added.add(createMovie(96_100L + i, i == 1 ? null : "/poster" + i + ".jpg").getId());
        }
        collectionService.addMoviesToCollection(user.getId(), collectionId, new AddMoviesToCollectionRequest(added));
        // 추가 순서의 역순이 아니라 원래 순서(0..6)로 재배치 — 미리보기가 position ASC를 따르는지 본다
        collectionService.reorderCollectionMovies(user.getId(), collectionId, new CollectionMovieOrderRequest(added));
        flushAndClear();

        CollectionResponse response = collectionService.getCollections(user.getId(), user.getId(), PageRequest.of(0, 20))
                .getContent().get(0);

        assertThat(response.movieCount()).isEqualTo(7);
        assertThat(response.previewPosterPaths())
                .containsExactly("/poster0.jpg", "/poster2.jpg", "/poster3.jpg", "/poster4.jpg", "/poster5.jpg");
    }

    @Test
    void position이_겹쳐도_페이지_경계에서_중복_누락이_없다() {
        // 동시 생성으로 MIN-1이 겹친 상황 — 보조키 id DESC가 없으면 페이지 사이 순서가 보장되지 않는다
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            expected.add(collectionRepository.save(
                    Collection.builder().user(user).name("동률 " + i).position(0).build()).getId());
        }

        List<Long> paged = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            paged.addAll(collectionService.getCollections(user.getId(), user.getId(), PageRequest.of(page, 10))
                    .map(CollectionResponse::id).getContent());
        }

        assertThat(paged).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void 컬렉션이_없으면_벌크_쿼리를_건너뛰고_빈_페이지를_준다() {
        assertThat(collectionService.getCollections(user.getId(), user.getId(), PageRequest.of(0, 20))).isEmpty();
    }
}
