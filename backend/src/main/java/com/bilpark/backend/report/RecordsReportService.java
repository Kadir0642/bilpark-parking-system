package com.bilpark.backend.report;

import com.bilpark.backend.model.ParkingStatus;
import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.report.ReportSupport.Range;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Runaway report for the municipality and the full records export. */
@Service
public class RecordsReportService {

    public static final int MAX_EXPORT_ROWS = 100_000;

    private final ReportQueryRepository repo;
    private final ReportSupport support;

    public RecordsReportService(ReportQueryRepository repo, ReportSupport support) {
        this.repo = repo;
        this.support = support;
    }

    @Transactional(readOnly = true)
    public ReportSheet runaways(Range range, String street, boolean unpaidOnly) {
        StreetLocation filter = null;
        if (street != null && !street.isBlank()) {
            try {
                filter = StreetLocation.valueOf(street.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw ReportSupport.bad("Geçersiz cadde kodu: " + street);
            }
        }
        Map<String, String> names = support.streetNames();
        ReportSheet sheet = new ReportSheet("runaways", "Kaçak Araçlar", List.of("Plaka", "Cadde", "Saha",
                "Giriş tarih-saat", "Çıkış (kaçak işaretleme) tarih-saat", "Tutar (TL)", "Memur", "Borç durumu"));
        sheet.title("Kaçak Araç Raporu " + range.display());
        int count = 0;
        double sum = 0;
        for (RecordRow r : repo.byStatusAndExit(ParkingStatus.RUNAWAY, range.start(), range.end())) {
            if (filter != null && r.street() != filter) continue;
            if (unpaidOnly && r.debtPaid()) continue;
            sheet.add(r.plate(), ReportSupport.street(names, r.street()), r.zoneName(), r.entryTime(), r.exitTime(),
                    Money.of(r.fee()), r.collectedBy(), r.debtPaid() ? "Ödendi" : "Ödenmedi");
            count++;
            sum += r.feeOrZero();
        }
        sheet.addTotal("Toplam (" + count + " kayıt)", null, null, null, null, Money.of(sum), null, null);
        return sheet;
    }

    @Transactional(readOnly = true)
    public ReportSheet records(Range range) {
        if (repo.countByEntry(range.start(), range.end()) > MAX_EXPORT_ROWS) {
            throw ReportSupport.bad("Seçilen aralıkta " + MAX_EXPORT_ROWS
                    + " kayıttan fazla var; lütfen aralığı daraltın.");
        }
        Map<String, String> names = support.streetNames();
        ReportSheet sheet = new ReportSheet("records", "Kayıtlar", List.of("ID", "Plaka", "Cadde", "Saha", "Araç tipi",
                "Giriş", "Çıkış", "Süre (dk)", "Ücretli dk", "Ücret TL", "Durum", "Ödeme yöntemi", "Tahsil eden",
                "Cihaz", "Tarife ID", "İptal gerekçesi"));
        for (RecordRow r : repo.byEntry(range.start(), range.end())) {
            Long minutes = r.entryTime() != null && r.exitTime() != null
                    ? Math.max(0, Duration.between(r.entryTime(), r.exitTime()).toMinutes()) : null;
            sheet.add(r.id(), r.plate(), ReportSupport.street(names, r.street()), r.zoneName(),
                    r.vehicleType() == null ? null : r.vehicleType().name(), r.entryTime(), r.exitTime(), minutes,
                    r.paidMinutes(), r.fee() == null ? null : Money.of(r.fee()), status(r.status()),
                    r.paymentMethod() == null ? null : r.paymentMethod().name(), r.collectedBy(), r.deviceId(),
                    r.tariffId(), r.cancelReason());
        }
        return sheet;
    }

    private static String status(ParkingStatus s) {
        if (s == null) return null;
        return switch (s) {
            case PAID -> "Ödendi";
            case RUNAWAY -> "Kaçak";
            case CANCELLED -> "İptal";
            case ACTIVE -> "Aktif";
        };
    }
}
