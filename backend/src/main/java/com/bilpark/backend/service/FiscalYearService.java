package com.bilpark.backend.service;

import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.model.FiscalYear;
import com.bilpark.backend.repository.FiscalYearRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class FiscalYearService {

    private final FiscalYearRepository fiscalYearRepository;

    public FiscalYearService(FiscalYearRepository fiscalYearRepository) {
        this.fiscalYearRepository = fiscalYearRepository;
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
        return fiscalYearRepository.save(fy);
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
        return fiscalYearRepository.save(fy);
    }

    /** Deletes only the period definition; parking records are untouched. */
    @Transactional
    public void deleteFiscalYear(Long id) {
        fiscalYearRepository.delete(find(id));
    }

    private FiscalYear find(Long id) {
        return fiscalYearRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Dönem bulunamadı: " + id));
    }
}
