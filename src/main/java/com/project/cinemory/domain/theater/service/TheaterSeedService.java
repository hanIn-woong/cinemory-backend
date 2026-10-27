package com.project.cinemory.domain.theater.service;

import com.project.cinemory.domain.theater.dto.TheaterSeedData;
import com.project.cinemory.domain.theater.dto.TheaterSeedReport;
import com.project.cinemory.global.exception.BusinessException;
import com.project.cinemory.global.exception.ErrorCode;
import com.project.cinemory.global.infra.kakao.KakaoLocalClient;
import com.project.cinemory.global.infra.kakao.KakaoLocalClient.KakaoLocalException;
import com.project.cinemory.global.infra.kakao.KakaoLocalClient.LatLng;
import com.project.cinemory.global.infra.publicdata.PublicDataTheaterClient;
import com.project.cinemory.global.infra.publicdata.dto.TheaterSourcePage;
import com.project.cinemory.global.infra.publicdata.dto.TheaterSourceRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 극장 시드(cinemap-spec C-4) — 공공데이터 전 페이지 순회 → 영업 중 필터 → 극장 단위 묶기 → 좌표 결정 → (dryRun이 아니면) upsert.
 *
 * <p><b>트랜잭션을 걸지 않는다</b> — 외부 HTTP를 수십~수백 번 부르는 동안 커넥션을 잡지 않기 위해서다(tmdb-sync 6-4).
 * 저장만 {@link TheaterPersister}가 한다.
 *
 * <p>{@code running} 플래그는 영화 시드·resync·임베딩 플래그와 <b>분리</b>한다 — 호출 대상과 한도가 다르다.
 */
@Slf4j
@Service
public class TheaterSeedService {

    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 200;        // totalCount가 어긋나도 무한 순회하지 않는다
    private static final String ACTIVE_STATUS = "01";
    private static final int SPLIT_GROUP_LIMIT = 50;
    private static final int FAILURE_LIMIT = 200;
    private static final int SAMPLE_SIZE = 20;
    private static final int SHAPE_EXAMPLES = 5;
    private static final int OTHER_CHAIN_LIMIT = 100;
    private static final int OUTLIER_LIMIT = 15;
    private static final int MAX_CONSECUTIVE_KAKAO_ERRORS = 3;

    private final PublicDataTheaterClient publicDataClient;
    private final KakaoLocalClient kakaoLocalClient;
    private final TheaterPersister persister;
    private final Set<String> excludedShapes;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public TheaterSeedService(PublicDataTheaterClient publicDataClient,
                              KakaoLocalClient kakaoLocalClient,
                              TheaterPersister persister,
                              // 제외 목록이다(허용 목록 아님) — 첫 dryRun에서 영업 형태가 '빈 값'인 42행이 실제 체인 극장으로 드러났다
                              @Value("${cinemory.theater.seed.excluded-shapes:}") List<String> excludedShapes) {
        this.publicDataClient = publicDataClient;
        this.kakaoLocalClient = kakaoLocalClient;
        this.persister = persister;
        this.excludedShapes = excludedShapes.stream().map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    public TheaterSeedReport seed(boolean dryRun) {
        if (!publicDataClient.isConfigured() || !kakaoLocalClient.isConfigured()) {
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR,
                    "PUBLICDATA_SERVICE_KEY / KAKAO_REST_API_KEY가 설정되지 않았습니다.");
        }
        if (!running.compareAndSet(false, true)) {
            throw new BusinessException(ErrorCode.THEATER_SEED_ALREADY_RUNNING);
        }
        try {
            return run(dryRun);
        } finally {
            running.set(false);
        }
    }

