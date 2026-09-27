package com.bilpark.backend.controller;

import com.bilpark.backend.model.FiscalYear;
import com.bilpark.backend.service.FiscalYearService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/fiscal-years")
@PreAuthorize("hasRole('ADMIN')") // Only admin manages fiscal years
public class FiscalYearController {

    private final FiscalYearService fiscalYearService;

    public FiscalYearController(FiscalYearService fiscalYearService) {
        this.fiscalYearService = fiscalYearService;
    }

    @GetMapping
    public List<FiscalYear> getAllFiscalYears() {
        return fiscalYearService.getAllFiscalYears();
    }

    @PostMapping("/create")
    public ResponseEntity<?> createFiscalYear(@RequestBody @jakarta.validation.Valid com.bilpark.backend.dto.FiscalYearRequest request) {
        try {
            FiscalYear fy = fiscalYearService.createFiscalYear(request.getStartDate(), request.getEndDate(), request.getLabel());
            return ResponseEntity.ok(fy);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<?> archiveFiscalYear(@PathVariable Long id) {
        try {
            FiscalYear archived = fiscalYearService.archiveFiscalYear(id);
            return ResponseEntity.ok(archived);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteFiscalYear(@PathVariable Long id) {
        try {
            fiscalYearService.deleteFiscalYear(id);
            return ResponseEntity.ok(Map.of("message", "Dönem başarıyla silindi."));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
