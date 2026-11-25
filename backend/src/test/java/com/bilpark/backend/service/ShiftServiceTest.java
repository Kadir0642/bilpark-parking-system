package com.bilpark.backend.service;

import com.bilpark.backend.audit.AuditService;
import com.bilpark.backend.model.AuditAction;
import com.bilpark.backend.model.Role;
import com.bilpark.backend.model.Shift;
import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.model.User;
import com.bilpark.backend.model.Zone;
import com.bilpark.backend.repository.ShiftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
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
    private AuditService audit;
    private final List<Shift> store = new ArrayList<>();
    private User officer;

    @BeforeEach
    void setUp() {
        repo = mock(ShiftRepository.class);
        audit = mock(AuditService.class);
        service = new ShiftService(repo, Clock.system(com.bilpark.backend.config.ClockConfig.ZONE), audit);
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
        when(repo.findByUserAndEndTimeIsNull(any(User.class)))
                .thenAnswer(i -> store.stream().filter(s -> s.getUser() == i.getArgument(0) && s.isOpen()).toList());
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
    void loginOnOtherDeviceClosesOldShiftAndKeepsSingleOpenShift() {
        Shift a = service.openShift(officer, "devA");
        Shift b = service.openShift(officer, "devB");
        assertNotSame(a, b);
        assertFalse(a.isOpen());
        assertNotNull(a.getEndTime());
        assertTrue(a.isAutoClosed());
        assertTrue(b.isOpen());
        assertFalse(b.isAutoClosed());
        assertEquals(List.of(b), service.listOpenShifts());
        verify(audit, times(1)).logSystem(eq(AuditAction.SHIFT_AUTO_CLOSED), eq("Shift"), eq(a.getId()),
                contains("başka cihazdan giriş"), any(), any());
    }

    @Test
    void sameDeviceSameDayWritesNoAudit() {
        service.openShift(officer, "devA");
        service.openShift(officer, "devA");
        verifyNoInteractions(audit);
    }

    @Test
    void otherUsersShiftsAreNotTouched() {
        User other = new User("off2", "x", Role.OFFICER, officer.getAssignedZone());
        Shift o = service.openShift(other, "devX");
        service.openShift(officer, "devA");
        service.openShift(officer, "devB");
        assertTrue(o.isOpen());
        assertEquals(2, service.listOpenShifts().size());
    }

    private ShiftService serviceAt(LocalDateTime at) {
        Clock fixed = Clock.fixed(ZonedDateTime.of(at, com.bilpark.backend.config.ClockConfig.ZONE).toInstant(),
                com.bilpark.backend.config.ClockConfig.ZONE);
        return new ShiftService(repo, fixed, audit);
    }

    @Test
    void nightlyJobClosesForgottenShiftsAtTenPm() {
        LocalDateTime day = LocalDateTime.of(2026, 11, 25, 10, 0);
        Shift s1 = serviceAt(day).openShift(officer, "devA");
        User o2 = new User("off2", "x", Role.OFFICER, officer.getAssignedZone());
        Shift s2 = serviceAt(day.plusHours(3)).openShift(o2, "devB");

        int n = serviceAt(LocalDateTime.of(2026, 11, 25, 22, 30)).closeForgottenShifts();

        assertEquals(2, n);
        for (Shift s : List.of(s1, s2)) {
            assertEquals(LocalDateTime.of(2026, 11, 25, 22, 0), s.getEndTime());
            assertTrue(s.isAutoClosed());
        }
        assertTrue(service.listOpenShifts().isEmpty());
        verify(audit, times(2)).logSystem(eq(AuditAction.SHIFT_AUTO_CLOSED), eq("Shift"), any(), contains("gün sonu"), any(), any());
    }

    @Test
    void nightlyJobClosesShiftStartedAfterTenPmAtNow() {
        Shift late = serviceAt(LocalDateTime.of(2026, 11, 25, 22, 10)).openShift(officer, "devA");
        serviceAt(LocalDateTime.of(2026, 11, 25, 22, 30)).closeForgottenShifts();
        assertEquals(LocalDateTime.of(2026, 11, 25, 22, 30), late.getEndTime());
        assertTrue(late.isAutoClosed());
    }

    @Test
    void nightlyJobIgnoresAlreadyClosedShiftsAndIsNoOpWhenNoneOpen() {
        Shift s = serviceAt(LocalDateTime.of(2026, 11, 25, 9, 0)).openShift(officer, "devA");
        service.closeShift(officer, "devA");
        LocalDateTime end = s.getEndTime();
        assertEquals(0, serviceAt(LocalDateTime.of(2026, 11, 25, 22, 30)).closeForgottenShifts());
        assertEquals(end, s.getEndTime());
        assertFalse(s.isAutoClosed());
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
