package com.bilpark.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.bilpark.backend.audit.AuditService;
import com.bilpark.backend.model.AuditAction;
import com.bilpark.backend.model.Role;
import com.bilpark.backend.model.Street;
import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.model.User;
import com.bilpark.backend.model.Zone;
import com.bilpark.backend.repository.StreetRepository;
import com.bilpark.backend.repository.UserRepository;
import com.bilpark.backend.repository.ZoneRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Bean
    public CommandLineRunner initData(UserRepository userRepository,
                                      ZoneRepository zoneRepository,
                                      StreetRepository streetRepository,
                                      PasswordEncoder passwordEncoder,
                                      @Value("${bilpark.seed.admin-password:}") String adminPassword,
                                      @Value("${bilpark.seed.officer-password:}") String officerPassword,
                                      @Value("${bilpark.seed.developer-username:gelistirici}") String developerUsername,
                                      @Value("${bilpark.seed.developer-password:}") String developerPassword,
                                      AuditService auditService,
                                      java.time.Clock clock) {
        return args -> {

            // --- Seed Streets (only once; Flyway V3 seeds them on PostgreSQL, this covers H2 tests) ---
            if (streetRepository.count() == 0) {
                for (StreetLocation loc : StreetLocation.values()) {
                    streetRepository.save(new Street(loc.name(), loc.getDisplayName(), loc.ordinal() + 1));
                }
                log.info("Streets created.");
            }

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

                log.info("6 working zones created.");
            }

            // --- Seed Users (only once) ---
            if (userRepository.count() == 0) {
                if (adminPassword == null || adminPassword.isBlank()) {
                    throw new IllegalStateException("ADMIN_PASSWORD env var is required to seed the admin user.");
                }
                if (officerPassword == null || officerPassword.isBlank()) {
                    throw new IllegalStateException("OFFICER_PASSWORD env var is required to seed the officer user.");
                }
                // Admin — no zone assignment
                userRepository.save(new User(
                        "admin",
                        passwordEncoder.encode(adminPassword),
                        Role.ADMIN,
                        null
                ));

                // Sample officer — Tevfik Bey Caddesi Bölüm 1
                // (In production, assign officers via admin panel)
                Zone officerZone = zoneRepository.findByStreetAndZoneNumber(StreetLocation.TEVFIK_BEY, 1).orElse(null);
                userRepository.save(new User(
                        "tevfik",
                        passwordEncoder.encode(officerPassword),
                        Role.OFFICER,
                        officerZone
                ));

                log.info("Default users (admin, tevfik) created.");
            }

            // --- Seed the developer (maintenance) account once, only when DEVELOPER_PASSWORD is set ---
            // Its existence and powers are written in the contract annex; the admin sees it in the user list.
            if (developerPassword != null && !developerPassword.isBlank() && !userRepository.existsByRole(Role.DEVELOPER)) {
                String name = developerUsername == null || developerUsername.isBlank() ? "gelistirici" : developerUsername.trim();
                if (userRepository.existsByUsername(name)) {
                    log.warn("Developer account not created: username '{}' is taken.", name);
                } else {
                    User dev = new User(name, passwordEncoder.encode(developerPassword), Role.DEVELOPER, null);
                    dev.setFullName("Sistem geliştiricisi (bakım)");
                    dev.setCreatedAt(java.time.LocalDateTime.now(clock));
                    userRepository.save(dev);
                    auditService.logSystem(AuditAction.DEVELOPER_ACCOUNT_CREATED, "User", dev.getId(),
                            "Geliştirici (bakım) hesabı oluşturuldu: " + name, null,
                            java.util.Map.of("username", name, "role", Role.DEVELOPER.name()));
                    log.info("Developer account '{}' created.", name);
                }
            }
        };
    }
}
