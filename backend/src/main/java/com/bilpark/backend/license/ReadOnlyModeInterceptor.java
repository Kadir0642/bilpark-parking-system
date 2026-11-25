package com.bilpark.backend.license;

import com.bilpark.backend.common.ErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Set;

/**
 * Read-only mode after the license and its grace period ended: every writing request
 * (POST/PUT/PATCH/DELETE) under /api answers 423 Locked with a readable message.
 * GET requests (lists, Excel/CSV) are never blocked. Runs after Spring Security, so
 * 401/403 still come first.
 *
 * Always open: login/logout, health, own password change, SSE ticket and the developer's
 * maintenance endpoints (license renewal must stay possible).
 */
@Component
public class ReadOnlyModeInterceptor implements HandlerInterceptor {

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    static final List<String> OPEN_PREFIXES = List.of(
            "/api/auth/",
            "/api/health",
            "/api/developer/",
            "/api/me/password",
            "/api/admin/notifications/",
            "/api/events/"
    );

    private final LicenseService licenseService;

    public ReadOnlyModeInterceptor(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!WRITE_METHODS.contains(request.getMethod())) return true;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        for (String p : OPEN_PREFIXES) {
            if (path.startsWith(p)) return true;
        }
        if (!licenseService.isReadOnly()) return true;

        response.setStatus(HttpStatus.LOCKED.value());
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"" + LicenseService.READ_ONLY_MESSAGE + "\",\"code\":\""
                + ErrorCodes.READ_ONLY + "\"}");
        return false;
    }
}
