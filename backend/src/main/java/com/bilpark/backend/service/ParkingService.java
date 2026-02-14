package com.bilpark.backend.service;

import com.bilpark.backend.model.ParkSpot;
import com.bilpark.backend.model.ParkingRecord;
import com.bilpark.backend.model.VehicleType;
import com.bilpark.backend.repository.ParkSpotRepository;
import com.bilpark.backend.repository.ParkingRecordRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service //İş mantigini yöneten sinif | Uygulama Beyni
public class ParkingService
{
    //yardımcı depo nesnesi olusturuluyor,baska sıniflar kullanmasin ve Degiştirilemez olsun | Güvenlik ve saglamlik
    private final ParkSpotRepository parkSpotRepository; //1.Depocu : Park yerlerine bakar.
    private final ParkingRecordRepository parkingRecordRepository; // 2.Depocu: Muhasebe fislerine bakar.

    //Dependency Injection sağlayan constructer
    public ParkingService(ParkSpotRepository parkSpotRepository,ParkingRecordRepository parkingRecordRepository)
    {
        this.parkSpotRepository=parkSpotRepository; // İs yaparken kullanacagimiz repository elemanini aliyoruz
        this.parkingRecordRepository=parkingRecordRepository;
    }

    //Bize park yerlerini döndüren fonksiyon | Neon'a gider, sorgu atip sonucu getiricek.
    public List<ParkSpot> getAllSpots()
    {
        return parkSpotRepository.findAll(); // Buradan gelen listeyi controllera atar
    }

    // --- CHECK-IN (Giris islemi ) ---
    // Otoparka giris olmadan önce calisir.
    public ParkSpot checkInVehicle(Long spotId,String licensePlate)
    {
        // A. Yer Kontrolü (Validation)
        ParkSpot spot=parkSpotRepository.findById(spotId) // "spotId" adinda park yerine bakıyor
                .orElseThrow(()->new RuntimeException("Park yeri bulunamadı!")); // yoksa hata firlatiriz.

        // B. Durum Kontrolü
        if(spot.isOccupied()){
            throw new RuntimeException("Hata: Burası zaten dolu!"); // doluysa hata firlatiriz
        }

        // C. Durum Degisikligi (State Change)
        spot.setOccupied(true); // park yerini dolu yapıp
        parkSpotRepository.save(spot); // hemen veritabanina kaydederiz.

        // D. Fis Kesme (History Logging)
        ParkingRecord record= new ParkingRecord(); // record adında yepyeni fis kagidi olusturuyoruz.
        record.setLicensePlate(licensePlate); // plakayi set ediyoruz.
        record.setEntryTime(LocalDateTime.now()); // giris saatini o anki saniye olarak basiyoruz.

        // E. Adres Kopyalama (Snapshot-Pattern)
        record.setRegion(spot.getRegion());
        record.setNeighborhood(spot.getNeighborhood());
        record.setStreet(spot.getStreet());
        record.setSpotName(spot.getSpotName());

        parkingRecordRepository.save(record); // Kaydı veritabanina yollariz.

        return spot; // Sonucta --> Veritabaninda park yeri "DOLU" ve bir adet "Giris Saati" olan ama "Cikis Saati" bos olan bir fisimiz var.
    }

    // --- CHECK-OUT (Cikis İslemi (GERCEK TARİFE ÜZERİNDEN)) ---
    // Burasi paranin kazanildigi ve hesabin kapatildigi yerdir. | sadece Parkyeri ID'si ile kim oldugunu bulucaz.
    public ParkingRecord checkOutVehicle(Long spotId)
    {
        // A. Yer Kontrolü (Validation)
        ParkSpot spot = parkSpotRepository.findById(spotId) // "spotId" adinda park yerine bakiyor.
                .orElseThrow(()-> new RuntimeException("Park yeri bulunamadi!")); // yoksa hata firlatiyoruz.

        // B. Durum Kontrolü
        if(!spot.isOccupied()) // Yer zaten bos ise, bos yerden cikis olmaz | hata firlatiriz.
        {
            throw new RuntimeException("Hata: Burasi zaten bos");
        }

        // C. Acık Fisi Bulmak (Henüz cikis yapilmamis olani)
        ParkingRecord record=parkingRecordRepository.findBySpotNameAndExitTimeIsNull(spot.getSpotName()) //park yeri "spotName" olup | ExitTime IS NULL olan
                .orElseThrow(()->new RuntimeException("Hata: Arac görünüyor ama giris kaydi bulunamadi!")); // bulamazsak kacak giris uyarisi firlatiriz.

        //D.1. Cikis Zamani Ayari
        record.setExitTime(LocalDateTime.now());

        //D.2. Süre Hesabi (Dakika cinsinden)
        long totalMinutes=Duration.between(record.getEntryTime(),record.getExitTime()).toMinutes();

        //D.3. Ücret Hesabi
        double fee=calculateFee(spot.getSuitableFor(),totalMinutes);

        record.setFee(fee); // fişteki ücret kaydı
        parkingRecordRepository.save(record);

        return record;
    }

    // Ücret Hesaplama Motoru
    private double calculateFee(VehicleType vehicleType,long minutes)
    {
        // Kural 1: İlk 5 dakika ÜCRETSİZ
        if(minutes<=5)
        {
            return 0.0;
        }

        double baseFee; // İlk 1 saatin ücreti
        double extraFee; // Sonraki her saatin ücreti

        // Kural 2: Arac Tipine Göre Tarife Belirleme
        if(vehicleType== VehicleType.LARGE){
            //Büyük Arac tarifesi
            baseFee=50.0;
            extraFee=30.0;
        }else {
            //Kücük Arac Tarifesi (Default)
            baseFee=25.0;
            extraFee=15.0;
        }

        //Kural 3: Süreye Göre Hesap
        if(minutes <=60)
        {
            //İlk 1 saat icinde ise sabit ücret
            return baseFee;
        }else {
            //1 saati aştıysa
            long extraMinutes =minutes-60;

            //Her başlayan saat icin tam ücret alinir. | 61.dakikada ise 2.saate girmiş demektir ( +15 TL).
            //Math.ceil() yukari yuvarlar.
            long extraHours = (long) Math.ceil(extraMinutes/ 60.0);

            return baseFee + (extraHours*extraFee);
        }
    }




}
