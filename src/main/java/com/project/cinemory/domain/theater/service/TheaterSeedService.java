package com.project.cinemory.domain.theater.service;

import com.project.cinemory.domain.theater.dto.TheaterSeedData;
import com.project.cinemory.domain.theater.entity.Theater;
import com.project.cinemory.domain.theater.repository.TheaterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 전국영화상영관표준데이터 1회성 시드 적재.
 *
 * <p>극장은 개·폐점이 드물어 주기 배치의 실익이 적으므로 스케줄러를 두지 않는다.
 * 재적재가 필요하면 같은 진입점을 다시 호출하면 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TheaterSeedService {

    private final TheaterRepository theaterRepository;

    /**
     * {@code sourceCode} 기준으로 아직 없는 극장만 저장한다.
     *
     * <p>기존 행을 갱신하지 않고 건너뛰는 이유: 극장 속성(주소/좌표/관 수)은 거의 변하지 않는데,
     * 갱신을 지원하면 Theater 엔티티에 수정 메서드를 열어야 해서 불변성이 약해진다.
     * 실제로 정보가 바뀐 극장이 생기면 그때 개별 대응한다.
     *
     * @return 신규 적재 건수
     */
    @Transactional
    public int seedAll(List<TheaterSeedData> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }

        Set<String> sourceCodes = rows.stream()
                .map(TheaterSeedData::sourceCode)
                .collect(Collectors.toSet());

        // 이미 적재된 코드 집합을 1쿼리로 읽어 차집합만 저장 (재실행해도 중복이 생기지 않음)
        Set<String> existing = new HashSet<>(theaterRepository.findSourceCodesIn(sourceCodes));

        List<Theater> toSave = rows.stream()
                .filter(row -> !existing.contains(row.sourceCode()))
                .map(row -> Theater.builder()
                        .sourceCode(row.sourceCode())
                        .name(row.name())
                        .chainName(row.chainName())
                        .address(row.address())
                        .latitude(row.latitude())
                        .longitude(row.longitude())
                        .screenCount(row.screenCount())
                        .seatCount(row.seatCount())
                        .build())
                .toList();

        theaterRepository.saveAll(toSave);
        log.info("극장 시드 적재 완료. 전체={}건, 신규={}건", rows.size(), toSave.size());

        return toSave.size();
    }
}
