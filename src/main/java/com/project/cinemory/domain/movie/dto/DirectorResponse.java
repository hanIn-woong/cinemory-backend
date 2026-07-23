package com.project.cinemory.domain.movie.dto;

import com.project.cinemory.domain.person.entity.Person;

public record DirectorResponse(Long id, String name, String profilePath) {

    public static DirectorResponse from(Person person) {
        return new DirectorResponse(person.getId(), person.getName(), person.getProfilePath());
    }
}
