package com.project.cinemory.domain.common.entity;

import com.project.cinemory.domain.movie.entity.Movie;
import jakarta.persistence.*;
import lombok.*;
import jakarta.persistence.Id;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class WishMovie extends BaseCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 찜 목록과 영화의 관계 (하나의 찜 데이터는 하나의 영화만 가리킴)
    // unique = true: 같은 영화를 중복해서 찜하는 것을 DB 단에서 차단
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movie_id", unique = true)
    private Movie movie;
}
