package com.project.cinemory.domain.keyword.entity;

import com.project.cinemory.domain.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.*;

/** TMDB 키워드(영문). 임베딩 입력용 참조 테이블 — tmdb-sync-spec 6-11. */
@Entity
@Table(name = "keyword")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Keyword extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tmdb_keyword_id", nullable = false, unique = true)
    private Long tmdbKeywordId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    private Keyword(Long tmdbKeywordId, String name) {
        this.tmdbKeywordId = tmdbKeywordId;
        this.name = name;
    }

    public static Keyword of(Long tmdbKeywordId, String name) {
        return new Keyword(tmdbKeywordId, name);
    }

    /** TMDB 동기화 시 이름이 변경된 경우에만 갱신 (불필요한 dirty checking 방지) */
    public void rename(String newName) {
        if (!this.name.equals(newName)) {
            this.name = newName;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Keyword keyword)) return false;
        return id != null && id.equals(keyword.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
