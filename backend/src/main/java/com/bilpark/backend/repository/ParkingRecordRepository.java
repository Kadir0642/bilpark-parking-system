package com.bilpark.backend.repository;

import com.bilpark.backend.model.ParkingRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository // Veritabani islerinden sorumlu | interface seçmemizin sebebi sadece Spring'e ne istediğimizi söyleriz o SQL kodunu Runtime da kendisi yazar.
public interface ParkingRecordRepository extends JpaRepository<ParkingRecord, Long> //Mirasi ParkingRecord tablosu üzerinde kullanıcaz | Long -> tablonun ID'si bir sayidir
{
    //Bize "spotName" ismine sahip park yerinden
    //henüz cikis yapmamis (ExitTime is Null) kaydı getir.
    Optional<ParkingRecord> findBySpotNameAndExitTimeIsNull(String spotName);//Derived Query Methods (Türetilmiş Sorgular)
}
