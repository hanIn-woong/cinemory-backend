package com.project.cinemory.domain.country.service;

import com.project.cinemory.domain.country.entity.Country;
import com.project.cinemory.domain.country.repository.CountryRepository;
import com.project.cinemory.global.infra.tmdb.TmdbClient;
import com.project.cinemory.global.infra.tmdb.dto.TmdbCountryListItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * TMDB 국가 참조 테이블 선행 적재 (6-1).
 *
 * <p>{@link com.project.cinemory.domain.genre.service.GenreSeedService}와 같은 이유로
 * 영화 동기화보다 먼저 실행해야 한다. 동기화는 미등록 국가를 만나면
 * {@code COUNTRY_NOT_FOUND}로 실패시킨다.
 *
 * <p>멱등하다. 재실행하면 신규만 저장하고 기존은 이름만 갱신한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CountrySeedService {

    /** {@code country.code}는 ISO 3166-1 alpha-2라 컬럼이 length 2다. 어긋나는 값은 저장 전에 버린다. */
    private static final int ISO_3166_1_LENGTH = 2;

    private final TmdbClient tmdbClient;
    private final CountryRepository countryRepository;

    /**
     * TMDB 국가 목록을 읽어 upsert한다.
     *
     * @return 신규 적재 건수 (갱신 건수는 로그로만 남긴다)
     */
    @Transactional
    public int seedAll() {
        List<TmdbCountryListItem> items = tmdbClient.fetchCountries();
        if (items.isEmpty()) {
            log.warn("TMDB 국가 목록이 비어 있어 적재를 건너뜁니다.");
            return 0;
        }

        Map<String, TmdbCountryListItem> byCode = items.stream()
                .filter(this::hasStorableCode)
                .collect(Collectors.toMap(
                        TmdbCountryListItem::iso31661,
                        Function.identity(),
                        (first, duplicate) -> first,
                        LinkedHashMap::new));

        Map<String, Country> existing = countryRepository.findByCodeIn(byCode.keySet()).stream()
                .collect(Collectors.toMap(Country::getCode, Function.identity()));

        List<Country> toInsert = new ArrayList<>();
        int updated = 0;

        for (TmdbCountryListItem item : byCode.values()) {
            String name = item.resolveName();
            Country found = existing.get(item.iso31661());
            if (found == null) {
                toInsert.add(Country.of(item.iso31661(), name));
            } else {
                found.rename(name);
                updated++;
            }
        }

        countryRepository.saveAll(toInsert);
        log.info("TMDB 국가 시드 완료. 신규={}, 기존={}", toInsert.size(), updated);
        return toInsert.size();
    }

    /**
     * 저장 가능한 행인지 검사한다.
     *
     * <p>외부 데이터를 신뢰하지 않는다는 4-7의 원칙과 같다. 코드 길이가 어긋나거나
     * 이름이 비면 {@code DataIntegrityViolationException}으로 <b>트랜잭션 전체가</b>
     * 롤백되어, 정상인 250여 건이 한 건 때문에 통째로 날아간다.
     */
    private boolean hasStorableCode(TmdbCountryListItem item) {
        String code = item.iso31661();
        if (code == null || code.length() != ISO_3166_1_LENGTH) {
            log.warn("국가 코드가 ISO 3166-1 alpha-2 형식이 아니라 건너뜁니다. code={}", code);
            return false;
        }
        if (item.resolveName() == null || item.resolveName().isBlank()) {
            log.warn("국가명이 비어 있어 건너뜁니다. code={}", code);
            return false;
        }
        return true;
    }
}
