package com.project.cinemory.domain.recommendation.service;

import com.project.cinemory.domain.movie.entity.KrCertification;
import com.project.cinemory.domain.movie.entity.RecommendOverride;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 추천 제외 자동 판정 (M3b-recommendation-spec R-5-1). 하나라도 해당하면 제외한다.
 *
 * <table>
 *   <caption>규칙</caption>
 *   <tr><td>KEYWORD</td><td>TMDB 키워드가 차단 목록에 있음 — <b>보조</b>(키워드 누락이 흔하다)</td></tr>
 *   <tr><td>SHAPE_RULE</td><td><b>주 방어</b> — {@code AGE_18} ∧ 로맨스·드라마 ∧ 상영시간 ≤ 기준 ∧ 투표 수 &lt; 기준. 국내 에로 영화의 공통 형태</td></tr>
 *   <tr><td>RESTRICTED</td><td>제한상영가</td></tr>
 * </table>
 *
 * <p>최종 제외 = 관리자 덮어쓰기가 있으면 그 값, 없으면 자동 판정. <b>판정 결과를 DB에 저장하지 않는다</b> — 특징 인덱스를 만들 때 계산하므로
 * 기준값을 바꾸면 재기동만으로 반영된다. 관리자 화면도 같은 빈으로 계산해 보여 준다.
 */
@Component
public class RecommendSafetyPolicy {

    /** TMDB 장르 id — 로맨스 · 드라마. */
    static final int GENRE_ROMANCE = 10749;
    static final int GENRE_DRAMA = 18;

    /** 자동 판정이 막은 사유. 이름이 관리자 응답의 {@code reasons}에 그대로 나간다. */
    public enum Reason { KEYWORD, SHAPE_RULE, RESTRICTED }

    /**
     * 판정에 필요한 영화의 사실.
     *
     * @param genreTmdbIds   {@code genre.tmdb_genre_id}
     * @param blockedKeyword 차단 키워드가 붙어 있는가 — {@link #matchesBlockedKeyword}로 얻는다
     */
    public record Facts(KrCertification certification, Integer runtime, Integer voteCount,
                        Collection<Integer> genreTmdbIds, boolean blockedKeyword) {
    }

    private final RecommendSafetyProperties properties;
    private final Set<String> blockedKeywords;

    public RecommendSafetyPolicy(RecommendSafetyProperties properties) {
        this.properties = properties;
        this.blockedKeywords = properties.blockedKeywords().stream()
                .map(RecommendSafetyPolicy::normalize)
                .filter(k -> !k.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 자동 판정이 막은 사유. 비어 있으면 자동 판정으로는 통과다. */
    public List<Reason> evaluate(Facts facts) {
        List<Reason> reasons = new ArrayList<>(3);
        if (facts.blockedKeyword()) {
            reasons.add(Reason.KEYWORD);
        }
        if (matchesShapeRule(facts)) {
            reasons.add(Reason.SHAPE_RULE);
        }
        if (facts.certification() == KrCertification.RESTRICTED) {
            reasons.add(Reason.RESTRICTED);
        }
        return reasons;
    }

    /** 최종 제외 여부 — 덮어쓰기가 있으면 그 값이 이기고, 없으면 자동 판정. */
    public static boolean isBlocked(boolean autoBlocked, RecommendOverride override) {
        if (override == RecommendOverride.BLOCK) {
            return true;
        }
        if (override == RecommendOverride.ALLOW) {
            return false;
        }
        return autoBlocked;
    }

    /** 키워드 이름 중 차단 목록에 있는 것이 있는가(대소문자·앞뒤 공백 무시). */
    public boolean matchesBlockedKeyword(Collection<String> keywordNames) {
        for (String name : keywordNames) {
            if (name != null && blockedKeywords.contains(normalize(name))) {
                return true;
            }
        }
        return false;
    }

    /** 키워드 조회용 — 정규화(소문자)된 차단 목록. 비어 있으면 키워드 규칙은 꺼진 것이다. */
    public Set<String> blockedKeywords() {
        return blockedKeywords;
    }

    private boolean matchesShapeRule(Facts facts) {
        if (facts.certification() != KrCertification.AGE_18) {
            return false;
        }
        if (facts.runtime() == null || facts.runtime() > properties.shapeMaxRuntime()) {
            return false;
        }
        int votes = facts.voteCount() == null ? 0 : facts.voteCount();
        if (votes >= properties.shapeMinVoteCount()) {
            return false;
        }
        return facts.genreTmdbIds().contains(GENRE_ROMANCE) || facts.genreTmdbIds().contains(GENRE_DRAMA);
    }

    private static String normalize(String keyword) {
        return keyword.trim().toLowerCase(Locale.ROOT);
    }
}
