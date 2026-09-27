package com.bilpark.backend.controller;

import com.bilpark.backend.model.ParkSpot;
import com.bilpark.backend.model.ParkingRecord;
import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.service.ParkingService;
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

    public ParkingController(ParkingService parkingService, 
                             com.bilpark.backend.repository.UserRepository userRepository,
                             com.bilpark.backend.repository.ParkingRecordRepository parkingRecordRepository) {
        this.parkingService = parkingService;
        this.userRepository = userRepository;
        this.parkingRecordRepository = parkingRecordRepository;
    }

    @GetMapping("/bi/shifts")
    public ResponseEntity<?> getActiveShifts() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.util.List<java.util.Map<String, Object>> result = new java.util.ArrayList<>();
        
        java.util.List<com.bilpark.backend.model.User> officers = userRepository.findAll().stream()
            .filter(u -> u.getRole() == com.bilpark.backend.model.Role.OFFICER && u.getLastLoginTime() != null)
            .toList();
            
        for (com.bilpark.backend.model.User o : officers) {
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            map.put("username", o.getUsername());
            
            if (o.getAssignedZone() != null) {
                map.put("zoneName", o.getAssignedZone().getZoneName());
                map.put("street", o.getAssignedZone().getStreet().name());
                
                Double income = parkingRecordRepository.getIncomeByZoneAndDateRange(o.getAssignedZone(), o.getLastLoginTime(), now);
                map.put("revenue", income != null ? income : 0.0);
            } else {
                map.put("zoneName", "Bölge Yok");
                map.put("street", "Belirsiz");
                map.put("revenue", 0.0);
            }
            
            map.put("lastLogin", o.getLastLoginTime());
            java.time.Duration duration = java.time.Duration.between(o.getLastLoginTime(), now);
            map.put("activeMinutes", duration.toMinutes());
            
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
            @RequestParam @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp = "^[A-Za-z0-9 ]{5,11}$", message = "Invalid plate format") String plate,
            @RequestParam StreetLocation street,
            @RequestParam(defaultValue = "SMALL") String type,
            @RequestParam(defaultValue = "LEFT") String side,
            @RequestParam(required = false) Long zoneId) {
        return ResponseEntity.ok(parkingService.checkInVehicle(plate, type, street, side, zoneId));
    }

    // POST /api/parking/check-out?plate=34ABC123
    @PostMapping("/check-out")
    public ResponseEntity<ParkingRecord> checkOut(
            @RequestParam @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp = "^[A-Za-z0-9 ]{5,11}$", message = "Invalid plate format") String plate) {
        return ResponseEntity.ok(parkingService.checkOutVehicle(plate));
    }

    // POST /api/parking/runaway?plate=34ABC123
    @PostMapping("/runaway")
    public ResponseEntity<Map<String, Object>> markAsRunaway(@RequestParam String plate) {
        ParkingRecord record = parkingService.markAsRunaway(plate);
        return ResponseEntity.ok(Map.of(
                "message", "Vehicle flagged as runaway and added to blacklist.",
                "debt", record.getFee(),
                "record", record
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
            return ResponseEntity.status(404).body(Map.of("message", "No history found for this plate."));
        }
        return ResponseEntity.ok(results);
    }

    // GET /api/parking/debt?plate=34ABC123 — Query outstanding debt for citizen portal
    @GetMapping("/debt")
    public ResponseEntity<Map<String, Object>> getDebt(@RequestParam String plate) {
        return ResponseEntity.ok(parkingService.getDebtByPlate(plate));
    }

    // POST /api/parking/pay?plate=34ABC123 — Citizen self-checkout
    @PostMapping("/pay")
    public ResponseEntity<Map<String, Object>> payAndCheckOut(@RequestParam String plate) {
        ParkingRecord record = parkingService.processPaymentAndCheckout(plate);
        return ResponseEntity.ok(Map.of(
                "message", "Payment successful. Vehicle checked out.",
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
    public List<ParkingRecord> getLastRecordsBI() {
        return parkingService.getLast100Records();
    }
}