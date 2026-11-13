package br.com.autocuidado.push;

import br.com.autocuidado.auth.AccountPrincipal;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agenda/web-push")
class WebPushController {
    private final WebPushTransport transport;
    private final WebPushSubscriptions subscriptions;

    WebPushController(WebPushTransport transport, WebPushSubscriptions subscriptions) {
        this.transport = transport;
        this.subscriptions = subscriptions;
    }

    @GetMapping
    ResponseEntity<Settings> settings(@AuthenticationPrincipal AccountPrincipal account, HttpSession session,
            @RequestHeader(value = "X-Push-Endpoint", required = false) String endpoint) {
        if (!transport.available()) return ResponseEntity.notFound().build();
        boolean active = endpoint != null && endpoint.length() <= 2048
                && subscriptions.active(account.accountId(), session.getId(), endpoint);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Settings(transport.publicKey(), active));
    }

    @GetMapping("/delivery")
    ResponseEntity<Void> delivery(@RequestHeader(value = "X-Push-Endpoint", required = false) String endpoint) {
        // The opaque endpoint authorizes only the display check, even if the sign-in cookie expired.
        boolean allowed = transport.available() && endpoint != null && endpoint.length() <= 2048
                && subscriptions.canDisplay(endpoint);
        return ResponseEntity.status(allowed ? 204 : 404).cacheControl(CacheControl.noStore()).build();
    }

    @PutMapping
    ResponseEntity<Void> update(@AuthenticationPrincipal AccountPrincipal account, HttpSession session,
                                @RequestBody Change change) {
        if (change.enabled() == null) return ResponseEntity.badRequest().build();
        try {
            WebPushSubscriptions.endpoint(change.endpoint());
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().build();
        }
        if (!change.enabled()) {
            subscriptions.disable(account.accountId(), session.getId(), change.endpoint());
        } else {
            if (!transport.available()) return ResponseEntity.notFound().build();
            if (!subscriptions.enable(account.accountId(), session.getId(), change.endpoint())) {
                return ResponseEntity.status(409).build();
            }
        }
        return ResponseEntity.noContent().build();
    }

    record Settings(String publicKey, boolean active) {}
    record Change(String endpoint, Boolean enabled) {}
}
