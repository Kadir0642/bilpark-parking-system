package com.bilpark.backend.service;

import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.model.Subscription;
import com.bilpark.backend.model.SubscriptionStatus;
import com.bilpark.backend.model.Tariff;
import com.bilpark.backend.model.VehicleType;
import com.bilpark.backend.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SubscriptionServiceTest {

    private SubscriptionRepository repo;
    private SubscriptionService service;
    private TariffService tariffService;

    @BeforeEach
    void setUp() {
        repo = mock(SubscriptionRepository.class);
        tariffService = mock(TariffService.class);
        Tariff t = new Tariff();
        t.setSubscriptionSmallMonthly(2600.0);
        t.setSubscriptionLargeMonthly(3100.0);
        when(tariffService.current()).thenReturn(t);
        service = new SubscriptionService(repo, tariffService);
        when(repo.saveAndFlush(any(Subscription.class))).thenAnswer(i -> i.getArguments()[0]);
    }

    @Test
    void createNormalizesPlate() {
        when(repo.findByPlateAndStatusForUpdate("34ABC123", SubscriptionStatus.ACTIVE)).thenReturn(List.of());
        Subscription s = service.createSubscription("34 abc-123", "LARGE", "o", "5");
        assertEquals("34ABC123", s.getLicensePlate());
        assertEquals(3100.0, s.getMonthlyFee());
    }

    @Test
    void smallSubscriptionUsesCurrentTariffPrice() {
        when(repo.findByPlateAndStatusForUpdate("34ABC123", SubscriptionStatus.ACTIVE)).thenReturn(List.of());
        Subscription s = service.createSubscription("34ABC123", "SMALL", "o", "5");
        assertEquals(2600.0, s.getMonthlyFee());
    }

    @Test
    void createWhenActiveExists_409() {
        Subscription active = new Subscription("34ABC123", VehicleType.SMALL, LocalDate.now(), "o", "5");
        when(repo.findByPlateAndStatusForUpdate("34ABC123", SubscriptionStatus.ACTIVE)).thenReturn(List.of(active));
        ApiException ex = assertThrows(ApiException.class, () -> service.createSubscription("34ABC123", "SMALL", "o", "5"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(ErrorCodes.SUBSCRIPTION_EXISTS, ex.getCode());
    }

    @Test
    void staleActiveRowIsExpiredThenNewRowCreated() {
        Subscription stale = new Subscription("34ABC123", VehicleType.SMALL, LocalDate.now().minusMonths(3), "o", "5");
        when(repo.findByPlateAndStatusForUpdate("34ABC123", SubscriptionStatus.ACTIVE)).thenReturn(List.of(stale));
        Subscription s = service.createSubscription("34ABC123", "SMALL", "o", "5");
        assertEquals(SubscriptionStatus.EXPIRED, stale.getStatus());
        assertEquals(SubscriptionStatus.ACTIVE, s.getStatus());
    }

    @Test
    void uniqueViolationMapsTo409() {
        when(repo.findByPlateAndStatusForUpdate("34ABC123", SubscriptionStatus.ACTIVE)).thenReturn(List.of());
        when(repo.saveAndFlush(any(Subscription.class))).thenThrow(new DataIntegrityViolationException("dup"));
        ApiException ex = assertThrows(ApiException.class, () -> service.createSubscription("34ABC123", "SMALL", "o", "5"));
        assertEquals(ErrorCodes.SUBSCRIPTION_EXISTS, ex.getCode());
    }

    @Test
    void cancelWithoutActive_404() {
        when(repo.findByPlateAndStatusForUpdate("34ABC123", SubscriptionStatus.ACTIVE)).thenReturn(List.of());
        ApiException ex = assertThrows(ApiException.class, () -> service.cancelSubscription("34 abc 123"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }
}
