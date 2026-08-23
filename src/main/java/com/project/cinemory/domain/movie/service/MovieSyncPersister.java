package com.project.cinemory.domain.movie.service;

import com.project.cinemory.domain.country.entity.Country;
import com.project.cinemory.domain.country.repository.CountryRepository;
import com.project.cinemory.domain.genre.entity.Genre;
import com.project.cinemory.domain.genre.repository.GenreRepository;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.movie.entity.MovieActor;
import com.project.cinemory.domain.movie.entity.MovieCountry;
import com.project.cinemory.domain.movie.entity.MovieDirector;
import com.project.cinemory.domain.movie.entity.MovieGenre;
import com.project.cinemory.domain.movie.entity.RoleTier;
import com.project.cinemory.domain.movie.repository.MovieActorRepository;
import com.project.cinemory.domain.movie.repository.MovieCountryRepository;
import com.project.cinemory.domain.movie.repository.MovieDirectorRepository;
import com.project.cinemory.domain.movie.repository.MovieGenreRepository;
import com.project.cinemory.domain.movie.repository.MovieRepository;
import com.project.cinemory.domain.person.entity.Person;
import com.project.cinemory.domain.person.repository.PersonRepository;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.tmdb.dto.TmdbCast;
import com.project.cinemory.global.infra.tmdb.dto.TmdbCredits;
import com.project.cinemory.global.infra.tmdb.dto.TmdbCrew;
import com.project.cinemory.global.infra.tmdb.dto.TmdbGenreListResponse;
import com.project.cinemory.global.infra.tmdb.dto.TmdbMovieDetailResponse;
import com.project.cinemory.global.infra.tmdb.dto.TmdbProductionCountry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * TMDB 영화 상세 응답을 DB에 반영하는 5단계 파이프라인 (tmdb-sync-spec 6-4).
 *
 * <p>{@link #persist(TmdbMovieDetailResponse)} 전체가 한 트랜잭션이다. HTTP 호출은
 * {@code MovieSyncService}가 이미 끝낸 뒤 이 메서드로 넘어오므로, 여기서는 네트워크
 * 대기 없이 DB 작업만 수행한다.
 *
 * <p>순서: ① adult 검사 → ② Movie upsert → ③ Person 통합 upsert(cast ∪ directors) →
 * ④ 매핑 4종 벌크 삭제 → ⑤ 매핑 4종 재삽입. ③을 cast/crew 각각 upsert하지 않고 합쳐서
 * 하는 이유는 배우 겸 감독인 사람에서 {@code uk_person_tmdb_id}를 위반하기 때문이다
 * (같은 트랜잭션 flush 전이라 두 번째 조회가 첫 번째 저장을 못 본다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MovieSyncPersister {

    private static final int TITLE_MAX_LENGTH = 255;
    private static final int OVERVIEW_MAX_LENGTH = 1000;
    private static final int PERSON_NAME_MAX_LENGTH = 100;
    private static final int CHARACTER_NAME_MAX_LENGTH = 100;

    private final MovieRepository movieRepository;
    private final PersonRepository personRepository;
    private final GenreRepository genreRepository;
    private final CountryRepository countryRepository;
    private final MovieGenreRepository movieGenreRepository;
    private final MovieCountryRepository movieCountryRepository;
    private final MovieActorRepository movieActorRepository;
    private final MovieDirectorRepository movieDirectorRepository;

    @Transactional
    public Movie persist(TmdbMovieDetailResponse detail) {
        // ① adult 검사는 매핑보다 먼저 한다 — Person upsert가 먼저 돌면 거부된 영화의
        // 출연진이 person 테이블에 남는다.
        if (detail.isAdult()) {
            if (movieRepository.existsByTmdbId(detail.id())) {
                // 이미 저장된 영화가 adult로 바뀐 경우. watch_record/review/wish_movie/
                // collection_movie의 FK가 전부 RESTRICT라 삭제 자체가 불가능하다.
                // 기존 데이터는 그대로 두고 운영자가 개별 판단한다.
                log.warn("이미 저장된 영화가 adult로 바뀌었습니다. tmdbId={}", detail.id());
            }
            throw new BusinessException(ErrorCode.ADULT_CONTENT_NOT_ALLOWED);
        }

        // ② Movie upsert
        Movie movie = upsertMovie(detail);

        // ③ Person 통합 upsert (cast ∪ directors)
        TmdbCredits credits = detail.creditsOrEmpty();
        List<TmdbCast> cast = credits.castOrEmpty();
        List<TmdbCrew> directors = credits.crewOrEmpty().stream().filter(TmdbCrew::isDirector).toList();
        Map<Long, Person> personIndex = upsertPersons(detail.id(), cast, directors);

        // ④ 매핑 4종 삭제 (벌크 DML)
        movieActorRepository.deleteByMovieId(movie.getId());
        movieDirectorRepository.deleteByMovieId(movie.getId());
        movieGenreRepository.deleteByMovieId(movie.getId());
        movieCountryRepository.deleteByMovieId(movie.getId());

        // ⑤ 매핑 4종 재삽입
        applyGenres(movie, detail.genresOrEmpty());
        applyCountries(movie, detail.productionCountriesOrEmpty(), detail.originCountryOrEmpty());
        applyCast(movie, cast, personIndex);
        applyDirectors(movie, directors, personIndex);

        return movie;
    }

    private Movie upsertMovie(TmdbMovieDetailResponse detail) {
        // 폴백 발동 빈도 실측용 (잔여 #4). title 폴백은 드물고 사용자 눈에 직접 띄므로 편당 WARN.
        if (detail.isTitleFallback()) {
            log.warn("영화 제목이 비어 있어 원어 제목으로 폴백했습니다. tmdbId={}, originalTitle={}",
                    detail.id(), detail.originalTitle());
        }

        String title = truncate(detail.resolveTitle(), TITLE_MAX_LENGTH, "movie.title", detail.id());
        String originalTitle = truncate(detail.originalTitle(), TITLE_MAX_LENGTH, "movie.original_title", detail.id());
        String overview = truncate(detail.overview(), OVERVIEW_MAX_LENGTH, "movie.overview", detail.id());
        String posterPath = detail.posterPath();
        String backdropPath = detail.backdropPath();
        Integer runtime = detail.normalizedRuntime();
        LocalDate releaseDate = detail.parsedReleaseDate();
        BigDecimal voteAverage = detail.normalizedVoteAverage();
        Integer voteCount = detail.voteCount();

        return movieRepository.findByTmdbId(detail.id())
                .map(existing -> {
                    existing.updateMetadata(title, posterPath, overview, runtime, releaseDate,
                            originalTitle, backdropPath, voteAverage, voteCount);
                    return existing;
                })
                .orElseGet(() -> movieRepository.save(Movie.builder()
                        .tmdbId(detail.id())
                        .title(title)
                        .originalTitle(originalTitle)
                        .posterPath(posterPath)
                        .backdropPath(backdropPath)
                        .releaseDate(releaseDate)
                        .overview(overview)
                        .runtime(runtime)
                        .voteAverage(voteAverage)
                        .voteCount(voteCount)
                        .build()));
    }

    /**
     * cast ∪ directors 합집합을 {@code findByTmdbPersonIdIn}으로 1쿼리 조회해 upsert한다.
     *
     * @return {@code tmdbPersonId → Person} — applyCast/applyDirectors가 FK로 쓴다
     */
    private Map<Long, Person> upsertPersons(Long movieTmdbId, List<TmdbCast> cast, List<TmdbCrew> directors) {
        // tmdbPersonId 기준 이름/프로필 경로 합집합. 같은 사람이 cast/directors 양쪽에 있으면
        // 먼저 만난 값을 쓴다 — 사람 이름은 어느 쪽이든 동일하므로 문제되지 않는다.
        //
        // 폴백 발동 빈도 실측용 (잔여 #4). cast가 편당 최대 수백 명이라 건별 WARN은 로그를
        // 묻으므로 편 단위로 집계해 한 번만 남긴다.
        int nameFallbackCount = 0;
        Map<Long, PersonProfile> byTmdbPersonId = new LinkedHashMap<>();
        for (TmdbCast c : cast) {
            if (c.isNameFallback()) {
                nameFallbackCount++;
            }
            byTmdbPersonId.putIfAbsent(c.id(),
                    new PersonProfile(truncate(c.resolveName(), PERSON_NAME_MAX_LENGTH, "person.name", c.id()), c.profilePath()));
        }
        for (TmdbCrew c : directors) {
            if (c.isNameFallback()) {
                nameFallbackCount++;
            }
            byTmdbPersonId.putIfAbsent(c.id(),
                    new PersonProfile(truncate(c.resolveName(), PERSON_NAME_MAX_LENGTH, "person.name", c.id()), c.profilePath()));
        }
        if (nameFallbackCount > 0) {
            log.warn("인물명이 비어 있어 원어명으로 폴백했습니다. movieTmdbId={}, 폴백 건수={}/{}",
                    movieTmdbId, nameFallbackCount, cast.size() + directors.size());
        }

        if (byTmdbPersonId.isEmpty()) {
            return Map.of();
        }

        Map<Long, Person> existing = personRepository.findByTmdbPersonIdIn(byTmdbPersonId.keySet()).stream()
                .collect(Collectors.toMap(Person::getTmdbPersonId, Function.identity()));

        Map<Long, Person> personIndex = new LinkedHashMap<>(existing);
        List<Person> toInsert = new ArrayList<>();

        for (Map.Entry<Long, PersonProfile> entry : byTmdbPersonId.entrySet()) {
            Long tmdbPersonId = entry.getKey();
            PersonProfile profile = entry.getValue();
            Person person = existing.get(tmdbPersonId);
            if (person == null) {
                Person created = Person.of(tmdbPersonId, profile.name(), profile.profilePath());
                toInsert.add(created);
                personIndex.put(tmdbPersonId, created);
            } else {
                person.updateProfile(profile.name(), profile.profilePath());
            }
        }

        // IDENTITY 전략이라 save 시점에 즉시 insert되어 id가 채워진다 — 아래 매핑 단계가 바로 쓸 수 있다.
        personRepository.saveAll(toInsert);
        return personIndex;
    }

    private void applyGenres(Movie movie, List<TmdbGenreListResponse.Item> genres) {
        if (genres.isEmpty()) {
            // 1/N을 루프 밖에서 미리 계산하면 빈 배열일 때 ArithmeticException이다 — 즉시 반환.
            return;
        }

        List<TmdbGenreListResponse.Item> distinct = distinctBy(genres, TmdbGenreListResponse.Item::id);
        Set<Integer> tmdbGenreIds = distinct.stream().map(TmdbGenreListResponse.Item::id).collect(Collectors.toSet());
        Map<Integer, Genre> genreByTmdbId = genreRepository.findByTmdbGenreIdIn(tmdbGenreIds).stream()
                .collect(Collectors.toMap(Genre::getTmdbGenreId, Function.identity()));

        BigDecimal weight = BigDecimal.ONE.divide(BigDecimal.valueOf(distinct.size()), 3, RoundingMode.HALF_UP);

        List<MovieGenre> toInsert = new ArrayList<>();
        for (TmdbGenreListResponse.Item item : distinct) {
            Genre genre = genreByTmdbId.get(item.id());
            if (genre == null) {
                // 참조 테이블 미적재 — 그 자리에서 생성하지 않고 실패시킨다 (6-1 원칙).
                throw new BusinessException(ErrorCode.GENRE_NOT_FOUND, "미적재 장르입니다. tmdbGenreId=" + item.id());
            }
            toInsert.add(MovieGenre.of(movie, genre, weight));
        }
        movieGenreRepository.saveAll(toInsert);
    }

    private void applyCountries(Movie movie, List<TmdbProductionCountry> productionCountries, List<String> originCountry) {
        if (productionCountries.isEmpty()) {
            return;
        }

        List<TmdbProductionCountry> distinct = distinctBy(productionCountries, TmdbProductionCountry::iso31661);
        Set<String> codes = distinct.stream().map(TmdbProductionCountry::iso31661).collect(Collectors.toSet());
        Map<String, Country> countryByCode = countryRepository.findByCodeIn(codes).stream()
                .collect(Collectors.toMap(Country::getCode, Function.identity()));

        String representativeCode = resolveRepresentativeCountryCode(distinct, originCountry);

        int n = distinct.size();
        BigDecimal denominator = BigDecimal.valueOf((long) n * n + 1);
        BigDecimal representativeWeight = BigDecimal.valueOf(n + 1).divide(denominator, 3, RoundingMode.HALF_UP);
        BigDecimal othersWeight = BigDecimal.valueOf(n).divide(denominator, 3, RoundingMode.HALF_UP);

        List<MovieCountry> toInsert = new ArrayList<>();
        for (TmdbProductionCountry item : distinct) {
            Country country = countryByCode.get(item.iso31661());
            if (country == null) {
                throw new BusinessException(ErrorCode.COUNTRY_NOT_FOUND, "미적재 국가입니다. code=" + item.iso31661());
            }
            BigDecimal weight = item.iso31661().equals(representativeCode) ? representativeWeight : othersWeight;
            toInsert.add(MovieCountry.of(movie, country, weight));
        }
        movieCountryRepository.saveAll(toInsert);
    }

    /**
     * D-3 확정: {@code origin_country}가 있고 그 값이 {@code production_countries}에도
     * 존재하면 그것을 대표로, 아니면 배열 첫 번째를 대표로 본다.
     *
     * <p>{@code origin_country}가 {@code production_countries}에 없는 경우를 무시하고
     * 폴백하는 이유 — 없는 국가를 대표로 세우면 나머지 가중치의 분모 N과 어긋난다.
     */
    private String resolveRepresentativeCountryCode(List<TmdbProductionCountry> productionCountries, List<String> originCountry) {
        if (!originCountry.isEmpty()) {
            String origin = originCountry.get(0);
            boolean presentInProductionCountries = productionCountries.stream()
                    .anyMatch(country -> country.iso31661().equals(origin));
            if (presentInProductionCountries) {
                return origin;
            }
        }
        return productionCountries.get(0).iso31661();
    }

    private void applyCast(Movie movie, List<TmdbCast> cast, Map<Long, Person> personIndex) {
        if (cast.isEmpty()) {
            return;
        }

        // uk_movie_actor(movie_id, person_id) — 1인 2역이면 order가 가장 작은 항목만 남긴다.
        Map<Long, TmdbCast> dedup = new LinkedHashMap<>();
        for (TmdbCast c : cast) {
            dedup.merge(c.id(), c, (a, b) -> b.order() < a.order() ? b : a);
        }

        List<MovieActor> toInsert = new ArrayList<>();
        for (TmdbCast c : dedup.values()) {
            String characterName = truncate(c.character(), CHARACTER_NAME_MAX_LENGTH, "movie_actor.character_name", movie.getTmdbId());
            toInsert.add(MovieActor.builder()
                    .movie(movie)
                    .person(personIndex.get(c.id()))
                    .characterName(characterName)
                    .displayOrder(c.order())
                    .roleTier(RoleTier.fromDisplayOrder(c.order()))
                    .build());
        }
        movieActorRepository.saveAll(toInsert);
    }

    private void applyDirectors(Movie movie, List<TmdbCrew> directors, Map<Long, Person> personIndex) {
        if (directors.isEmpty()) {
            return;
        }

        // uk_movie_director(movie_id, person_id) — 같은 사람이 job=="Director"로 두 번
        // 나오는 경우(파트별 크레딧)를 personId 기준으로 접는다.
        List<TmdbCrew> distinct = distinctBy(directors, TmdbCrew::id);

        List<MovieDirector> toInsert = new ArrayList<>();
        for (TmdbCrew c : distinct) {
            toInsert.add(MovieDirector.of(movie, personIndex.get(c.id())));
        }
        movieDirectorRepository.saveAll(toInsert);
    }

    /** cast/crew에서 뽑아낸, upsert 이전 Person 원본 값. 도메인 개념이 아니라 이 클래스 내부 집계용이다. */
    private record PersonProfile(String name, String profilePath) {
    }

    private <T, K> List<T> distinctBy(List<T> items, Function<T, K> keyExtractor) {
        return items.stream()
                .collect(Collectors.toMap(keyExtractor, Function.identity(), (first, duplicate) -> first, LinkedHashMap::new))
                .values().stream().toList();
    }

    /**
     * 길이 초과 시 절단 + WARN (6-3 ④). 컬럼을 넓히지 않고, 발동 자체를 "상한 가정이
     * 틀렸을 수 있다"는 신호로 남긴다.
     *
     * <p>⚠️ 서로게이트 페어 — {@code String.length()}는 UTF-16 코드 단위를 세고 MySQL
     * {@code varchar(N)}의 N은 문자 수다. 이모지 등 보조 평면 문자가 섞이면 Java 길이가
     * 더 크게 나와 절단이 보수적으로 걸리지만(안전), {@code substring}이 서로게이트 페어
     * 중간을 자르면 깨진 문자가 저장되므로 절단 위치를 보정한다.
     */
    private String truncate(String value, int maxLength, String fieldName, Long tmdbId) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        int cut = maxLength - 3;
        if (cut > 0 && Character.isLowSurrogate(value.charAt(cut))) {
            cut--;
        }
        log.warn("{} 길이 초과로 절단했습니다. tmdbId={}, 원본 길이={}", fieldName, tmdbId, value.length());
        return value.substring(0, cut) + "...";
    }
}
