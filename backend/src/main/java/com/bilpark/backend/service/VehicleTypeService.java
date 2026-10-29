package com.bilpark.backend.service;

import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.model.Tariff;
import com.bilpark.backend.model.TariffVehiclePrice;
import com.bilpark.backend.model.VehicleType;
import com.bilpark.backend.model.VehicleTypes;
import com.bilpark.backend.repository.VehicleTypeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Vehicle types, cached in memory (no periodic DB polling, so a serverless DB can sleep).
 * The cache is loaded on first use / at startup and refreshed only by syncActiveFrom.
 */
@Service
public class VehicleTypeService {

    /** Which types (display names) a tariff activation turned on / off. */
    public record Change(List<String> enabled, List<String> disabled) {
        public boolean isEmpty() { return enabled.isEmpty() && disabled.isEmpty(); }
    }

    private final VehicleTypeRepository repository;
    private final TransactionTemplate tx;

    private volatile List<VehicleType> cache; // sorted by sortOrder

    public VehicleTypeService(VehicleTypeRepository repository, PlatformTransactionManager txManager) {
        this.repository = repository;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Seeds the three built-in rows if the table is empty (H2 tests run without Flyway), then loads the cache. */
    public synchronized void ensureSeeded() {
        tx.executeWithoutResult(s -> {
            if (repository.count() == 0) {
                repository.saveAll(List.of(
                        new VehicleType(VehicleTypes.SMALL, "Otomobil", "car", 10, true),
                        new VehicleType(VehicleTypes.LARGE, "Kamyonet", "truck", 20, true),
                        new VehicleType(VehicleTypes.MOTORCYCLE, "Motosiklet", "motorcycle", 30, false)));
            }
        });
        reload();
    }

    public synchronized void reload() {
        List<VehicleType> all = new ArrayList<>(repository.findAll());
        all.sort(Comparator.comparing(VehicleType::getSortOrder).thenComparing(VehicleType::getCode));
        cache = List.copyOf(all);
    }

    private List<VehicleType> cached() {
        List<VehicleType> c = cache;
        if (c == null) {
            ensureSeeded();
            c = cache;
        }
        return c;
    }

    /** All types, sorted. */
    public List<VehicleType> all() {
        return cached();
    }

    public List<VehicleType> activeTypes() {
        return cached().stream().filter(VehicleType::isActive).toList();
    }

    public VehicleType find(String code) {
        if (code == null) return null;
        for (VehicleType t : cached()) {
            if (t.getCode().equals(code)) return t;
        }
        return null;
    }

    /** Display name of a code; null code (old record) counts as SMALL; unknown codes show the code itself. */
    public String displayName(String code) {
        VehicleType t = find(code == null ? VehicleTypes.SMALL : code);
        if (t != null) return t.getDisplayName();
        return code == null ? "Otomobil" : code;
    }

    /** Returns the active type or throws 400 UNKNOWN_VEHICLE_TYPE / VEHICLE_TYPE_INACTIVE. */
    public VehicleType requireActive(String code) {
        VehicleType t = find(code);
        if (t == null) {
            throw ApiException.badRequest(ErrorCodes.UNKNOWN_VEHICLE_TYPE, "Bilinmeyen araç tipi: " + code);
        }
        if (!t.isActive()) {
            throw ApiException.badRequest(ErrorCodes.VEHICLE_TYPE_INACTIVE, t.getDisplayName()
                    + " şu an tarifede etkin değil. Yönetici tarife sayfasından etkinleştirebilir.");
        }
        return t;
    }

    /** Sets active = the tariff's enabled set. Returns the display names that were enabled / disabled. */
    public synchronized Change syncActiveFrom(Tariff tariff) {
        Set<String> enabled = tariff.getPrices().stream().filter(TariffVehiclePrice::isEnabled)
                .map(TariffVehiclePrice::getVehicleType).collect(Collectors.toSet());
        List<String> on = new ArrayList<>();
        List<String> off = new ArrayList<>();
        tx.executeWithoutResult(s -> {
            for (VehicleType t : repository.findAll()) {
                boolean want = enabled.contains(t.getCode());
                if (t.isActive() != want) {
                    t.setActive(want);
                    repository.save(t);
                    (want ? on : off).add(t.getDisplayName());
                }
            }
        });
        reload();
        return new Change(on, off);
    }

    /** API form of a type. */
    public static Map<String, Object> toMap(VehicleType t, boolean withActive) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", t.getCode());
        m.put("displayName", t.getDisplayName());
        m.put("iconKey", t.getIconKey());
        m.put("sortOrder", t.getSortOrder());
        if (withActive) m.put("active", t.isActive());
        return m;
    }

    public List<Map<String, Object>> activePublicList() {
        return activeTypes().stream().map(t -> toMap(t, false)).toList();
    }
}
