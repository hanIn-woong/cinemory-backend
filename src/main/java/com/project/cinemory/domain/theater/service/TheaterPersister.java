package com.project.cinemory.domain.theater.service;

import com.project.cinemory.domain.theater.dto.TheaterSeedData;
import com.project.cinemory.domain.theater.dto.TheaterSeedReport;
import com.project.cinemory.domain.theater.entity.Theater;
import com.project.cinemory.domain.theater.repository.TheaterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 극장 upsert · 숨김 · 복구(cinemap-spec C-4-5, C-5-1, C-5-2). HTTP 호출은 {@code TheaterSeedService}가 트랜잭션 밖에서 끝내고,
 * 여기서는 DB 쓰기만 한다(tmdb-sync 6-4 — 별도 빈이어야 프록시를 거쳐 트랜잭션이 걸린다).
 *
 * <p>upsert · 숨김 · 복구는 <b>한 트랜잭션</b>이다 — 중간에 실패하면 전부 롤백돼 "신규만 들어가고 숨김은 안 된" 반쪽 상태가 없다.
 * dryRun은 같은 계산을 하되 엔티티를 바꾸지 않는다(보고서가 실행 결과와 같은 수를 보여주도록 코드 경로를 하나로 둔다).
 *
 * <p>{@code booking_url}은 어디서도 건드리지 않는다 — 관리자 보강 필드는 동기화가 덮지 않는다(C-8-2).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TheaterPersister {

    private final TheaterRepository theaterRepository;

    // C-5-2 ② — 한 번에 숨길 극장이 max(영업 중 극장의 비율, 최소 건수)를 넘으면 숨김만 보류한다
    @Value("${cinemory.theater.sync.mass-close-ratio:0.05}")
    private double massCloseRatio;

    @Value("${cinemory.theater.sync.mass-close-min:10}")
    private int massCloseMin;

    /**
     * @param closed          이번에 숨긴(dryRun이면 숨길) 극장 수
     * @param massCloseHeld   대량 숨김 방지로 <b>보류한</b> 극장 수. 0이면 장치가 발동하지 않았다
     * @param closedWithBooking 이번에 숨긴 극장 중 예매 링크가 있는 곳 — 관리자 확인 대상
     */
    public record SyncResult(int inserted, int updated, int reopened, int closed, int massCloseHeld,
                             List<TheaterSeedReport.ClosedWithBooking> closedWithBooking) {
    }

    /**
     * @param rows        좌표까지 정해진 극장들(신규·갱신 대상)
     * @param presentKeys 이번 원천에 존재한 모든 묶음 키 — 좌표 실패로 {@code rows}에 못 든 그룹도 포함한다.
     *                    영업 중인데 여기에 없는 극장만 숨긴다
     * @param dryRun      true면 아무것도 바꾸지 않고 수만 센다
     */
    @Transactional
    public SyncResult reconcile(List<TheaterSeedData> rows, Set<String> presentKeys, boolean allowMassClose, boolean dryRun) {
        Map<String, Theater> existing = new HashMap<>(theaterRepository
                .findBySourceCodeIn(rows.stream().map(TheaterSeedData::sourceCode).toList())
                .stream().collect(Collectors.toMap(Theater::getSourceCode, Function.identity())));

        int inserted = 0;
        int updated = 0;
        int reopened = 0;
        for (TheaterSeedData row : rows) {
            Theater theater = existing.get(row.sourceCode());
            if (theater == null) {
                inserted++;
                if (!dryRun) {
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
                }
                continue;
            }
            updated++;
            if (theater.isClosed()) {
                reopened++;
                if (!dryRun) {
                    theater.reopen();
                }
            }
            if (!dryRun) {
                theater.updateFromSource(row.name(), row.chainName(), row.address(),
                        row.latitude(), row.longitude(), row.screenCount());
            }
        }

        // 숨김 — 영업 중인데 이번 원천에 묶음이 없는 극장. 이번에 신규로 저장한 극장은 presentKeys에 있어 걸리지 않는다
        List<Theater> open = theaterRepository.findByClosedAtIsNull();
        List<Theater> toClose = new ArrayList<>();
        for (Theater t : open) {
            if (!presentKeys.contains(t.getSourceCode())) {
                toClose.add(t);
            }
        }

        int threshold = (int) Math.max(Math.floor(open.size() * massCloseRatio), massCloseMin);
        int held = 0;
        List<TheaterSeedReport.ClosedWithBooking> closedWithBooking = new ArrayList<>();
        int closed = toClose.size();
        if (toClose.size() > threshold && !allowMassClose) {
            held = toClose.size();
            closed = 0;
            log.warn("극장 대량 숨김 보류. 숨길 극장={}곳 > 기준 {}곳(영업 중 {}곳). 진짜 대량 폐업이면 allowMassClose=true로 다시 실행하세요.",
                    held, threshold, open.size());
        } else {
            Instant now = Instant.now();
            for (Theater t : toClose) {
                if (!dryRun) {
                    t.close(now);
                }
                if (t.getBookingUrl() != null && !t.getBookingUrl().isBlank()) {
                    closedWithBooking.add(new TheaterSeedReport.ClosedWithBooking(
                            t.getId(), t.getSourceCode(), t.getName(), t.getBookingUrl()));
                }
            }
        }

        if (!dryRun) {
            log.info("극장 동기화 저장 완료. 신규={}, 갱신={}, 복구={}, 숨김={}, 숨김 보류={}", inserted, updated, reopened, closed, held);
        }
        return new SyncResult(inserted, updated, reopened, closed, held, closedWithBooking);
    }
}
