package com.bilpark.backend.report;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Writes ReportSheets as .xlsx (streaming workbook, window 500, so 100k rows stay cheap). */
public final class XlsxWriter {

    private XlsxWriter() {}

    public static byte[] write(List<ReportSheet> sheets) {
        SXSSFWorkbook wb = new SXSSFWorkbook(500);
        try {
            DataFormat fmt = wb.createDataFormat();
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle header = wb.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle title = wb.createCellStyle();
            title.setFont(bold);
            CellStyle text = wb.createCellStyle();
            CellStyle money = style(wb, null, fmt.getFormat("#,##0.00 \"TL\""));
            CellStyle dec = style(wb, null, fmt.getFormat("0.0"));
            CellStyle integer = style(wb, null, fmt.getFormat("0"));
            CellStyle dateTime = style(wb, null, fmt.getFormat("dd.MM.yyyy HH:mm"));
            CellStyle date = style(wb, null, fmt.getFormat("dd.MM.yyyy"));
            // totals variants (bold)
            CellStyle tText = style(wb, bold, (short) 0);
            CellStyle tMoney = style(wb, bold, fmt.getFormat("#,##0.00 \"TL\""));
            CellStyle tDec = style(wb, bold, fmt.getFormat("0.0"));
            CellStyle tInt = style(wb, bold, fmt.getFormat("0"));

            for (ReportSheet rs : sheets) {
                Sheet sheet = wb.createSheet(rs.name());
                int r = 0;
                if (rs.title() != null) {
                    Cell c = sheet.createRow(r++).createCell(0);
                    c.setCellValue(rs.title());
                    c.setCellStyle(title);
                }
                int headerRow = r;
                int cols = rs.headers().size();
                int[] widths = new int[cols];
                Row hr = sheet.createRow(r++);
                for (int i = 0; i < cols; i++) {
                    Cell c = hr.createCell(i);
                    c.setCellValue(rs.headers().get(i));
                    c.setCellStyle(header);
                    widths[i] = rs.headers().get(i).length() + 2;
                }
                int n = rs.rows().size();
                for (int ri = 0; ri < n; ri++) {
                    boolean total = rs.lastRowIsTotal() && ri == n - 1;
                    Row row = sheet.createRow(r++);
                    List<Object> cells = rs.rows().get(ri);
                    for (int i = 0; i < cols; i++) {
                        Object v = cells.get(i);
                        if (v == null) continue;
                        Cell c = row.createCell(i);
                        int len;
                        if (v instanceof Money m) {
                            c.setCellValue(m.value());
                            c.setCellStyle(total ? tMoney : money);
                            len = 14;
                        } else if (v instanceof Double d) {
                            c.setCellValue(d);
                            c.setCellStyle(total ? tDec : dec);
                            len = 8;
                        } else if (v instanceof Integer || v instanceof Long) {
                            c.setCellValue(((Number) v).doubleValue());
                            c.setCellStyle(total ? tInt : integer);
                            len = 8;
                        } else if (v instanceof LocalDateTime t) {
                            c.setCellValue(t);
                            c.setCellStyle(dateTime);
                            len = 17;
                        } else if (v instanceof LocalDate d) {
                            c.setCellValue(d);
                            c.setCellStyle(date);
                            len = 12;
                        } else {
                            String s = String.valueOf(v);
                            c.setCellValue(s);
                            c.setCellStyle(total ? tText : text);
                            len = Math.min(s.length(), 60) + 2;
                        }
                        if (len > widths[i]) widths[i] = len;
                    }
                }
                for (int i = 0; i < cols; i++) {
                    sheet.setColumnWidth(i, Math.min(Math.max(widths[i], 8), 60) * 256);
                }
                sheet.createFreezePane(0, headerRow + 1);
                int lastData = headerRow + (rs.lastRowIsTotal() ? n - 1 : n);
                if (cols > 0 && lastData > headerRow) {
                    sheet.setAutoFilter(new CellRangeAddress(headerRow, lastData, 0, cols - 1));
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            wb.dispose();
        }
    }

    private static CellStyle style(Workbook wb, Font font, short format) {
        CellStyle s = wb.createCellStyle();
        if (font != null) s.setFont(font);
        if (format != 0) s.setDataFormat(format);
        return s;
    }
}
