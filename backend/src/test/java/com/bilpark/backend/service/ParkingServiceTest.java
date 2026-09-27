package com.bilpark.backend.service;

import com.bilpark.backend.model.ParkSpot;
import com.bilpark.backend.model.ParkingRecord;
import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.model.VehicleType;
import com.bilpark.backend.model.ParkingStatus;
import com.bilpark.backend.repository.ParkSpotRepository;
import com.bilpark.backend.repository.ParkingRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class ParkingServiceTest {

    @Mock
    private ParkSpotRepository parkSpotRepository;

    @Mock
    private ParkingRecordRepository parkingRecordRepository;

    @InjectMocks
    private ParkingService parkingService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testCheckInVehicle_Success() {
        // Arrange
        String licensePlate = "34ABC123";
        String vehicleType = "SMALL";
        StreetLocation street = StreetLocation.TEVFIK_BEY;
        String side = "LEFT";

        when(parkSpotRepository.findByCurrentPlateIgnoreCase(licensePlate)).thenReturn(Optional.empty());
        when(parkSpotRepository.save(any(ParkSpot.class))).thenAnswer(i -> i.getArguments()[0]);

        // Act
        ParkSpot spot = parkingService.checkInVehicle(licensePlate, vehicleType, street, side, null);

        // Assert
        assertNotNull(spot);
        assertEquals(licensePlate, spot.getCurrentPlate());
        assertEquals(VehicleType.SMALL, spot.getCurrentType());
        assertEquals(StreetLocation.TEVFIK_BEY, spot.getStreet());
        assertEquals("LEFT", spot.getSide());

        verify(parkSpotRepository, times(1)).save(any(ParkSpot.class));
    }

    @Test
    void testCheckInVehicle_FailIfAlreadyParked() {
        // Arrange
        String licensePlate = "34ABC123";
        ParkSpot existingSpot = new ParkSpot(licensePlate, StreetLocation.TEVFIK_BEY, VehicleType.SMALL, "Merkez", "Bilecik", "LEFT", null);

        when(parkSpotRepository.findByCurrentPlateIgnoreCase(licensePlate)).thenReturn(Optional.of(existingSpot));

        // Act & Assert
        Exception exception = assertThrows(RuntimeException.class, () -> {
            parkingService.checkInVehicle(licensePlate, "SMALL", StreetLocation.TEVFIK_BEY, "LEFT", null);
        });

        assertTrue(exception.getMessage().contains("zaten otoparkta kayıtlı"));
        verify(parkSpotRepository, never()).save(any(ParkSpot.class));
    }

    @Test
    void testCheckOutVehicle_Success() {
        // Arrange
        String plate = "34XYZ789";
        ParkSpot spot = new ParkSpot(plate, StreetLocation.CUMHURIYET, VehicleType.LARGE, "Merkez", "Bilecik", "RIGHT", null);
        // Araç 1.5 saat önce girmiş olsun (1 saat = 50 TL, yarım saat = 30 TL ekstra) Toplam = 80 TL
        spot.setEntryTime(LocalDateTime.now().minusMinutes(90)); 

        when(parkSpotRepository.findByCurrentPlateIgnoreCase(plate)).thenReturn(Optional.of(spot));
        when(parkingRecordRepository.save(any(ParkingRecord.class))).thenAnswer(i -> i.getArguments()[0]);

        // Act
        ParkingRecord record = parkingService.checkOutVehicle(plate);

        // Assert
        assertNotNull(record);
        assertEquals(plate, record.getLicensePlate());
        assertEquals(ParkingStatus.PAID, record.getStatus());
        assertEquals(VehicleType.LARGE, record.getVehicleType());
        assertEquals(80.0, record.getFee()); // 50 (base) + 30 (extra)

        verify(parkSpotRepository, times(1)).delete(spot);
        verify(parkingRecordRepository, times(1)).save(any(ParkingRecord.class));
    }

    @Test
    void testCheckOutVehicle_FailIfNotFound() {
        // Arrange
        String plate = "NOTFOUND";
        when(parkSpotRepository.findByCurrentPlateIgnoreCase(plate)).thenReturn(Optional.empty());

        // Act & Assert
        Exception exception = assertThrows(RuntimeException.class, () -> {
            parkingService.checkOutVehicle(plate);
        });

        assertTrue(exception.getMessage().contains("bulunamadı"));
    }
}
