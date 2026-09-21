package com.project.cinemory.domain.ott.service;

import com.project.cinemory.domain.ott.dto.OttPlatformResponse;
import com.project.cinemory.domain.ott.repository.OttPlatformRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * OTT 플랫폼은 사용자 소유 데이터가 아닌 공용 참조 데이터이므로 {@code viewerId}를 받지 않는다
 * (극장·영화와 동일한 성격, 4-7/5-2).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OttPlatformService {

    private final OttPlatformRepository ottPlatformRepository;

    public List<OttPlatformResponse> getActivePlatforms() {
        return ottPlatformRepository.findByActiveTrueOrderByIdAsc().stream()
                .map(OttPlatformResponse::from)
                .toList();
    }
}
