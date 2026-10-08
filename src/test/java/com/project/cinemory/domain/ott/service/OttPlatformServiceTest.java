package com.project.cinemory.domain.ott.service;

import com.project.cinemory.domain.ott.dto.OttPlatformResponse;
import com.project.cinemory.domain.ott.entity.OttPlatform;
import com.project.cinemory.domain.ott.repository.OttPlatformRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OTT 플랫폼 목록 순서(V23 sort_order). id 순이 아니라 sort_order 순이어야
 * 나중에 추가된 플랫폼이 '기타'(99) 뒤로 가지 않는다.
 */
@SpringBootTest
@Transactional
class OttPlatformServiceTest {

    @Autowired
    private OttPlatformService ottPlatformService;
    @Autowired
    private OttPlatformRepository ottPlatformRepository;

    @Test
    void 기타는_먼저_저장돼도_목록_맨_뒤에_온다() {
        ottPlatformRepository.save(OttPlatform.of("기타", 99));
        ottPlatformRepository.save(OttPlatform.of("순서테스트OTT"));

        List<OttPlatformResponse> platforms = ottPlatformService.getActivePlatforms();

        assertThat(platforms).extracting(OttPlatformResponse::name).contains("순서테스트OTT");
        assertThat(platforms.get(platforms.size() - 1).name()).isEqualTo("기타");
    }
}
