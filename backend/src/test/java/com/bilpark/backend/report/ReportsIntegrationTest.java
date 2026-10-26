package com.bilpark.backend.report;

import com.bilpark.backend.license.LicenseService;
import com.bilpark.backend.model.*;
import com.bilpark.backend.repository.*;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportsIntegrationTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ZoneRepository zones;
    @Autowired ShiftRepository shifts;
    @Autowired ParkingRecordRepository records;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired LicenseInfoRepository licenseRepo;
    @Autowired LicenseService licenseService;
    @Autowired AuditLogRepository auditLogs;

    // ---- helpers ------------------------------------------------------------------------------

    private String login(String user, String pass) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Matcher m = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (!m.find()) throw new IllegalStateException("no token in: " + body);
        return "Bearer " + m.group(1);
    }

    private String admin() throws Exception { return login("admin", "test-admin-pass"); }
    private String officerToken() throws Exception { return login("tevfik", "test-officer-pass"); }

    private Zone zone() {
        return zones.findAllByOrderByStreetAscZoneNumberAsc().get(0);
    }

    private User newOfficer(Zone z) {
        User u = new User("t_" + UUID.randomUUID().toString().substring(0, 12), "x", Role.OFFICER, z);
        u.setFullName("Test Memur");
        return users.save(u);
    }

    private Shift shift(User u, Zone z, String start, String end) {
        Shift s = new Shift(u, z, "dev-" + u.getUsername(), LocalDateTime.parse(start));
        s.setEndTime(LocalDateTime.parse(end));
        return shifts.save(s);
    }

    private ParkingRecord record(Zone z, User by, String exit, ParkingStatus st, double fee, PaymentMethod pm) {
        ParkingRecord r = new ParkingRecord("34RW" + ThreadLocalRandom.current().nextInt(100000, 999999),
                z.getStreet(), z, null, null, LocalDateTime.parse(exit).minusHours(1));
        r.setExitTime(LocalDateTime.parse(exit));
        r.setVehicleType(VehicleType.SMALL);
        r.setFee(fee);
        r.setStatus(st);
        r.setPaymentMethod(pm);
        r.setDebtPaid(st != ParkingStatus.RUNAWAY);
        if (by != null) {
            r.setCollectedBy(by);
            r.setCollectedByUsername(by.getUsername());
        }
        return records.save(r);
    }

    private ParkingRecord cancelled(Zone z, User by, String exit, ParkingStatus from, double fee, PaymentMethod pm,
                                    String cancelledAt) {
        ParkingRecord r = record(z, by, exit, ParkingStatus.CANCELLED, fee, pm);
        r.setCancelledFromStatus(from);
        r.setCancelledAt(LocalDateTime.parse(cancelledAt));
        r.setCancelledByUsername("admin");
        r.setCancelReason("test iptal");
        return records.save(r);
    }

    private static XSSFWorkbook open(byte[] bytes) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    private static List<String> sheetNames(XSSFWorkbook wb) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < wb.getNumberOfSheets(); i++) names.add(wb.getSheetName(i));
        return names;
    }

    private static Row rowStartingWith(XSSFSheet sheet, String first) {
        for (Row row : sheet) {
            Cell c = row.getCell(0);
            if (c != null && c.getCellType() == CellType.STRING && first.equals(c.getStringCellValue())) return row;
        }
        return null;
    }

    private static List<String> headers(XSSFSheet sheet, int rowIndex) {
        List<String> out = new ArrayList<>();
        for (Cell c : sheet.getRow(rowIndex)) out.add(c.getStringCellValue());
        return out;
    }

    private byte[] download(String url) throws Exception {
        return mvc.perform(get(url).header("Authorization", admin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    // ---- personnel ----------------------------------------------------------------------------

    @Test
    void personnelReportXlsxAndCsv() throws Exception {
        Zone z = zone();
        User u = newOfficer(z);
        String name = u.getUsername();
        // 2092-03: day 1 two shifts (first on time, 4h + 4h), day 2 one late shift (8h)
        shift(u, z, "2092-03-02T08:03:00", "2092-03-02T12:03:00");
        shift(u, z, "2092-03-02T13:00:00", "2092-03-02T17:00:00");
        shift(u, z, "2092-03-03T08:30:00", "2092-03-03T16:30:00");
        record(z, u, "2092-03-02T10:00:00", ParkingStatus.PAID, 40, PaymentMethod.CASH);
        record(z, u, "2092-03-02T11:00:00", ParkingStatus.PAID, 40, PaymentMethod.CASH);
        record(z, u, "2092-03-02T14:00:00", ParkingStatus.PAID, 20, PaymentMethod.POS);
        record(z, u, "2092-03-02T15:00:00", ParkingStatus.PAID, 0, PaymentMethod.SUBSCRIPTION);
        record(z, u, "2092-03-03T10:00:00", ParkingStatus.RUNAWAY, 30, null);
        cancelled(z, u, "2092-03-03T12:00:00", ParkingStatus.PAID, 55, PaymentMethod.CASH, "2092-03-10T09:00:00");
        // outside the month: must not count
        record(z, u, "2092-04-02T10:00:00", ParkingStatus.PAID, 999, PaymentMethod.CASH);

        byte[] bytes = download("/api/admin/reports/personnel?month=2092-03&format=xlsx");
        try (XSSFWorkbook wb = open(bytes)) {
            assertEquals(List.of("Özet", "Mesai", "Puan Yöntemi"), sheetNames(wb));
            XSSFSheet sum = wb.getSheet("Özet");
            List<String> h = headers(sum, 0);
            assertEquals("Personel", h.get(0));
            assertEquals("Çalışılan saat", h.get(5));
            assertEquals("Tahsilat toplam TL", h.get(9));
            assertEquals("Performans puanı", h.get(16));
            assertEquals("Değerlendirme", h.get(17));
            assertEquals(18, h.size());
            assertNotNull(sum.getPaneInformation(), "frozen header");
            assertTrue(sum.getCTWorksheet().isSetAutoFilter());

            Row row = rowStartingWith(sum, name);
            assertNotNull(row);
            assertEquals("Test Memur", row.getCell(1).getStringCellValue());
            assertEquals(2, row.getCell(3).getNumericCellValue(), 0);   // workDays
            assertEquals(3, row.getCell(4).getNumericCellValue(), 0);   // shifts
            assertEquals(16.0, row.getCell(5).getNumericCellValue(), 1e-9);
            assertEquals(50.0, row.getCell(6).getNumericCellValue(), 1e-9);
            assertEquals(6, row.getCell(7).getNumericCellValue(), 0);   // 4 paid + runaway + cancelled paid
            assertEquals(100.0, row.getCell(9).getNumericCellValue(), 1e-9);
            assertTrue(row.getCell(9).getCellStyle().getDataFormatString().contains("TL"));
            assertEquals(80.0, row.getCell(10).getNumericCellValue(), 1e-9);
            assertEquals(20.0, row.getCell(11).getNumericCellValue(), 1e-9);
            assertEquals(1, row.getCell(12).getNumericCellValue(), 0);  // subscription exits
            assertEquals(1, row.getCell(13).getNumericCellValue(), 0);  // runaways
            assertEquals(1, row.getCell(15).getNumericCellValue(), 0);  // cancellations
            double score = row.getCell(16).getNumericCellValue();
            assertTrue(score >= 0 && score <= 100, "score " + score);
            assertNotNull(rowStartingWith(sum, "Toplam"));

            XSSFSheet mesai = wb.getSheet("Mesai");
            assertEquals("Zamanında mı", headers(mesai, 0).get(6));
            int mine = 0;
            for (Row r : mesai) {
                Cell c = r.getCell(0);
                if (c != null && name.equals(c.getStringCellValue())) {
                    mine++;
                    assertEquals(CellType.NUMERIC, r.getCell(3).getCellType());
                    assertEquals("dd.MM.yyyy HH:mm", r.getCell(3).getCellStyle().getDataFormatString());
                }
            }
            assertEquals(3, mine);
            assertNotNull(wb.getSheet("Puan Yöntemi"));
            assertNotNull(rowStartingWith(wb.getSheet("Puan Yöntemi"), "Gizlilik"));
        }

        // CSV: BOM, ';' separator, decimal comma
        var res = mvc.perform(get("/api/admin/reports/personnel?month=2092-03&format=csv").header("Authorization", admin()))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertTrue(res.getContentType().startsWith("text/csv"));
        assertTrue(res.getHeader("Content-Disposition").contains("personel-raporu-2092-03.csv"));
        byte[] csv = res.getContentAsByteArray();
        assertEquals((byte) 0xEF, csv[0]);
        assertEquals((byte) 0xBB, csv[1]);
        assertEquals((byte) 0xBF, csv[2]);
        String text = new String(csv, 3, csv.length - 3, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("Personel;Ad Soyad;Saha;"));
        assertTrue(text.contains("\r\n"));
        String line = text.lines().filter(l -> l.startsWith(name + ";")).findFirst().orElseThrow();
        assertTrue(line.contains(";16,0;50,0;6;"), line);
        assertTrue(line.contains(";100,00;80,00;20,00;"), line);

        // shifts sheet as CSV
        String shiftsCsv = new String(download("/api/admin/reports/personnel?month=2092-03&format=csv&sheet=shifts"),
                StandardCharsets.UTF_8);
        assertTrue(shiftsCsv.contains("Başlangıç;Bitiş;Süre (dk)"));
        assertEquals(3, shiftsCsv.lines().filter(l -> l.startsWith(name + ";")).count());

        // JSON summary
        mvc.perform(get("/api/admin/reports/personnel/summary?month=2092-03").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username=='" + name + "')].hours").value(16.0))
                .andExpect(jsonPath("$[?(@.username=='" + name + "')].transactions").value(6))
                .andExpect(jsonPath("$[?(@.username=='" + name + "')].collectionTotal").value(100.0))
                .andExpect(jsonPath("$[?(@.username=='" + name + "')].runawayCount").value(1));

        // export is audited
        assertTrue(auditLogs.findAll().stream().anyMatch(a -> a.getAction() == AuditAction.REPORT_EXPORTED
                && a.getSummary() != null && a.getSummary().contains("2092-03")));

        mvc.perform(get("/api/admin/reports/personnel?month=bozuk").header("Authorization", admin()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/admin/reports/personnel?month=2092-03&format=pdf").header("Authorization", admin()))
                .andExpect(status().isBadRequest());
    }

    // ---- runaways -----------------------------------------------------------------------------

    @Test
    void runawayReport() throws Exception {
        Zone z = zone();
        User u = newOfficer(z);
        ParkingRecord a = record(z, u, "2093-05-10T14:00:00", ParkingStatus.RUNAWAY, 45, null);
        ParkingRecord paidDebt = record(z, u, "2093-05-20T14:00:00", ParkingStatus.RUNAWAY, 15, null);
        paidDebt.setDebtPaid(true);
        records.save(paidDebt);
        cancelled(z, u, "2093-05-12T14:00:00", ParkingStatus.RUNAWAY, 99, null, "2093-05-13T10:00:00");
        record(z, u, "2093-06-05T14:00:00", ParkingStatus.RUNAWAY, 77, null); // outside May

        try (XSSFWorkbook wb = open(download("/api/admin/reports/runaways?from=2093-05-01&to=2093-05-31"))) {
            XSSFSheet sheet = wb.getSheet("Kaçak Araçlar");
            assertNotNull(sheet);
            assertTrue(sheet.getRow(0).getCell(0).getStringCellValue().contains("01.05.2093"));
            assertEquals(List.of("Plaka", "Cadde", "Saha", "Giriş tarih-saat", "Çıkış (kaçak işaretleme) tarih-saat",
                    "Tutar (TL)", "Memur", "Borç durumu"), headers(sheet, 1));
            Row r = sheet.getRow(2);
            assertEquals(a.getLicensePlate(), r.getCell(0).getStringCellValue());
            assertEquals(z.getStreet().getDisplayName(), r.getCell(1).getStringCellValue());
            assertEquals(z.getZoneName(), r.getCell(2).getStringCellValue());
            assertEquals(LocalDateTime.parse("2093-05-10T14:00:00"), r.getCell(4).getLocalDateTimeCellValue());
            assertEquals(45.0, r.getCell(5).getNumericCellValue(), 1e-9);
            assertEquals(u.getUsername(), r.getCell(6).getStringCellValue());
            assertEquals("Ödenmedi", r.getCell(7).getStringCellValue());
            assertEquals("Ödendi", sheet.getRow(3).getCell(7).getStringCellValue());
            // cancelled and out-of-range excluded: 2 data rows + totals
            Row total = sheet.getRow(4);
            assertTrue(total.getCell(0).getStringCellValue().contains("2 kayıt"));
            assertEquals(60.0, total.getCell(5).getNumericCellValue(), 1e-9);
            assertNull(sheet.getRow(5));
        }

        // date filter: only the first day range
        String narrow = new String(download("/api/admin/reports/runaways?from=2093-05-10&to=2093-05-10&format=csv"),
                StandardCharsets.UTF_8);
        assertEquals(1, narrow.lines().filter(l -> l.startsWith(a.getLicensePlate() + ";")).count());
        assertEquals(3, narrow.lines().count()); // header + 1 row + totals row
        // unpaidOnly
        String unpaid = new String(download("/api/admin/reports/runaways?from=2093-05-01&to=2093-05-31&format=csv&unpaidOnly=true"),
                StandardCharsets.UTF_8);
        assertTrue(unpaid.contains(a.getLicensePlate()));
        assertFalse(unpaid.contains(paidDebt.getLicensePlate()));
        assertTrue(unpaid.contains("Ödenmedi"));
        assertTrue(unpaid.contains("45,00"));

        mvc.perform(get("/api/admin/reports/runaways?from=2093-05-01").header("Authorization", admin()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/reports/runaways?from=2093-05-01&to=2095-05-31").header("Authorization", admin()))
                .andExpect(status().isBadRequest());
    }

    // ---- period -------------------------------------------------------------------------------

    @Test
    void periodReportSeparatesMethodsExcludesCancelledAndProratesRent() throws Exception {
        Zone z = zone();
        User u = newOfficer(z);
        record(z, u, "2091-05-05T10:00:00", ParkingStatus.PAID, 100, PaymentMethod.CASH);
        record(z, u, "2091-05-06T10:00:00", ParkingStatus.PAID, 50, PaymentMethod.POS);
        record(z, u, "2091-05-06T11:00:00", ParkingStatus.PAID, 0, PaymentMethod.SUBSCRIPTION);
        record(z, u, "2091-05-07T10:00:00", ParkingStatus.PAID, 30, null);
        cancelled(z, u, "2091-05-07T12:00:00", ParkingStatus.PAID, 70, PaymentMethod.CASH, "2091-05-08T09:00:00");
        record(z, u, "2091-05-08T10:00:00", ParkingStatus.RUNAWAY, 25, null);
        subscriptions.save(new Subscription("34SUB" + ThreadLocalRandom.current().nextInt(1000, 9999),
                VehicleType.SMALL, LocalDate.parse("2091-05-10"), null, null));

        mvc.perform(put("/api/admin/settings/rent").header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"year\":2091,\"annualRent\":1200000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.year").value(2091))
                .andExpect(jsonPath("$.annualRent").value(1200000.0))
                .andExpect(jsonPath("$.monthlyShare").value(100000.0));

        mvc.perform(get("/api/admin/reports/period/summary?from=2091-05-01&to=2091-05-31").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cashRevenue").value(100.0))
                .andExpect(jsonPath("$.posRevenue").value(50.0))
                .andExpect(jsonPath("$.otherRevenue").value(30.0))
                .andExpect(jsonPath("$.subscriptionSales").value(2500.0))
                .andExpect(jsonPath("$.totalRevenue").value(2680.0))
                .andExpect(jsonPath("$.subscriptionExitCount").value(1))
                .andExpect(jsonPath("$.rentShare").value(100000.0))
                .andExpect(jsonPath("$.netRemaining").value(2680.0 - 100000.0))
                .andExpect(jsonPath("$.cancelledCount").value(1))
                .andExpect(jsonPath("$.cancelledFees").value(70.0))
                .andExpect(jsonPath("$.unpaidRunawayDebt").value(25.0))
                .andExpect(jsonPath("$.rentMissingYears").isEmpty());

        // half month pro-rated: 1,200,000 / 12 * 15 / 31
        mvc.perform(get("/api/admin/reports/period/summary?from=2091-05-01&to=2091-05-15").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rentShare").value(Math.round(1_200_000d / 12 * 15 / 31 * 100) / 100.0));

        // a year without rent entered
        mvc.perform(get("/api/admin/reports/period/summary?from=2089-02-01&to=2089-02-10").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rentShare").value(0.0))
                .andExpect(jsonPath("$.rentMissingYears[0]").value(2089));

        try (XSSFWorkbook wb = open(download("/api/admin/reports/period?from=2091-05-01&to=2091-05-31"))) {
            assertEquals(List.of("Özet", "Saha bazında", "Günlük"), sheetNames(wb));
            XSSFSheet sum = wb.getSheet("Özet");
            assertEquals(100.0, rowStartingWith(sum, "Nakit ciro").getCell(1).getNumericCellValue(), 1e-9);
            assertEquals(50.0, rowStartingWith(sum, "POS ciro").getCell(1).getNumericCellValue(), 1e-9);
            assertEquals(2500.0, rowStartingWith(sum, "Abonelik satışı").getCell(1).getNumericCellValue(), 1e-9);
            double total = rowStartingWith(sum, "Toplam ciro").getCell(1).getNumericCellValue();
            double rent = rowStartingWith(sum, "Kira payı").getCell(1).getNumericCellValue();
            double net = rowStartingWith(sum, "Net kalan").getCell(1).getNumericCellValue();
            assertEquals(2680.0, total, 1e-9);
            assertEquals(100000.0, rent, 1e-9);
            assertEquals(total - rent, net, 1e-9);
            assertEquals(100000.0, rowStartingWith(sum, "Aylık kira payı 2091-05").getCell(1).getNumericCellValue(), 1e-9);
            assertEquals(1, rowStartingWith(sum, "İptal edilen kayıt sayısı").getCell(1).getNumericCellValue(), 0);

            XSSFSheet zonesSheet = wb.getSheet("Saha bazında");
            Row zr = rowStartingWith(zonesSheet, z.getStreet().getDisplayName());
            assertEquals(z.getZoneName(), zr.getCell(1).getStringCellValue());
            assertEquals(100.0, zr.getCell(2).getNumericCellValue(), 1e-9);
            assertEquals(50.0, zr.getCell(3).getNumericCellValue(), 1e-9);
            assertEquals(30.0, zr.getCell(4).getNumericCellValue(), 1e-9);
            assertEquals(180.0, zr.getCell(5).getNumericCellValue(), 1e-9);
            assertEquals(5, zr.getCell(6).getNumericCellValue(), 0);
            assertEquals(1, zr.getCell(7).getNumericCellValue(), 0);

            XSSFSheet daily = wb.getSheet("Günlük");
            assertEquals(32, daily.getPhysicalNumberOfRows()); // header + 31 days
            Row d5 = daily.getRow(5); // row 1 = May 1st
            assertEquals(LocalDate.parse("2091-05-05"), d5.getCell(0).getLocalDateTimeCellValue().toLocalDate());
            assertEquals(100.0, d5.getCell(1).getNumericCellValue(), 1e-9);
        }

        String csv = new String(download("/api/admin/reports/period?from=2091-05-01&to=2091-05-31&format=csv"),
                StandardCharsets.UTF_8);
        assertTrue(csv.contains("Toplam ciro;2680,00"));
        String daily = new String(download("/api/admin/reports/period?from=2091-05-01&to=2091-05-31&format=csv&sheet=daily"),
                StandardCharsets.UTF_8);
        assertTrue(daily.contains("05.05.2091;100,00;0,00;0,00;100,00"));
        mvc.perform(get("/api/admin/reports/period?from=2091-05-01&to=2091-05-31&format=csv&sheet=nope")
                .header("Authorization", admin())).andExpect(status().isBadRequest());
    }

    // ---- records export -----------------------------------------------------------------------

    @Test
    void recordsExportIncludesCancelledAndOnlyEntriesInRange() throws Exception {
        Zone z = zone();
        User u = newOfficer(z);
        ParkingRecord paid = record(z, u, "2094-02-03T10:00:00", ParkingStatus.PAID, 40, PaymentMethod.CASH);
        ParkingRecord canc = cancelled(z, u, "2094-02-04T10:00:00", ParkingStatus.PAID, 20, PaymentMethod.POS,
                "2094-02-05T10:00:00");
        record(z, u, "2094-03-20T10:00:00", ParkingStatus.PAID, 10, PaymentMethod.CASH);

        try (XSSFWorkbook wb = open(download("/api/admin/reports/records?from=2094-02-01&to=2094-02-28"))) {
            XSSFSheet sheet = wb.getSheet("Kayıtlar");
            assertEquals("ID", headers(sheet, 0).get(0));
            assertEquals("İptal gerekçesi", headers(sheet, 0).get(15));
            assertEquals(3, sheet.getPhysicalNumberOfRows()); // header + 2
            Row r1 = sheet.getRow(1);
            assertEquals(paid.getId(), (long) r1.getCell(0).getNumericCellValue());
            assertEquals("Ödendi", r1.getCell(10).getStringCellValue());
            assertEquals(60.0, r1.getCell(7).getNumericCellValue(), 1e-9);
            assertEquals(40.0, r1.getCell(9).getNumericCellValue(), 1e-9);
            Row r2 = sheet.getRow(2);
            assertEquals(canc.getId(), (long) r2.getCell(0).getNumericCellValue());
            assertEquals("İptal", r2.getCell(10).getStringCellValue());
            assertEquals("test iptal", r2.getCell(15).getStringCellValue());
        }
    }

    // ---- settings -----------------------------------------------------------------------------

    @Test
    void settingsWeightsAndRent() throws Exception {
        String adminToken = admin();
        try {
            mvc.perform(get("/api/admin/settings/performance-weights").header("Authorization", adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.hours").value(20))
                    .andExpect(jsonPath("$.startTime").value("08:00"));
            mvc.perform(put("/api/admin/settings/performance-weights").header("Authorization", adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"hours\":25,\"punctuality\":15,\"throughput\":20,\"collection\":15,"
                                    + "\"runaway\":15,\"cancellation\":10,\"startTime\":\"08:30\",\"graceMinutes\":10}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.hours").value(25))
                    .andExpect(jsonPath("$.graceMinutes").value(10));
            mvc.perform(get("/api/admin/settings/performance-weights").header("Authorization", adminToken))
                    .andExpect(jsonPath("$.startTime").value("08:30"))
                    .andExpect(jsonPath("$.collection").value(15));
            // sum != 100
            mvc.perform(put("/api/admin/settings/performance-weights").header("Authorization", adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"hours\":50,\"punctuality\":15,\"throughput\":20,\"collection\":15,"
                                    + "\"runaway\":15,\"cancellation\":10,\"startTime\":\"08:00\",\"graceMinutes\":5}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_SETTINGS"));
        } finally {
            mvc.perform(put("/api/admin/settings/performance-weights").header("Authorization", adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"hours\":20,\"punctuality\":15,\"throughput\":20,\"collection\":20,"
                                    + "\"runaway\":15,\"cancellation\":10,\"startTime\":\"08:00\",\"graceMinutes\":5}"))
                    .andExpect(status().isOk());
        }

        mvc.perform(put("/api/admin/settings/rent").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"year\":2091,\"annualRent\":600000}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.monthlyShare").value(50000.0));
        mvc.perform(get("/api/admin/settings/rent").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.years[?(@.year==2091)].annualRent").value(600000.0))
                .andExpect(jsonPath("$.years[?(@.year==2091)].monthlyShare").value(50000.0));
        mvc.perform(put("/api/admin/settings/rent").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"year\":1999,\"annualRent\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SETTINGS"));
        mvc.perform(put("/api/admin/settings/rent").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"year\":2091}"))
                .andExpect(status().isBadRequest());
    }

    // ---- access -------------------------------------------------------------------------------

    @Test
    void officerForbiddenAndAnonymousUnauthorized() throws Exception {
        String officer = officerToken();
        String[] urls = {
                "/api/admin/reports/personnel?month=2092-03",
                "/api/admin/reports/personnel/summary?month=2092-03",
                "/api/admin/reports/runaways?from=2093-05-01&to=2093-05-31",
                "/api/admin/reports/period?from=2091-05-01&to=2091-05-31",
                "/api/admin/reports/period/summary?from=2091-05-01&to=2091-05-31",
                "/api/admin/reports/records?from=2094-02-01&to=2094-02-28",
                "/api/admin/settings/performance-weights",
                "/api/admin/settings/rent"};
        for (String url : urls) {
            mvc.perform(get(url).header("Authorization", officer)).andExpect(status().isForbidden());
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
        }
        mvc.perform(put("/api/admin/settings/rent").header("Authorization", officer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"year\":2091,\"annualRent\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void reportsStayAvailableInReadOnlyMode() throws Exception {
        String adminToken = admin();
        LicenseInfo before = licenseRepo.findById(LicenseInfo.SINGLETON_ID).orElseThrow();
        LocalDate oldExpires = before.getExpiresOn();
        int oldGrace = before.getGraceDays();
        try {
            before.setExpiresOn(LocalDate.now().minusDays(30));
            before.setGraceDays(5);
            licenseRepo.save(before);
            licenseService.evictCache();
            assertTrue(licenseService.isReadOnly());

            mvc.perform(get("/api/admin/reports/period?from=2091-05-01&to=2091-05-31")
                            .header("Authorization", adminToken))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/admin/reports/personnel/summary?month=2092-03").header("Authorization", adminToken))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/admin/settings/rent").header("Authorization", adminToken))
                    .andExpect(status().isOk());
        } finally {
            LicenseInfo restore = licenseRepo.findById(LicenseInfo.SINGLETON_ID).orElseThrow();
            restore.setExpiresOn(oldExpires);
            restore.setGraceDays(oldGrace);
            licenseRepo.save(restore);
            licenseService.evictCache();
        }
    }
}
