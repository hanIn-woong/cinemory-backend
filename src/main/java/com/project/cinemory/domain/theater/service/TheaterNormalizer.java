package com.project.cinemory.domain.theater.service;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 극장 단위 묶기·이름 정규화·체인 판정 규칙(cinemap-spec C-4-2, C-4-3). 상태 없는 순수 함수만 둔다 —
 * 규칙은 dryRun 결과로 계속 보완되므로 테스트하기 쉬워야 한다.
 */
public final class TheaterNormalizer {

    public static final String CHAIN_OTHER = "기타";

    private static final Pattern PARENS = Pattern.compile("\\([^)]*\\)");
    // 닫히지 않은 괄호 — 원천 주소가 "(삼성동, 코엑스)"처럼 쉼표를 괄호 안에 넣으면 쉼표로 자른 뒤 남는다
    private static final Pattern UNCLOSED_PAREN = Pattern.compile("\\([^)]*$");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    // 지번 주소: 첫 "번지" 토큰(숫자 또는 숫자-숫자)까지. 동 이름에 숫자가 붙은 경우(역삼1동)는 앞이 공백이 아니라 걸리지 않는다.
    // 번지 뒤에 "867외1필지"처럼 글자가 바로 붙는 표기도 번지에서 자른다
    private static final Pattern LOT_BASE = Pattern.compile("^(.*?\\s\\d+(?:-\\d+)?)(?=[^\\d-]|$)");
    private static final Pattern NUMBERED_HALL = Pattern.compile("\\s*(?:제\\s*)?\\d+\\s*(?:상영관|관)$");
    private static final Pattern SPECIAL_HALL = Pattern.compile(
            "(?:\\s+|\\s*-\\s*)(?:I\\s?MAX|4DX|SCREENX|골드클래스|샤롯데|부티크|르\\s*리클라이너|리클라이너|컴포트|COMFORT|ART|STAR|LED)\\s*관?$",
            Pattern.CASE_INSENSITIVE);
    // 이름 끝의 "(제10관)" "[CGV아트하우스]" "(4DX)" — 괄호 안이 관·특별관 표기일 때만 뗀다("(주)"는 남긴다)
    private static final Pattern TRAILING_BRACKET = Pattern.compile("\\s*[(\\[]([^()\\[\\]]*)[)\\]]$");
    private static final Pattern HALL_WORD = Pattern.compile(
            "관|I\\s?MAX|4DX|SCREENX|아트하우스|DOLBY|MX4D|COMFORT|LED", Pattern.CASE_INSENSITIVE);

    private TheaterNormalizer() {
    }

    /**
     * 주소 기준부 — 도로명 주소의 첫 쉼표 앞, 괄호 제거. 도로명이 비면 지번 주소에서 번지까지.
     *
     * @return 둘 다 비면 빈 문자열
     */
    public static String addressBase(String roadAddr, String lotAddr) {
        if (roadAddr != null && !roadAddr.isBlank()) {
            // 괄호를 먼저 지운 뒤 쉼표로 자른다 — "(삼성동, 코엑스)"의 쉼표에서 잘리면 괄호가 열린 채 남는다
            String head = PARENS.matcher(roadAddr).replaceAll(" ").split(",", 2)[0];
            return clean(UNCLOSED_PAREN.matcher(head).replaceFirst(""));
        }
        return lotAddressBase(lotAddr);
    }

    /** 지번 주소 기준부 — 좌표 2순위 검색어로도 쓴다. */
    public static String lotAddressBase(String lotAddr) {
        if (lotAddr == null || lotAddr.isBlank()) {
            return "";
        }
        String cleaned = clean(UNCLOSED_PAREN.matcher(PARENS.matcher(lotAddr).replaceAll(" ")).replaceFirst(""));
        var m = LOT_BASE.matcher(cleaned);
        return m.find() ? m.group(1) : cleaned;
    }

