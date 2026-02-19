package com.bilpark.backend.config;

import com.bilpark.backend.model.ParkSpot;
import com.bilpark.backend.model.VehicleType;
import com.bilpark.backend.repository.ParkSpotRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// ÖNEMLİ: Normalde veritabanları bomboş doğar. Sen uygulamayı her çalıştırdığında gidip elle 40 tane park yeri eklemek zorunda kalma diye, bu sınıf uygulama ayağa kalktığı ilk saniye devreye girer ve otoparkın çizgilerini (verilerini) çizer.
// BUNUN YERİNE BİZ EKLEDİKÇE PARK YERİ GELSE NASIL OLUR - SİSTEM AÇILIR AÇILMAZ PARK YERİ OLUŞTURMASIN -ZATEN PARK YERİ KAPASİTESİ BELLİ

@Configuration // Ayar sınıfı olduğunu söyler | "Seed Data" (Tohum Verisi) yüklemek
public class DataInitializer
{
    // CommandLineRunner: Uygulama başlar başlamaz çalışacak olan metod.
    @Bean 
    CommandLineRunner initDataBase(ParkSpotRepository repository) {
        return args -> {
            //Veritabanı zaten doluysa tekrar ekleme yapmayız(çakışmayı önlemek için)
            if (repository.count() == 0){ //"Idempotency" (Tekrarlanabilirlik)
                System.out.println("--- KROKİ VERİLERİ YÜKLENİYOR ---");

                //SOL KALDIRIM (A Blok - Öncü Dürüm Tarafı ) -20 Adet park yeri
                for(int i=1;i<=20;i++){ // <-- DÖNGÜ BAŞLADI
                    ParkSpot spot =new ParkSpot("A-"+i,VehicleType.SMALL,false,"Bilecik","Atatürk Mah","Merkez");
                repository.save(spot); // Kaydet
                } // <-- DÖNGÜ BİTTİ (Spot öldü!)

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
