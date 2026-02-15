package com.bilpark.backend.controller;

// Adres Tarifleri
import com.bilpark.backend.model.ParkSpot;
import com.bilpark.backend.model.ParkingRecord;
import com.bilpark.backend.service.ParkingService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController //Bu sınıf artık REST API (sistemlere JSON cevap verir)
@RequestMapping("/api/parking") // Bu sınıfa ulaşmak için adresimiz: localhost:8080/api/parking | /api konulmasi web sayfasi ile veri servisi yollari karısmasin diye konulur
public class ParkingController
{
    private final ParkingService parkingService;

    // Controller için service tanıtımı
    public ParkingController(ParkingService parkingService)
    {
        this.parkingService=parkingService; // istekleri iletebilmemiz için
    }

    //"GET" (Getir/Oku) isteği: URL: http://localhost:8080/api/parking/spots
    //Tüm park yerlerini(spots) JSON listesi olarak döner  |  controller -> service -> repository -> service -> controller ->Müşteri(JSON olarak)
    @GetMapping("/spots")
    public List<ParkSpot> getAllSpots()
    {
        return parkingService.getAllSpots();
    }

    //1. CHECK-IN (Giris Yap) | Eylem varsa POST kullanılır.
    // URL: http://localhost:8080/api/parking/check-in?spotId=1&licensePlate=34ABC123
    //@RequestParam: URL'in sonundaki soru işaretinden sonraki verileri okur.
    @PostMapping("/check-in") // GİRİŞ İŞLEMLERİ | PostMapping (Postala/Gönder) "Yeni park yeri eklerken" Sunucuda değişiklik, oluşturma, silme gibi işleri vardır(CRUD)
    public ParkSpot checkIn(@RequestParam Long spotId,@RequestParam String licensePlate)
    {
        return parkingService.checkInVehicle(spotId,licensePlate); // Controller, gelen verileri servise verir. | Sonucta -> Araci iceri alir ve park yerinin son halini basar.
    }

    //2. CHECK-OUT (Cikis Yap)
    //URL: http://localhost:8080/api/parking/check-out?spotId=1
    @PostMapping("/check-out")
    public ParkingRecord checkOut(@RequestParam Long spotId) // Sadece ID bilgisini kullanarak bulup ,service tarafına iletiriz.
    {
        return parkingService.checkOutVehicle(spotId); // Arac cikisini ve ücret hesabini yapar. | Sonucta -> Fis/Fatura ücret bilgisi gösterilir.
    }

    //3. CİRO GÖRÜNTÜLEME
    //URL: http://localhost:8080/api/parking/income
    @GetMapping("/income")
    public Double getIncome(){
        return parkingService.getTotalIncome();// Controller -> Service -> Repository -> Service ->Controller->Kullanıcıya gösterilecek
    }
}
