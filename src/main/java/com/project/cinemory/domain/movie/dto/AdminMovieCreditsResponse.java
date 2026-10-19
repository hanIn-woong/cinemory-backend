package com.project.cinemory.domain.movie.dto;

import com.project.cinemory.domain.movie.entity.MovieActor;
import com.project.cinemory.domain.movie.entity.MovieDirector;
import com.project.cinemory.domain.movie.entity.RoleTier;
import com.project.cinemory.domain.person.entity.Person;

import java.util.List;

/**
 * 관리자 영화 출연진·감독(admin-spec 12-3) — 인물 한글명 편집 동선의 출발점(BD-8). 이름은 표시 이름이 아니라
 * TMDB 원본({@code name})과 한글명({@code nameKo})을 따로 싣는다. 출연진은 앱 영화 상세가 보여 주는 범위와 같다.
 */
public record AdminMovieCreditsResponse(List<AdminCreditItem> directors, List<AdminCreditItem> cast) {

    /** {@code displayOrder}·{@code roleTier}·{@code characterName}은 출연진만 값이 있다(감독은 null). */
    public record AdminCreditItem(
            Long personId,
            Long tmdbPersonId,
            String name,
            String nameKo,
            String profilePath,
            Integer displayOrder,
            RoleTier roleTier,
            String characterName
    ) {

        public static AdminCreditItem from(MovieDirector movieDirector) {
            Person person = movieDirector.getPerson();
            return new AdminCreditItem(person.getId(), person.getTmdbPersonId(), person.getName(), person.getNameKo(),
                    person.getProfilePath(), null, null, null);
        }

        public static AdminCreditItem from(MovieActor movieActor) {
            Person person = movieActor.getPerson();
            return new AdminCreditItem(person.getId(), person.getTmdbPersonId(), person.getName(), person.getNameKo(),
                    person.getProfilePath(), movieActor.getDisplayOrder(), movieActor.getRoleTier(),
                    movieActor.getCharacterName());
        }
    }
}
