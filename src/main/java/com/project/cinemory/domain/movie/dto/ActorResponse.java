package com.project.cinemory.domain.movie.dto;

import com.project.cinemory.domain.movie.entity.MovieActor;
import com.project.cinemory.domain.movie.entity.RoleTier;

public record ActorResponse(Long id, String name, String profilePath, String characterName, RoleTier roleTier) {

    public static ActorResponse from(MovieActor movieActor) {
        return new ActorResponse(
                movieActor.getPerson().getId(),
                movieActor.getPerson().getName(),
                movieActor.getPerson().getProfilePath(),
                movieActor.getCharacterName(),
                movieActor.getRoleTier()
        );
    }
}
