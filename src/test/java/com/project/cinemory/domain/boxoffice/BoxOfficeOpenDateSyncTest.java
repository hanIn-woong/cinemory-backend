package com.project.cinemory.domain.boxoffice;

import com.project.cinemory.domain.boxoffice.entity.BoxOfficeRecord;
import com.project.cinemory.domain.boxoffice.entity.RankType;
import com.project.cinemory.domain.boxoffice.repository.BoxOfficeRecordRepository;
import com.project.cinemory.domain.boxoffice.service.BoxOfficeSyncService;
import com.project.cinemory.domain.country.entity.Country;
import com.project.cinemory.domain.country.repository.CountryRepository;
import com.project.cinemory.domain.genre.entity.Genre;
import com.project.cinemory.domain.genre.repository.GenreRepository;
import com.project.cinemory.domain.movie.service.MovieSeedService;
import com.project.cinemory.global.infra.kofic.KoficClient;
import com.project.cinemory.global.infra.kofic.dto.KoficDailyBoxOfficeResponse.DailyBoxOffice;
import com.project.cinemory.global.infra.tmdb.TmdbClient;
import com.project.cinemory.global.infra.tmdb.dto.TmdbSearchResponse;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 수집 시 {@code open_date} 채우기(catalog-enrichment-spec CE-3) — KOFIC {@code openDt} 파싱(정상·빈 값·잘못된 형식 → NULL)과,
 * 그 값이 역방향 시드의 TMDB 검색 연도로 이어지는지까지 본다. KOFIC·TMDB는 목이다.
 */
@SpringBootTest
@Transactional
class BoxOfficeOpenDateSyncTest {

    // 다른 테스트·실데이터와 겹치지 않는 집계일
    private static final LocalDate TARGET = LocalDate.of(2099, 2, 1);

    @Autowired
    private BoxOfficeSyncService boxOfficeSyncService;
    @Autowired
    private MovieSeedService movieSeedService;
    @Autowired
    private BoxOfficeRecordRepository boxOfficeRecordRepository;
    @Autowired
    private GenreRepository genreRepository;
    @Autowired
    private CountryRepository countryRepository;
    @Autowired
    private EntityManager em;

    @MockitoBean
    private KoficClient koficClient;
    @MockitoBean
    private TmdbClient tmdbClient;

    @BeforeEach
    void setUp() {
        // 시드는 참조 테이블이 비어 있으면 시작하지 않는다 — 트랜잭션 안에서만 채운다
        if (genreRepository.count() == 0) {
            genreRepository.save(Genre.of(99_901, "테스트장르"));
        }
        if (countryRepository.count() == 0) {
            countryRepository.save(Country.of("ZZ", "테스트국가"));
        }
        when(tmdbClient.searchMovie(anyString(), any())).thenReturn(new TmdbSearchResponse(List.of()));
        when(koficClient.fetchDailyBoxOffice(TARGET)).thenReturn(List.of(
                item("1", "OD001", "정상개봉일", "2026-10-29"),
                item("2", "OD002", "빈개봉일", " "),
                item("3", "OD003", "잘못된개봉일", "20261029")));
    }

    private static DailyBoxOffice item(String rank, String movieCd, String movieNm, String openDt) {
        return new DailyBoxOffice(rank, "0", "OLD", movieCd, movieNm, openDt, "1", "1.0", "1", "1", "1", "1");
    }

    @Test
    void 수집_시_openDt를_파싱하고_빈_값과_잘못된_형식은_NULL로_둔다() {
        int saved = boxOfficeSyncService.syncDaily(TARGET);
        em.flush();
        em.clear();

        assertThat(saved).isEqualTo(3);   // 파싱 실패가 수집을 멈추지 않는다
        Map<String, LocalDate> openDates = boxOfficeRecordRepository
                .findByTargetDateAndRankTypeOrderByBoxOfficeRankAsc(TARGET, RankType.DAILY).stream()
                .filter(b -> b.getOpenDate() != null)
                .collect(Collectors.toMap(BoxOfficeRecord::getKoficMovieCd, BoxOfficeRecord::getOpenDate));
        assertThat(openDates).containsExactly(Map.entry("OD001", LocalDate.of(2026, 10, 29)));
    }

    @Test
    void 수집한_개봉연도가_역방향_시드의_TMDB_검색_연도로_넘어간다() {
        boxOfficeSyncService.syncDaily(TARGET);
        em.flush();

        movieSeedService.seedFromBoxOffice(20, TARGET);

        verify(tmdbClient).searchMovie(eq("정상개봉일"), eq(2026));
        verify(tmdbClient).searchMovie(eq("빈개봉일"), isNull());     // 연도가 없으면 기존처럼 연도 없이 검색
    }
}
