package com.bilpark.backend.service;

import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.dto.TariffRequest;
import com.bilpark.backend.model.Role;
import com.bilpark.backend.model.SyncEventType;
import com.bilpark.backend.model.Tariff;
import com.bilpark.backend.model.TariffStatus;
import com.bilpark.backend.model.User;
import com.bilpark.backend.repository.TariffRepository;
import com.bilpark.backend.sync.ChangePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TariffServiceTest {

    private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");

    /** Clock the test can move forward. */
    static class MutableClock extends Clock {
        private Instant now;
        MutableClock(LocalDateTime at) { this.now = at.atZone(ISTANBUL).toInstant(); }
        void set(LocalDateTime at) { this.now = at.atZone(ISTANBUL).toInstant(); }
        @Override public ZoneId getZone() { return ISTANBUL; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final Map<Long, Tariff> store = new LinkedHashMap<>();
    private long seq = 0;
    private TariffRepository repo;
    private ChangePublisher publisher;
    private TaskScheduler scheduler;
    private ScheduledFuture<?> future;
    private MutableClock clock;
    private PasswordEncoder encoder;
    private TariffService service;
    private User admin;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(LocalDateTime.of(2026, 10, 21, 14, 0));
        encoder = new BCryptPasswordEncoder(4);
        admin = new User("admin", encoder.encode("secret"), Role.ADMIN, null);
        admin.setId(1L);

        repo = mock(TariffRepository.class);
        when(repo.save(any(Tariff.class))).thenAnswer(i -> persist(i.getArgument(0)));
        when(repo.saveAndFlush(any(Tariff.class))).thenAnswer(i -> persist(i.getArgument(0)));
        when(repo.findById(any(Long.class))).thenAnswer(i -> Optional.ofNullable(store.get(i.<Long>getArgument(0))));
        when(repo.findFirstByStatus(any(TariffStatus.class))).thenAnswer(i -> store.values().stream()
                .filter(t -> t.getStatus() == i.<TariffStatus>getArgument(0)).findFirst());
        when(repo.count()).thenAnswer(i -> (long) store.size());
        when(repo.findAllByOrderByIdDesc()).thenAnswer(i -> {
            List<Tariff> l = new ArrayList<>(store.values());
            java.util.Collections.reverse(l);
            return l;
        });

        publisher = mock(ChangePublisher.class);
        scheduler = mock(TaskScheduler.class);
        future = mock(ScheduledFuture.class);
        doReturn(future).when(scheduler).schedule(any(Runnable.class), any(Instant.class));
        service = new TariffService(repo, publisher, clock, encoder, scheduler, mock(PlatformTransactionManager.class));
        service.init(); // seeds the default ACTIVE tariff (empty table)
    }

    private Tariff persist(Tariff t) {
        if (t.getId() == null) t.setId(++seq);
        store.put(t.getId(), t);
        return t;
    }

    private TariffRequest request() {
        TariffRequest r = new TariffRequest();
        r.setSmallFirstHour(30.0);
        r.setSmallExtraHour(20.0);
        r.setLargeFirstHour(60.0);
        r.setLargeExtraHour(40.0);
        r.setFreeMinutes(10);
        r.setPaidStart("09:00");
        r.setPaidEnd("20:00");
        r.setSubscriptionSmallMonthly(2800.0);
        r.setSubscriptionLargeMonthly(3300.0);
        r.setNote("test");
        return r;
    }

    private ApiException thrown(Runnable r) {
        return assertThrows(ApiException.class, r::run);
    }

    private Long draftId() {
        return (Long) service.createDraft(request(), admin).get("id");
    }

    @Test
    void startupSeedsDefaultActiveTariff() {
        assertEquals(1, store.size());
        Tariff t = service.current();
        assertEquals(TariffStatus.ACTIVE, t.getStatus());
        assertEquals(25.0, t.getSmallFirstHour());
        assertEquals("system", t.getCreatedByUsername());
    }

    @Test
    void draftCreatedAndUpdated() {
        Map<String, Object> d = service.createDraft(request(), admin);
        assertEquals("DRAFT", d.get("status"));
        assertEquals("admin", d.get("createdByUsername"));
        TariffRequest r = request();
        r.setSmallFirstHour(35.0);
        Map<String, Object> u = service.updateDraft((Long) d.get("id"), r);
        @SuppressWarnings("unchecked")
        Map<String, Object> small = (Map<String, Object>) u.get("small");
        assertEquals(35.0, small.get("firstHour"));
    }

    @Test
    void draftValidation() {
        TariffRequest r = request();
        r.setSmallFirstHour(0.0);
        assertEquals(ErrorCodes.INVALID_TARIFF, thrown(() -> service.createDraft(r, admin)).getCode());

        TariffRequest neg = request();
        neg.setLargeExtraHour(-1.0);
        assertEquals(HttpStatus.BAD_REQUEST, thrown(() -> service.createDraft(neg, admin)).getStatus());

        TariffRequest free = request();
        free.setFreeMinutes(121);
        assertEquals(ErrorCodes.INVALID_TARIFF, thrown(() -> service.createDraft(free, admin)).getCode());

        TariffRequest window = request();
        window.setPaidStart("20:00");
        window.setPaidEnd("09:00");
        assertEquals(ErrorCodes.INVALID_TARIFF, thrown(() -> service.createDraft(window, admin)).getCode());

        TariffRequest badTime = request();
        badTime.setPaidStart("8");
        assertEquals(ErrorCodes.INVALID_TARIFF, thrown(() -> service.createDraft(badTime, admin)).getCode());

        TariffRequest sub = request();
        sub.setSubscriptionLargeMonthly(0.0);
        assertEquals(ErrorCodes.INVALID_TARIFF, thrown(() -> service.createDraft(sub, admin)).getCode());

        TariffRequest missing = request();
        missing.setFreeMinutes(null);
        assertEquals(ErrorCodes.INVALID_TARIFF, thrown(() -> service.createDraft(missing, admin)).getCode());
    }

    @Test
    void midnightEndIsAccepted() {
        TariffRequest r = request();
        r.setPaidStart("00:00");
        r.setPaidEnd("00:00");
        assertEquals("00:00", service.createDraft(r, admin).get("paidEnd"));
    }

    @Test
    void onlyDraftCanBeEdited() {
        Long id = draftId();
        service.schedule(id, LocalDateTime.of(2026, 11, 1, 0, 0), "secret", admin);
        ApiException ex = thrown(() -> service.updateDraft(id, request()));
        assertEquals(ErrorCodes.TARIFF_STATE, ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    void scheduleWithWrongPassword_403() {
        Long id = draftId();
        ApiException ex = thrown(() -> service.schedule(id, LocalDateTime.of(2026, 11, 1, 0, 0), "wrong", admin));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals(ErrorCodes.PASSWORD_MISMATCH, ex.getCode());
        assertEquals(TariffStatus.DRAFT, store.get(id).getStatus());
    }

    @Test
    void scheduleInThePast_400() {
        Long id = draftId();
        ApiException ex = thrown(() -> service.schedule(id, LocalDateTime.of(2026, 10, 21, 13, 0), "secret", admin));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals(ErrorCodes.INVALID_TARIFF, ex.getCode());
        ApiException now = thrown(() -> service.schedule(id, LocalDateTime.of(2026, 10, 21, 14, 0), "secret", admin));
        assertEquals(ErrorCodes.INVALID_TARIFF, now.getCode());
    }

    @Test
    void secondScheduleIsRejected() {
        Long a = draftId();
        Long b = draftId();
        service.schedule(a, LocalDateTime.of(2026, 11, 1, 0, 0), "secret", admin);
        ApiException ex = thrown(() -> service.schedule(b, LocalDateTime.of(2026, 12, 1, 0, 0), "secret", admin));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(ErrorCodes.TARIFF_STATE, ex.getCode());
    }

    @Test
    void scheduleOnlyDraft() {
        Long id = draftId();
        service.schedule(id, LocalDateTime.of(2026, 11, 1, 0, 0), "secret", admin);
        ApiException ex = thrown(() -> service.schedule(id, LocalDateTime.of(2026, 12, 1, 0, 0), "secret", admin));
        assertEquals(ErrorCodes.TARIFF_STATE, ex.getCode());
    }

    @Test
    void scheduleSetsTimerAndPublishesUpcoming() {
        Long id = draftId();
        service.schedule(id, LocalDateTime.of(2026, 11, 1, 0, 0), "secret", admin);

        verify(scheduler).schedule(any(Runnable.class),
                eq(LocalDateTime.of(2026, 11, 1, 0, 0).atZone(ISTANBUL).toInstant()));
        verify(publisher).globalEvent(eq(SyncEventType.TARIFF_UPDATED), argThatUpcomingId(id));
        assertNotNull(service.upcoming());
        // not yet due: the old tariff still applies
        assertEquals(25.0, service.current().getSmallFirstHour());
    }

    private Map<String, Object> argThatUpcomingId(Long id) {
        return org.mockito.ArgumentMatchers.argThat(m -> {
            Object up = m.get("upcoming");
            return up instanceof Map<?, ?> um && id.equals(um.get("id"));
        });
    }

    @Test
    void activateDueSwitchesActiveAndArchivesOld() {
        Long id = draftId();
        Tariff old = service.current();
        service.schedule(id, LocalDateTime.of(2026, 11, 1, 0, 0), "secret", admin);
        reset(publisher);

        clock.set(LocalDateTime.of(2026, 11, 1, 0, 0, 1));
        service.activateDue();

        assertEquals(TariffStatus.ARCHIVED, old.getStatus());
        assertNotNull(old.getArchivedAt());
        assertEquals(TariffStatus.ACTIVE, store.get(id).getStatus());
        assertNotNull(store.get(id).getActivatedAt());
        assertEquals(id, service.current().getId());
        assertNull(service.upcoming());
        verify(publisher).globalEvent(eq(SyncEventType.TARIFF_UPDATED), any());
        verify(future).cancel(false);
    }

    @Test
    void currentActivatesDueTariffLazily() {
        Long id = draftId();
        service.schedule(id, LocalDateTime.of(2026, 11, 1, 0, 0), "secret", admin);
        clock.set(LocalDateTime.of(2026, 11, 2, 9, 0));

        assertEquals(id, service.current().getId());
        assertEquals(30.0, service.current().getSmallFirstHour());
    }

    @Test
    void startupActivatesAlreadyPastScheduledTariff() {
        Long id = draftId();
        service.schedule(id, LocalDateTime.of(2026, 11, 1, 0, 0), "secret", admin);
        clock.set(LocalDateTime.of(2026, 11, 5, 9, 0)); // app was down over the effective time

        TariffService restarted = new TariffService(repo, publisher, clock, encoder, scheduler, mock(PlatformTransactionManager.class));
        restarted.init();

        assertEquals(id, restarted.current().getId());
        assertEquals(TariffStatus.ACTIVE, store.get(id).getStatus());
    }

    @Test
    void cancelDraftAndScheduled() {
        Long draft = draftId();
        Map<String, Object> c = service.cancel(draft);
        assertEquals("ARCHIVED", c.get("status"));
        assertNotNull(store.get(draft).getArchivedAt());

        Long id = draftId();
        service.schedule(id, LocalDateTime.of(2026, 11, 1, 0, 0), "secret", admin);
        service.cancel(id);
        assertEquals(TariffStatus.ARCHIVED, store.get(id).getStatus());
        assertNull(service.upcoming());
        verify(future).cancel(false);
    }

    @Test
    void cancelActiveOrArchived_409() {
        Long activeId = service.current().getId();
        ApiException ex = thrown(() -> service.cancel(activeId));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(ErrorCodes.TARIFF_STATE, ex.getCode());

        Long draft = draftId();
        service.cancel(draft);
        assertEquals(ErrorCodes.TARIFF_STATE, thrown(() -> service.cancel(draft)).getCode());
    }

    @Test
    void previewComparesCurrentAndProposed() {
        Long id = draftId();
        Map<String, Object> p = service.preview(id);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) p.get("rows");
        assertEquals(20, rows.size()); // 10 scenarios x 2 vehicle types
        Map<String, Object> row = rows.get(1); // SMALL, 6 minutes
        assertEquals("SMALL", row.get("vehicleType"));
        @SuppressWarnings("unchecked")
        Map<String, Object> cur = (Map<String, Object>) row.get("current");
        @SuppressWarnings("unchecked")
        Map<String, Object> prop = (Map<String, Object>) row.get("proposed");
        assertEquals(25.0, cur.get("fee"));   // 6 > 5 free minutes
        assertEquals(0.0, prop.get("fee"));   // 6 <= 10 free minutes
        assertNotNull(prop.get("segments"));
        assertEquals("2026-10-22T10:00:00", row.get("entry"));
    }

    @Test
    void publicMapShape() {
        Map<String, Object> m = service.toPublicMap(service.current());
        assertEquals("08:00", m.get("paidStart"));
        assertEquals("22:00", m.get("paidEnd"));
        assertEquals(5, m.get("freeMinutes"));
        assertTrue(m.containsKey("small") && m.containsKey("large") && m.containsKey("subscription"));
        assertNull(service.currentWithUpcoming().get("upcoming"));
        assertTrue(service.currentWithUpcoming().containsKey("upcoming"));
    }

}
