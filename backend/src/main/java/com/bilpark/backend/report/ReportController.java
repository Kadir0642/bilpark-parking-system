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

/** Admin reports (Excel / CSV). ADMIN only via SecurityConfig (/api/admin/**). Every download is audited.
 *  Parameters are validated first; the computation then runs through {@link ReportGate}. */
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
    private final ReportGate gate; // one report at a time (heap), else 503 REPORT_BUSY

    public ReportController(PersonnelReportService personnel, PeriodReportService period,
                            RecordsReportService records, AuditService auditService, ReportGate gate) {
        this.personnel = personnel;
        this.period = period;
        this.records = records;
        this.auditService = auditService;
        this.gate = gate;
    }

    // ---- personnel -------------------------------------------------------------------------------

    @GetMapping("/personnel")
    public ResponseEntity<byte[]> personnelReport(@RequestParam(required = false) String month,
                                                  @RequestParam(defaultValue = "xlsx") String format,
                                                  @RequestParam(required = false) String sheet) {
        YearMonth m = ReportSupport.month(month);
        boolean csv = isCsv(format);
        return gate.run(() -> {
            PersonnelReportService.Report rep = personnel.compute(m);
            List<ReportSheet> sheets = personnel.toSheets(rep);
            return download("personel-raporu-" + m, "personnel", "Personel raporu " + m,
                    Map.of("month", m.toString()), sheets, csv, sheet, "summary");
        });
    }

    @GetMapping("/personnel/summary")
    public List<Map<String, Object>> personnelSummary(@RequestParam(required = false) String month) {
        YearMonth m = ReportSupport.month(month);
        return gate.run(() -> personnel.compute(m).rows().stream().map(PersonnelReportService::toMap).toList());
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
        Map<String, Object> params = params(range);
        if (street != null && !street.isBlank()) params.put("street", street);
        params.put("unpaidOnly", unpaidOnly);
        return gate.run(() -> download("kacak-raporu-" + range.from() + "_" + range.to(), "runaways",
                "Kaçak araç raporu " + range.display(), params,
                List.of(records.runaways(range, street, unpaidOnly)), csv, null, "runaways"));
    }

    // ---- period ----------------------------------------------------------------------------------

    @GetMapping("/period")
    public ResponseEntity<byte[]> periodReport(@RequestParam(required = false) String from,
                                               @RequestParam(required = false) String to,
                                               @RequestParam(defaultValue = "xlsx") String format,
                                               @RequestParam(required = false) String sheet) {
        boolean csv = isCsv(format);
        Range range = ReportSupport.range(from, to);
        return gate.run(() -> download("donem-raporu-" + range.from() + "_" + range.to(), "period",
                "Dönem raporu " + range.display(), params(range),
                PeriodReportService.toSheets(period.compute(range)), csv, sheet, "summary"));
    }

    @GetMapping("/period/summary")
    public Map<String, Object> periodSummary(@RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to) {
        Range range = ReportSupport.range(from, to);
        return gate.run(() -> PeriodReportService.toMap(period.compute(range)));
    }

    // ---- records ---------------------------------------------------------------------------------

    @GetMapping("/records")
    public ResponseEntity<byte[]> recordsExport(@RequestParam(required = false) String from,
                                                @RequestParam(required = false) String to,
                                                @RequestParam(defaultValue = "xlsx") String format) {
        boolean csv = isCsv(format);
        Range range = ReportSupport.range(from, to);
        return gate.run(() -> download("kayitlar-" + range.from() + "_" + range.to(), "records",
                "Kayıt dökümü " + range.display(), params(range), List.of(records.records(range)), csv, null,
                "records"));
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