    /**
     * 시·도 접두를 뗀 검색어 — 원천이 "전남광주통합특별시 광양시"처럼 카카오가 모르는 행정구역명을 쓸 때의 마지막 재시도.
     * 둘째 토큰이 시·군일 때만 뗀다("남구"처럼 시·도 밖에도 있는 이름이면 엉뚱한 곳이 잡힌다). 못 떼면 빈 문자열.
     */
    public static String withoutProvince(String address) {
        String[] tokens = address == null ? new String[0] : address.trim().split(" ");
        if (tokens.length >= 3 && (tokens[1].endsWith("시") || tokens[1].endsWith("군"))) {
            return String.join(" ", java.util.Arrays.copyOfRange(tokens, 1, tokens.length));
        }
        return "";
    }

    public static String groupKey(String groupCode, String addressBase) {
        return groupCode + "|" + addressBase;
    }

    /** 앞뒤 공백 제거 → 연속 공백 1개 → 끝의 관 표기 제거(반복). */
    public static String normalizeName(String rawName) {
        String name = rawName == null ? "" : SPACES.matcher(stripLegalEntity(rawName).trim()).replaceAll(" ");
        for (int i = 0; i < 3; i++) {
            String next = stripHallBracket(name);
            next = NUMBERED_HALL.matcher(next).replaceFirst("");
            next = SPECIAL_HALL.matcher(next).replaceFirst("").trim();
            if (next.equals(name) || next.isEmpty()) {
                break;
            }
            name = next;
        }
        return name;
    }

    /** 법인 표기 제거 — 앱에 보일 극장명에 "(주)"·"주식회사"가 남지 않게. "메가박스중앙"은 상호이므로 "메가박스"로 줄인다. */
    private static String stripLegalEntity(String raw) {
        // "롯데컬처웍스(주) 도곡"처럼 운영사명만 있고 브랜드가 없으면 브랜드로 바꾼다(그냥 지우면 "도곡"만 남아 체인을 잃는다)
        String operator = raw.contains("롯데시네마") ? " " : "롯데시네마 ";
        String s = raw.replace("(주)", " ").replace("㈜", " ").replace("주식회사", " ")
                .replace("롯데컬처웍스", operator).replace("메가박스중앙", "메가박스 ");
        return s.trim().isEmpty() ? raw : s;
    }

    private static String stripHallBracket(String name) {
        var m = TRAILING_BRACKET.matcher(name);
        return m.find() && HALL_WORD.matcher(m.group(1)).find() ? name.substring(0, m.start()) : name;
    }

    /**
     * 이름에 체인명이 들어 있으면 그 체인. 공백·마침표·대소문자는 무시한다.
     * 앞부분 일치만으로는 "CJ CGV 광주용봉" "(주)씨지브이 화정" "롯데컬처웍스(주) 롯데시네마 …" "주식회사 트리스타 메가박스 …"가
     * 전부 '기타'가 된다(첫 dryRun) — 법인명이 앞에 붙는 표기가 흔해 포함 여부로 판정한다.
     */
    public static String chainOf(String normalizedName) {
        String compact = normalizedName.replace(" ", "").replace(".", "").toUpperCase(Locale.ROOT);
        if (compact.contains("CGV") || compact.contains("씨지브이") || compact.contains("시지브이")) return "CGV";
        if (compact.contains("롯데시네마")) return "롯데시네마";
        // MX4D는 메가박스 전용 상영관 브랜드라 이름에 '메가박스'가 없어도 단서가 된다
        if (compact.contains("메가박스") || compact.contains("MEGABOX") || compact.contains("MX4D")) return "메가박스";
        if (compact.contains("씨네Q") || compact.contains("씨네큐") || compact.contains("CINEQ")) return "씨네Q";
        return CHAIN_OTHER;
    }

    private static String clean(String s) {
        return SPACES.matcher(PARENS.matcher(s).replaceAll(" ")).replaceAll(" ").trim();
    }
}
