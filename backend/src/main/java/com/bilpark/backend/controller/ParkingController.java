package com.bilpark.backend.controller;

// Adres Tarifleri
import com.bilpark.backend.model.ParkSpot;
import com.bilpark.backend.model.ParkingRecord;
import com.bilpark.backend.repository.ParkSpotRepository;
import com.bilpark.backend.service.ParkingService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

//işlemlerin başarılı veya başarısız olduğunu gösteren anlamlı HTTP durum kodları döndürmenizi sağlayarak
// API'lerimizi daha güvenilir ve kullanıcı dostu hale getirir .

import java.util.List;
import java.util.Map;


@RestController //Bu sınıf artık REST API (sistemlere JSON cevap verir)
@CrossOrigin("*") //Dünyadaki tüm cihazlar ve uygulamalar bana sorunsuz bağlanabili
@RequestMapping("/api/parking") // Bu sınıfa ulaşmak için adresimiz: localhost:8080/api/parking | /api konulmasi web sayfasi ile veri servisi yollari karısmasin diye konulur
public class ParkingController
{
    private final ParkingService parkingService;

    // Controller için service tanıtımı
    public ParkingController(ParkingService parkingService)
    {
        this.parkingService=parkingService; // istekleri iletebilmemiz için
    }

    //"GET" (Getir/Oku) isteği: URL: http://localhost:8080/api/parking/spots <- Tetikleyici adres
    //Tüm park yerlerini(spots) JSON listesi olarak döner  |  controller -> service -> repository -> service -> controller ->Müşteri(JSON olarak)
    @GetMapping("/spots")// <-- ENDPOINT (Tuş dış günyaya açar) | Tüm Park yerlerini service aracılığıyla getirir.
    public List<ParkSpot> getAllSpots()
    {
        return parkingService.getAllSpots();
    }

    //1. CHECK-IN (Giris Yap) | Eylem varsa POST kullanılır.
    // URL: http://localhost:8080/api/parking/check-in?spotId=1&licensePlate=34ABC123
    //@RequestParam: URL'in sonundaki soru işaretinden sonraki verileri okur.
    @PostMapping("/check-in") // GİRİŞ İŞLEMLERİ | PostMapping (Postala/Gönder) "Yeni park yeri eklerken" Sunucuda değişiklik, oluşturma, silme gibi işleri vardır(CRUD)
    public ParkSpot checkIn(@RequestParam Long spotId,@RequestParam String licensePlate,@RequestParam(value= "type",defaultValue= "SMALL") String vehicleType)
    {
        return parkingService.checkInVehicle(spotId,licensePlate,vehicleType); // Controller, gelen verileri servise verir. | Sonucta -> Araci iceri alir ve park yerinin son halini basar.
    }

    //2. CHECK-OUT (Cikis Yap)
    //URL: http://localhost:8080/api/parking/check-out?spotId=1 <- Tetikleyici adres
    @PostMapping("/check-out")// <-- ENDPOINT (Tuş dış dünyaya açar)
    public ParkingRecord checkOut(@RequestParam Long spotId) // Sadece ID bilgisini kullanarak bulup ,service tarafına iletiriz.
    {
        return parkingService.checkOutVehicle(spotId); // Arac cikisini ve ücret hesabini yapar. | Sonucta -> Fis/Fatura ücret bilgisi gösterilir.
    }

    //3. CİRO GÖRÜNTÜLEME (Bütün zamanlar)
    //URL: http://localhost:8080/api/parking/income <- Tetikleyici adres
    @GetMapping("/income")// <-- ENDPOINT (Tuş dış dünyaya açar)
    public Double getIncome(){
        return parkingService.getTotalIncome();// Controller -> Service -> Repository -> Service ->Controller->Kullanıcıya gösterilecek
    }

    //3.1. GÜNLÜK CİRO GÖRÜNTÜLEME
    //URL: http://localhost:8080/api/parking/income/daily <- Tetikleyici adres
    @GetMapping("/income/daily") // <-- ENDPOINT (Tuş dış dünyaya açar)
    public Double getDailyIncome(){
        return parkingService.getDailyIncome();
    }