    private TheaterSeedReport run(boolean dryRun) {
        // ① 전 페이지 순회 — 원천 행 식별은 OPN_ATMY_GRP_CD + MNG_NO (MNG_NO만으로는 중복)
        Map<String, TheaterSourceRow> unique = new LinkedHashMap<>();
        int duplicates = 0;
        int totalCount = Integer.MAX_VALUE;
        for (int page = 1; page <= MAX_PAGES && (page - 1) * PAGE_SIZE < totalCount; page++) {
            TheaterSourcePage result = publicDataClient.fetchPage(page, PAGE_SIZE);
            totalCount = result.totalCount();
            if (result.rows().isEmpty()) {
                break;
            }
            for (TheaterSourceRow row : result.rows()) {
                if (unique.putIfAbsent(row.opnAtmyGrpCd() + "|" + row.mngNo(), row) != null) {
                    duplicates++;
                }
            }
        }
        log.info("극장 시드: 원천 {}행 수신 (totalCount={}, 중복={})", unique.size(), totalCount, duplicates);

        // ② 영업 중 + 영업 형태 필터
        Map<String, Integer> statusCounts = new TreeMap<>();
        Map<String, Integer> shapeCounts = new TreeMap<>();
        Map<String, List<String>> shapeExamples = new TreeMap<>();
        List<TheaterSourceRow> active = new ArrayList<>();
        int excludedByShape = 0;
        for (TheaterSourceRow row : unique.values()) {
            statusCounts.merge(row.salsSttsCd(), 1, Integer::sum);
            if (!ACTIVE_STATUS.equals(row.salsSttsCd())) {
                continue;
            }
            String shape = row.prfrplcShpSeNm().isEmpty() ? "(빈 값)" : row.prfrplcShpSeNm();
            shapeCounts.merge(shape, 1, Integer::sum);
            List<String> examples = shapeExamples.computeIfAbsent(shape, k -> new ArrayList<>());
            if (examples.size() < SHAPE_EXAMPLES) {
                examples.add(row.bplcNm());
            }
            active.add(row);
        }

        // ③ 극장 단위 묶기 — 주소 기준부가 없는 행은 묶을 수 없어 실패 목록으로 보낸다
        Map<String, List<TheaterSourceRow>> groups = new LinkedHashMap<>();
        List<TheaterSeedReport.FailedGroup> failures = new ArrayList<>();
        int failedTotal = 0;
        for (TheaterSourceRow row : active) {
            String base = TheaterNormalizer.addressBase(row.roadNmAddr(), row.lotnoAddr());
            if (base.isEmpty()) {
                failedTotal++;
                if (failures.size() < FAILURE_LIMIT) {
                    failures.add(new TheaterSeedReport.FailedGroup(row.opnAtmyGrpCd() + "|(주소 없음) " + row.mngNo(),
                            TheaterNormalizer.normalizeName(row.bplcNm()), row.roadNmAddr(), row.lotnoAddr()));
                }
                continue;
            }
            groups.computeIfAbsent(TheaterNormalizer.groupKey(row.opnAtmyGrpCd(), base), k -> new ArrayList<>()).add(row);
        }

        // 영업 형태 제외는 그룹 단위다 — 그룹의 모든 행이 제외 형태일 때만 뺀다. 형태 라벨이 행마다 틀리게 붙은 체인 극장
        // ("메가박스 대구세븐밸리 2관"이 자동차극장으로 표기)에서 관 하나 때문에 극장 전체가 빠지거나 관 수가 줄지 않게 한다.
        if (!excludedShapes.isEmpty()) {
            var it = groups.entrySet().iterator();
            while (it.hasNext()) {
                List<TheaterSourceRow> g = it.next().getValue();
                if (g.stream().allMatch(r -> excludedShapes.contains(r.prfrplcShpSeNm()))) {
                    excludedByShape += g.size();
                    it.remove();
                }
            }
        }

        // ④ 그룹별 이름·체인·좌표 결정 (주소 검색은 그룹당 1회 — 1순위 실패 시에만 지번으로 1회 더)
        List<TheaterSeedData> seedData = new ArrayList<>();
        List<String> coordSources = new ArrayList<>();
        Map<String, Integer> chainCounts = new TreeMap<>();
        Map<Integer, Integer> screenDist = new TreeMap<>();
        List<TheaterSeedReport.SplitNameGroup> splitGroups = new ArrayList<>();
        List<TheaterSeedReport.MultiChainGroup> multiChain = new ArrayList<>();
        int byAddress = 0;
        int byLot = 0;
        int byTm = 0;
        int kakaoErrors = 0;
        int consecutiveErrors = 0;
        int compared = 0;
        double diffSum = 0;
        double diffMax = 0;
        int over100 = 0;
        List<TheaterSeedReport.Outlier> outliers = new ArrayList<>();
        Map<String, Integer> otherNames = new LinkedHashMap<>();

        for (Map.Entry<String, List<TheaterSourceRow>> entry : groups.entrySet()) {
            String key = entry.getKey();
            List<TheaterSourceRow> rows = entry.getValue();
            String base = key.substring(key.indexOf('|') + 1);

            Map<String, Integer> nameCounts = new TreeMap<>();
            Map<String, Integer> rowChains = new TreeMap<>();
            for (TheaterSourceRow r : rows) {
                String n = TheaterNormalizer.normalizeName(r.bplcNm());
                nameCounts.merge(n, 1, Integer::sum);
                rowChains.merge(TheaterNormalizer.chainOf(n), 1, Integer::sum);
            }
            String name = mostCommon(nameCounts);
            // 체인은 이름 하나가 아니라 행 전체의 다수로 정한다 — 대표 이름이 상영관 별칭("코엑스")이어도 체인은 살린다
            String chain = rowChains.entrySet().stream()
                    .filter(e -> !TheaterNormalizer.CHAIN_OTHER.equals(e.getKey()))
                    .max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                    .map(Map.Entry::getKey).orElse(TheaterNormalizer.CHAIN_OTHER);
            // 대표 이름에 체인이 안 보이는데("주공공이") 체인명이 든 이름이 2행 이상이면 그쪽을 쓴다. 1행뿐이면 우연일 수 있어 바꾸지 않는다
            if (TheaterNormalizer.CHAIN_OTHER.equals(TheaterNormalizer.chainOf(name))
                    && !TheaterNormalizer.CHAIN_OTHER.equals(chain)) {
                Map<String, Integer> chainNames = new TreeMap<>();
                for (Map.Entry<String, Integer> e : nameCounts.entrySet()) {
                    if (chain.equals(TheaterNormalizer.chainOf(e.getKey()))) {
                        chainNames.put(e.getKey(), e.getValue());
                    }
                }
                if (chainNames.values().stream().mapToInt(Integer::intValue).sum() >= 2) {
                    name = mostCommon(chainNames);
                }
            }

            chainCounts.merge(chain, 1, Integer::sum);
            if (TheaterNormalizer.CHAIN_OTHER.equals(chain)) {
                otherNames.put(name + " (" + rows.size() + "관)", rows.size());
            }
            screenDist.merge(rows.size(), 1, Integer::sum);
            if (nameCounts.size() > 1) {
                splitGroups.add(new TheaterSeedReport.SplitNameGroup(key, rows.size(), nameCounts, name));
            }
            long namedChains = rowChains.keySet().stream().filter(c -> !TheaterNormalizer.CHAIN_OTHER.equals(c)).count();
            if (namedChains >= 2) {
                multiChain.add(new TheaterSeedReport.MultiChainGroup(key, rowChains, new ArrayList<>(nameCounts.keySet())));
            }

            // 좌표: 주소 기준부 → 지번 기준부 → 시·도를 뗀 지번 → TM. 검색은 앞 단계가 실패할 때만 다음으로 간다.
            TheaterSourceRow first = rows.get(0);
            boolean hasRoad = !first.roadNmAddr().isBlank();
            String lotBase = TheaterNormalizer.lotAddressBase(first.lotnoAddr());
            List<String[]> attempts = new ArrayList<>();
            attempts.add(new String[]{base, hasRoad ? "address" : "lot"});
            if (hasRoad && !lotBase.isEmpty()) {
                attempts.add(new String[]{lotBase, "lot"});
            }
            String stripped = TheaterNormalizer.withoutProvince(hasRoad ? lotBase : base);
            if (!stripped.isEmpty()) {
                attempts.add(new String[]{stripped, "lot"});
            }

            LatLng searched = null;
            String source = null;
            try {
                for (String[] attempt : attempts) {
                    Optional<LatLng> found = kakaoLocalClient.searchAddress(attempt[0]);
                    if (found.isPresent()) {
                        searched = found.get();
                        source = attempt[1];
                        break;
                    }
                }
                consecutiveErrors = 0;
            } catch (KakaoLocalException e) {
                kakaoErrors++;
                if (++consecutiveErrors >= MAX_CONSECUTIVE_KAKAO_ERRORS) {
                    throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR,
                            "카카오 주소 검색이 연속으로 실패해 중단했습니다.");
                }
            }

            LatLng tm = firstTmCoordinate(rows);
            LatLng chosen = searched;
            if (chosen == null && tm != null) {
                chosen = tm;
                source = "tm";
            }
            if (searched != null && tm != null) {
                double diff = GeoUtils.haversineMeters(searched.latitude(), searched.longitude(),
                        tm.latitude(), tm.longitude());
                compared++;
                diffSum += diff;
                diffMax = Math.max(diffMax, diff);
                if (diff > 100) {
                    over100++;
                    outliers.add(new TheaterSeedReport.Outlier(key, name, round1(diff)));
                }
            }

            if (chosen == null) {
                failedTotal++;
                log.warn("극장 좌표 결정 실패. key={}, name={}", key, name);
                if (failures.size() < FAILURE_LIMIT) {
                    failures.add(new TheaterSeedReport.FailedGroup(key, name, first.roadNmAddr(), first.lotnoAddr()));
                }
                continue;
            }
            switch (source) {
                case "address" -> byAddress++;
                case "lot" -> byLot++;
                default -> byTm++;
            }
            seedData.add(new TheaterSeedData(key, name, chain, base, scale(chosen.latitude()), scale(chosen.longitude()),
                    rows.size(), null));
            coordSources.add(source);
        }

