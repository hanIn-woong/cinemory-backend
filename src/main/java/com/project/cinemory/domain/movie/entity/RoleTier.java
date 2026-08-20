package com.project.cinemory.domain.movie.entity;

/**
 * 배우의 영화 내 비중. 추천(M3) 임베딩 벡터 구성 시 {@code weight}가 직접 곱해진다.
 *
 * <p>TMDB {@code credits.cast[].order} 절대 순번으로 파생한다 (tmdb-sync-spec D-1 확정).
 * <pre>
 *   order  0 ~  4  → LEAD       (0.5)
 *   order  5 ~  9  → SUPPORTING (0.4)
 *   order 10 ~ 20  → MINOR      (0.1)
 *   order 21 ~     → EXTRA      (0.0)
 * </pre>
 *
 * <p>cast는 자르지 않고 전량 저장하되, 21번 이후는 가중치 0인 {@code EXTRA}라
 * 집계 쿼리가 필터를 걸지 않아도 추천에 기여하지 않는다. 그 결과 한 영화가 만들어내는
 * 배우 선호 총점은 출연진 수와 무관하게 최대 5.6으로 고정된다
 * (LEAD 2.5 + SUPPORTING 2.0 + MINOR 1.1).
 *
 * <p><b>⚠️ 선언 순서를 바꾸지 말 것.</b> DB 컬럼이 MySQL {@code ENUM}이라 값을 1-based
 * 인덱스로 저장한다. 중간에 값을 끼워 넣으면 기존 행의 의미가 통째로 재해석된다.
 * 새 값은 항상 맨 끝에 추가한다.
 */
public enum RoleTier {
    LEAD(0.5), SUPPORTING(0.4), MINOR(0.1), EXTRA(0.0);

    private final double weight;

    RoleTier(double weight) {
        this.weight = weight;
    }

    public double getWeight() {
        return weight;
    }

    /**
     * TMDB {@code credits.cast[].order} 절대 순번으로 tier를 파생한다 (D-1 경계값).
     *
     * <p>서비스 private 메서드가 아니라 enum에 두는 이유 — {@code displayOrder}는 이미
     * {@code MovieActor}의 필드라 도메인 개념이고, 경계값과 가중치가 한 파일에 모이며
     * M3 추천에서도 재사용된다 (tmdb-sync-spec 잔여 #15).
     */
    public static RoleTier fromDisplayOrder(int displayOrder) {
        if (displayOrder <= 4) {
            return LEAD;
        } else if (displayOrder <= 9) {
            return SUPPORTING;
        } else if (displayOrder <= 20) {
            return MINOR;
        } else {
            return EXTRA;
        }
    }
}
