package com.project.cinemory.domain.movie.dto;

import com.project.cinemory.domain.country.entity.Country;

public record CountryResponse(Long id, String name) {

    public static CountryResponse from(Country country) {
        return new CountryResponse(country.getId(), country.getName());
    }
}
