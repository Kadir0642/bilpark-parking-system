package com.bilpark.backend.config;

import com.bilpark.backend.model.Role;
import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.model.User;
import com.bilpark.backend.model.Zone;
import com.bilpark.backend.repository.UserRepository;
import com.bilpark.backend.repository.ZoneRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class DataInitializer {

    @Bean
    public CommandLineRunner initData(UserRepository userRepository,
                                      ZoneRepository zoneRepository,
                                      PasswordEncoder passwordEncoder) {
        return args -> {

            // --- Seed Zones (only once) ---
            if (zoneRepository.count() == 0) {
                // Tevfik Bey Caddesi — 3 zones (3 officers)
                // Landmarks are placeholders — update via admin panel
                Zone zone1 = zoneRepository.save(new Zone(StreetLocation.TEVFIK_BEY, 1,
                        "Tevfik Bey - Bölüm 1",
                        "[Sol Landmark - Güncelleyin]",
                        "[Sağ Landmark - Güncelleyin]"));
                zoneRepository.save(new Zone(StreetLocation.TEVFIK_BEY, 2,
                        "Tevfik Bey - Bölüm 2",
                        "[Sol Landmark - Güncelleyin]",
                        "[Sağ Landmark - Güncelleyin]"));
                zoneRepository.save(new Zone(StreetLocation.TEVFIK_BEY, 3,
                        "Tevfik Bey - Bölüm 3",
                        "[Sol Landmark - Güncelleyin]",
                        "[Sağ Landmark - Güncelleyin]"));

                // Ali Rıza Özkay Caddesi — 2 zones (2 officers)
                zoneRepository.save(new Zone(StreetLocation.ALI_RIZA_OZKAY, 1,
                        "Ali Rıza Özkay - Bölüm 1",
                        "[Sol Landmark - Güncelleyin]",
                        "[Sağ Landmark - Güncelleyin]"));
                zoneRepository.save(new Zone(StreetLocation.ALI_RIZA_OZKAY, 2,
                        "Ali Rıza Özkay - Bölüm 2",
                        "[Sol Landmark - Güncelleyin]",
                        "[Sağ Landmark - Güncelleyin]"));

                // Cumhuriyet Caddesi — 1 zone (1 officer)
                zoneRepository.save(new Zone(StreetLocation.CUMHURIYET, 1,
                        "Cumhuriyet Caddesi - Bölüm 1",
                        "[Sol Landmark - Güncelleyin]",
                        "[Sağ Landmark - Güncelleyin]"));

                System.out.println("[DataInitializer] 6 working zones created.");
            }

            // --- Seed Users (only once) ---
            if (userRepository.count() == 0) {
                // Admin — no zone assignment
                userRepository.save(new User(
                        "admin",
                        passwordEncoder.encode("admin123"),
                        Role.ADMIN,
                        null
                ));

                // Sample officer — Tevfik Bey Caddesi Bölüm 1
                // (In production, assign officers via admin panel)
                Zone officerZone = zoneRepository.findByStreetAndZoneNumber(StreetLocation.TEVFIK_BEY, 1).orElse(null);
                userRepository.save(new User(
                        "tevfik",
                        passwordEncoder.encode("1234"),
                        Role.OFFICER,
                        officerZone
                ));

                System.out.println("[DataInitializer] Default users (admin, tevfik) created.");
            }
        };
    }
}
