package com.project.cinemory.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;


@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED) // 빈 객체 생성 방어
@AllArgsConstructor(access = AccessLevel.PRIVATE) // builder 사용용
public class Movie extends BaseEntity {

    @Id // PK
    @GeneratedValue(strategy = GenerationType.IDENTITY) //데이터가 들어올 때마다 자동 번호 입력
    private Long id;

    private Long tmdbId;
    private String title;
    private String posterPath;
    private LocalDate releaseDate;

    @Column(length = 1000)
    private String overview;

    private Integer runtime;
}
