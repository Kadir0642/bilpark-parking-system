package com.bilpark.backend.service;

import com.bilpark.backend.audit.AuditService;
import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.model.AuditAction;
import com.bilpark.backend.model.FiscalYear;
import com.bilpark.backend.repository.FiscalYearRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class FiscalYearService {

    private final FiscalYearRepository fiscalYearRepository;

    private final AuditService auditService;

    public FiscalYearService(FiscalYearRepository fiscalYearRepository, AuditService auditService) {
        this.fiscalYearRepository = fiscalYearRepository;
        this.auditService = auditService;
    }

    public List<FiscalYear> getAllFiscalYears() {
        return fiscalYearRepository.findAllByOrderByStartDateDesc();
    }

    @Transactional
    public FiscalYear createFiscalYear(LocalDate startDate, LocalDate endDate, String label) {
        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR,
                    "Başlangıç tarihi bitiş tarihinden sonra olamaz.");
        }
        // Deactivate currently active one
        fiscalYearRepository.findByIsActiveTrue().ifPresent(fy -> {
            fy.setActive(false);
            fiscalYearRepository.save(fy);
        });

        FiscalYear fy = new FiscalYear(startDate, endDate, label);
        fy.setActive(true);
        FiscalYear saved = fiscalYearRepository.save(fy);
        auditService.log(AuditAction.FISCAL_YEAR_CREATED, "FiscalYear", saved.getId(),
                "Dönem oluşturuldu: " + label, null, summary(saved));
        return saved;
    }

    /** Marks the period as archived. Parking records are never deleted. */
    @Transactional
    public FiscalYear archiveFiscalYear(Long id) {
        FiscalYear fy = find(id);
        if (fy.isArchived()) {
            throw ApiException.conflict(ErrorCodes.ALREADY_ARCHIVED, "Bu dönem zaten arşivlenmiş.");
        }
        fy.setArchived(true);
        fy.setActive(false);
        fy.setArchivedAt(LocalDateTime.now());
        FiscalYear saved = fiscalYearRepository.save(fy);
        auditService.log(AuditAction.FISCAL_YEAR_ARCHIVED, "FiscalYear", id,
                "Dönem arşivlendi: " + saved.getLabel(), null, summary(saved));
        return saved;
    }

    /** Deletes only the period definition; parking records are untouched. */
    @Transactional
    public void deleteFiscalYear(Long id) {
        FiscalYear fy = find(id);
        Map<String, Object> before = summary(fy);
        fiscalYearRepository.delete(fy);
        auditService.log(AuditAction.FISCAL_YEAR_DELETED, "FiscalYear", id,
                "Dönem tanımı silindi: " + fy.getLabel(), before, null);
    }

    private static Map<String, Object> summary(FiscalYear fy) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", fy.getLabel());
        m.put("startDate", String.valueOf(fy.getStartDate()));
        m.put("endDate", String.valueOf(fy.getEndDate()));
        m.put("active", fy.isActive());
        m.put("archived", fy.isArchived());
        return m;
    }

    private FiscalYear find(Long id) {
        return fiscalYearRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Dönem bulunamadı: " + id));
    }
}
