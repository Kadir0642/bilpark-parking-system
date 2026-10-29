package com.bilpark.backend.service;

import com.bilpark.backend.model.Role;
import com.bilpark.backend.model.Shift;
import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.model.User;
import com.bilpark.backend.model.Zone;
import com.bilpark.backend.repository.ShiftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ShiftServiceTest {

    private ShiftRepository repo;
    private ShiftService service;
    private final List<Shift> store = new ArrayList<>();
    private User officer;

    @BeforeEach
    void setUp() {
        repo = mock(ShiftRepository.class);
        service = new ShiftService(repo, java.time.Clock.system(com.bilpark.backend.config.ClockConfig.ZONE));
        store.clear();
        long[] ids = {0};
        when(repo.save(any(Shift.class))).thenAnswer(i -> {
            Shift s = i.getArgument(0);
            if (s.getId() == null) {
                s.setId(++ids[0]);
                store.add(s);
            }
            return s;
        });
        when(repo.findFirstByUserAndDeviceIdAndEndTimeIsNullOrderByStartTimeDesc(any(User.class), any(String.class)))
                .thenAnswer(i -> store.stream()
                        .filter(s -> s.getUser() == i.getArgument(0) && s.getDeviceId().equals(i.getArgument(1)) && s.isOpen())
                        .findFirst());
        when(repo.findByEndTimeIsNullOrderByStartTimeAsc())
                .thenAnswer(i -> store.stream().filter(Shift::isOpen).toList());
        Zone z = new Zone(StreetLocation.TEVFIK_BEY, 1, "Z1", "l", "r");
        officer = new User("off", "x", Role.OFFICER, z);
    }

    @Test
    void sameDeviceReusesOpenShift() {
        Shift a = service.openShift(officer, "devA");
        Shift b = service.openShift(officer, "devA");
        assertSame(a, b);
        assertEquals(1, store.size());
    }

    @Test
    void secondDeviceOpensSeparateShiftAndLeavesFirstOpen() {
        Shift a = service.openShift(officer, "devA");
        Shift b = service.openShift(officer, "devB");
        assertNotSame(a, b);
        assertTrue(a.isOpen());
        assertTrue(b.isOpen());
        assertEquals(2, service.listOpenShifts().size());
    }

    @Test
    void logoutClosesOnlyThatDevice() {
        Shift a = service.openShift(officer, "devA");
        Shift b = service.openShift(officer, "devB");
        service.closeShift(officer, "devA");
        assertFalse(a.isOpen());
        assertNotNull(a.getEndTime());
        assertTrue(b.isOpen());
        // second close is a no-op
        service.closeShift(officer, "devA");
        assertEquals(1, service.listOpenShifts().size());
    }

    @Test
    void staleShiftFromEarlierDayIsAutoClosed() {
        Shift old = service.openShift(officer, "devA");
        old.setStartTime(LocalDateTime.now(com.bilpark.backend.config.ClockConfig.ZONE).minusDays(1));
        Shift fresh = service.openShift(officer, "devA");
        assertNotSame(old, fresh);
        assertFalse(old.isOpen());
        assertTrue(old.isAutoClosed());
        assertTrue(fresh.isOpen());
        assertFalse(fresh.isAutoClosed());
    }

    @Test
    void adminGetsNoShift() {
        User admin = new User("admin", "x", Role.ADMIN, null);
        assertNull(service.openShift(admin, "devA"));
        verify(repo, never()).save(any(Shift.class));
    }
}
