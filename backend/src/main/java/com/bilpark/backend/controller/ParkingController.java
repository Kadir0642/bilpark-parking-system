package com.bilpark.backend.controller;

import com.bilpark.backend.model.ParkSpot;
import com.bilpark.backend.model.ParkingRecord;
import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.DeviceIds;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.model.PaymentMethod;
import com.bilpark.backend.service.ParkingService;
import com.bilpark.backend.service.ShiftService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

/**
 * REST Controller for all parking operations.
 * Business logic is delegated to ParkingService.
 * Error handling is managed by GlobalExceptionHandler.
 *
 * Base URL: /api/parking
 */
@RestController
@CrossOrigin("*") // TODO: Restrict to known origins after authentication is implemented
@RequestMapping("/api/parking")
@org.springframework.validation.annotation.Validated
public class ParkingController {

    private final ParkingService parkingService;
    private final com.bilpark.backend.repository.UserRepository userRepository;
    private final com.bilpark.backend.repository.ParkingRecordRepository parkingRecordRepository;
    private final ShiftService shiftService;

    public ParkingController(ParkingService parkingService,
                             com.bilpark.backend.repository.UserRepository userRepository,
                             com.bilpark.backend.repository.ParkingRecordRepository parkingRecordRepository,
                             ShiftService shiftService) {
        this.shiftService = shiftService;
        this.parkingService = parkingService;
        this.userRepository = userRepository;
        this.parkingRecordRepository = parkingRecordRepository;
    }

