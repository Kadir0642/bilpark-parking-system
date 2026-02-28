package com.bilpark.backend.config;

import com.bilpark.backend.model.ParkSpot;
import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.model.VehicleType;
import com.bilpark.backend.repository.ParkSpotRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

//BİZ EKLEDİKÇE PARK YERİ GELSE NASIL OLUR - SİSTEM AÇILIR AÇILMAZ PARK YERİ OLUŞTURMASIN -ZATEN PARK YERİ KAPASİTESİ BELLİ

@Configuration // Ayar sınıfı olduğunu söyler | "Seed Data" (Tohum Verisi) yüklemek
public class DataInitializer
{
    // CommandLineRunner: Uygulama başlar başlamaz çalışacak olan metod.
    @Bean 
    CommandLineRunner initDataBase(ParkSpotRepository repository) {
        return args -> {
            // Eğer veritabanu tamamen boşsa (0 araç varsa), test için 3 araç ekleriz.
            if(repository.count()==0){
                // Tevfik Bey caddesine küçük bir araç
                repository.save(new ParkSpot("34TEST01",StreetLocation.TEVFIK_BEY, VehicleType.SMALL, "Merkez", "Bilecik"));

                // Cumhuriyet caddesine büyük bir ticari araç
                repository.save(new ParkSpot("06BASKAN99", StreetLocation.CUMHURIYET, VehicleType.LARGE, "Merkez", "Bilecik"));

                // Ali Rıza Özkay caddesine senin adınla bir araç :)
                repository.save(new ParkSpot("11KADIR01", StreetLocation.ALI_RIZA_OZKAY, VehicleType.SMALL, "Merkez", "Bilecik"));

                System.out.println("✅ BİLGİ: BilPark 2.0 Dinamik Test Araçları sokağa park edildi!");
            }
        };
    }
}
