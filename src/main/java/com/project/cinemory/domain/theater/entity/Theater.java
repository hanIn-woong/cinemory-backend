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

    // 공공데이터 원본 이름(정규화 전, V48, admin-spec DD-8). 동기화가 항상 갱신한다 - 관리자가 "원천은 뭐라고 왔는지" 보는 용도
    @Column(name = "source_name", length = 255)
    private String sourceName;

    // 관리자가 정한 표시 이름(V48). NULL이면 규칙 이름을 쓴다. 동기화가 덮지 않는다
    @Column(name = "name_override", length = 100)
    private String nameOverride;

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
                    BigDecimal latitude, BigDecimal longitude, Integer screenCount, Integer seatCount,
                    String sourceName) {
        this.sourceCode = sourceCode;
        this.name = name;
        this.sourceName = sourceName;
        this.chainName = chainName;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.screenCount = screenCount;
        this.seatCount = seatCount;
    }

    /** 재동기화 upsert(cinemap-spec C-4-5) — 원천이 바꿀 수 있는 값만. {@code sourceCode}와 {@code seatCount}는 건드리지 않는다. */
    public void updateFromSource(String name, String chainName, String address,
                                 BigDecimal latitude, BigDecimal longitude, Integer screenCount, String sourceName) {
        // 관리자 덮어쓰기가 있으면 규칙 이름 대신 그것을 유지한다(admin-spec DD-2·19-3)
        this.name = nameOverride != null ? nameOverride : name;
        this.sourceName = sourceName;
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

    public static final int NAME_OVERRIDE_MAX_LENGTH = 100;

    /**
     * 관리자 이름 덮어쓰기(admin-spec 19-3). 앞뒤 공백을 떼고, 빈 값은 해제로 본다. 바뀐 즉시 표시 이름을 다시 정한다 -
     * 덮어쓰기가 있으면 그 값, 해제하면 {@code ruleName}(체인 표기 통일 규칙으로 만든 이름)이다.
     * 규칙 이름은 {@code TheaterNormalizer}(service 패키지)가 만들므로 호출부가 넘긴다 - 엔티티가 서비스 유틸을 모르게 한다.
     *
     * @throws IllegalArgumentException 100자 초과
     */
    public void changeNameOverride(String override, String ruleName) {
        String trimmed = override == null ? null : override.trim();
        if (trimmed != null && trimmed.isEmpty()) {
            trimmed = null;
        }
        if (trimmed != null && trimmed.length() > NAME_OVERRIDE_MAX_LENGTH) {
            throw new IllegalArgumentException("극장 이름은 " + NAME_OVERRIDE_MAX_LENGTH + "자를 넘을 수 없습니다.");
        }
        this.nameOverride = trimmed;
        this.name = trimmed != null ? trimmed : ruleName;
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