    // Token sahibi kullanıcıyı bulur (bölge kısıtı için)
    private com.bilpark.backend.model.User currentUser(java.security.Principal principal) {
        if (principal == null) {
            throw new org.springframework.security.access.AccessDeniedException("Oturum bulunamadı.");
        }
        return userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("Kullanıcı bulunamadı."));
    }

    // GET /api/parking/bi/shifts - open shifts (one per officer per device)
    @GetMapping("/bi/shifts")
    public ResponseEntity<?> getActiveShifts() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.util.List<java.util.Map<String, Object>> result = new java.util.ArrayList<>();

        for (com.bilpark.backend.model.Shift sh : shiftService.listOpenShifts()) {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("shiftId", sh.getId());
            map.put("username", sh.getUsername());
            if (sh.getZone() != null) {
                map.put("zoneName", sh.getZone().getZoneName());
                map.put("street", sh.getZone().getStreet().name());
            } else {
                map.put("zoneName", "Bölge Yok");
                map.put("street", "Belirsiz");
            }
            map.put("deviceId", sh.getDeviceId());
            map.put("startTime", sh.getStartTime());
            map.put("lastLogin", sh.getStartTime()); // panel uyumu
            map.put("activeMinutes", java.time.Duration.between(sh.getStartTime(), now).toMinutes());
            Double income = parkingRecordRepository.getIncomeByCollectorAndDevice(
                    sh.getUser(), sh.getDeviceId(), sh.getStartTime(), now);
            map.put("revenue", income != null ? income : 0.0);
            result.add(map);
        }
        return ResponseEntity.ok(result);
    }

    // GET /api/parking/spots — List all currently active vehicles
    @GetMapping("/spots")
    public List<ParkSpot> getAllSpots() {
        return parkingService.getAllSpots();
    }

    // POST /api/parking/check-in?plate=34ABC123&street=TEVFIK_BEY&type=SMALL&side=LEFT&zoneId=1
    @PostMapping("/check-in")
    public ResponseEntity<ParkSpot> checkIn(
            @RequestParam @jakarta.validation.constraints.NotBlank(message = "Plaka boş olamaz.") String plate,
            @RequestParam StreetLocation street,
            @RequestParam(defaultValue = "SMALL") String type,
            @RequestParam(defaultValue = "LEFT") String side,
            @RequestParam(required = false) Long zoneId,
            java.security.Principal principal) {
        return ResponseEntity.ok(parkingService.checkInVehicle(plate, type, street, side, zoneId, currentUser(principal)));
    }

    // paymentMethod: CASH (default) | POS | SUBSCRIPTION; ONLINE is reserved
    private PaymentMethod parsePaymentMethod(String raw) {
        if (raw == null || raw.isBlank()) return PaymentMethod.CASH;
        try {
            PaymentMethod m = PaymentMethod.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
            if (m == PaymentMethod.ONLINE) {
                throw ApiException.badRequest(ErrorCodes.INVALID_PAYMENT_METHOD, "Online ödeme henüz kullanılmıyor.");
            }
            return m;
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(ErrorCodes.INVALID_PAYMENT_METHOD,
                    "Geçersiz ödeme yöntemi: " + raw + ". Geçerli değerler: CASH, POS, SUBSCRIPTION.");
        }
    }

    // POST /api/parking/check-out?plate=34ABC123&paymentMethod=POS
    @PostMapping("/check-out")
    public ResponseEntity<ParkingRecord> checkOut(
            @RequestParam @jakarta.validation.constraints.NotBlank(message = "Plaka boş olamaz.") String plate,
            @RequestParam(required = false) String paymentMethod,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceHeader,
            java.security.Principal principal) {
        return ResponseEntity.ok(parkingService.checkOutVehicle(
                plate, parsePaymentMethod(paymentMethod), currentUser(principal), DeviceIds.sanitize(deviceHeader)));
    }

    // POST /api/parking/runaway?plate=34ABC123
    @PostMapping("/runaway")
    public ResponseEntity<Map<String, Object>> markAsRunaway(@RequestParam String plate,
                                                             @RequestHeader(value = "X-Device-Id", required = false) String deviceHeader,
                                                             java.security.Principal principal) {
        ParkingService.RunawayResult result = parkingService.markAsRunawayDetailed(
                plate, currentUser(principal), DeviceIds.sanitize(deviceHeader));
        ParkingRecord record = result.record();
        return ResponseEntity.ok(Map.of(
                "message", "Araç kaçak olarak işaretlendi ve kara listeye eklendi.",
                "debt", record.getFee(),
                "record", record,
                "segments", com.bilpark.backend.fee.DaySegment.toMaps(result.segments())
        ));
    }

    // GET /api/parking/income — Total revenue (all time)
    @GetMapping("/income")
    public Double getIncome() {
        return parkingService.getTotalIncome();
    }

    // GET /api/parking/income/daily
    @GetMapping("/income/daily")
    public Double getDailyIncome() {
        return parkingService.getDailyIncome();
    }

    // GET /api/parking/income/weekly
    @GetMapping("/income/weekly")
    public Double getWeeklyIncome() {
        return parkingService.getWeeklyIncome();
    }

    // GET /api/parking/income/monthly
    @GetMapping("/income/monthly")
    public Double getMonthlyIncome() {
        return parkingService.getMonthlyIncome();
    }

    // GET /api/parking/income/yearly
    @GetMapping("/income/yearly")
    public Double getYearlyIncome() {
        return parkingService.getYearlyIncome();
    }

    // GET /api/parking/filter?street=TEVFIK_BEY — Active vehicles by street
    @GetMapping("/filter")
    public List<ParkSpot> filterSpots(@RequestParam StreetLocation street) {
        return parkingService.getSpotsByStreet(street);
    }

    // GET /api/parking/history?street=TEVFIK_BEY — Last 50 records for a street
    @GetMapping("/history")
    public List<ParkingRecord> getHistory(@RequestParam StreetLocation street) {
        return parkingService.getHistoryByLocation(street);
    }

    // GET /api/parking/history/search?plate=34ABC
    @GetMapping("/history/search")
    public ResponseEntity<?> searchHistory(@RequestParam String plate) {
        List<ParkingRecord> results = parkingService.searchHistoryByPlate(plate);
        if (results.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "Bu plaka için kayıt bulunamadı.", "code", ErrorCodes.NOT_FOUND));
        }
        return ResponseEntity.ok(results);
    }

    // GET /api/parking/debt?plate=34ABC123 — Query outstanding debt for citizen portal
    @GetMapping("/debt")
    public ResponseEntity<Map<String, Object>> getDebt(@RequestParam String plate) {
        return ResponseEntity.ok(parkingService.getDebtByPlate(plate));
    }

    // GET /api/parking/fee-preview?plate=34ABC123 — officer: live fee of a parked vehicle in own zone
    @GetMapping("/fee-preview")
    public ResponseEntity<Map<String, Object>> feePreview(@RequestParam String plate,
                                                          java.security.Principal principal) {
        return ResponseEntity.ok(parkingService.getFeePreview(plate, currentUser(principal)));
    }

    // POST /api/parking/pay?plate=34ABC123 — Citizen self-checkout
    @PostMapping("/pay")
    public ResponseEntity<Map<String, Object>> payAndCheckOut(@RequestParam String plate,
                                                              @RequestHeader(value = "X-Device-Id", required = false) String deviceHeader,
                                                              java.security.Principal principal) {
        ParkingRecord record = parkingService.processPaymentAndCheckout(plate, currentUser(principal), DeviceIds.sanitize(deviceHeader));
        return ResponseEntity.ok(Map.of(
                "message", "Ödeme alındı. Araç çıkışı yapıldı.",
                "paidAmount", record.getFee(),
                "record", record
        ));
    }

    // --- BI Endpoints for Admin Dashboard ---
    
    // GET /api/parking/bi/income-summary?start=2026-01-01&end=2026-12-31
    @GetMapping("/bi/income-summary")
    public Map<String, Double> getIncomeSummary(
            @RequestParam(required = false) String start, 
            @RequestParam(required = false) String end) {
        
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.time.LocalDateTime customStart = null;
        java.time.LocalDateTime customEnd = null;
        
        if (start != null && end != null) {
            customStart = java.time.LocalDate.parse(start).atStartOfDay();
            customEnd = java.time.LocalDate.parse(end).plusDays(1).atStartOfDay();
        }

        // Daily
        java.time.LocalDateTime startOfDay = now.toLocalDate().atStartOfDay();
        java.time.LocalDateTime endOfDay = startOfDay.plusDays(1);
        if (customStart != null) {
            if (startOfDay.isBefore(customStart)) startOfDay = customStart;
            if (endOfDay.isAfter(customEnd)) endOfDay = customEnd;
        }
        Double daily = (startOfDay.isBefore(endOfDay)) ? parkingService.getIncomeByDateRange(startOfDay, endOfDay) : 0.0;

        // Weekly
        java.time.LocalDateTime startOfWeek = now.minusDays(7);
        java.time.LocalDateTime endOfWeek = now;
        if (customStart != null) {
            if (startOfWeek.isBefore(customStart)) startOfWeek = customStart;
            if (endOfWeek.isAfter(customEnd)) endOfWeek = customEnd;
        }
        Double weekly = (startOfWeek.isBefore(endOfWeek)) ? parkingService.getIncomeByDateRange(startOfWeek, endOfWeek) : 0.0;

        // Monthly
        java.time.LocalDateTime startOfMonth = java.time.LocalDate.now().withDayOfMonth(1).atStartOfDay();
        java.time.LocalDateTime endOfMonth = now;
        if (customStart != null) {
            if (startOfMonth.isBefore(customStart)) startOfMonth = customStart;
            if (endOfMonth.isAfter(customEnd)) endOfMonth = customEnd;
        }
        Double monthly = (startOfMonth.isBefore(endOfMonth)) ? parkingService.getIncomeByDateRange(startOfMonth, endOfMonth) : 0.0;

        // Yearly (Total in period if selected)
        java.time.LocalDateTime startOfYear = java.time.LocalDate.now().withDayOfYear(1).atStartOfDay();
        java.time.LocalDateTime endOfYear = now;
        if (customStart != null) {
            startOfYear = customStart;
            endOfYear = customEnd;
        }
        Double yearly = (startOfYear.isBefore(endOfYear)) ? parkingService.getIncomeByDateRange(startOfYear, endOfYear) : 0.0;

        return Map.of(
                "daily", daily != null ? daily : 0.0,
                "weekly", weekly != null ? weekly : 0.0,
                "monthly", monthly != null ? monthly : 0.0,
                "yearly", yearly != null ? yearly : 0.0
        );
    }

    // GET /api/parking/bi/income-by-type
    @GetMapping("/bi/income-by-type")
    public Map<String, Double> getIncomeByType() {
        return parkingService.calculateIncomeByVehicleType();
    }

    // GET /api/parking/bi/status-count
    @GetMapping("/bi/status-count")
    public Map<String, Long> getStatusCount() {
        return parkingService.getParkingStatusCounts();
    }

    // GET /api/parking/bi/last-records
    @GetMapping("/bi/last-records")
    public List<ParkingRecord> getLastRecordsBI(
            @RequestParam(required = false) String start, 
            @RequestParam(required = false) String end) {
        if (start != null && end != null) {
            java.time.LocalDateTime customStart = java.time.LocalDate.parse(start).atStartOfDay();
            java.time.LocalDateTime customEnd = java.time.LocalDate.parse(end).plusDays(1).atStartOfDay();
            return parkingService.getRecordsByDateRange(customStart, customEnd);
        }
        return parkingService.getLast100Records();
    }
}