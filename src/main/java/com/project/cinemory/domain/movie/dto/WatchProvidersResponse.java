package com.project.cinemory.domain.movie.dto;

import com.project.cinemory.global.infra.tmdb.dto.TmdbWatchProviders;

import java.util.Comparator;
import java.util.List;

/**
 * 어디서 볼 수 있나(service-layer-spec 4-2-B ③) - TMDB {@code results.KR}. KR 항목이 없으면 {@code link = null} + 빈 배열.
 * ⚠️ 데이터 원천이 JustWatch라 화면에 출처 표기가 의무다.
 */
public record WatchProvidersResponse(String link, List<ProviderResponse> flatrate, List<ProviderResponse> rent,
                                     List<ProviderResponse> buy) {

    public record ProviderResponse(Long providerId, String name, String logoPath) {
    }

    public static WatchProvidersResponse empty() {
        return new WatchProvidersResponse(null, List.of(), List.of(), List.of());
    }

    /** KR 지역 항목 변환. 각 목록은 TMDB {@code display_priority} 오름차순(없으면 맨 뒤)으로 놓는다. */
    public static WatchProvidersResponse from(TmdbWatchProviders.Region region) {
        if (region == null) {
            return empty();
        }
        return new WatchProvidersResponse(region.link(), sorted(region.flatrate()), sorted(region.rent()), sorted(region.buy()));
    }

    private static List<ProviderResponse> sorted(List<TmdbWatchProviders.Provider> providers) {
        if (providers == null) {
            return List.of();
        }
        return providers.stream()
                .filter(provider -> provider.providerId() != null && provider.providerName() != null)
                .sorted(Comparator.comparing(TmdbWatchProviders.Provider::displayPriority,
                        Comparator.nullsLast(Comparator.<Integer>naturalOrder())))
                .map(provider -> new ProviderResponse(provider.providerId(), provider.providerName(), provider.logoPath()))
                .toList();
    }
}
