package com.project.cinemory.domain.country.entity;

import com.project.cinemory.domain.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.*;

@Entity
@Table(name = "country")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Country extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 2)
    private String code; // ISO 3166-1 alpha-2

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    private Country(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public static Country of(String code, String name) {
        return new Country(code, name);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Country country)) return false;
        return id != null && id.equals(country.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}