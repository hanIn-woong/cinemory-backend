package com.project.cinemory.domain.ott.entity;

import com.project.cinemory.domain.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.*;

@Entity
@Table(name = "ott_platform")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OttPlatform extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, unique = true, length = 50)
    private String name;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    private OttPlatform(String name) {
        this.name = name;
        this.active = true;
    }

    public static OttPlatform of(String name) {
        return new OttPlatform(name);
    }

    /** 서비스 종료된 OTT를 물리 삭제하지 않고 비활성화 — watch_record에서 참조 무결성 유지 */
    public void deactivate() {
        this.active = false;
    }

    public void activate() {
        this.active = true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OttPlatform that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}