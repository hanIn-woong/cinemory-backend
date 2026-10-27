package com.project.cinemory.domain.theater.service;

import com.project.cinemory.domain.theater.dto.TheaterSeedData;
import com.project.cinemory.domain.theater.entity.Theater;
import com.project.cinemory.domain.theater.repository.TheaterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 극장 upsert 저장(cinemap-spec C-4-5). HTTP 호출은 {@code TheaterSeedService}가 트랜잭션 밖에서 끝내고,
 * 여기서는 DB 쓰기만 한다(tmdb-sync 6-4 — 별도 빈이어야 프록시를 거쳐 트랜잭션이 걸린다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TheaterPersister {

    private final TheaterRepository theaterRepository;

    public record UpsertResult(int inserted, int updated) {
    }

    @Transactional
    public UpsertResult upsertAll(List<TheaterSeedData> rows) {
        if (rows.isEmpty()) {
            return new UpsertResult(0, 0);
        }
        Map<String, Theater> existing = new HashMap<>(theaterRepository
                .findBySourceCodeIn(rows.stream().map(TheaterSeedData::sourceCode).toList())
                .stream().collect(Collectors.toMap(Theater::getSourceCode, Function.identity())));

        int inserted = 0;
        int updated = 0;
        for (TheaterSeedData row : rows) {
            Theater theater = existing.get(row.sourceCode());
            if (theater == null) {
                theaterRepository.save(Theater.builder()
                        .sourceCode(row.sourceCode())
                        .name(row.name())
                        .chainName(row.chainName())
                        .address(row.address())
                        .latitude(row.latitude())
                        .longitude(row.longitude())
                        .screenCount(row.screenCount())
                        .seatCount(row.seatCount())
                        .build());
                inserted++;
            } else {
                theater.updateFromSource(row.name(), row.chainName(), row.address(),
                        row.latitude(), row.longitude(), row.screenCount());
                updated++;
            }
        }
        log.info("극장 시드 저장 완료. 신규={}건, 갱신={}건", inserted, updated);
        return new UpsertResult(inserted, updated);
    }
}
