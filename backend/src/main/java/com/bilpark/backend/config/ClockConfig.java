package com.bilpark.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/** Single time source (Europe/Istanbul) so fee and tariff logic can be tested with a fixed clock. */
@Configuration
public class ClockConfig {

    public static final ZoneId ZONE = ZoneId.of("Europe/Istanbul");

    @Bean
    public Clock clock() {
        return Clock.system(ZONE);
    }
}
