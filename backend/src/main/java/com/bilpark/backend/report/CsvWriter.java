package com.bilpark.backend.report;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Excel-friendly Turkish CSV: UTF-8 BOM, ';' separator, CRLF, decimal comma. */
public final class CsvWriter {

    static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private CsvWriter() {}

    public static byte[] write(ReportSheet sheet) {
        StringBuilder sb = new StringBuilder("﻿");
        appendLine(sb, sheet.headers().stream().map(h -> (Object) h).toList());
        for (List<Object> row : sheet.rows()) appendLine(sb, row);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendLine(StringBuilder sb, List<Object> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(';');
            sb.append(escape(format(cells.get(i))));
        }
        sb.append("\r\n");
    }

    static String format(Object v) {
        if (v == null) return "";
        if (v instanceof Money m) return String.format(Locale.ROOT, "%.2f", m.value()).replace('.', ',');
        if (v instanceof Double d) return String.format(Locale.ROOT, "%.1f", d).replace('.', ',');
        if (v instanceof LocalDateTime t) return DATE_TIME.format(t);
        if (v instanceof LocalDate d) return DATE.format(d);
        return String.valueOf(v);
    }

    static String escape(String s) {
        if (s.indexOf(';') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
