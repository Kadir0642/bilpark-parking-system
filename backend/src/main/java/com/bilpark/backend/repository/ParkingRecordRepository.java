package com.bilpark.backend.repository;

import com.bilpark.backend.model.ParkingRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

//Muhasebe işleri sorguları/giriş-çıkış kayıtları ile ilgilenir.
@Repository // Veritabani islerinden sorumlu | interface seçmemizin sebebi sadece Spring'e ne istediğimizi söyleriz o SQL kodunu Runtime da kendisi yazar.
public interface ParkingRecordRepository extends JpaRepository<ParkingRecord, Long> //Mirasi ParkingRecord tablosu üzerinde kullanıcaz | Long -> tablonun ID'si bir sayidir
{
    //1. Açık Kaydı Bulma
    // Bize "spotName" ismine sahip park yerinden
    //henüz cikis yapmamis (ExitTime is Null) kaydı getir.
    Optional<ParkingRecord> findBySpotNameAndExitTimeIsNull(String spotName);//Derived Query Methods | JPA (Türetilmiş Sorgular | metot isminden SQL üretebiliyordu)

    //2. Toplam Ciro Hesaplama
    @Query("SELECT SUM(r.fee) FROM ParkingRecord r") // @Query ->Özel Sorgu (JPQL) | SELECT SUM(r.fee) ->Bütün kayıtların "fee" alanlarını toplar | FROM ParkingRecord r ->ParkingRecord sınıfındaki ("r" onun takma adı) kayıtlara bakar.
    Double getTotalIncome();

    //3. Belirli İki Tarih Arasındaki Ciroyu Topla
    // SQL ->SELECT SUM(fee) FROM parking_records WHERE exit_time BETWEEN :start AND :end
    // WHERE->filtre ayarı (kriter çıkışSaati) | BETWEEN ... AND ... -> iki değer arasında kalanı seçer | " : " -> Parametre place holder
    @Query("SELECT SUM(r.fee) FROM ParkingRecord r WHERE r.exitTime BETWEEN :start AND :end") // ParkingRecord(r) sınıfındaki kayıtlarda start-end arasını seçip, fee sütünlarını toplayıp bize vericek.
    Double getIncomeByDateRange(LocalDateTime start, LocalDateTime end); //Veritabanı null dönerse hata olmasın diye "Double" yazdık
}
