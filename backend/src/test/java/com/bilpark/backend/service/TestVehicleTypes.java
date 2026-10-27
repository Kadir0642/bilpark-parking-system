package com.bilpark.backend.service;

import com.bilpark.backend.model.Tariff;
import com.bilpark.backend.model.TariffVehiclePrice;
import com.bilpark.backend.model.VehicleType;
import com.bilpark.backend.repository.VehicleTypeRepository;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Helpers for unit tests that need a VehicleTypeService without a database. */
final class TestVehicleTypes {
    private TestVehicleTypes() {}

    static VehicleTypeService service() {
        List<VehicleType> rows = new ArrayList<>(List.of(
                new VehicleType("SMALL", "Otomobil", "car", 10, true),
                new VehicleType("LARGE", "Kamyonet", "truck", 20, true),
                new VehicleType("MOTORCYCLE", "Motosiklet", "motorcycle", 30, false)));
        VehicleTypeRepository repo = mock(VehicleTypeRepository.class);
        when(repo.findAll()).thenReturn(rows);
        when(repo.count()).thenReturn((long) rows.size());
        when(repo.save(any(VehicleType.class))).thenAnswer(i -> i.getArgument(0));
        VehicleTypeService s = new VehicleTypeService(repo, mock(PlatformTransactionManager.class));
        s.reload();
        return s;
    }

    static void price(Tariff t, String code, double first, double extra, double sub) {
        t.getPrices().add(new TariffVehiclePrice(t, code, true, first, extra, sub));
    }
}
