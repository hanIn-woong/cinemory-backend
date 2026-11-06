package com.project.cinemory.global.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 검색 키 정규화 (service-layer-spec 4-2-C). 저장하는 키({@code movie.search_key})와 검색어가 <b>같은 규칙</b>이어야
 * 하므로 규칙은 이 클래스 한 곳에만 둔다 - DB 생성 컬럼·SQL 정규식으로 옮기지 않는다(규칙이 갈라지면 키와 검색어가 달라진다).
 *
 * <p>NFKC → 소문자 → 공백·문장부호·기호({@code [\p{Z}\p{P}\p{S}]}) 제거. 한글·영문·숫자만 남는다.
 */
public final class SearchKey {

    private static final Pattern STRIPPED = Pattern.compile("[\\p{Z}\\p{P}\\p{S}]");

    private SearchKey() {
    }

    /** {@code null}이면 빈 문자열. 결과가 비었다면 검색어로 쓸 수 없는 입력(문장부호만 등)이다. */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String nfkc = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return STRIPPED.matcher(nfkc).replaceAll("");
    }
}
