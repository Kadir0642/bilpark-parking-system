package com.bilpark.backend.sync;

import com.bilpark.backend.model.Role;
import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.model.SyncEvent;
import com.bilpark.backend.model.SyncEventType;
import com.bilpark.backend.model.SyncScope;
import com.bilpark.backend.repository.SyncEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes change events to sync_events (in the caller's transaction) and pushes them to the connected
 * SSE clients after commit. Connected clients and the latest version live in memory, so heartbeats
 * and broadcasts never touch the database.
 */
@Service
public class SyncService implements ChangePublisher {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    public static final int MAX_EVENTS = 500;
    public static final long EMITTER_TIMEOUT_MS = 30L * 60 * 1000;
    private static final int MAX_BATCHES = 20;
    private static final int RETENTION_DAYS = 7;

    /** One open SSE connection. */
    static final class Client {
        final SyncViewer viewer;
        final SseEmitter emitter;
        volatile boolean closed;

        Client(SyncViewer viewer, SseEmitter emitter) {
            this.viewer = viewer;
            this.emitter = emitter;
        }
    }

    /** A page of visible events. */
    public record Page(List<SyncEvent> events, boolean hasMore) {}

    private final SyncEventRepository repository;
    private final JsonMapper mapper;
    private final Clock clock;
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private final AtomicLong lastVersion = new AtomicLong(-1);

    public SyncService(SyncEventRepository repository, JsonMapper mapper, Clock clock) {
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
    }

    // ---- publishing -------------------------------------------------------------------------

    @Override
    public void zoneEvent(SyncEventType type, Long zoneId, StreetLocation street, Map<String, Object> payload) {
        publish(type, SyncScope.ZONE, zoneId, street != null ? street.name() : null, null, payload);
    }

    @Override
    public void globalEvent(SyncEventType type, Map<String, Object> payload) {
        publish(type, SyncScope.GLOBAL, null, null, null, payload);
    }

    @Override
    public void userEvent(SyncEventType type, Long targetUserId, Map<String, Object> payload) {
        publish(type, SyncScope.USER, null, null, targetUserId, payload);
    }

    private void publish(SyncEventType type, SyncScope scope, Long zoneId, String street, Long userId,
                         Map<String, Object> payload) {
        SyncEvent e = new SyncEvent();
        e.setType(type);
        e.setScope(scope);
        e.setZoneId(zoneId);
        e.setStreet(street);
        e.setTargetUserId(userId);
        e.setPayload(mapper.writeValueAsString(payload != null ? payload : Map.of()));
        e.setCreatedAt(LocalDateTime.now(clock));
        SyncEvent saved = repository.save(e);
        afterCommit(() -> broadcast(saved));
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private void broadcast(SyncEvent e) {
        lastVersion.accumulateAndGet(e.getId(), Math::max);
        String json = eventJson(e);
        for (Client c : clients) {
            if (isVisible(e, c.viewer)) {
                send(c, SseEmitter.event().id(String.valueOf(e.getId())).name(e.getType().name()).data(json));
            }
        }
    }

    /** After the surrounding transaction commits, completes every stream of this user. */
    public void closeUserStreams(Long userId) {
        afterCommit(() -> {
            for (Client c : clients) {
                if (userId != null && userId.equals(c.viewer.userId())) close(c);
            }
        });
    }

    // ---- visibility -------------------------------------------------------------------------

    public static boolean isVisible(SyncEvent e, SyncViewer v) {
        if (v.role() == Role.ADMIN) return true;
        SyncScope scope = e.getScope();
        if (scope == SyncScope.GLOBAL) return true;
        if (scope == SyncScope.USER) return v.userId() != null && v.userId().equals(e.getTargetUserId());
        if (v.zoneId() == null) return false; // officer without a zone sees only GLOBAL / own USER events
        if (e.getZoneId() != null) return e.getZoneId().equals(v.zoneId());
        return e.getStreet() != null && e.getStreet().equals(v.street());
    }

    // ---- versions & queries -----------------------------------------------------------------

    /** Latest version; hits the database only once (first call), then it is kept up to date in memory. */
    public long latestVersion() {
        long v = lastVersion.get();
        if (v >= 0) return v;
        lastVersion.compareAndSet(-1, repository.latestVersion());
        return lastVersion.get();
    }

    /** Latest version straight from the database (also detects a database reset). */
    public long latestVersionFromDb() {
        long v = repository.latestVersion();
        lastVersion.set(v);
        return v;
    }

    /** True when the client cannot catch up from since (older than retained events, or newer than the DB). */
    public boolean resetRequired(long since, long latest) {
        if (since > latest) return true;
        Long oldest = repository.findFirstByOrderByIdAsc().map(SyncEvent::getId).orElse(null);
        return oldest != null && since < oldest - 1;
    }

    /** Visible events after the version, oldest first, at most 500. */
    public Page visibleAfter(long since, SyncViewer viewer) {
        List<SyncEvent> out = new ArrayList<>();
        long cursor = since;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            List<SyncEvent> rows = repository.findAfter(cursor, PageRequest.of(0, MAX_EVENTS));
            for (int i = 0; i < rows.size(); i++) {
                SyncEvent e = rows.get(i);
                if (!isVisible(e, viewer)) continue;
                out.add(e);
                if (out.size() == MAX_EVENTS) {
                    return new Page(out, i < rows.size() - 1 || rows.size() == MAX_EVENTS);
                }
            }
            if (rows.size() < MAX_EVENTS) return new Page(out, false);
            cursor = rows.get(rows.size() - 1).getId();
        }
        return new Page(out, true);
    }

    public Map<String, Object> toMap(SyncEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", e.getId());
        m.put("type", e.getType().name());
        m.put("scope", e.getScope().name());
        m.put("zoneId", e.getZoneId());
        m.put("street", e.getStreet());
        if (e.getTargetUserId() != null) m.put("targetUserId", e.getTargetUserId());
        m.put("at", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
        JsonNode payload;
        try {
            payload = e.getPayload() != null ? mapper.readTree(e.getPayload()) : mapper.createObjectNode();
        } catch (RuntimeException ex) {
            payload = mapper.createObjectNode();
        }
        m.put("payload", payload);
        return m;
    }

    String eventJson(SyncEvent e) {
        return mapper.writeValueAsString(toMap(e));
    }

    // ---- SSE clients ------------------------------------------------------------------------

    /**
     * Registers a stream and sends HELLO (+ replay when since != null, RESET when it cannot be replayed).
     * The client is registered first and locked while the initial frames go out, so no event is lost
     * or interleaved before HELLO.
     */
    public SseEmitter connect(SyncViewer viewer, Long since) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        Client client = new Client(viewer, emitter);
        Runnable remove = () -> {
            client.closed = true;
            clients.remove(client);
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(t -> remove.run());

        long latest = latestVersion();
        synchronized (client) {
            clients.add(client);
            Map<String, Object> hello = new LinkedHashMap<>();
            hello.put("version", latest);
            hello.put("userId", viewer.userId());
            hello.put("role", viewer.role().name());
            hello.put("zoneId", viewer.zoneId());
            sendLocked(client, SseEmitter.event().name("HELLO").data(mapper.writeValueAsString(hello)));

            if (since != null && !client.closed) {
                boolean reset;
                Page page = null;
                if (resetRequired(since, latestVersionFromDb())) {
                    reset = true;
                } else {
                    page = visibleAfter(since, viewer);
                    reset = page.hasMore();
                }
                if (reset) {
                    sendLocked(client, SseEmitter.event().name("RESET").data("{\"version\":" + lastVersion.get() + "}"));
                } else {
                    for (SyncEvent e : page.events()) {
                        if (client.closed) break;
                        sendLocked(client, SseEmitter.event().id(String.valueOf(e.getId()))
                                .name(e.getType().name()).data(eventJson(e)));
                    }
                }
            }
        }
        return emitter;
    }

    private void send(Client c, SseEmitter.SseEventBuilder event) {
        synchronized (c) {
            sendLocked(c, event);
        }
    }

    private void sendLocked(Client c, SseEmitter.SseEventBuilder event) {
        if (c.closed) return;
        try {
            c.emitter.send(event);
        } catch (Exception ex) {
            c.closed = true;
            clients.remove(c);
            try {
                c.emitter.completeWithError(ex);
            } catch (Exception ignored) {
                // already gone
            }
        }
    }

    private void close(Client c) {
        synchronized (c) {
            c.closed = true;
            clients.remove(c);
            try {
                c.emitter.complete();
            } catch (Exception ignored) {
                // already gone
            }
        }
    }

    // Runs before the web server's graceful shutdown, which would otherwise wait for every open stream
    @EventListener(ContextClosedEvent.class)
    public void closeAllStreams() {
        for (Client c : clients) close(c);
    }

    public int connectedCount() {
        return clients.size();
    }

    // ---- scheduled jobs ---------------------------------------------------------------------

    /** Keeps proxies/mobile connections alive and tells clients the latest version. No database access. */
    @Scheduled(fixedRate = 25000)
    public void heartbeat() {
        if (clients.isEmpty()) return;
        long v = Math.max(lastVersion.get(), 0);
        String data = "{\"version\":" + v + ",\"at\":\"" + LocalDateTime.now(clock) + "\"}";
        for (Client c : clients) {
            send(c, SseEmitter.event().name("HEARTBEAT").data(data));
        }
    }

    /** Once a day: drop events older than the retention window. */
    @Scheduled(cron = "0 5 1 * * *")
    @Transactional
    public void cleanup() {
        int n = repository.deleteOlderThan(LocalDateTime.now(clock).minusDays(RETENTION_DAYS));
        log.info("sync_events temizlendi: {} kayıt silindi", n);
    }
}
