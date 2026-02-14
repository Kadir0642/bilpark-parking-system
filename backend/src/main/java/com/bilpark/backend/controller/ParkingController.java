package com.bilpark.backend.controller;

// Adres Tarifleri
import com.bilpark.backend.model.ParkSpot;
import com.bilpark.backend.service.ParkingService;
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

    //"GET" (Getir/Oku) isteği: http://localhost:8080/api/parking/spots
    //Tüm park yerlerini(spots) JSON olarak döner  |  controller -> service -> repository -> service -> controller ->Müşteri(JSON olarak)
    @GetMapping("/spots")
    public List<ParkSpot> getAllSpots()
    {
        return parkingService.getAllSpots();
    }
    //PostMapping olsaydı "Yeni park yeri ekle" denirdi.
}
