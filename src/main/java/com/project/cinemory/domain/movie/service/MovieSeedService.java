package com.project.cinemory.domain.movie.service;

import com.project.cinemory.domain.boxoffice.repository.BoxOfficeRecordRepository;
import com.project.cinemory.domain.boxoffice.repository.UnmatchedBoxOfficeTitle;
import com.project.cinemory.domain.country.repository.CountryRepository;
import com.project.cinemory.domain.genre.repository.GenreRepository;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.tmdb.TmdbClient;
import com.project.cinemory.global.infra.tmdb.dto.TmdbDiscoverResponse;
import com.project.cinemory.global.infra.tmdb.dto.TmdbSearchResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TMDB 영화 시드 — "어떤 {@code tmdbId}들을 고를 것인가" (tmdb-sync-spec 6-5).
 *
 * <p>{@link MovieSyncService}("{@code tmdbId} 하나를 반영한다")와 책임이 다르다.
 * {@code TheaterSeedService}와 같은 이유로 분리했다.
 *
 * <p>⚠️ <b>{@code @Transactional}을 붙이지 않는다.</b> 극장 시드와 달리 영화 시드는 외부
 * 호출이 수백 번이라, 붙이면 중간 실패 시 전부 롤백돼 멱등 이어받기가 무의미해지고
 * 429 백오프 대기가 트랜잭션 커넥션을 점유한다. 각 영화의 트랜잭션 경계는
 * {@link MovieSyncPersister#persist}가 편별로 이미 갖고 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MovieSeedService {

    @Value("${cinemory.movie.seed.box-office-default-limit}")
    private int defaultBoxOfficeLimit;

    @Value("${cinemory.movie.seed.discover-default-pages}")
    private int defaultDiscoverPages;

    private final BoxOfficeRecordRepository boxOfficeRecordRepository;
    private final MovieRepository movieRepository;
    private final GenreRepository genreRepository;
    private final CountryRepository countryRepository;
    private final TmdbClient tmdbClient;
    private final MovieSyncService movieSyncService;

    /**
     * 한 JVM 안에서만 유효한 중복 실행 방어(잔여 #18). 관리자가 두 번 누르면 같은 목록을
     * 각자 두드려 요청량이 2배가 되고 429를 서로 유발한다 — 데이터는 멱등이라 깨지지 않지만
     * 낭비와 오해가 문제다. 두 진입점이 같은 rate limit 예산을 쓰므로 필드 하나로 함께 막는다.
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 박스오피스 역방향 시드 — {@code movie_id IS NULL}인 제목을 TMDB에서 역조회해 적재한다.
     *
     * <p>⚠️ {@code movie_id}는 채우지 않는다. 매칭 책임은 4-7 재매칭 배치 하나로 유지한다 —
     * 두 곳에서 채우면 매칭 규칙이 이원화된다.
     *
     * @param limit null이면 {@code cinemory.movie.seed.box-office-default-limit} 기본값을 쓴다
     */
    public SeedResult seedFromBoxOffice(Integer limit) {
        if (!running.compareAndSet(false, true)) {
            throw new BusinessException(ErrorCode.SEED_ALREADY_RUNNING);
        }
        try {
            ensureReferenceDataSeeded();

            int resolvedLimit = limit != null ? limit : defaultBoxOfficeLimit;
            List<UnmatchedBoxOfficeTitle> titles =
                    boxOfficeRecordRepository.findUnmatchedTitles(PageRequest.of(0, resolvedLimit));

            int matched = 0;
            int skipped = 0;
            int alreadyExists = 0;
            boolean stoppedByRateLimit = false;

            for (UnmatchedBoxOfficeTitle title : titles) {
                Integer year = title.openDate() != null ? title.openDate().getYear() : null;
                try {
                    TmdbSearchResponse searchResponse = tmdbClient.searchMovie(title.movieTitleSnapshot(), year);
                    List<TmdbSearchResponse.Item> results = searchResponse.resultsOrEmpty();
                    if (results.isEmpty()) {
                        // 실패한 제목 자체를 남긴다 — skipped 카운트만으로는 부제/띄어쓰기/시리즈
                        // 표기 중 무엇이 원인인지 판단할 수 없다 (잔여 #10).
                        log.warn("박스오피스 역방향 시드: 제목 매칭 실패. title={}, year={}",
                                title.movieTitleSnapshot(), year);
                        skipped++;
                        continue;
                    }

                    Long tmdbId = results.get(0).id();
                    if (movieRepository.existsByTmdbId(tmdbId)) {
                        alreadyExists++;
                        continue;
                    }
                    movieSyncService.syncFromTmdb(tmdbId);
                    matched++;

                } catch (BusinessException e) {
                    if (e.getErrorCode() == ErrorCode.TMDB_RATE_LIMITED) {
                        // 예외로 던지지 않고 break 후 정상 반환한다 — 던지면 여기까지의 진척이
                        // 사라져 관리자가 어디까지 됐는지 모른다. 멱등이라 재실행하면 이어받는다.
                        log.warn("박스오피스 역방향 시드: rate limit 도달, 중단합니다. matched={}, skipped={}, alreadyExists={}",
                                matched, skipped, alreadyExists);
                        stoppedByRateLimit = true;
                        break;
                    }
                    log.warn("박스오피스 역방향 시드 실패. title={}", title.movieTitleSnapshot(), e);
                    skipped++;
                }
            }

            SeedResult result = new SeedResult(matched, skipped, alreadyExists, stoppedByRateLimit);
            log.info("박스오피스 역방향 시드 완료. {}", result);
            return result;

        } finally {
            running.set(false);
        }
    }

    /**
     * discover 보충 시드 — {@code region=KR} 인기작을 페이지 단위로 적재한다.
     *
     * @param pages null이면 {@code cinemory.movie.seed.discover-default-pages} 기본값을 쓴다
     */
    public SeedResult seedFromDiscover(Integer pages) {
        if (!running.compareAndSet(false, true)) {
            throw new BusinessException(ErrorCode.SEED_ALREADY_RUNNING);
        }
        try {
            ensureReferenceDataSeeded();

            int resolvedPages = pages != null ? pages : defaultDiscoverPages;
            int matched = 0;
            int skipped = 0;
            int alreadyExists = 0;
            boolean stoppedByRateLimit = false;

            pageLoop:
            for (int page = 1; page <= resolvedPages; page++) {
                TmdbDiscoverResponse response;
                try {
                    response = tmdbClient.discoverMovies(page);
                } catch (BusinessException e) {
                    if (e.getErrorCode() == ErrorCode.TMDB_RATE_LIMITED) {
                        log.warn("discover 시드: rate limit 도달, 중단합니다. matched={}, skipped={}, alreadyExists={}",
                                matched, skipped, alreadyExists);
                        stoppedByRateLimit = true;
                    } else {
                        // 페이지 순회 자체의 실패라 개별 영화 skip과 성격이 다르다 — 재개 지점이
                        // "이 페이지부터"라 여기서 멈추고 현재까지의 결과를 그대로 반환한다.
                        log.warn("discover 시드: 페이지 조회 실패로 중단합니다. page={}, matched={}, skipped={}",
                                page, matched, skipped, e);
                    }
                    break;
                }

                if (page > response.totalPagesOrZero()) {
                    break;
                }

                for (TmdbDiscoverResponse.Item item : response.resultsOrEmpty()) {
                    if (movieRepository.existsByTmdbId(item.id())) {
                        alreadyExists++;
                        continue;
                    }
                    try {
                        movieSyncService.syncFromTmdb(item.id());
                        matched++;
                    } catch (BusinessException e) {
                        if (e.getErrorCode() == ErrorCode.TMDB_RATE_LIMITED) {
                            log.warn("discover 시드: rate limit 도달, 중단합니다. matched={}, skipped={}, alreadyExists={}",
                                    matched, skipped, alreadyExists);
                            stoppedByRateLimit = true;
                            break pageLoop;
                        }
                        log.warn("discover 시드 실패. tmdbId={}", item.id(), e);
                        skipped++;
                    }
                }
            }

            SeedResult result = new SeedResult(matched, skipped, alreadyExists, stoppedByRateLimit);
            log.info("discover 시드 완료. {}", result);
            return result;

        } finally {
            running.set(false);
        }
    }

    /**
     * 참조 테이블 가드 (tmdb-sync-spec 6-5) — 순서를 틀리면 {@code syncFromTmdb}가
     * {@code GENRE_NOT_FOUND}로 죽는데, 시드는 이를 편별 실패로 흡수해 {@code skipped}로
     * 집계하고 끝까지 정상 종료해버린다. 로그만 보면 "제목 매칭이 다 실패했나?"로 읽혀
     * 진단이 엉뚱한 곳으로 간다 — TMDB 호출 전에 미리 걸러 예외로 중단한다.
     *
     * <p>{@code SeedResult}를 반환하지 않고 예외를 던지는 이유도 같다. 정상 종료로 보이면
     * 안 되는 것이 이 가드의 존재 이유다.
     */
    private void ensureReferenceDataSeeded() {
        if (genreRepository.count() == 0 || countryRepository.count() == 0) {
            throw new BusinessException(ErrorCode.REFERENCE_DATA_NOT_SEEDED);
        }
    }
}
