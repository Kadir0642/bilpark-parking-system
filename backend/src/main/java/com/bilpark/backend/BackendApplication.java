package com.bilpark.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import jakarta.annotation.PostConstruct;
import java.util.TimeZone;

@SpringBootApplication
public class BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

	// Sunucu başlar başlamaz saat dilimini Türkiye yapar.
	@PostConstruct
	public void init(){
		TimeZone.setDefault(TimeZone.getTimeZone("Europe/Istanbul"));
		System.out.println("Sistem Saati Türkiye (UTC+3) Olarak Ayarlandı !");
	}

}
