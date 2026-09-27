package com.bilpark.backend.service;

import com.bilpark.backend.model.FiscalYear;
import com.bilpark.backend.repository.FiscalYearRepository;
import com.bilpark.backend.repository.ParkingRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
public class FiscalYearService {

    private final FiscalYearRepository fiscalYearRepository;
    private final ParkingRecordRepository parkingRecordRepository;

    public FiscalYearService(FiscalYearRepository fiscalYearRepository,
                             ParkingRecordRepository parkingRecordRepository) {
        this.fiscalYearRepository = fiscalYearRepository;
        this.parkingRecordRepository = parkingRecordRepository;
    }

    public List<FiscalYear> getAllFiscalYears() {
        return fiscalYearRepository.findAllByOrderByStartDateDesc();
    }

    @Transactional
    public FiscalYear createFiscalYear(LocalDate startDate, LocalDate endDate, String label) {
        // Deactivate currently active one
        fiscalYearRepository.findByIsActiveTrue().ifPresent(fy -> {
            fy.setActive(false);
            fiscalYearRepository.save(fy);
        });

        FiscalYear fy = new FiscalYear(startDate, endDate, label);
        fy.setActive(true);
        return fiscalYearRepository.save(fy);
    }

    @Transactional
    public FiscalYear archiveFiscalYear(Long id) {
        FiscalYear fy = fiscalYearRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Fiscal year not found: " + id));

        // Delete all parking records within this fiscal year to clean up DB
        parkingRecordRepository.deleteByEntryTimeBetween(
                fy.getStartDate().atStartOfDay(),
                fy.getEndDate().plusDays(1).atStartOfDay()
        );

        fy.setActive(false);
        return fiscalYearRepository.save(fy);
    }

    @Transactional
    public void deleteFiscalYear(Long id) {
        FiscalYear fy = fiscalYearRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Fiscal year not found: " + id));
        
        fiscalYearRepository.delete(fy);
    }
}
