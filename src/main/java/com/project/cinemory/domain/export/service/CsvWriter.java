package com.project.cinemory.domain.export.service;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * RFC 4180 CSV 쓰기 — 줄 끝은 CRLF, 쉼표·큰따옴표·줄바꿈이 든 칸은 큰따옴표로 감싸고 내부 큰따옴표는 두 번 쓴다.
 * 파일 맨 앞에 UTF-8 BOM을 둔다(엑셀에서 한글이 깨지지 않게 — settings-spec ST-5). null은 빈 칸이다.
 */
final class CsvWriter {

    private static final char BOM = '﻿';

    private final Writer out;

    CsvWriter(Writer out) throws IOException {
        this.out = out;
        out.write(BOM);
    }

    void writeRow(List<String> cells) throws IOException {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.write(',');
            }
            out.write(escape(cells.get(i)));
        }
        out.write("\r\n");
    }

    void flush() throws IOException {
        out.flush();
    }

    static String escape(String cell) {
        if (cell == null || cell.isEmpty()) {
            return "";
        }
        boolean needsQuote = cell.indexOf(',') >= 0 || cell.indexOf('"') >= 0
                || cell.indexOf('\n') >= 0 || cell.indexOf('\r') >= 0;
        return needsQuote ? '"' + cell.replace("\"", "\"\"") + '"' : cell;
    }
}
