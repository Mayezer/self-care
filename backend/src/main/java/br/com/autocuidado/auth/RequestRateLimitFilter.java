package br.com.autocuidado.auth;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

final class RequestRateLimitFilter extends OncePerRequestFilter {
    private final LoginRateLimit limits;
    private final String trustedProxyHost;

    RequestRateLimitFilter(LoginRateLimit limits) {
        this(limits, "");
    }

    RequestRateLimitFilter(LoginRateLimit limits, String trustedProxyHost) {
        this.limits = limits;
        this.trustedProxyHost = trustedProxyHost;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        String method = request.getMethod();
        String key = null;
        int budget = 60;
        if (method.equals("GET") && path.equals("/api/auth/csrf") && request.getSession(false) == null) {
            key = "session-ip:" + clientAddress(request);
            budget = 20;
        } else if (method.equals("POST") && path.startsWith("/api/auth/")) {
            key = "auth-ip:" + clientAddress(request);
        } else if (method.equals("POST") && path.equals("/api/hair-plans/preview")) {
            key = "preview-ip:" + clientAddress(request);
            budget = 120;
        } else if (path.startsWith("/api/") && !method.equals("GET") && !method.equals("HEAD")
                && !method.equals("OPTIONS")) {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof AccountPrincipal account) {
                key = "write-account:" + account.accountId();
            }
        }
        if (key != null && !limits.tryAcquire(key, budget, 60)) {
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            response.setHeader("Cache-Control", "no-store");
            return;
        }
        chain.doFilter(request, response);
    }

    private String clientAddress(HttpServletRequest request) {
        if (!trustedProxyHost.isBlank()) {
            try {
                for (var address : InetAddress.getAllByName(trustedProxyHost)) {
                    if (address.getHostAddress().equals(request.getRemoteAddr())) {
                        String forwarded = request.getHeader("X-Forwarded-For");
                        if (forwarded != null) return InetAddress.ofLiteral(forwarded).getHostAddress();
                    }
                }
            } catch (UnknownHostException | IllegalArgumentException exception) {
                // A missing proxy or malformed identity shares the conservative peer budget.
            }
        }
        return request.getRemoteAddr();
    }
}
