package com.project.cinemory.domain.movie.dto;

import com.project.cinemory.domain.company.entity.Company;

/** 영화 상세의 제작사(service-layer-spec 4-2-B). 로고는 화면이 쓰지 않아 싣지 않는다. */
public record CompanyResponse(Long id, String name) {

    public static CompanyResponse from(Company company) {
        return new CompanyResponse(company.getId(), company.getName());
    }
}
