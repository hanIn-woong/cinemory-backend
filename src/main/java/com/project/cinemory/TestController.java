package com.project.cinemory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@RestController
public class TestController {

    @Value("${tmdb.api.token}")
    private String tmdbToken;

    @GetMapping(value = "/test/movie")
    public String searchMovieTest() {
        // 최신 스프링의 HTTP 통신 객체 생성
        RestClient restClient = RestClient.create();

        // TMDB로 '라라랜드' 검색 요청
        return restClient.get()
                .uri("https://api.themoviedb.org/3/search/movie?query=라라랜드&language=ko-KR")
                .header("Authorization", "Bearer " + tmdbToken) // 실무 API 인증 표준 방식
                .retrieve()
                .body(String.class); // 결과를 문자열(JSON)로 그대로 받습니다.
    }

    // 브라우저가 단순 글자가 아닌 'HTML 문서'로 인식하도록 produces 옵션을 추가합니다.
    @GetMapping(value = "/test/movie/poster", produces = "text/html;charset=UTF-8")
    public String searchMoviePosterTest() throws Exception {

        // 1. TMDB에 데이터 요청하기
        RestClient restClient = RestClient.create();
        String responseJson = restClient.get()
                .uri("https://api.themoviedb.org/3/search/movie?query=라라랜드&language=ko-KR")
                .header("Authorization", "Bearer " + tmdbToken)
                .retrieve()
                .body(String.class);

        // 2. 받은 JSON 데이터 파싱하기 (Jackson 라이브러리 사용)
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(responseJson);

        // 첫 번째 검색 결과(results[0])의 포스터 경로(poster_path)만 쏙 뽑아냅니다.
        String posterPath = root.path("results").get(0).path("poster_path").asText();

        // 3. TMDB 공통 이미지 주소 + 뽑아낸 파일 경로 합치기 (w500은 이미지 가로 사이즈입니다)
        String imageUrl = "https://image.tmdb.org/t/p/w200" + posterPath;

        // 4. 브라우저가 이미지를 그릴 수 있도록 HTML <img> 태그로 만들어서 반환!
        return "<h2>CineMory TMDB 연동 테스트</h2>" +
                "<img src='" + imageUrl + "' alt='영화 포스터' style='border-radius: 10px; box-shadow: 5px 5px 15px rgba(0,0,0,0.3);'>";
    }
}