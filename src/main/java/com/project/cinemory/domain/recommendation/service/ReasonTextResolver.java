package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.country.entity.Country;
import com.project.cinemory.domain.country.repository.CountryRepository;
import com.project.cinemory.domain.genre.entity.Genre;
import com.project.cinemory.domain.genre.repository.GenreRepository;
import com.project.cinemory.domain.person.entity.Person;
import com.project.cinemory.domain.person.repository.PersonRepository;
import com.project.cinemory.domain.recommendation.dto.ReasonType;
import com.project.cinemory.domain.recommendation.dto.RecommendReasonResponse;
import com.project.cinemory.domain.recommendation.service.RecommendationScorer.ReasonChoice;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 이유 대상 id → 표시 이름 → 한국어 문구 (S-1-5 · W-1-4). 순위가 확정된 최대 100편의 이유만 모아 IN 조회 3회로 끝낸다.
 * 인물명은 표시명 규칙({@code COALESCE(name_ko, name)})을 따른다.
 */
@Component
@RequiredArgsConstructor
public class ReasonTextResolver {

    private final GenreRepository genreRepository;
    private final CountryRepository countryRepository;
    private final PersonRepository personRepository;

    /** 이유 선택 목록(null 포함 가능)을 같은 순서의 응답으로 바꾼다. */
    @Transactional(readOnly = true)
    public java.util.List<RecommendReasonResponse> resolve(java.util.List<ReasonChoice> choices) {
        Set<Long> genreIds = new HashSet<>();
        Set<Long> countryIds = new HashSet<>();
        Set<Long> personIds = new HashSet<>();
        for (ReasonChoice choice : choices) {
            if (choice == null || choice.targetId() == null) {
                continue;
            }
            switch (choice.type()) {
                case GENRE, WISH_GENRE -> genreIds.add(choice.targetId());
                case COUNTRY, WISH_COUNTRY -> countryIds.add(choice.targetId());
                case DIRECTOR, ACTOR, WISH_DIRECTOR -> personIds.add(choice.targetId());
                default -> { }
            }
        }
        Map<Long, String> genres = names(genreRepository.findAllById(genreIds), Genre::getId, Genre::getName);
        Map<Long, String> countries = names(countryRepository.findAllById(countryIds), Country::getId, Country::getName);
        Map<Long, String> people = names(personRepository.findAllById(personIds), Person::getId, Person::displayName);

        java.util.List<RecommendReasonResponse> result = new java.util.ArrayList<>(choices.size());
        for (ReasonChoice choice : choices) {
            result.add(choice == null ? null : toResponse(choice, genres, countries, people));
        }
        return result;
    }

    private RecommendReasonResponse toResponse(ReasonChoice choice, Map<Long, String> genres,
                                               Map<Long, String> countries, Map<Long, String> people) {
        ReasonType type = choice.type();
        return switch (type) {
            case QUALITY -> new RecommendReasonResponse(type, null, null, "평이 좋은 작품");
            case POPULARITY -> new RecommendReasonResponse(type, null, null, "많은 사람이 본 작품");
            case DIRECTOR -> named(choice, people, name -> "좋아하는 감독 " + name + "의 작품");
            case ACTOR -> named(choice, people, name -> "좋아하는 배우 " + name + " 출연");
            case GENRE -> named(choice, genres, name -> "자주 좋게 본 " + name);
            case COUNTRY -> named(choice, countries, name -> "좋아하는 " + name + " 영화");
            case WISH_GENRE -> named(choice, genres, name -> "찜 목록에 많은 " + name + " 장르");
            case WISH_COUNTRY -> named(choice, countries, name -> "찜 목록에 많은 " + name + " 영화");
            case WISH_DIRECTOR -> named(choice, people, name -> "찜 목록에 " + name + " 감독 작품이 많아요");
        };
    }

    /** 이름을 못 찾으면(삭제된 대상) 이유를 달지 않는다 — 문구가 비어 나가는 것보다 낫다. */
    private RecommendReasonResponse named(ReasonChoice choice, Map<Long, String> names,
                                          Function<String, String> text) {
        String name = names.get(choice.targetId());
        return name == null ? null : new RecommendReasonResponse(choice.type(), choice.targetId(), name, text.apply(name));
    }

    private static <T> Map<Long, String> names(Iterable<T> entities, Function<T, Long> id, Function<T, String> name) {
        Map<Long, String> map = new HashMap<>();
        for (T entity : entities) {
            map.put(id.apply(entity), name.apply(entity));
        }
        return map;
    }
}
