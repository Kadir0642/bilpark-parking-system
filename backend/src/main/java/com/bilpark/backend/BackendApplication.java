package com.bilpark.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.util.Locale;
import java.util.TimeZone;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BackendApplication {

	public static void main(String[] args) {
		useRootLocale();
		useTurkeyTimeZone();
		SpringApplication.run(BackendApplication.class, args);
	}

	// Under a Turkish default locale "I".toLowerCase() is "ı": Hibernate then renders MIN as "mın" and
	// case conversions turn plate letters into İ/ı. Must run before Spring/Hibernate start.
	static void useRootLocale() {
		Locale.setDefault(Locale.ROOT);
	}

	// Sunucu başlamadan önce saat dilimini Türkiye yapar (Spring/Hibernate ve JDBC sürücüsü açılmadan önce).
	static void useTurkeyTimeZone() {
		TimeZone.setDefault(TimeZone.getTimeZone("Europe/Istanbul"));
		LoggerFactory.getLogger(BackendApplication.class).info("Sistem saati Türkiye (UTC+3) olarak ayarlandı.");
	}

}
