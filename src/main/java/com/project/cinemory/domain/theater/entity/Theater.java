package com.project.cinemory.domain.theater.entity;

import com.project.cinemory.domain.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "theater")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Theater extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_code", nullable = false, unique = true, length = 255)
    private String sourceCode; // 묶음 키 = 자치단체코드|주소 기준부 (cinemap-spec C-4-2, V33에서 30→255)

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "chain_name", length = 50)
    private String chainName;

    @Column(name = "address", nullable = false)
    private String address;

    @Column(name = "latitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "screen_count")
    private Integer screenCount;

    @Column(name = "seat_count")
    private Integer seatCount;

    @Builder
    private Theater(String sourceCode, String name, String chainName, String address,
                    BigDecimal latitude, BigDecimal longitude, Integer screenCount, Integer seatCount) {
        this.sourceCode = sourceCode;
        this.name = name;
        this.chainName = chainName;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.screenCount = screenCount;
        this.seatCount = seatCount;
    }

    /** 재동기화 upsert(cinemap-spec C-4-5) — 원천이 바꿀 수 있는 값만. {@code sourceCode}와 {@code seatCount}는 건드리지 않는다. */
    public void updateFromSource(String name, String chainName, String address,
                                 BigDecimal latitude, BigDecimal longitude, Integer screenCount) {
        this.name = name;
        this.chainName = chainName;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.screenCount = screenCount;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Theater theater)) return false;
        return id != null && id.equals(theater.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}