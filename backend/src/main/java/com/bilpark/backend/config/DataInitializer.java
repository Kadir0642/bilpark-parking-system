package com.bilpark.backend.config;

import com.bilpark.backend.model.ParkSpot;
import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.model.VehicleType;
import com.bilpark.backend.repository.ParkSpotRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// BİZ EKLEDİKÇE PARK YERİ GELSE NASIL OLUR - SİSTEM AÇILIR AÇILMAZ PARK YERİ OLUŞTURMASIN -ZATEN PARK YERİ KAPASİTESİ BELLİ

@Configuration // Ayar sınıfı olduğunu söyler | "Seed Data" (Tohum Verisi) yüklemek
public class DataInitializer{}
