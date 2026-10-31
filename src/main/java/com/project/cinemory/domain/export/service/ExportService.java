package com.project.cinemory.domain.export.service;

import com.project.cinemory.domain.collection.entity.Collection;
import com.project.cinemory.domain.collection.entity.CollectionMovie;
import com.project.cinemory.domain.collection.repository.CollectionMovieRepository;
import com.project.cinemory.domain.collection.repository.CollectionRepository;
import com.project.cinemory.domain.movie.entity.Movie;
import com.project.cinemory.domain.review.entity.Review;
import com.project.cinemory.domain.review.repository.ReviewRepository;
import com.project.cinemory.domain.watch.entity.WatchRecord;
import com.project.cinemory.domain.watch.repository.WatchRecordRepository;
import com.project.cinemory.domain.wish.entity.WishMovie;
import com.project.cinemory.domain.wish.repository.WishMovieRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 내 기록 내보내기 — ZIP(CSV 4개) 생성 (settings-spec ST-5).
 *
 * <p>사용자 한 명 범위를 <b>벌크 조회</b>(기록당 쿼리가 아니라 테이블당 1회, movie 등은 함께 로딩)로 읽어 {@link ExportData}로 만들고
 * ({@link #load}, 읽기 전용 트랜잭션), 트랜잭션이 끝난 뒤 {@link #writeZip}이 {@link ZipOutputStream}으로 응답에 직접 쓴다.
 * 열 이름은 기획노트 8절 "영화 기록 가져오기" 템플릿(Letterboxd 형식)과 맞춘다 — 나중에 가져오기를 만들면 그대로 되돌려 넣을 수 있다.
 */
@Service
@RequiredArgsConstructor
public class ExportService {

    static final List<String> WATCH_RECORDS_HEADER = List.of("WatchedDate", "Title", "OriginalTitle", "Year", "tmdbID",
            "Rating10", "Rating5", "WatchType", "Place", "OttPlatform", "PrivateMemo", "Rewatch", "CreatedAt");
    static final List<String> REVIEWS_HEADER = List.of("Title", "Year", "tmdbID", "Review", "CreatedAt", "UpdatedAt");
    static final List<String> WISHES_HEADER = List.of("Title", "Year", "tmdbID", "AddedAt");
    static final List<String> COLLECTIONS_HEADER = List.of("Collection", "Description", "CollectionOrder", "Title", "Year",
            "tmdbID", "MovieOrder");

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private final WatchRecordRepository watchRecordRepository;
    private final ReviewRepository reviewRepository;
    private final WishMovieRepository wishMovieRepository;
    private final CollectionRepository collectionRepository;
    private final CollectionMovieRepository collectionMovieRepository;

    /** CSV별 데이터 행(머리글 제외). 칸은 이미 문자열로 바뀌어 있어 영속성 컨텍스트와 무관하다. */
    public record ExportData(List<List<String>> watchRecords, List<List<String>> reviews,
                             List<List<String>> wishes, List<List<String>> collections) {
    }

    @Transactional(readOnly = true)
    public ExportData load(Long userId) {
        return new ExportData(
                watchRecordRows(watchRecordRepository.findAllByUserId(userId)),
                reviewRows(reviewRepository.findAllByUserId(userId)),
                wishRows(wishMovieRepository.findAllByUserId(userId)),
                collectionRows(collectionRepository.findByUserId(userId),
                        collectionMovieRepository.findAllByCollectionUserId(userId)));
    }

    /**
     * ZIP을 {@code out}에 쓴다. 스트림을 닫는 책임은 호출부다(서블릿 컨테이너가 응답 스트림을 닫는다) — 여기서는 끝 기록만 마친다.
     * 파일 순서는 {@code watch_records.csv, reviews.csv, wishes.csv, collections.csv}.
     */
    public void writeZip(ExportData data, OutputStream out) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
        writeEntry(zip, "watch_records.csv", WATCH_RECORDS_HEADER, data.watchRecords());
        writeEntry(zip, "reviews.csv", REVIEWS_HEADER, data.reviews());
        writeEntry(zip, "wishes.csv", WISHES_HEADER, data.wishes());
        writeEntry(zip, "collections.csv", COLLECTIONS_HEADER, data.collections());
        zip.finish();
        zip.flush();
    }

    private static void writeEntry(ZipOutputStream zip, String name, List<String> header, List<List<String>> rows)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        // Writer를 닫으면 ZIP 스트림까지 닫히므로 flush만 한다
        CsvWriter csv = new CsvWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8));
        csv.writeRow(header);
        for (List<String> row : rows) {
            csv.writeRow(row);
        }
        csv.flush();
        zip.closeEntry();
    }

    // ---- 행 변환 ----------------------------------------------------------------------------------

    /**
     * 관람일 오름차순(없는 날짜는 맨 뒤, 같으면 id 순)으로 줄을 세운다. {@code Rewatch}는 같은 영화의 두 번째 이후 기록이면 {@code true}.
     */
    private static List<List<String>> watchRecordRows(List<WatchRecord> records) {
        Comparator<WatchRecord> order = Comparator
                .comparing(WatchRecord::getWatchDate, Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
                .thenComparing(WatchRecord::getId);
        List<WatchRecord> sorted = records.stream().sorted(order).toList();

        Set<Long> seenMovies = new HashSet<>();
        List<List<String>> rows = new ArrayList<>(sorted.size());
        for (WatchRecord record : sorted) {
            Movie movie = record.getMovie();
            boolean rewatch = !seenMovies.add(movie.getId());
            rows.add(row(
                    date(record.getWatchDate()),
                    movie.displayTitle(),
                    movie.getOriginalTitle(),
                    year(movie),
                    String.valueOf(movie.getTmdbId()),
                    rating10(record.getRating()),
                    rating5(record.getRating()),
                    record.getWatchType() == null ? null : record.getWatchType().name(),
                    record.getPlaceDetail(),
                    record.getOttPlatform() == null ? null : record.getOttPlatform().getName(),
                    record.getPrivateReview(),
                    String.valueOf(rewatch),
                    dateTime(record.getCreatedAt())));
        }
        return rows;
    }

    private static List<List<String>> reviewRows(List<Review> reviews) {
        return reviews.stream()
                .sorted(Comparator.comparing(Review::getCreatedAt, Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder()))
                        .thenComparing(Review::getId))
                .map(review -> row(
                        review.getMovie().displayTitle(),
                        year(review.getMovie()),
                        String.valueOf(review.getMovie().getTmdbId()),
                        review.getContent(),
                        dateTime(review.getCreatedAt()),
                        dateTime(review.getUpdatedAt())))
                .toList();
    }

    private static List<List<String>> wishRows(List<WishMovie> wishes) {
        return wishes.stream()
                .sorted(Comparator.comparing(WishMovie::getCreatedAt, Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder()))
                        .thenComparing(WishMovie::getId))
                .map(wish -> row(
                        wish.getMovie().displayTitle(),
                        year(wish.getMovie()),
                        String.valueOf(wish.getMovie().getTmdbId()),
                        dateTime(wish.getCreatedAt())))
                .toList();
    }

    /** 컬렉션은 {@code position} 순, 안의 영화도 {@code position} 순. 빈 컬렉션도 한 줄은 남긴다(영화 칸만 비움). */
    private static List<List<String>> collectionRows(List<Collection> collections, List<CollectionMovie> items) {
        Map<Long, List<CollectionMovie>> byCollection = new HashMap<>();
        for (CollectionMovie item : items) {
            byCollection.computeIfAbsent(item.getCollection().getId(), id -> new ArrayList<>()).add(item);
        }

        List<List<String>> rows = new ArrayList<>();
        List<Collection> orderedCollections = collections.stream()
                .sorted(Comparator.comparingInt(Collection::getPosition).thenComparing(Collection::getId))
                .toList();
        for (Collection collection : orderedCollections) {
            List<CollectionMovie> movies = byCollection.getOrDefault(collection.getId(), List.of()).stream()
                    .sorted(Comparator.comparingInt(CollectionMovie::getPosition).thenComparing(CollectionMovie::getId))
                    .toList();
            String position = String.valueOf(collection.getPosition());
            if (movies.isEmpty()) {
                rows.add(row(collection.getName(), collection.getDescription(), position, null, null, null, null));
                continue;
            }
            for (CollectionMovie item : movies) {
                Movie movie = item.getMovie();
                rows.add(row(collection.getName(), collection.getDescription(), position, movie.displayTitle(),
                        year(movie), String.valueOf(movie.getTmdbId()), String.valueOf(item.getPosition())));
            }
        }
        return rows;
    }

    // ---- 칸 포맷 ----------------------------------------------------------------------------------

    /** {@code List.of}는 null을 거부한다 — 빈 칸을 null로 둘 수 있게 {@code Arrays.asList}를 쓴다. */
    private static List<String> row(String... cells) {
        return Arrays.asList(cells);
    }

    private static String date(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private static String dateTime(LocalDateTime time) {
        return time == null ? null : time.format(DATE_TIME);
    }

    private static String year(Movie movie) {
        return movie.getReleaseDate() == null ? null : String.valueOf(movie.getReleaseDate().getYear());
    }

    /** 저장값(1.0~10.0)을 그대로, 소수 끝 0은 뗀다(7.0 → 7, 7.5 → 7.5). */
    private static String rating10(BigDecimal rating) {
        return rating == null ? null : rating.stripTrailingZeros().toPlainString();
    }

    /** 5점 만점 환산 — 10점 값 ÷ 2, 소수 첫째 자리까지. */
    private static String rating5(BigDecimal rating) {
        return rating == null ? null
                : rating.divide(TWO, 1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
