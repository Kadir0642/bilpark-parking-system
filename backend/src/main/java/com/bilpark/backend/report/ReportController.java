package com.bilpark.backend.report;

import com.bilpark.backend.audit.AuditService;
import com.bilpark.backend.model.AuditAction;
import com.bilpark.backend.report.ReportSupport.Range;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Admin reports (Excel / CSV). ADMIN only via SecurityConfig (/api/admin/**). Every download is audited. */
@RestController
@RequestMapping("/api/admin/reports")
public class ReportController {

    static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    static final MediaType CSV = MediaType.parseMediaType("text/csv; charset=UTF-8");

    private final PersonnelReportService personnel;
    private final PeriodReportService period;
    private final RecordsReportService records;
    private final AuditService auditService;

    public ReportController(PersonnelReportService personnel, PeriodReportService period,
                            RecordsReportService records, AuditService auditService) {
        this.personnel = personnel;
        this.period = period;
        this.records = records;
        this.auditService = auditService;
    }

    // ---- personnel -------------------------------------------------------------------------------

    @GetMapping("/personnel")
    public ResponseEntity<byte[]> personnelReport(@RequestParam(required = false) String month,
                                                  @RequestParam(defaultValue = "xlsx") String format,
                                                  @RequestParam(required = false) String sheet) {
        YearMonth m = ReportSupport.month(month);
        boolean csv = isCsv(format);
        PersonnelReportService.Report rep = personnel.compute(m);
        List<ReportSheet> sheets = personnel.toSheets(rep);
        return download("personel-raporu-" + m, "personnel", "Personel raporu " + m, Map.of("month", m.toString()),
                sheets, csv, sheet, "summary");
    }

    @GetMapping("/personnel/summary")
    public List<Map<String, Object>> personnelSummary(@RequestParam(required = false) String month) {
        return personnel.compute(ReportSupport.month(month)).rows().stream().map(PersonnelReportService::toMap).toList();
    }

    // ---- runaways --------------------------------------------------------------------------------

    @GetMapping("/runaways")
    public ResponseEntity<byte[]> runawayReport(@RequestParam(required = false) String from,
                                                @RequestParam(required = false) String to,
                                                @RequestParam(defaultValue = "xlsx") String format,
                                                @RequestParam(required = false) String street,
                                                @RequestParam(defaultValue = "false") boolean unpaidOnly) {
        boolean csv = isCsv(format);
        Range range = ReportSupport.range(from, to);
        ReportSheet sheet = records.runaways(range, street, unpaidOnly);
        Map<String, Object> params = params(range);
        if (street != null && !street.isBlank()) params.put("street", street);
        params.put("unpaidOnly", unpaidOnly);
        return download("kacak-raporu-" + range.from() + "_" + range.to(), "runaways",
                "Kaçak araç raporu " + range.display(), params, List.of(sheet), csv, null, "runaways");
    }

    // ---- period ----------------------------------------------------------------------------------

    @GetMapping("/period")
    public ResponseEntity<byte[]> periodReport(@RequestParam(required = false) String from,
                                               @RequestParam(required = false) String to,
                                               @RequestParam(defaultValue = "xlsx") String format,
                                               @RequestParam(required = false) String sheet) {
        boolean csv = isCsv(format);
        Range range = ReportSupport.range(from, to);
        List<ReportSheet> sheets = PeriodReportService.toSheets(period.compute(range));
        return download("donem-raporu-" + range.from() + "_" + range.to(), "period",
                "Dönem raporu " + range.display(), params(range), sheets, csv, sheet, "summary");
    }

    @GetMapping("/period/summary")
    public Map<String, Object> periodSummary(@RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to) {
        return PeriodReportService.toMap(period.compute(ReportSupport.range(from, to)));
    }

    // ---- records ---------------------------------------------------------------------------------

    @GetMapping("/records")
    public ResponseEntity<byte[]> recordsExport(@RequestParam(required = false) String from,
                                                @RequestParam(required = false) String to,
                                                @RequestParam(defaultValue = "xlsx") String format) {
        boolean csv = isCsv(format);
        Range range = ReportSupport.range(from, to);
        ReportSheet sheet = records.records(range);
        return download("kayitlar-" + range.from() + "_" + range.to(), "records",
                "Kayıt dökümü " + range.display(), params(range), List.of(sheet), csv, null, "records");
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static Map<String, Object> params(Range range) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("from", range.from().toString());
        p.put("to", range.to().toString());
        return p;
    }

    private static boolean isCsv(String format) {
        String f = format == null ? "xlsx" : format.trim().toLowerCase(Locale.ROOT);
        if (f.equals("csv")) return true;
        if (f.equals("xlsx")) return false;
        throw ReportSupport.bad("Biçim xlsx veya csv olmalı.");
    }

    private ResponseEntity<byte[]> download(String baseName, String reportKey, String summary,
                                            Map<String, Object> params, List<ReportSheet> sheets, boolean csv,
                                            String sheetParam, String defaultSheet) {
        byte[] body;
        if (csv) {
            String key = sheetParam == null || sheetParam.isBlank() ? defaultSheet : sheetParam.trim();
            ReportSheet chosen = sheets.stream().filter(s -> s.key().equals(key)).findFirst()
                    .orElseThrow(() -> ReportSupport.bad("Geçersiz sheet değeri: " + key));
            body = CsvWriter.write(chosen);
        } else {
            body = XlsxWriter.write(sheets);
        }
        String ext = csv ? "csv" : "xlsx";
        Map<String, Object> audit = new LinkedHashMap<>(params);
        audit.put("report", reportKey);
        audit.put("format", ext);
        auditService.log(AuditAction.REPORT_EXPORTED, "Report", reportKey,
                summary + " indirildi (" + ext + ")", null, audit);
        return ResponseEntity.ok()
                .contentType(csv ? CSV : XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(baseName + "." + ext).build().toString())
                .body(body);
    }
}
