package br.com.autocuidado.auth;

import java.util.List;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class RequestSecurityTest {
    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsSessionAllocationBeforeCreatingItAndIgnoresUntrustedForwardingHeaders() throws Exception {
        var limits = mock(LoginRateLimit.class);
        var request = new MockHttpServletRequest("GET", "/api/auth/csrf");
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("X-Forwarded-For", "192.0.2.99");
        request.addHeader("CF-Connecting-IP", "192.0.2.99");
        var response = new MockHttpServletResponse();

        new RequestRateLimitFilter(limits).doFilter(request, response, (input, output) -> fail("Session budget bypassed"));

        verify(limits).tryAcquire("session-ip:192.0.2.10", 20, 60);
        assertEquals(429, response.getStatus());
        assertEquals("60", response.getHeader("Retry-After"));
        assertNull(request.getSession(false));
        request.setSession(new MockHttpSession());
        clearInvocations(limits);
        new RequestRateLimitFilter(limits).doFilter(request, new MockHttpServletResponse(), (input, output) -> {});
        verifyNoInteractions(limits);
    }

    @Test
    void limitsPasswordSprayingBySourceAndWritesByTheAuthenticatedOwner() throws Exception {
        var limits = mock(LoginRateLimit.class);
        when(limits.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
        var filter = new RequestRateLimitFilter(limits);
        var request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr("192.0.2.11");
        filter.doFilter(request, new MockHttpServletResponse(), (input, output) -> {});
        verify(limits).tryAcquire("auth-ip:192.0.2.11", 60, 60);

        var account = mock(AccountPrincipal.class);
        when(account.accountId()).thenReturn(42L);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(account, null, List.of()));
        request = new MockHttpServletRequest("POST", "/api/hair-plans");
        request.addParameter("accountId", "another-account");
        filter.doFilter(request, new MockHttpServletResponse(), (input, output) -> {});
        verify(limits).tryAcquire("write-account:42", 60, 60);
    }

    @Test
    void acceptsAForwardedAddressOnlyFromTheConfiguredProxy() throws Exception {
        var limits = mock(LoginRateLimit.class);
        when(limits.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
        var filter = new RequestRateLimitFilter(limits, "localhost");
        var request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "192.0.2.12");
        filter.doFilter(request, new MockHttpServletResponse(), (input, output) -> {});
        verify(limits).tryAcquire("auth-ip:192.0.2.12", 60, 60);
        request.setRemoteAddr("192.0.2.13");
        filter.doFilter(request, new MockHttpServletResponse(), (input, output) -> {});
        verify(limits).tryAcquire("auth-ip:192.0.2.13", 60, 60);
    }

    @Test
    void encodedRoutesUseTheSameBudgetsAsTheirDecodedMappings() throws Exception {
        var limits = mock(LoginRateLimit.class);
        var filter = new RequestRateLimitFilter(limits);
        for (var input : List.of(
                new String[]{"GET", "/%61pi/auth/csrf", "session-ip:192.0.2.14", "20"},
                new String[]{"POST", "/api/%61uth/login", "auth-ip:192.0.2.14", "60"},
                new String[]{"POST", "/api/%68air-plans/preview", "preview-ip:192.0.2.14", "120"})) {
            var request = new MockHttpServletRequest(input[0], input[1]);
            request.setRemoteAddr("192.0.2.14");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> fail("Encoded route bypassed its budget"));
            verify(limits).tryAcquire(input[2], Integer.parseInt(input[3]), 60);
            assertEquals(429, response.getStatus());
            assertNull(request.getSession(false));
        }

        var account = mock(AccountPrincipal.class);
        when(account.accountId()).thenReturn(42L);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(account, null, List.of()));
        var request = new MockHttpServletRequest("POST", "/%61pi/occurrences/skin");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("Encoded write bypassed its owner budget"));
        verify(limits).tryAcquire("write-account:42", 60, 60);
        assertEquals(429, response.getStatus());
    }

    @Test
    void encodedCredentialRoutesKeepTheAllowlistedSecurityEvent(CapturedOutput output) throws Exception {
        var request = new MockHttpServletRequest("POST", "/%61pi/auth/%70assword/change");
        request.setQueryString("private-query-marker");
        var response = new MockHttpServletResponse();
        new SecurityEventsFilter().doFilter(request, response, (req, res) ->
                ((jakarta.servlet.http.HttpServletResponse) res).setStatus(403));
        assertTrue(output.getAll().contains("security_event action=password/change status=403 request_id="));
        assertFalse(output.getAll().contains("private-query-marker"));
        assertFalse(output.getAll().contains("%61pi"));
    }

    @Test
    void securityEventsKeepOnlyActionStatusAndValidatedCorrelation(CapturedOutput output) throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/auth/password/change");
        request.setQueryString("password=private-query-marker");
        request.setContent("private-body-marker".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        request.addHeader("Cookie", "private-cookie-marker");
        request.addHeader("X-Request-Id", "private-correlation-marker");
        var response = new MockHttpServletResponse();
        new SecurityEventsFilter().doFilter(request, response, (input, result) ->
                ((jakarta.servlet.http.HttpServletResponse) result).setStatus(403));
        assertTrue(response.getHeader("X-Request-Id").matches("[a-f0-9]{32}"));
        assertTrue(output.getAll().contains("security_event action=password/change status=403 request_id="));
        assertFalse(output.getAll().contains("private-"));

        assertThrows(ServletException.class, () -> new SecurityEventsFilter().doFilter(request, response,
                (input, result) -> { throw new ServletException("private-exception-marker"); }));
        assertTrue(output.getAll().contains("action=password/change status=500"));
        assertFalse(output.getAll().contains("private-"));
    }
}
