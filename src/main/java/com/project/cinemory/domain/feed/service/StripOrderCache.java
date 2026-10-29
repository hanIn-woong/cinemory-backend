package com.project.cinemory.domain.feed.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.project.cinemory.domain.feed.repository.StripRow;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 보는 사람별 스트립 순서 캐시 (social-feed-spec 4-2-A). 키는 {@code (viewerId, asOf)} — 같은 {@code asOf}의 커서만 같은 순서를 공유하고,
 * 새로고침은 새 {@code asOf}라 새 항목이 된다. 값은 대상 전원의 {@code (userId, lastActivityAt)} 최근순 목록이다.
 *
 * <p>⚠️ 항목 수가 아니라 <b>목록 길이의 합</b>({@code maximumWeight} + weigher)으로 상한을 둔다 — 1천 명 목록이 수천 개 쌓이면 힙(-Xmx640m)을
 * 위협한다. 무효화는 하지 않는다(5분 뒤 만료) — 미스는 기간 경계 쿼리로 대체되므로 정확성에는 영향이 없다. 단일 인스턴스 전제(로컬 캐시).
 */
@Component
public class StripOrderCache {

    static final long MAX_WEIGHT = 200_000;
    static final Duration TTL = Duration.ofMinutes(5);

    private record Key(Long viewerId, LocalDateTime asOf) {
    }

    private final Cache<Key, List<StripRow>> cache = Caffeine.newBuilder()
            .maximumWeight(MAX_WEIGHT)
            .weigher((Key key, List<StripRow> order) -> order.size())
            .expireAfterWrite(TTL)
            .build();

    /** 없으면(만료·재시작·다른 인스턴스) {@code null}. */
    public List<StripRow> get(Long viewerId, LocalDateTime asOf) {
        return cache.getIfPresent(new Key(viewerId, asOf));
    }

    public void put(Long viewerId, LocalDateTime asOf, List<StripRow> order) {
        cache.put(new Key(viewerId, asOf), List.copyOf(order));
    }

    /** 테스트·측정용 — 캐시 미스 경로를 강제한다. */
    public void clear() {
        cache.invalidateAll();
    }
}
