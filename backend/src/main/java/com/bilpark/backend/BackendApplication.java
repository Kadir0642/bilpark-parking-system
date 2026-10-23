package com.bilpark.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import jakarta.annotation.PostConstruct;
import java.util.TimeZone;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

	// Sunucu başlar başlamaz saat dilimini Türkiye yapar.
	@PostConstruct
	public void init(){
		TimeZone.setDefault(TimeZone.getTimeZone("Europe/Istanbul"));
		LoggerFactory.getLogger(BackendApplication.class).info("Sistem saati Türkiye (UTC+3) olarak ayarlandı.");
	}

}
