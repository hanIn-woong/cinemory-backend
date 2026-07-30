package com.project.cinemory.domain.user.entity;

/**
 * 사용자 권한. 고정 폐쇄 집합이고 FK 참조가 없어 참조 테이블이 아닌 ENUM 컬럼으로 둔다.
 *
 * <p>권한 승격 API는 만들지 않는다. 승격 엔드포인트는 그 자체가 공격 표면이고,
 * 관리자는 운영자 1명이라 DB에서 직접 UPDATE하면 충분하다.
 */
public enum RoleType {

    USER,
    ADMIN;

    /**
     * Spring Security의 {@code hasRole()}은 비교 시 "ROLE_" 접두사를 자동으로 부착하므로,
     * Authentication에 담는 authority 문자열에는 접두사를 <b>포함</b>시켜야 한다.
     * 접두사를 빠뜨리면 {@code /api/admin/**}이 무조건 403이 되고 원인 추적이 까다롭다.
     */
    public String authority() {
        return "ROLE_" + name();
    }
}
