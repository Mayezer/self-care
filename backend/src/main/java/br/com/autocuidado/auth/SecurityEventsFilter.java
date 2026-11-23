package br.com.autocuidado.auth;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

final class SecurityEventsFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(SecurityEventsFilter.class);
    private static final Set<String> ACTIONS = Set.of("login", "logout", "register", "confirm", "sessions/revoke",
            "mfa/email", "mfa/email/confirm", "mfa/recover", "mfa/activate", "mfa/verify", "password/change",
            "password/reset/request", "password/reset/complete", "password/reset/recovery", "password/reset/recovery/complete");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        String action = path.startsWith("/api/auth/") ? path.substring("/api/auth/".length()) : "";
        if (!request.getMethod().equals("POST") || !ACTIONS.contains(action)) {
            chain.doFilter(request, response);
            return;
        }
        String id = request.getHeader("X-Request-Id");
        if (id == null || !id.matches("[a-f0-9]{32}")) {
            id = UUID.randomUUID().toString().replace("-", "");
        }
        response.setHeader("X-Request-Id", id);
        int status = 500;
        try {
            chain.doFilter(request, response);
            status = response.getStatus();
        } finally {
            LOG.info("security_event action={} status={} request_id={}", action, status, id);
        }
    }
}
