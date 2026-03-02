package com.bilpark.backend.service;

import com.bilpark.backend.model.ParkSpot;
import com.bilpark.backend.model.ParkingRecord;
import com.bilpark.backend.model.VehicleType;
import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.model.ParkingStatus;
import com.bilpark.backend.repository.ParkSpotRepository;
import com.bilpark.backend.repository.ParkingRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;

@Service // İş mantigini yöneten sinif | Uygulama Beyni
public class ParkingService
{
    //yardımcı depo nesnesi olusturuluyor,baska sıniflar kullanmasin ve Degiştirilemez olsun | Güvenlik ve saglamlik
    private final ParkSpotRepository parkSpotRepository; //1.Depocu : Park yerlerine bakar.
    private final ParkingRecordRepository parkingRecordRepository; // 2.Depocu: Muhasebe fislerine bakar.

    //Dependency Injection sağlayan constructer
    public ParkingService(ParkSpotRepository parkSpotRepository,ParkingRecordRepository parkingRecordRepository)
    {
        this.parkSpotRepository=parkSpotRepository; // İs yaparken kullanacagimiz repository elemanini aliyoruz
        this.parkingRecordRepository=parkingRecordRepository; // Fis keserken kayıtlara bakacak elemanı alıyoruz.
    }

    //Bize park yerlerini döndüren fonksiyon | Neon'a gider, sorgu atip sonucu getiricek.
    public List<ParkSpot> getAllSpots()
    {
        return parkSpotRepository.findAll(); // Buradan gelen listeyi controllera atar
    }

    // --- CHECK-IN (Giris islemi ) ---
    // Otoparka giris olmadan önce calisir. | UPDATE: Artık araç tipinide alıyoruz | spotId yok ve Araç geldikçe yeni kayıt doğar.
    public ParkSpot checkInVehicle(String licensePlate,String vehicleType,StreetLocation street,String side)
    {
        // A. Güvenlik Duvarı: Çifte Kayıt Engelleme (Fail-Fast)
        Optional<ParkSpot> existingVehicle= parkSpotRepository.findByCurrentPlateIgnoreCase(licensePlate);
        if(existingVehicle.isPresent()){ // nesnenin mevcut olup olmadığına bakılıyor.
            throw new RuntimeException("DİKKAT: "+licensePlate.toUpperCase()+ " plakalı araç zaten otoparkta kayıtlı!");
        }

        // B. Araç Tipi Belirleme (Enum Dönüşümü)
        //Gelen String tipi (SMALL/LARGE) Enum'a çevirip kaydediyoruz.
        //Eğer boş gelirse default SMALL olur.
        VehicleType type= VehicleType.SMALL; // Default
        if(vehicleType !=null && !vehicleType.isEmpty()) {
            try {
                type = VehicleType.valueOf(vehicleType.toUpperCase());
            } catch (IllegalArgumentException e) {
                type = VehicleType.SMALL; // Hatalı gelirse küçük say
            }
        }

        // C.Yeni araç Oluşturma (Sınırsız Kapasite Mantığı)
        // Araç sokağa girdiği an ParkSpot (AKTİF) tablosunu bir satıra eklenir.
        ParkSpot newSpot = new ParkSpot(licensePlate.toUpperCase(),street,type, "Merkez", "Bilecik",side);

        // Fişi ŞİMDİ KESMİYORUZ! Fiş sadece araç çıkarken kesilir.
        return parkSpotRepository.save(newSpot);
    }



    // --- CHECK-OUT (Cikis İslemi (GERCEK TARİFE ÜZERİNDEN)) ---
    // Burasi paranin kazanildigi ve hesabin kapatildigi yerdir. | Sadece plaka ile çıkış yapılır.
    public ParkingRecord checkOutVehicle(String plate) {
        // A. Araç Kontrolü (Validation)
        ParkSpot spot = parkSpotRepository.findByCurrentPlateIgnoreCase(plate) // "plate" adina bakiyor.
                .orElseThrow(() -> new RuntimeException("Hata: " + plate + "plakalı araç aktif otoparkta bulunamadı!")); // yoksa hata firlatiyoruz.

        // B. Ücret Hesabi && Süre Hesabi (Dakika cinsinden) | Zaman formatının orijinali long
        long totalMinutes = Duration.between(spot.getEntryTime(), LocalDateTime.now()).toMinutes();// Java'da zaman hesaplamaları (Milisaniye, saniye, dakika) endüstri standardı olarak her zaman long ile yapılır
        double fee = calculateFee(spot.getCurrentType(), totalMinutes);

        // C. Arşive Aktarma (Muhasebe Fişini Şimdi Kesiyoruz)
        ParkingRecord record = new ParkingRecord(
                spot.getCurrentPlate(), spot.getStreet(), spot.getRegion(), spot.getNeighborhood(), spot.getEntryTime()
        );
        record.setExitTime(LocalDateTime.now());
        record.setFee(fee);
        record.setStatus(ParkingStatus.PAID); // NORMAL ÇIKIŞ
        record.setVehicleType(spot.getCurrentType()); // Aktif araçtaki tipi arşive kaydederiz

        parkingRecordRepository.save(record);

        // D. SOKAKTAN SİL (Kapasite açılıyor)
        parkSpotRepository.delete(spot);

        return record;
    }

