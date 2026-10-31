package com.project.cinemory.domain.watch.entity;

/**
 * 관람 방식 (service-layer-spec 4-3-B). {@code @Enumerated(STRING)}이라 선언 순서는 의미가 없다.
 *
 * <p>{@code place_detail}은 방식별 상세다 — 극장 이름 · TV 채널 · VOD 서비스 · 기타 장소 · OTT 직접 입력 플랫폼 이름. DISC·일반 OTT는 쓰지 않는다.
 * {@code OTT}만 {@code ott_platform_id}를 가지며(DB CHECK와 Service 검증), 나머지는 모두 플랫폼이 없어야 한다.
 */
public enum WatchType {
    /** 극장 — 일반 상영 · 영화제 · GV · 시네마테크 */
    THEATER,
    /** OTT — 구독형 + 플랫폼 */
    OTT,
    /** 기타 — 기내 · 수업 · 상영회 등 */
    ETC,
    /** TV 방송 — 지상파 · 케이블 영화 채널 */
    TV,
    /** VOD — IPTV · 스토어 구매·대여(구독형 OTT와 분리) */
    VOD,
    /** DVD·블루레이 — 물리 매체 */
    DISC
}
