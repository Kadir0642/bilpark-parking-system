package com.bilpark.backend.repository;

import com.bilpark.backend.model.ParkingRecord;
import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.model.ParkingStatus;
import com.bilpark.backend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

//Muhasebe işleri sorguları/giriş-çıkış kayıtları ile ilgilenir.
// Ciro sorguları yalnızca PAID kayıtları toplar: ödenmemiş kaçak borçlar (RUNAWAY) ciro değildir.
// Tarih aralıkları yarı açıktır: start <= zaman < end.
@Repository // Veritabani islerinden sorumlu | interface seçmemizin sebebi sadece Spring'e ne istediğimizi söyleriz o SQL kodunu Runtime da kendisi yazar.
public interface ParkingRecordRepository extends JpaRepository<ParkingRecord, Long> //Mirasi ParkingRecord tablosu üzerinde kullanıcaz | Long -> tablonun ID'si bir sayidir
{

    // 1. Toplam Ciro (yalnızca tahsil edilmiş kayıtlar)
    @Query("SELECT COALESCE(SUM(r.fee), 0) FROM ParkingRecord r WHERE r.status = com.bilpark.backend.model.ParkingStatus.PAID")
    Double getTotalIncome();

    //2. Belirli İki Tarih Arasındaki Ciroyu Topla (çıkış zamanına göre)
    @Query("SELECT COALESCE(SUM(r.fee), 0) FROM ParkingRecord r WHERE r.status = com.bilpark.backend.model.ParkingStatus.PAID AND r.exitTime >= :start AND r.exitTime < :end")
    Double getIncomeByDateRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    // 2.1 Bir vardiyanın cirosu: o kullanıcının o cihazdan tahsil ettikleri
    @Query("SELECT COALESCE(SUM(r.fee), 0) FROM ParkingRecord r WHERE r.status = com.bilpark.backend.model.ParkingStatus.PAID AND r.collectedBy = :user AND r.deviceId = :deviceId AND r.exitTime >= :start AND r.exitTime < :end")
    Double getIncomeByCollectorAndDevice(@Param("user") User user,
                                         @Param("deviceId") String deviceId,
                                         @Param("start") LocalDateTime start,
                                         @Param("end") LocalDateTime end);

    // 2.2 Araç tipine göre ciro: [VehicleType, Double] satırları (vehicleType null olabilir)
    @Query("SELECT r.vehicleType, COALESCE(SUM(r.fee), 0) FROM ParkingRecord r WHERE r.status = com.bilpark.backend.model.ParkingStatus.PAID GROUP BY r.vehicleType")
    List<Object[]> sumPaidIncomeByVehicleType();

    // 2.3 Duruma göre kayıt sayısı: [ParkingStatus, Long] satırları
    @Query("SELECT r.status, COUNT(r) FROM ParkingRecord r GROUP BY r.status")
    List<Object[]> countByStatus();

    //3. Sadece seçilen cadde filtresi ile en son 50 kaydı, bizim parametreyle birebir eşleşen, En yeni en üstte olacak şekilde getirir.
    // Geçmiş kayıt sorgusu | Sistemi yormamak için sadece son 50 olayı getirdik
    List<ParkingRecord> findTop50ByStreetOrderByEntryTimeDesc(StreetLocation street); // byregionAndNeighborhoodAndStreet -> WHERE | OrderByEntryTime ->EntryTime göre sıralar | Desc (Descending) ->En son giren araç en tepede ,Aşağı doğru giderek büyüyen [ Yeniden eskiye doğru]

    // 3.1. Tüm caddelerdeki son 100 kaydı getir.
    List<ParkingRecord> findTop100ByOrderByEntryTimeDesc();

    // 3.2 Plakanın en son kapanan kaydı (eşzamanlı ikinci çıkış isteğine 409 vermek için)
    Optional<ParkingRecord> findTopByLicensePlateOrderByExitTimeDesc(String licensePlate);

    // 4. Plakaya ve Duruma Göre (Örn: Sadece KAÇANLARI) getir.
    // Bu sayede bir araç caddeye girdiğinde "Geçmişten kaçak borcu var mı? " diye sorabileceğiz.!
    List<ParkingRecord> findByLicensePlateAndStatus(String licensePlate, ParkingStatus status);

    // 4.1 Ödenmemiş kaçak borç toplamı, plaka başına tek sorguda: [String plate, Double debt] satırları
    @Query("SELECT r.licensePlate, COALESCE(SUM(r.fee), 0) FROM ParkingRecord r WHERE r.licensePlate IN :plates "
            + "AND r.status = com.bilpark.backend.model.ParkingStatus.RUNAWAY AND r.debtPaid = false GROUP BY r.licensePlate")
    List<Object[]> sumUnpaidRunawayDebtByPlate(@Param("plates") java.util.Collection<String> plates);

    // 5. Plakaya göre geçmiş fişleri getir. (Arama çubuğu için; temizlenmiş plaka veya parçası)
    List<ParkingRecord> findByLicensePlateContainingOrderByEntryTimeDesc(String platePart);

    // 6. Tarih aralığına göre tüm kayıtları getir (Excel indirme için, start <= entryTime < end)
    @Query("SELECT r FROM ParkingRecord r WHERE r.entryTime >= :start AND r.entryTime < :end ORDER BY r.entryTime DESC")
    List<ParkingRecord> findByEntryTimeRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
