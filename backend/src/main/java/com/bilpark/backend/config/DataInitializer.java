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
            if (repository.count() == 0) {

                // TEST VERİSİ
                //1.Park Yeri: A-1 (Küçük Araçlar için)
                ParkSpot spot1 = new ParkSpot("A-1", VehicleType.SMALL, false);
                repository.save(spot1); // Kaydet

                //2.Park Yeri: B-1 (Büyük araçlar için)
                ParkSpot spot2 = new ParkSpot("B-1", VehicleType.LARGE, false);
                repository.save(spot2); //Kaydet;

                System.out.println("TEST VERİLERİ VERİ TABANINA EKLENDİ");
            }
        };
    }
}
