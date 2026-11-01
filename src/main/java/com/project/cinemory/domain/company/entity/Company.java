package com.project.cinemory.domain.company.entity;

import com.project.cinemory.domain.common.entity.BaseCreatedAtEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.*;

import java.util.Objects;

/** TMDB 제작사(tmdb-sync-spec 6-12-3). 이름은 TMDB가 주는 그대로(대개 영문 원어 표기). */
@Entity
@Table(name = "company")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Company extends BaseCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tmdb_company_id", nullable = false, unique = true)
    private Long tmdbCompanyId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "logo_path", length = 255)
    private String logoPath;

    @Column(name = "origin_country", length = 2)
    private String originCountry;

    private Company(Long tmdbCompanyId, String name, String logoPath, String originCountry) {
        this.tmdbCompanyId = tmdbCompanyId;
        this.name = name;
        this.logoPath = logoPath;
        this.originCountry = originCountry;
    }

    public static Company of(Long tmdbCompanyId, String name, String logoPath, String originCountry) {
        return new Company(tmdbCompanyId, name, logoPath, originCountry);
    }

    /** 재동기화 갱신 — 값이 바뀐 경우에만 대입한다(불필요한 dirty checking 방지, 6-4 updateProfile과 같은 원칙). */
    public void updateFrom(String name, String logoPath, String originCountry) {
        if (!this.name.equals(name)) {
            this.name = name;
        }
        if (!Objects.equals(this.logoPath, logoPath)) {
            this.logoPath = logoPath;
        }
        if (!Objects.equals(this.originCountry, originCountry)) {
            this.originCountry = originCountry;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Company that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
