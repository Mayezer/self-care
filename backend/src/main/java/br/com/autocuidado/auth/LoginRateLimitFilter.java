package br.com.autocuidado.auth;

import java.io.IOException;
import java.util.Objects;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
        if (request.getQueryString() != null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        String contentType = request.getContentType();
        if (contentType == null || !MediaType.APPLICATION_FORM_URLENCODED_VALUE.equalsIgnoreCase(
                contentType.split(";", 2)[0].strip())) {
            response.setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
            return;
        }
        String login = AccountService.normalizeIdentifier(Objects.toString(request.getParameter("username"), "").trim());
        String password = request.getParameter("password");
        if (login.isEmpty() || login.length() > 254 || password == null || password.isEmpty() || password.length() > 1024) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        if (!limits.tryAcquire("login:" + login)) {
            response.setStatus(429);
            response.setHeader(HttpHeaders.RETRY_AFTER, Integer.toString(LoginRateLimit.WINDOW_SECONDS));
            return;
        }
        chain.doFilter(request, response);
    }
}
