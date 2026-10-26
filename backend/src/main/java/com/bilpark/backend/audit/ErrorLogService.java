package com.bilpark.backend.audit;

import com.bilpark.backend.model.ErrorLog;
import com.bilpark.backend.repository.ErrorLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Keeps unexpected server errors for the developer's "recent errors" view.
 * Written in its own transaction (the failing request's transaction may be rolling back);
 * when the database itself is down the error stays in a small in-memory buffer.
 * Never stores request bodies or passwords; only path, exception class, message and a short stack.
 */
@Service
public class ErrorLogService {

    private static final Logger log = LoggerFactory.getLogger(ErrorLogService.class);
    private static final int MEMORY_SIZE = 100;
    private static final int STACK_LINES = 15;

    private final ErrorLogRepository repository;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Deque<ErrorLog> memory = new ArrayDeque<>();

    public ErrorLogService(ErrorLogRepository repository, PlatformTransactionManager txManager, Clock clock) {
        this.repository = repository;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** Best effort: never throws. */
    public void record(Throwable ex, String errorCode) {
        try {
            ErrorLog e = new ErrorLog();
            e.setCreatedAt(LocalDateTime.now(clock));
            HttpServletRequest req = currentRequest();
            if (req != null) {
                e.setMethod(req.getMethod());
                e.setPath(cut(req.getRequestURI(), 255));
            }
            e.setErrorCode(errorCode);
            e.setExceptionClass(cut(ex.getClass().getName(), 255));
            e.setMessage(cut(ex.getMessage(), 1000));
            e.setStackExcerpt(stack(ex));
            Authentication a = SecurityContextHolder.getContext().getAuthentication();
            if (a != null && a.isAuthenticated() && !"anonymousUser".equals(a.getName())) e.setUsername(a.getName());

            synchronized (memory) {
                memory.addFirst(e);
                while (memory.size() > MEMORY_SIZE) memory.removeLast();
            }
            tx.executeWithoutResult(s -> repository.save(e));
        } catch (RuntimeException failure) {
            log.warn("Error log could not be stored: {}", failure.getClass().getSimpleName());
        }
    }

    /** Newest first. Falls back to the in-memory buffer when the database cannot be read. */
    public List<ErrorLog> recent(int limit) {
        int n = Math.max(1, Math.min(limit, 200));
        try {
            return repository.findAllByOrderByIdDesc(PageRequest.of(0, n));
        } catch (RuntimeException e) {
            synchronized (memory) {
                return new ArrayList<>(memory).subList(0, Math.min(n, memory.size()));
            }
        }
    }

    /** Errors since the given time (-1 when the database cannot be read). */
    public long countSince(LocalDateTime since) {
        try {
            return repository.countByCreatedAtGreaterThanEqual(since);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static String stack(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        StackTraceElement[] st = ex.getStackTrace();
        for (int i = 0; i < Math.min(STACK_LINES, st.length); i++) sb.append("at ").append(st[i]).append('\n');
        Throwable cause = ex.getCause();
        if (cause != null && cause != ex) {
            sb.append("Caused by: ").append(cause.getClass().getName());
        }
        return sb.toString();
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
