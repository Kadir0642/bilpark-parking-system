package com.bilpark.backend.controller;

import com.bilpark.backend.model.User;
import com.bilpark.backend.repository.UserRepository;
import com.bilpark.backend.sync.SyncService;
import com.bilpark.backend.sync.SyncViewer;
import com.bilpark.backend.sync.TicketService;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Live sync: SSE stream, one-time stream ticket and catch-up endpoint. */
@RestController
@RequestMapping("/api")
public class SyncController {

    private final SyncService syncService;
    private final TicketService ticketService;
    private final UserRepository userRepository;

    public SyncController(SyncService syncService, TicketService ticketService, UserRepository userRepository) {
        this.syncService = syncService;
        this.ticketService = ticketService;
        this.userRepository = userRepository;
    }

    private User currentUser(Principal principal) {
        if (principal == null) throw new AccessDeniedException("Oturum bulunamadı.");
        return userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new AccessDeniedException("Kullanıcı bulunamadı."));
    }

    private static Long parseVersion(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // GET /api/events  (Bearer header, or ?ticket=... from POST /api/events/ticket)
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@RequestParam(required = false) Long since,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                             Principal principal) {
        Long from = parseVersion(lastEventId);
        if (from == null) from = since;
        return syncService.connect(SyncViewer.of(currentUser(principal)), from);
    }

    // POST /api/events/ticket -> {"ticket", "expiresInSeconds"}
    @PostMapping("/events/ticket")
    public Map<String, Object> ticket(Principal principal) {
        User user = currentUser(principal);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticket", ticketService.issue(user.getUsername()));
        body.put("expiresInSeconds", TicketService.TTL_SECONDS);
        return body;
    }

    // GET /api/sync?since=<version>
    @GetMapping("/sync")
    public Map<String, Object> sync(@RequestParam(required = false) Long since, Principal principal) {
        User user = currentUser(principal);
        Map<String, Object> body = new LinkedHashMap<>();
        long latest = syncService.latestVersionFromDb();
        body.put("version", latest);
        if (since == null || syncService.resetRequired(since, latest)) {
            body.put("events", List.of());
            body.put("hasMore", false);
            body.put("resetRequired", since != null);
            return body;
        }
        SyncService.Page page = syncService.visibleAfter(since, SyncViewer.of(user));
        body.put("events", page.events().stream().map(syncService::toMap).toList());
        body.put("hasMore", page.hasMore());
        body.put("resetRequired", false);
        return body;
    }
}
