package com.bilpark.backend.config;

import com.bilpark.backend.model.ParkSpot;
import com.bilpark.backend.model.VehicleType;
import com.bilpark.backend.repository.ParkSpotRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration // Ayar sınıfı olduğunu söyler
public class DataInitializer
{
    // CommandLineRunner: Uygulama başlar başlamaz çalışacak olan metod.
    @Bean 
    CommandLineRunner initDataBase(ParkSpotRepository repository) {
        return args -> {
            //Veritabanı zaten doluysa tekrar ekleme yapmayız(çakışmayı önlemek için)
            if (repository.count() == 0){
                System.out.println("--- KROKİ VERİLERİ YÜKLENİYOR ---");

                //SOL KALDIRIM (A Blok - Öncü Dürüm Tarafı ) -20 Adet park yeri
                for(int i=1;i<=20;i++){
                    ParkSpot spot =new ParkSpot("A-"+i,VehicleType.SMALL,false,"Bilecik","Atatürk Mah","Merkez");
                repository.save(spot); // Kaydet
                }

                // SAĞ KALDIRIM (B Blok )
                for(int i=1;i<=20;i++){
                    ParkSpot spot = new ParkSpot("B-"+i, VehicleType.SMALL, false,"Bilecik","Atatürk Mah","Merkez");
                    repository.save(spot); // Kaydet
                }
                System.out.println("--- KROKİ HAZIR ---");
            }
        };
    }
}