        splitGroups.sort(Comparator.comparingInt((TheaterSeedReport.SplitNameGroup g) -> -g.rows()));
        int splitTotal = splitGroups.size();

        // ⑤ 저장 — 좌표를 못 정한 그룹은 제외한다
        Integer inserted = null;
        Integer updated = null;
        if (!dryRun) {
            TheaterPersister.UpsertResult result = persister.upsertAll(seedData);
            inserted = result.inserted();
            updated = result.updated();
        }

        return new TheaterSeedReport(
                dryRun, unique.size(), duplicates, statusCounts, active.size(), shapeCounts,
                shapeExamples, excludedByShape, groups.size(), chainCounts,
                otherNames.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .limit(OTHER_CHAIN_LIMIT).map(Map.Entry::getKey).toList(),
                splitGroups.stream().limit(SPLIT_GROUP_LIMIT).toList(), splitTotal,
                multiChain, screenDist,
                new TheaterSeedReport.Coordinates(byAddress, byLot, byTm, failedTotal, kakaoErrors,
                        new TheaterSeedReport.TmCheck(compared, compared == 0 ? 0 : round1(diffSum / compared),
                                round1(diffMax), over100,
                                outliers.stream().sorted(Comparator.comparingDouble(TheaterSeedReport.Outlier::meters).reversed())
                                        .limit(OUTLIER_LIMIT).toList()),
                        failures),
                samples(seedData, coordSources), inserted, updated);
    }

    /** 가장 많이 나온 값. 동률이면 사전순으로 앞선 값(결과가 실행마다 같도록). */
    private static String mostCommon(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .orElseThrow().getKey();
    }

    /** 그룹 안에서 처음으로 유효한 TM 좌표를 가진 행의 변환값. 한국 밖으로 떨어지면 무효로 본다. */
    private static LatLng firstTmCoordinate(List<TheaterSourceRow> rows) {
        for (TheaterSourceRow r : rows) {
            try {
                double x = Double.parseDouble(r.crdInfoX());
                double y = Double.parseDouble(r.crdInfoY());
                double[] ll = Epsg5174Converter.toWgs84(x, y);
                if (ll[0] > 33 && ll[0] < 39.5 && ll[1] > 124 && ll[1] < 132) {
                    return new LatLng(ll[0], ll[1]);
                }
            } catch (NumberFormatException | NullPointerException ignored) {
                // 빈 값 — 다음 행
            }
        }
        return null;
    }

    /** 극장 목록 전체에서 고르게 20개(전체가 20개 미만이면 전부). */
    private static List<TheaterSeedReport.SampleTheater> samples(List<TheaterSeedData> data, List<String> sources) {
        List<TheaterSeedReport.SampleTheater> out = new ArrayList<>();
        int step = Math.max(1, data.size() / SAMPLE_SIZE);
        for (int i = 0; i < data.size() && out.size() < SAMPLE_SIZE; i += step) {
            TheaterSeedData d = data.get(i);
            out.add(new TheaterSeedReport.SampleTheater(d.sourceCode(), d.name(), d.chainName(), d.address(),
                    d.latitude(), d.longitude(), d.screenCount(), sources.get(i)));
        }
        return out;
    }

    private static BigDecimal scale(double v) {
        return BigDecimal.valueOf(v).setScale(7, RoundingMode.HALF_UP);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
