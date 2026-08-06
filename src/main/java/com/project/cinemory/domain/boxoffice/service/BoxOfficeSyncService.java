package com.project.cinemory.domain.boxoffice.service;

import com.project.cinemory.domain.boxoffice.entity.BoxOfficeRecord;
import com.project.cinemory.domain.boxoffice.entity.RankType;
import com.project.cinemory.domain.boxoffice.repository.BoxOfficeRecordRepository;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.global.infra.kofic.KoficClient;
import com.project.cinemory.global.infra.kofic.dto.KoficDailyBoxOfficeResponse.DailyBoxOffice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * KOFIC 박스오피스 수집 배치.
 *
 * <p>스케줄러와 관리자 수동 트리거가 <b>동일한 메서드</b>를 호출한다. 배치 로직을 두 벌로 두지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BoxOfficeSyncService {

    private final KoficClient koficClient;
    private final BoxOfficeRecordRepository boxOfficeRecordRepository;
    private final MovieRepository movieRepository;

    /**
     * 일별 박스오피스 수집.
     *
     * <p><b>멱등성</b>: {@code uk_box_office_record(target_date, rank_type, kofic_movie_cd)} 때문에
     * 재실행 시 중복 INSERT가 유니크 위반을 낸다. 기존 행을 지우고 다시 넣는 대신
     * <b>이미 적재된 코드 집합을 1쿼리로 읽어 차집합만 저장</b>한다.
     * (4-5 {@code addMoviesToCollection}에서 쓴 패턴의 재사용)
     *
     * <p><b>매칭</b>: 여기서는 1순위 {@code koficMovieCd} 직접 매칭만 수행한다. 수집 경로는 빠르고
     * 결정적이어야 재실행·복구가 쉽기 때문이며, 비용이 크고 오매칭 위험이 있는 휴리스틱은
     * {@link #rematchUnlinked(int)}로 분리했다.
     *
     * @return 신규 적재 건수
     */
    @Transactional
    public int syncDaily(LocalDate targetDate) {
        List<DailyBoxOffice> items = koficClient.fetchDailyBoxOffice(targetDate);
        if (items.isEmpty()) {
            log.warn("KOFIC 일별 박스오피스 응답이 비어 있습니다. targetDate={}", targetDate);
            return 0;
        }

        Set<String> alreadySaved =
                new HashSet<>(boxOfficeRecordRepository.findKoficMovieCds(targetDate, RankType.DAILY));

        List<DailyBoxOffice> newItems = items.stream()
                .filter(this::hasRequiredFields)
                .filter(item -> !alreadySaved.contains(item.movieCd()))
                .toList();

        if (newItems.isEmpty()) {
            log.info("이미 수집된 박스오피스입니다. targetDate={}", targetDate);
            return 0;
        }

        Map<String, Movie> movieByKoficCd = findMoviesByKoficCd(newItems);

        List<BoxOfficeRecord> records = newItems.stream()
                .map(item -> toEntity(item, targetDate, movieByKoficCd.get(item.movieCd())))
                .toList();

        boxOfficeRecordRepository.saveAll(records);

        long matched = records.stream().filter(r -> r.getMovie() != null).count();
        log.info("박스오피스 수집 완료. targetDate={}, 신규={}건, 매칭={}건", targetDate, records.size(), matched);

        return records.size();
    }

    /**
     * TMDB 미매칭 레코드 재매칭 (2순위 — 제목 완전 일치).
     *
     * <p><b>한계</b>: 기획 단계의 매칭 전략은 "한글 제목 + 개봉연도"였으나 개봉연도를 담을 곳이 없어
     * 축소했다. 따라서 제목이 정확히 일치하고 <b>후보가 유일할 때만</b> 연결하고, 2건 이상이면
     * 오매칭을 피해 건너뛴다.
     *
     * <p><b>TODO(v10)</b>: {@code box_office_record.open_date} 컬럼과 엔티티 필드는 추가됐지만
     * 아직 <b>수집 시 채우지 않고 이 메서드도 쓰지 않는다.</b> 2순위 전략을 복원하려면
     * {@code toEntity}에서 KOFIC {@code openDt}를 파싱해 넣고, 여기서 개봉연도로 후보를
     * 좁히는 작업이 함께 필요하다.
     *
     * <p>매칭에 성공하면 해당 {@code Movie}에 {@code koficMovieCd}를 역으로 채워, 다음 수집부터는
     * 1순위 매칭으로 바로 걸리게 한다.
     *
     * @return 새로 연결된 건수
     */
    @Transactional
    public int rematchUnlinked(int limit) {
        List<BoxOfficeRecord> unlinked =
                boxOfficeRecordRepository.findByMovieIsNull(PageRequest.of(0, limit));

        int linked = 0;
        for (BoxOfficeRecord boxOffice : unlinked) {
            List<Movie> candidates = movieRepository.findByTitle(boxOffice.getMovieTitleSnapshot());
            if (candidates.size() != 1) {
                continue; // 0건: 아직 TMDB에 없음 / 2건 이상: 동명 영화 — 오매칭 방지를 위해 보류
            }

            Movie movie = candidates.get(0);
            boxOffice.linkMovie(movie); // dirty checking으로 반영

            // uk_movie_kofic_cd 위반을 피하기 위해 아직 비어 있을 때만 채운다
            if (movie.getKoficMovieCd() == null) {
                movie.linkKoficCode(boxOffice.getKoficMovieCd());
            }
            linked++;
        }

        log.info("박스오피스 재매칭 완료. 대상={}건, 연결={}건", unlinked.size(), linked);
        return linked;
    }

    /**
     * NOT NULL 컬럼에 해당하는 값이 없으면 저장 자체가 실패하므로 미리 걸러낸다.
     * (rank/movieCd/movieNm 은 KOBIS 응답에서 항상 오지만, 외부 데이터를 신뢰하지 않는다)
     */
    private boolean hasRequiredFields(DailyBoxOffice item) {
        boolean valid = item.movieCd() != null && !item.movieCd().isBlank()
                && item.movieNm() != null && !item.movieNm().isBlank()
                && parseInt(item.rank()) != null;

        if (!valid) {
            log.warn("필수 항목이 없는 박스오피스 응답을 건너뜁니다. movieCd={}, movieNm={}, rank={}",
                    item.movieCd(), item.movieNm(), item.rank());
        }
        return valid;
    }

    /** 1순위 매칭 — 신규 항목의 KOFIC 코드로 movie를 벌크 조회(1쿼리)해 Map으로 만든다. */
    private Map<String, Movie> findMoviesByKoficCd(List<DailyBoxOffice> items) {
        Set<String> koficCds = items.stream()
                .map(DailyBoxOffice::movieCd)
                .collect(Collectors.toSet());

        return movieRepository.findByKoficMovieCdIn(koficCds).stream()
                .collect(Collectors.toMap(Movie::getKoficMovieCd, Function.identity()));
    }

    private BoxOfficeRecord toEntity(DailyBoxOffice item, LocalDate targetDate, Movie movie) {
        return BoxOfficeRecord.builder()
                .targetDate(targetDate)
                .rankType(RankType.DAILY)
                .boxOfficeRank(parseInt(item.rank()))
                .rankChange(parseInt(item.rankInten()))
                .isNew("NEW".equalsIgnoreCase(item.rankOldAndNew()))
                .koficMovieCd(item.movieCd())
                .movieTitleSnapshot(item.movieNm())
                .movie(movie) // 매칭 실패 시 null — 원본 데이터는 스냅샷으로 보존된다
                .salesAmount(parseLong(item.salesAmt()))
                .salesShare(parseDecimal(item.salesShare()))
                .audienceCount(parseInt(item.audiCnt()))
                .audienceAcc(parseLong(item.audiAcc()))
                .screenCount(parseInt(item.scrnCnt()))
                .showCount(parseInt(item.showCnt()))
                .build();
    }

    // KOBIS는 숫자 항목도 문자열로 내려주고 일부 필드가 비어 오는 경우가 있어 방어적으로 파싱한다
    private Integer parseInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            log.warn("KOFIC 정수 파싱 실패. value={}", value);
            return null;
        }
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            log.warn("KOFIC 정수 파싱 실패. value={}", value);
            return null;
        }
    }

    private BigDecimal parseDecimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            log.warn("KOFIC 실수 파싱 실패. value={}", value);
            return null;
        }
    }
}
