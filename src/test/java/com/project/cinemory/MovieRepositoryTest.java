package com.project.cinemory;

import com.project.cinemory.domain.common.entity.*;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;


@SpringBootTest // Spring Boot 환경과 MySQL을 그대로 연결해서 테스트
@Transactional // 테스트가 끝나면 DB에 넣었던 데이터를 모두 삭제(롤백).
class MovieRepositoryTest {

    @Autowired
    MovieRepository movieRepository;

    @Test
    @DisplayName("Movie 엔티티가 MySQL에 정상적으로 저장되고 조회되는지 테스트한다.")
    void saveAndFindMovieTest() {
        // 1. Given
        Movie movie = Movie.builder()
                .tmdbId(313369L)
                .title("라라랜드")
                .releaseDate(LocalDate.of(2016, 12, 7))
                .overview("꿈을 꾸는 사람들을 위한 별들의 노래")
                .runtime(128)
                .build();

        // 2. DB에 저장
        Movie savedMovie = movieRepository.save(movie);

        // 3. 검증: 저장된 데이터가 내 예상과 같은지 확인
        assertThat(savedMovie.getId()).isNotNull();
        assertThat(savedMovie.getTitle()).isEqualTo("라라랜드");

        System.out.println("영화 매핑 성공. 생성된 영화 ID: " + savedMovie.getId());
    }

    @Autowired WatchRecordRepository watchRecordRepository;
    @Autowired WishMovieRepository wishMovieRepository;

    @Test
    @DisplayName("특정 영화에 대한 시청 기록과 위시리스트 목록이 정상적으로 연관되어 저장되는지 테스트한다.")
    void movieRelationTest() {
        Movie movie = Movie.builder()
                .title("라라랜드")
                .tmdbId(313369L)
                .build();
        movieRepository.save(movie);

        WatchRecord record = WatchRecord.builder()
                .movie(movie)
                .rating(5.0)
                .review("영화 리뷰 텍스트 입니다.")
                .watchDate(LocalDate.now())
                .build();
        watchRecordRepository.save(record);

        WishMovie wish = WishMovie.builder()
                .movie(movie)
                .build();
        wishMovieRepository.save(wish);

        WatchRecord savedRecord = watchRecordRepository.findById(record.getId()).orElseThrow();
        assertThat(savedRecord.getMovie().getTitle()).isEqualTo("라라랜드");

        WishMovie savedWish = wishMovieRepository.findById(wish.getId()).orElseThrow();
        assertThat(savedWish.getMovie().getId()).isEqualTo(movie.getId());

        System.out.println("연관 관계 매핑 성공. 영화 '" + savedRecord.getMovie().getTitle() + "'의 기록이 생성되었습니다.");
    }

    
    @Autowired CollectionRepository collectionRepository;
    @Autowired CollectionMovieRepository collectionMovieRepository;

    @Test
    @DisplayName("컬렉션과 영화가 중간 매핑 테이블을 통해 정상적으로 연결되는지 확인한다.")
    void collectionMovieRelationTest() {
        // 1. Given: 영화와 컬렉션을 각각 생성 (양쪽 부모 데이터)
        Movie movie = Movie.builder()
                .title("라라랜드")
                .tmdbId(313369L)
                .build();
        movieRepository.save(movie);

        Collection collection = Collection.builder()
                .name("내 컬렉션")
                .build();
        collectionRepository.save(collection);

        // 2. When: 두 객체를 연결하는 매핑 데이터 생성 (자식 데이터)
        CollectionMovie mapping = CollectionMovie.builder()
                .collection(collection)
                .movie(movie)
                .build();
        collectionMovieRepository.save(mapping);

        // 3. Then: 매핑 테이블을 조회해서 양쪽 데이터가 잘 들어갔는지 검증
        CollectionMovie savedMapping = collectionMovieRepository
                .findById(mapping.getId())
                .orElseThrow();

        assertThat(savedMapping.getCollection().getName()).isEqualTo("내 컬렉션");
        assertThat(savedMapping.getMovie().getTitle()).isEqualTo("라라랜드");

        System.out.println("컬렉션 매핑 성공. [" + savedMapping.getCollection().getName() +
                "] 컬렉션에 [" + savedMapping.getMovie().getTitle() + "] 영화가 담겼습니다.");
    }
}