package lk.dexlanka.erp.admin;

import static lk.dexlanka.erp.platform.Store.require;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A small RFC 4180 reader for import files: comma (or, when the header has no comma, semicolon) separated, optional
 * UTF-8 byte-order mark, double-quoted cells with {@code ""} escapes and line breaks, CRLF or LF. The first row is the
 * header; header names are matched case-insensitively with spaces and hyphens read as underscores ({@code Unit cost} =
 * {@code unit_cost}). Blank rows are skipped.
 *
 * @param rows data rows; {@link Row#line} is the spreadsheet row number (the header is row 1)
 */
record CsvFile(List<String> headers, List<Row> rows) {
    record Row(int line, Map<String, String> values) {
        /** The trimmed cell, or "" when the column is missing or empty. */
        String get(String column) {
            String v = values.get(column);
            return v == null ? "" : v.strip();
        }
    }

    /** The text that is hashed: without a byte-order mark, with LF line ends and without trailing blank lines. */
    static String normalize(String content) {
        String text = content.startsWith("﻿") ? content.substring(1) : content;
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        return text.stripTrailing();
    }

    static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Parses normalized text; 409 for an empty file, a malformed quote or more than {@code maxRows} data rows. */
    static CsvFile parse(String normalized, int maxRows) {
        require(!normalized.isBlank(), "The file is empty");
        int firstBreak = normalized.indexOf('\n');
        String headerLine = firstBreak < 0 ? normalized : normalized.substring(0, firstBreak);
        char separator = headerLine.indexOf(',') < 0 && headerLine.indexOf(';') >= 0 ? ';' : ',';
        var records = records(normalized, separator);
        var header = records.getFirst().cells();
        var headers = new ArrayList<String>();
        for (String h : header) headers.add(column(h));
        for (int i = 0; i < headers.size(); i++) {
            require(!headers.get(i).isEmpty(), "Header cell " + (i + 1) + " is empty; every column needs a name");
            require(
                    headers.indexOf(headers.get(i)) == i,
                    "Column '" + headers.get(i) + "' appears more than once in the header");
        }
        var rows = new ArrayList<Row>();
        for (int r = 1; r < records.size(); r++) {
            var rec = records.get(r);
            if (rec.cells().stream().allMatch(String::isBlank)) continue;
            require(
                    rec.cells().size() <= headers.size(),
                    "Row " + rec.line() + " has " + rec.cells().size() + " cells but the header has " + headers.size());
            var values = new LinkedHashMap<String, String>();
            for (int c = 0; c < headers.size(); c++)
                values.put(headers.get(c), c < rec.cells().size() ? rec.cells().get(c) : "");
            rows.add(new Row(rec.line(), values));
            require(rows.size() <= maxRows, "A file can contain at most " + maxRows + " data rows; split the file");
        }
        require(!rows.isEmpty(), "The file has a header but no data rows");
        return new CsvFile(headers, rows);
    }

    /** {@code " Unit cost "} → {@code unit_cost}. */
    static String column(String header) {
        return header.strip().toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
    }

    private record Record(int line, List<String> cells) {}

    private static List<Record> records(String text, char separator) {
        var out = new ArrayList<Record>();
        var cells = new ArrayList<String>();
        var cell = new StringBuilder();
        boolean quoted = false;
        boolean cellStarted = false;
        int line = 1;
        int recordLine = 1;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else quoted = false;
                } else {
                    if (ch == '\n') line++;
                    cell.append(ch);
                }
            } else if (ch == '"' && !cellStarted) {
                quoted = true;
                cellStarted = true;
            } else if (ch == separator) {
                cells.add(cell.toString());
                cell.setLength(0);
                cellStarted = false;
            } else if (ch == '\n') {
                cells.add(cell.toString());
                out.add(new Record(recordLine, List.copyOf(cells)));
                cells.clear();
                cell.setLength(0);
                cellStarted = false;
                line++;
                recordLine = line;
            } else {
                cell.append(ch);
                if (!Character.isWhitespace(ch)) cellStarted = true;
            }
        }
        require(!quoted, "Row " + recordLine + " has an opening quote without a closing quote");
        cells.add(cell.toString());
        out.add(new Record(recordLine, List.copyOf(cells)));
        return out;
    }
}
