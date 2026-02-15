package com.bilpark.backend.repository;

import com.bilpark.backend.model.ParkingRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository // Veritabani islerinden sorumlu | interface seçmemizin sebebi sadece Spring'e ne istediğimizi söyleriz o SQL kodunu Runtime da kendisi yazar.
public interface ParkingRecordRepository extends JpaRepository<ParkingRecord, Long> //Mirasi ParkingRecord tablosu üzerinde kullanıcaz | Long -> tablonun ID'si bir sayidir
{
    //1. Açık Kaydı Bulma
    // Bize "spotName" ismine sahip park yerinden
    //henüz cikis yapmamis (ExitTime is Null) kaydı getir.
    Optional<ParkingRecord> findBySpotNameAndExitTimeIsNull(String spotName);//Derived Query Methods (Türetilmiş Sorgular)

    //2. Toplam Ciro Hesaplama
    @Query("SELECT SUM(r.fee) FROM ParkingRecord r") //Query ->Özel Sorgu (JPQL) | SELECT SUM(r.fee) ->Bütün kayıtların fee alanlarını toplar | FROM parkingRecord r ->ParkingRecord sınıfındaki ("r" onun takma adı) kayıtlara bakar.
    Double getTotalIncome();
}
