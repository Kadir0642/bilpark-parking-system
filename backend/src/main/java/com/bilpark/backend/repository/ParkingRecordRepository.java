package com.bilpark.backend.repository;

import com.bilpark.backend.model.ParkingRecord;
import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.model.ParkingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

//Muhasebe işleri sorguları/giriş-çıkış kayıtları ile ilgilenir.
@Repository // Veritabani islerinden sorumlu | interface seçmemizin sebebi sadece Spring'e ne istediğimizi söyleriz o SQL kodunu Runtime da kendisi yazar.
public interface ParkingRecordRepository extends JpaRepository<ParkingRecord, Long> //Mirasi ParkingRecord tablosu üzerinde kullanıcaz | Long -> tablonun ID'si bir sayidir
{


    // 1. Toplam Ciro Hesaplama
    //Derived Query Methods | JPA (Türetilmiş Sorgular | metot isminden SQL üretebiliyordu)
    @Query("SELECT SUM(r.fee) FROM ParkingRecord r") // @Query ->Özel Sorgu (JPQL) | SELECT SUM(r.fee) ->Bütün kayıtların "fee" alanlarını toplar | FROM ParkingRecord r ->ParkingRecord sınıfındaki ("r" onun takma adı) kayıtlara bakar.
    Double getTotalIncome();

    //2. Belirli İki Tarih Arasındaki Ciroyu Topla
    // SQL ->SELECT SUM(fee) FROM parking_records WHERE exit_time BETWEEN :start AND :end
    // WHERE->filtre ayarı (kriter çıkışSaati) | BETWEEN ... AND ... -> iki değer arasında kalanı seçer | " : " -> Parametre place holder
    @Query("SELECT SUM(r.fee) FROM ParkingRecord r WHERE r.exitTime BETWEEN :start AND :end") // ParkingRecord(r) sınıfındaki kayıtlarda start-end arasını seçip, fee sütünlarını toplayıp bize vericek.
    Double getIncomeByDateRange(LocalDateTime start, LocalDateTime end); //Veritabanı null dönerse hata olmasın diye "Double" yazdık

    //3. Sadece seçilen cadde filtresi ile en son 50 kaydı, bizim parametreyle birebir eşleşen, En yeni en üstte olacak şekilde getirir.
    // Geçmiş kayıt sorgusu | Sistemi yormamak için sadece son 50 olayı getirdik
    List<ParkingRecord> findTop50ByStreetOrderByEntryTimeDesc(StreetLocation street); // byregionAndNeighborhoodAndStreet -> WHERE | OrderByEntryTime ->EntryTime göre sıralar | Desc (Descending) ->En son giren araç en tepede ,Aşağı doğru giderek büyüyen [ Yeniden eskiye doğru]

    // 3.1. Tüm caddelerdeki son 100 kaydı getir.
    List<ParkingRecord> findTop100ByOrderByEntryTimeDesc();

    // 4. Plakaya ve Duruma Göre (Örn: Sadece KAÇANLARI) getir.
    // Bu sayede bir araç caddeye girdiğinde "Geçmişten kaçak borcu var mı? " diye sorabileceğiz.!
    List<ParkingRecord> findByLicensePlateIgnoreCaseAndStatus(String licensePLate,ParkingStatus status);

    // 5. Plakaya göre geçmiş fişleri getir. (Arama çubuğu için)
    List<ParkingRecord> findByLicensePlateIgnoreCase(String licensePlate);
}