    //3.2 HAFTALIK CİRO GÖRÜNTÜLEME
    @GetMapping("/income/weekly") // <-- ENDPOINT
    public Double getWeeklyIncome(){
        return parkingService.getWeeklyIncome();
    }

    //3.3 AYLIK CİRO GÖRÜNTÜLEME
    @GetMapping("income/monthly") //<-- ENDPOINT
    public Double getMonthlyIncome(){
        return parkingService.getMonthlyIncome();
    }

    //3.4 YILLIK CİRO GÖRÜNTÜLEME
    @GetMapping("/income/yearly") // <-- ENDPOINT
    public Double getYearlyIncome(){
        return parkingService.getYearlyIncome();
    }

    //4. ÇALIŞMA ALANI FİLTRELEME
    // http://localhost:8080/api/parking/filter <- Tetikleyici adres
    @GetMapping("/filter") // <-- ENDPOINT | @RequestParam metod çağrısında parametreninde gönderildiği notasyon | zorunlu parametreler | Cadde/sokak opsiyonel (required=false)
    public List<ParkSpot>filterSpots(@RequestParam String region,@RequestParam String neighborhood,@RequestParam(required=false) String street){
        return parkingService.getSpotsByLocation(region,neighborhood,street);
    }

    //5. --- GEÇMİŞ KAYITLAR --- | http://localhost:8080/api/parking/history
    @GetMapping("/history") // API UCU  <- ENDPOINT | Verileri getiricek/Okuma (GetMapping)
    public List<ParkingRecord>getHistory( //Controller URL'deki bu 3 bilgiyi alır, doğrudan Service kısmına verir | Service, Repositorye gidip en yeni 50 fişi alır | En son da JSON (metin) formatında sunar.
            @RequestParam String region,
            @RequestParam String neighborhood,
            @RequestParam String street){
        return parkingService.getHistoryByLocation(region,neighborhood,street);
    }

    // 6. --- MÜŞTERİ ÖDEME SİSTEMİ API'LERİ ---

    // 6.1 Borç Sorgulama (GET/SADECE OKUMA yapıyoruz) -> URL: /api/parking/debt?plate=42EGA123
    @GetMapping("/debt") // API UCU <- ENDPOINT
    public ResponseEntity<?> getDebt(@RequestParam String plate){ // ResponseEntity -> Hem veriyi hemde HTTP Status Code taşır. | <?> --> (Wildcard) İçinden her an her şey çıkabilir. İşlem başarılıysa "MAP" değilse "Hata Mesajı" içine koyarız. İki farklı tipi kabul etmesi için ? (Wildcard) joker var
        try{
            return ResponseEntity.ok(parkingService.getDebtByPlate(plate)); // ResponseEntitye.ok -> 200 başarılı kodu atıp müşteriye cevap verir.
        }catch(RuntimeException e){
            // Araç bulunamazsa 400 Bad Request döneriz.
            return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));
        }
    }

    // 6.2 Ödeme ve Çıkış İşlemi (POST) -> URL: /api/parking/pay?plate=42EGA432
    @PostMapping("/pay") // ENDPOINT
    public ResponseEntity<?> payAndCheckOut(@RequestParam String plate){
        try{
            ParkingRecord record = parkingService.processPaymentAndCheckout(plate); // Sistemin durumu değişiyor,para alınıp park yeri boşalıyor (occupied=false), yeni bir arşiv fişi oluşuyor (ParkingRecord) BU sebeple POST Kullanıyoruz.
            return ResponseEntity.ok(Map.of( // ResponseEntitye.ok -> 200 başarılı kodu atıp müşteriye cevap verir.
                    "message","Ödeme Başarılı! Çıkış yapıldı.",
                    "paidAmount", record.getFee(),
                    "record",record // Fişin tüm detayları (Eğer PDF olarak indirmek veya detayını görmek isterse diye).
            ));
        }catch(RuntimeException e){
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}