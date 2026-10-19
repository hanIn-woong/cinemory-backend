package com.project.cinemory.domain.person.service;

import com.project.cinemory.domain.movie.repository.MovieActorRepository;
import com.project.cinemory.domain.movie.repository.MovieDirectorRepository;
import com.project.cinemory.domain.person.dto.AdminPersonSummaryResponse;
import com.project.cinemory.domain.person.entity.Person;
import com.project.cinemory.domain.person.repository.PersonCreditCountProjection;
import com.project.cinemory.domain.person.repository.PersonRepository;
import com.project.cinemory.global.dto.LikePattern;
import com.project.cinemory.global.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 관리자 인물 조회(admin-spec 12-3). 한 페이지 = 쿼리 4회(목록·count·출연 건수·연출 건수) — N+1 금지.
 * 정렬은 {@code id ASC} 고정이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminPersonQueryService {

    private final PersonRepository personRepository;
    private final MovieActorRepository movieActorRepository;
    private final MovieDirectorRepository movieDirectorRepository;

    public PageResponse<AdminPersonSummaryResponse> getPersons(String q, boolean missingKorean, int page, int size) {
        Page<Person> persons = personRepository.searchForAdmin(
                LikePattern.contains(q), missingKorean, PageRequest.of(page, size));

        List<Long> personIds = persons.getContent().stream().map(Person::getId).toList();
        Map<Long, Long> creditCounts = creditCounts(personIds);

        return PageResponse.from(persons.map(person ->
                AdminPersonSummaryResponse.of(person, creditCounts.getOrDefault(person.getId(), 0L))));
    }

    private Map<Long, Long> creditCounts(List<Long> personIds) {
        Map<Long, Long> counts = new HashMap<>();
        if (personIds.isEmpty()) {
            return counts;
        }
        for (PersonCreditCountProjection row : movieActorRepository.countGroupByPersonIdIn(personIds)) {
            counts.merge(row.getPersonId(), row.getCount(), Long::sum);
        }
        for (PersonCreditCountProjection row : movieDirectorRepository.countGroupByPersonIdIn(personIds)) {
            counts.merge(row.getPersonId(), row.getCount(), Long::sum);
        }
        return counts;
    }
}
