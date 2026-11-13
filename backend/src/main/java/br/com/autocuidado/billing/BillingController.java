package br.com.autocuidado.billing;

import java.io.IOException;
import java.net.URI;

import br.com.autocuidado.auth.AccountPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty(name = "app.billing.enabled", havingValue = "true")
class BillingController {

    private static final int MAX_EVENT_BYTES = 1_048_576;
    private final BillingService billing;
    private final StripeWebhook webhook;

    BillingController(BillingService billing, StripeWebhook webhook) {
        this.billing = billing;
        this.webhook = webhook;
    }

    @GetMapping("/api/subscription")
    ResponseEntity<BillingService.State> state(@AuthenticationPrincipal AccountPrincipal account) {
        return privateResponse(billing.state(account.accountId()));
    }

    @PostMapping("/api/subscription/refresh")
    ResponseEntity<BillingService.State> refresh(@AuthenticationPrincipal AccountPrincipal account) {
        return privateResponse(billing.refresh(account.accountId()));
    }

    @PostMapping("/api/subscription/checkout")
    ResponseEntity<Link> checkout(@AuthenticationPrincipal AccountPrincipal account) {
        return privateResponse(new Link(billing.checkout(account.accountId())));
    }

    @PostMapping("/api/subscription/portal")
    ResponseEntity<Link> portal(@AuthenticationPrincipal AccountPrincipal account) {
        return privateResponse(new Link(billing.portal(account.accountId())));
    }

    @PostMapping(path = StripeWebhook.PATH, consumes = "application/json")
    ResponseEntity<Void> accept(HttpServletRequest request,
                                @RequestHeader(name = "Stripe-Signature", required = false) String signature) throws IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_EVENT_BYTES + 1);
        if (body.length > MAX_EVENT_BYTES) throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
        var event = webhook.read(body, signature);
        if (event != null) billing.apply(event);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler({RestClientException.class, StripeClient.InvalidResponse.class})
    ResponseEntity<Void> providerUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler(StripeClient.CheckoutPending.class)
    ResponseEntity<Void> checkoutPending() {
        return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore()).build();
    }

    private static <T> ResponseEntity<T> privateResponse(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(data);
    }

    record Link(URI url) {}
}
