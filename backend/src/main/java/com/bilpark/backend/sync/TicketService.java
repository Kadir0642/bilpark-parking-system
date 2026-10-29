package com.bilpark.backend.sync;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/** One-time, short-lived tickets so a browser EventSource (no custom headers) can open /api/events. In memory only. */
@Service
public class TicketService {

    public static final int TTL_SECONDS = 60;

    private record Ticket(String username, Instant expiresAt) {}

    private final ConcurrentHashMap<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public TicketService(Clock clock) {
        this.clock = clock;
    }

    public String issue(String username) {
        purgeExpired();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tickets.put(ticket, new Ticket(username, clock.instant().plusSeconds(TTL_SECONDS)));
        return ticket;
    }

    /** Returns the username and invalidates the ticket; null when unknown, used or expired. */
    public String consume(String ticket) {
        if (ticket == null) return null;
        Ticket t = tickets.remove(ticket);
        if (t == null || !t.expiresAt().isAfter(clock.instant())) return null;
        return t.username();
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        tickets.values().removeIf(t -> !t.expiresAt().isAfter(now));
    }
}
