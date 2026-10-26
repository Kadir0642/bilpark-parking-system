package com.bilpark.backend.controller;

import com.bilpark.backend.audit.AuditQueryService;
import com.bilpark.backend.audit.AuditQueryService.Filter;
import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.model.AuditLog;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Audit log for the admin (ADMIN-only via SecurityConfig: /api/admin/**). */
@RestController
@RequestMapping("/api/admin/audit")
public class AuditController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    private static final String[] HEADERS = {"Zaman", "Kullanıcı", "Rol", "Cihaz", "IP", "İşlem", "Varlık",
            "Varlık No", "Özet"};

    private final AuditQueryService queries;

    public AuditController(AuditQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to,
                                    @RequestParam(required = false) String action,
                                    @RequestParam(required = false) String actor,
                                    @RequestParam(required = false) String actorRole,
                                    @RequestParam(required = false) String entityType,
                                    @RequestParam(required = false) String entityId,
                                    @RequestParam(required = false) String q,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size) {
        return queries.list(new Filter(from, to, action, actor, actorRole, entityType, entityId, q), page, size);
    }

    @GetMapping("/actions")
    public List<Map<String, String>> actions() {
        return queries.actions();
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(required = false) String action,
                                         @RequestParam(required = false) String actor,
                                         @RequestParam(required = false) String actorRole,
                                         @RequestParam(required = false) String entityType,
                                         @RequestParam(required = false) String entityId,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(defaultValue = "xlsx") String format) throws IOException {
        String fmt = format == null ? "xlsx" : format.trim().toLowerCase(Locale.ROOT);
        if (!fmt.equals("csv") && !fmt.equals("xlsx")) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, "Geçersiz biçim: xlsx veya csv olmalıdır.");
        }
        List<AuditLog> rows = queries.forExport(new Filter(from, to, action, actor, actorRole, entityType, entityId, q));
        if (fmt.equals("csv")) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"denetim-kaydi.csv\"")
                    .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                    .body(csv(rows));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"denetim-kaydi.xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(xlsx(rows));
    }

    private static String[] cells(AuditLog a) {
        return new String[]{
                a.getCreatedAt() != null ? a.getCreatedAt().format(TS) : "",
                nz(a.getActorUsername()), nz(a.getActorRole()), nz(a.getDeviceId()), nz(a.getIpAddress()),
                nz(AuditQueryService.labelOf(a.getAction())), nz(a.getEntityType()), nz(a.getEntityId()),
                nz(a.getSummary())};
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static byte[] csv(List<AuditLog> rows) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(String.join(";", HEADERS)).append("\r\n");
        for (AuditLog a : rows) {
            String[] c = cells(a);
            for (int i = 0; i < c.length; i++) {
                if (i > 0) sb.append(';');
                sb.append(csvField(c[i]));
            }
            sb.append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String csvField(String v) {
        if (v.contains(";") || v.contains("\"") || v.contains("\r") || v.contains("\n")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private static byte[] xlsx(List<AuditLog> rows) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Denetim Kaydı");
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle head = wb.createCellStyle();
            head.setFont(bold);
            Row h = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = h.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(head);
            }
            int r = 1;
            for (AuditLog a : rows) {
                Row row = sheet.createRow(r++);
                String[] c = cells(a);
                for (int i = 0; i < c.length; i++) row.createCell(i).setCellValue(c[i]);
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, r - 1), 0, HEADERS.length - 1));
            int[] widths = {20, 18, 12, 20, 16, 28, 16, 12, 80};
            for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
            wb.write(out);
            return out.toByteArray();
        }
    }
}