    //  ---YENİ: KAÇAK ARAÇ BİLDİRİMİ (Runaway) ---
    // Görevli "Kaçtı" butonuna bastığında çalışır.
    @Transactional // birden fazla veritabanı işlemi gerçekleştiren ve bunların hepsinin birlikte başarılı veya başarısız olması gereken yöntemler için
    public ParkingRecord markAsRunaway(String plate){
        // A. Aracı bul

        ParkSpot spot = parkSpotRepository.findByCurrentPlateIgnoreCase(plate)
                .orElseThrow(()->new RuntimeException("Hata: Araç Bulunamadı!"));

        // B. Mevcut borcunu hesapla (Belki ileride ceza çarpanı ekleriz.)
        long totalMinutes = Duration.between(spot.getEntryTime(),LocalDateTime.now()).toMinutes();
        double debt = calculateFee(spot.getCurrentType(),totalMinutes);

        // C: Kara Listeye (Arşive) Ekle
        ParkingRecord record = new ParkingRecord(
                spot.getCurrentPlate(),spot.getStreet(),spot.getRegion(), spot.getNeighborhood(), spot.getEntryTime()
        );
        record.setExitTime(LocalDateTime.now());
        record.setFee(debt); // Ödenmemiş borç
        record.setStatus(ParkingStatus.RUNAWAY); // KAÇAK DAMGASI VURULDU!
        record.setVehicleType(spot.getCurrentType()); // Kaçak araçtaki tipi, arşive kaydeder.
        parkingRecordRepository.save(record);

        // D. Sokaktan Sil (Kapasite işgal etmesin)
        parkSpotRepository.delete(spot);

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

    // --- CİRO HESAPLAMA (INCOME) ---
    public double getTotalIncome()
    {
        // "Double" içi boş(Null) kalabilen bir Wrapper Class(Sarmalayıcı) ,methodları olan(.toString,.intValue)
        Double total=parkingRecordRepository.getTotalIncome(); // Veri tabanındaki bütün kayıtların fee sütünları toplanıp, total nesnesine atılır.
        return (total != null) ? total : 0.0; //Eğer hiç kayıt yoksa "null" gelir, biz 0.0 döndürürüz | veritabanından gelen belirsizliği (null), kullanıcıya gösterilecek net bir bilgiye (0.0) çeviren bir Converter
    }

    // --- Daily Income ---
    public Double getDailyIncome(){
        // Gün başlangıcı (00:00)
        LocalDateTime startOfDay=LocalDateTime.now().toLocalDate().atStartOfDay();//.now ->Şu an zamanı alır | .toLocalDate ->Saati atar,günü alır | .atStartOfDay -> gün başı 00:00 yapar.
        // Gün bitişi (1 gün eklendi) yarın 00:00 a kadar (00:00 dahil değil).
        LocalDateTime endOfDay=startOfDay.plusDays(1); // plusDays -> parametre kadar gün ekler.

        Double dailyTotal= parkingRecordRepository.getIncomeByDateRange(startOfDay,endOfDay); // Belirtilen zaman dilimindeki fişlerin fee sütünlarını toplar

        return (dailyTotal !=null) ? dailyTotal : 0.0; // Veritabanı null dönerse 0.0 yapıp programın patlamasını engelleriz.
    }

    // --- Weekly Income ---
    public Double getWeeklyIncome(){
        LocalDateTime end=LocalDateTime.now(); // Şu an (bitiş çizgisi)
        LocalDateTime start= end.minusDays(7); // 7 gün öncesi (Başlangıç)

        Double total=parkingRecordRepository.getIncomeByDateRange(start,end);
        return (total != null) ? total : 0.0;
    }

    // --- Monthly Income (Ayın 1'inden itibaren)---
    public Double getMonthlyIncome(){
        LocalDateTime end=LocalDateTime.now();// Şu an (bitiş çizgisi)
        //Ayın ilk gününü bul
        LocalDateTime start=java.time.LocalDate.now().withDayOfMonth(1).atStartOfDay(); // withDayOfMonth ->gün kısmını 1 yapıp | saati 00:00 yapar.

        Double total=parkingRecordRepository.getIncomeByDateRange(start,end);
        return (total != null) ? total :0.0;
    }

    // --- Yearly Income (Yılın başından itibaren) ---
    public Double getYearlyIncome(){
        LocalDateTime end =LocalDateTime.now();
        //Yılın ilk gününü bul
        LocalDateTime start=java.time.LocalDate.now().withDayOfYear(1).atStartOfDay();

        Double total=parkingRecordRepository.getIncomeByDateRange(start,end);
        return (total != null) ? total : 0.0;
    }

    // --- FİLTRELEME SERVİSLERİ ---

    // Sadece seçilen caddedeki aktif araçları getirir
    public List<ParkSpot> getSpotsByStreet(StreetLocation street){
        return parkSpotRepository.findByStreet(street);
    }

    // --- GEÇMİŞ KAYITLARI GETİR (Enum Filtreli)---
    // Delegation (Görev devri)
    public List<ParkingRecord> getHistoryByLocation(StreetLocation street){ // Şu an için tek görevi köprü olmak , repository'i çağırıyor mimari için ilerideki özellik eklemeleri için bu yapı tercih ediliyor.
        return parkingRecordRepository.findTop50ByStreetOrderByEntryTimeDesc(street); // Sadece cadde adıyla veri kayıtları alıyor.
    }

    // Araç Geçmişi Arama filtresi
    public List<ParkingRecord> searchHistoryByPlate(String plate){
        return parkingRecordRepository.findByLicensePlateIgnoreCase(plate);
    }

    // --- YENİ: BORÇ SORGULAMA ---
    public Map<String, Object> getDebtByPlate(String plate){
        ParkSpot spot= parkSpotRepository.findByCurrentPlateIgnoreCase(plate)
                .orElseThrow(()->new RuntimeException("Bu plakaya ait otoparkta aktif araç bulunamadı: "+plate));

        long minutes=Duration.between(spot.getEntryTime(),LocalDateTime.now()).toMinutes();
        double fee=calculateFee(spot.getCurrentType(),minutes);

        Map<String, Object> response = new HashMap<>();
        response.put("streetName",spot.getStreet().getDisplayName()); //Artık A-6 değil, "Atatürk Caddesi" yazacak
        response.put("plate",spot.getCurrentPlate());
        response.put("entryTime",spot.getEntryTime());
        response.put("durationMinutes",minutes);
        response.put("fee",fee);

        return response;
    }

    // --- MÜŞTERİ İÇİN: ÖDEME YAP VE ÇIKIŞI ONAYLA ---
    public ParkingRecord processPaymentAndCheckout(String plate){
        // DRY | LOGIC REUSE (DAHA ÖNCE YAZDIĞIMIZ ÇIKIŞ METODUNU ÇAĞIRIRIZ)
        return checkOutVehicle(plate);
    }

    // --- 9. ADMIN PANELİ GRAFİKLERİ VE ANALİZ ---

    //9.1 .Ciro Dağılımı
    public Map<String, Double> calculateIncomeByVehicle(){
        List<ParkingRecord> allRecords = parkingRecordRepository.findAll();
        double smallIncome=0;
        double largeIncome=0;

        for(ParkingRecord record : allRecords){
            if(record.getStatus() == ParkingStatus.PAID && record.getFee() != null){

                if(record.getVehicleType() == VehicleType.LARGE){
                    largeIncome+=record.getFee();
                    }else {
                        smallIncome+=record.getFee();
                    }
                }
            }
        return Map.of("Otomobil", smallIncome,"Kamyonet",largeIncome);
    }

    // 9.2. Operasyonel Başarı (Kaçan/Ödeyen Grafiği)
    public Map<String, Long> getParkingStatusCounts(){
        List<ParkingRecord> allRecord= parkingRecordRepository.findAll();
        long paidCount=0;
        long runawayCount=0;

        for (ParkingRecord record : allRecord) {
            if(record.getStatus()==ParkingStatus.PAID) paidCount++;
            if(record.getStatus()== ParkingStatus.RUNAWAY) runawayCount++;
        }
        return Map.of("Ödenen", paidCount,"Kaçan",runawayCount);
    }

    // 9.3 Son Arşiv Kayıtları (Excel Tablosu İçin Sınırsız Cadde)
    public List<ParkingRecord> getLast100Records(){
        //Sadece tek bir caddeyi değil tüm caddelerdeki son 100 işlemi getiriyor
        return parkingRecordRepository.findTop100ByOrderByEntryTimeDesc();
    }

}
