package br.com.autocuidado.auth;

import java.io.IOException;
import java.util.Objects;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import static org.springframework.http.HttpMethod.POST;

final class LoginRateLimitFilter extends OncePerRequestFilter {

    private static final PathPatternRequestMatcher LOGIN = PathPatternRequestMatcher.pathPattern(POST, "/api/auth/login");
    private final LoginRateLimit limits;

    LoginRateLimitFilter(LoginRateLimit limits) {
        this.limits = limits;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !LOGIN.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String login = AccountService.normalizeIdentifier(Objects.toString(request.getParameter("username"), "").trim());
        if (login.length() > 254) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        if (!limits.tryAcquire(login)) {
            response.setStatus(429);
            response.setHeader(HttpHeaders.RETRY_AFTER, Integer.toString(LoginRateLimit.WINDOW_SECONDS));
            return;
        }
        chain.doFilter(request, response);
    }
}
