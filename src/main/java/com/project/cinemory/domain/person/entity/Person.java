package com.project.cinemory.domain.person.entity;

import com.project.cinemory.domain.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.*;

import java.util.Objects;

@Entity
@Table(name = "person")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Person extends BaseTimeEntity {

    public static final int NAME_KO_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tmdb_person_id", nullable = false, unique = true)
    private Long tmdbPersonId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "profile_path", length = 500)
    private String profilePath;

    // 관리자가 직접 넣는 한글 표기(V28, admin-spec BD-7). TMDB 재동기화는 건드리지 않는다 — name은 TMDB 원본으로 남는다.
    @Column(name = "name_ko", length = 100)
    private String nameKo;

    private Person(Long tmdbPersonId, String name, String profilePath) {
        this.tmdbPersonId = tmdbPersonId;
        this.name = name;
        this.profilePath = profilePath;
    }

    public static Person of(Long tmdbPersonId, String name, String profilePath) {
        return new Person(tmdbPersonId, name, profilePath);
    }

    /**
     * TMDB 재동기화 시 값이 바뀐 경우에만 갱신한다 (불필요한 dirty checking 방지).
     *
     * <p>{@code Genre.rename()} / {@code Country.rename()}과 같은 원칙이다. 무조건 대입하면
     * cast 재동기화마다 출연진 전원에게 UPDATE가 나간다. {@code profilePath}가 nullable이라
     * {@code Objects.equals}로 비교한다.
     */
    public void updateProfile(String name, String profilePath) {
        if (!Objects.equals(this.name, name)) {
            this.name = name;
        }
        if (!Objects.equals(this.profilePath, profilePath)) {
            this.profilePath = profilePath;
        }
    }

    /**
     * 한글명을 바꾼다 — 앞뒤 공백을 자르고, 빈 문자열·{@code null}은 "해제"({@code null})로 저장한다.
     * 100자를 넘으면 {@code IllegalArgumentException}(요청 검증이 먼저 막지만 도메인 불변식으로도 지킨다).
     */
    public void changeNameKo(String nameKo) {
        String normalized = nameKo == null ? null : nameKo.trim();
        if (normalized != null && normalized.isEmpty()) {
            normalized = null;
        }
        if (normalized != null && normalized.length() > NAME_KO_MAX_LENGTH) {
            throw new IllegalArgumentException("한글명은 " + NAME_KO_MAX_LENGTH + "자를 넘을 수 없습니다.");
        }
        this.nameKo = normalized;
    }

    /** 앱·리포트에 내보내는 표시 이름 — 한글명이 있으면 그것, 없으면 TMDB 원본(admin-spec BD-7). */
    public String displayName() {
        return nameKo != null ? nameKo : name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Person person)) return false;
        return id != null && id.equals(person.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}