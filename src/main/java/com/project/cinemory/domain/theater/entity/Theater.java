package com.project.cinemory.domain.theater.entity;

import com.project.cinemory.domain.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

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

    // 관리자가 입력하는 예매 링크(V34, cinemap-spec C-8-2). 시드 upsert는 건드리지 않는다.
    @Column(name = "booking_url", length = 500)
    private String bookingUrl;

    // 폐업 숨김 시각(V34, C-5-1). NULL이면 영업 중 — 주변 극장 조회는 이 경우만 읽는다.
    @Column(name = "closed_at")
    private Instant closedAt;

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

    /** 동기화에서 묶음이 사라졌을 때 숨긴다(삭제하지 않는다 — 예매 링크 보존, 다시 나타나면 복구). 이미 숨겨져 있으면 시각을 유지한다. */
    public void close(Instant at) {
        if (this.closedAt == null) {
            this.closedAt = at;
        }
    }

    /** 숨긴 극장이 원천에 다시 나타났을 때 복구한다. */
    public void reopen() {
        this.closedAt = null;
    }

    public boolean isClosed() {
        return closedAt != null;
    }

    /** 예매 링크 대입. 형식 검증은 서비스 몫이다({@code null} = 삭제). */
    public void updateBookingUrl(String bookingUrl) {
        this.bookingUrl = bookingUrl;
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