package com.project.cinemory.domain.person.service;

import com.project.cinemory.domain.admin.entity.AdminAction;
import com.project.cinemory.domain.admin.entity.AdminTargetType;
import com.project.cinemory.domain.admin.service.AdminAuditService;
import com.project.cinemory.domain.movie.repository.MovieActorRepository;
import com.project.cinemory.domain.movie.repository.MovieDirectorRepository;
import com.project.cinemory.domain.person.dto.AdminPersonSummaryResponse;
import com.project.cinemory.domain.person.entity.Person;
import com.project.cinemory.domain.person.repository.PersonCreditCountProjection;
import com.project.cinemory.domain.person.repository.PersonRepository;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.security.resolver.AuditActor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 인물 한글명 변경(admin-spec 12-3). DB 쓰기가 있는 동작이라 <b>한 트랜잭션</b>에서 변경과 감사 로그를 함께 남긴다 —
 * 둘 다 커밋되거나 둘 다 롤백된다(11-3). 값이 같으면 저장도 기록도 하지 않고 그대로 돌려준다(멱등).
 */
@Service
@RequiredArgsConstructor
public class AdminPersonCommandService {

    private final PersonRepository personRepository;
    private final MovieActorRepository movieActorRepository;
    private final MovieDirectorRepository movieDirectorRepository;
    private final AdminAuditService adminAuditService;

    @Transactional
    public AdminPersonSummaryResponse changeNameKo(Long personId, String nameKo, AuditActor actor) {
        Person person = personRepository.findById(personId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PERSON_NOT_FOUND));

        String before = person.getNameKo();
        person.changeNameKo(nameKo);
        String after = person.getNameKo();

        if (!Objects.equals(before, after)) {
            // before/after를 null까지 명시한다 — "처음 입력"(before=null)과 "해제"(after=null)를 로그에서 구별해야 한다
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("before", before);
            detail.put("after", after);
            adminAuditService.record(actor, AdminAction.PERSON_NAME_KO_UPDATE, AdminTargetType.PERSON, personId, detail);
        }
        return AdminPersonSummaryResponse.of(person, creditCount(personId));
    }

    private long creditCount(Long personId) {
        List<Long> ids = List.of(personId);
        long total = 0;
        for (PersonCreditCountProjection row : movieActorRepository.countGroupByPersonIdIn(ids)) {
            total += row.getCount();
        }
        for (PersonCreditCountProjection row : movieDirectorRepository.countGroupByPersonIdIn(ids)) {
            total += row.getCount();
        }
        return total;
    }
}
