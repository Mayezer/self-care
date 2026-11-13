package br.com.autocuidado.billing;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = "app.billing.enabled", havingValue = "true")
class StripeWebhook {

    static final String PATH = "/api/billing/stripe/webhook";
    private static final Set<String> EVENTS = Set.of("checkout.session.completed", "customer.subscription.created",
            "customer.subscription.updated", "customer.subscription.deleted", "customer.subscription.paused",
            "customer.subscription.resumed", "invoice.paid", "invoice.payment_failed");
    private final SecretKeySpec secret;
    private final StripeClient stripe;
    private final Clock clock;
    private final JsonMapper json;

    StripeWebhook(@Value("${app.billing.stripe.webhook-secret}") String secret, StripeClient stripe,
                  Clock clock, JsonMapper json) {
        if (!secret.startsWith("whsec_") || secret.length() <= 6) {
            throw new IllegalArgumentException("Configure o segredo de assinatura dos webhooks do Stripe.");
        }
        this.secret = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.stripe = stripe;
        this.clock = clock;
        this.json = json;
    }

    Event read(byte[] bytes, String header) {
        verify(bytes, header);
        JsonNode event;
        try {
            event = json.readTree(bytes);
        } catch (JacksonException exception) {
            throw invalid();
        }
        var liveMode = event.path("livemode");
        if (!liveMode.isBoolean() || liveMode.asBoolean() != stripe.liveMode()) throw invalid();
        if (!EVENTS.contains(event.path("type").asString(""))) return null;

        String id = event.path("id").asString("");
        String customer = event.path("data").path("object").path("customer").asString("");
        if (!id.matches("evt_[A-Za-z0-9_]{1,150}") || !customer.matches("cus_[A-Za-z0-9_]{1,150}")) throw invalid();
        return new Event(id, customer);
    }

    private void verify(byte[] bytes, String header) {
        if (header == null || header.length() > 4096) throw invalid();
        String time = null;
        var signatures = new ArrayList<String>();
        for (String part : header.split(",")) {
            String value = part.strip();
            if (value.startsWith("t=")) {
                if (time != null) throw invalid();
                time = value.substring(2);
            } else if (value.startsWith("v1=")) {
                signatures.add(value.substring(3));
            }
        }
        if (time == null || !time.matches("[0-9]{1,18}")) throw invalid();
        long timestamp = Long.parseLong(time);
        long now = clock.instant().getEpochSecond();
        if (timestamp < now - 300 || timestamp > now + 300) throw invalid();

        byte[] expected;
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(secret);
            mac.update((time + ".").getBytes(StandardCharsets.UTF_8));
            expected = mac.doFinal(bytes);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
        for (String signature : signatures) {
            if (signature.matches("[0-9a-fA-F]{64}") && MessageDigest.isEqual(expected, HexFormat.of().parseHex(signature))) {
                return;
            }
        }
        throw invalid();
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Evento do Stripe inválido.");
    }

    record Event(String id, String customer) {}
}
