package com.bilpark.backend.report;

import java.util.ArrayList;
import java.util.List;

/**
 * Format-neutral table. Cell values: String, Integer/Long, Double (1 decimal), Money (TL),
 * LocalDateTime, LocalDate or null.
 */
public final class ReportSheet {

    private final String key;      // value of the sheet= parameter (CSV)
    private final String name;     // Excel sheet name (Turkish, max 31 chars)
    private String title;          // optional bold line above the header (Excel only)
    private final List<String> headers;
    private final List<List<Object>> rows = new ArrayList<>();
    private boolean lastRowIsTotal;

    public ReportSheet(String key, String name, List<String> headers) {
        this.key = key;
        this.name = name;
        this.headers = headers;
    }

    public ReportSheet title(String t) {
        this.title = t;
        return this;
    }

    public ReportSheet add(Object... cells) {
        List<Object> row = new ArrayList<>(headers.size());
        for (int i = 0; i < headers.size(); i++) row.add(i < cells.length ? cells[i] : null);
        rows.add(row);
        return this;
    }

    /** Adds the last row as a totals row (bold, outside the auto-filter). */
    public ReportSheet addTotal(Object... cells) {
        add(cells);
        lastRowIsTotal = true;
        return this;
    }

    public String key() { return key; }
    public String name() { return name; }
    public String title() { return title; }
    public List<String> headers() { return headers; }
    public List<List<Object>> rows() { return rows; }
    public boolean lastRowIsTotal() { return lastRowIsTotal; }
}
