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

    @Column(name = "source_code", nullable = false, unique = true, length = 30)
    private String sourceCode; // 전국영화상영관표준데이터 고유 코드

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