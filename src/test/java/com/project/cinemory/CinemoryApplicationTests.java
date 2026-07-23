package com.project.cinemory;

import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest // 스프링 부트 환경과 MySQL을 그대로 연결해서 테스트합니다.
@Transactional // 테스트가 끝나면 DB에 넣었던 데이터를 깔끔하게 다시 지워줍니다(롤백).
class CinemoryApplicationTests {

	@Autowired
	MovieRepository movieRepository;

	@Test
	@DisplayName("Movie 엔티티가 MySQL에 정상적으로 저장되고 조회되는지 테스트한다.")
	void saveAndFindMovieTest() {
		// 1. Given (데이터 준비)
		Movie movie = Movie.builder()
				.tmdbId(157336L)
				.title("인터스텔라")
				.releaseDate(LocalDate.of(2014, 11, 6))
				.overview("우린 답을 찾을 것이다, 늘 그랬듯이.")
				.runtime(169)
				.build();

		// 2. When (실행: DB에 저장)
		Movie savedMovie = movieRepository.save(movie);

		// 3. Then (검증: 저장된 데이터가 내 예상과 같은지 확인)
		assertThat(savedMovie.getId()).isNotNull(); // PK(id)가 자동으로 생성되었는가?
		assertThat(savedMovie.getTitle()).isEqualTo("인터스텔라"); // 제목이 정확히 들어갔는가?

		System.out.println("매핑 성공! 생성된 영화 ID: " + savedMovie.getId());
	}
}