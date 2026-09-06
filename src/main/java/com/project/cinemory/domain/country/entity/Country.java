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

    /**
     * TMDB 동기화 시 이름이 변경된 경우에만 갱신 (불필요한 dirty checking 방지).
     *
     * <p>{@code Genre.rename()}과 같은 형태다. 국가명은 거의 변하지 않지만, TMDB의
     * 국가명 한국어 지역화가 시간이 지나며 채워지는 경우가 있어 재적재로 반영할 수단이 필요하다.
     * {@code code}는 자연키라 변경 수단을 두지 않는다.
     */
    public void rename(String newName) {
        if (!this.name.equals(newName)) {
            this.name = newName;
        }
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