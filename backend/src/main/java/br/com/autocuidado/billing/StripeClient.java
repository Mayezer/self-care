package br.com.autocuidado.billing;

import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

class StripeClient {

    static final int MONTHLY_PRICE_CENTS = 990;
    private static final Set<String> TERMINAL = Set.of("canceled", "incomplete_expired");
    private final RestClient http;
    private final String priceId;
    private final String returnUrl;
    private final boolean liveMode;

    StripeClient(RestClient http, String priceId, String returnUrl, boolean liveMode) {
        this.http = http;
        this.priceId = priceId;
        this.returnUrl = returnUrl;
        this.liveMode = liveMode;
    }

    boolean liveMode() {
        return liveMode;
    }

    String createCustomer(UUID requestId) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("metadata[autocuidado_customer]", requestId.toString());
        return requiredId(post("/customers", form, "customer-" + requestId), "cus_");
    }

    Subscription subscription(String customer) {
        boolean exists = false;
        boolean ends = false;
        Instant accessUntil = null;
        String cursor = null;
        do {
            String after = cursor;
            JsonNode page = http.get().uri(builder -> {
                builder.path("/subscriptions").queryParam("customer", customer)
                        .queryParam("status", "all").queryParam("limit", 100);
                if (after != null) builder.queryParam("starting_after", after);
                return builder.build();
            }).retrieve().body(JsonNode.class);
            JsonNode data = listData(page);
            for (JsonNode subscription : data) {
                if (!customer.equals(subscription.path("customer").asString(""))) throw new InvalidResponse();
                String status = subscription.path("status").asString("");
                exists |= !TERMINAL.contains(status);
                if (!"active".equals(status)) continue;
                JsonNode items = subscription.path("items");
                // The plan sold here contains one item. Other plans never grant diary access.
                if (!items.path("data").isArray() || items.path("data").size() != 1
                        || items.path("has_more").asBoolean(false)) continue;
                JsonNode item = items.path("data").get(0);
                long periodEnd = item.path("current_period_end").asLong(0);
                if (!priceId.equals(item.path("price").path("id").asString("")) || periodEnd <= 0) continue;
                long cancelAt = subscription.path("cancel_at").asLong(0);
                Instant end = Instant.ofEpochSecond(cancelAt > 0 ? Math.min(periodEnd, cancelAt) : periodEnd);
                if (accessUntil == null || end.isAfter(accessUntil)) {
                    accessUntil = end;
                    ends = cancelAt > 0 || subscription.path("cancel_at_period_end").asBoolean(false);
                }
            }
            cursor = page.path("has_more").asBoolean(false) ? requiredId(data.path(data.size() - 1), "sub_") : null;
            if (cursor != null && cursor.equals(after)) throw new InvalidResponse();
        } while (cursor != null);
        return new Subscription(exists, accessUntil, ends);
    }

    URI checkout(String customer) {
        JsonNode latest = listData(http.get().uri(builder -> builder.path("/checkout/sessions")
                .queryParam("customer", customer).queryParam("limit", 1).build())
                .retrieve().body(JsonNode.class)).path(0);
        String lastId = "first";
        if (!latest.isMissingNode()) {
            lastId = requiredId(latest, "cs_");
            if (!customer.equals(latest.path("customer").asString(""))) throw new InvalidResponse();
            switch (latest.path("status").asString("")) {
                case "open" -> { return hostedUrl(latest, "checkout.stripe.com"); }
                case "complete" -> requireEndedCheckout(customer, latest.path("subscription").asString(""));
                case "expired" -> { }
                default -> throw new InvalidResponse();
            }
        }
        checkPrice();
        var form = new LinkedMultiValueMap<String, String>();
        form.add("customer", customer);
        form.add("mode", "subscription");
        form.add("line_items[0][price]", priceId);
        form.add("line_items[0][quantity]", "1");
        form.add("payment_method_types[0]", "card");
        form.add("locale", "pt-BR");
        form.add("success_url", returnUrl);
        form.add("cancel_url", returnUrl);
        return hostedUrl(post("/checkout/sessions", form, "checkout-" + customer + "-" + lastId), "checkout.stripe.com");
    }

    private void requireEndedCheckout(String customer, String subscriptionId) {
        if (!subscriptionId.matches("sub_[A-Za-z0-9_]{1,150}")) throw new CheckoutPending();
        JsonNode subscription = http.get().uri("/subscriptions/{id}", subscriptionId).retrieve().body(JsonNode.class);
        if (subscription == null || !customer.equals(subscription.path("customer").asString(""))) {
            throw new InvalidResponse();
        }
        if (!TERMINAL.contains(subscription.path("status").asString(""))) throw new CheckoutPending();
    }

    URI portal(String customer) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("customer", customer);
        form.add("return_url", returnUrl);
        form.add("locale", "pt-BR");
        return hostedUrl(post("/billing_portal/sessions", form, null), "billing.stripe.com");
    }

    private void checkPrice() {
        JsonNode price = http.get().uri("/prices/{price}", priceId).retrieve().body(JsonNode.class);
        if (price == null || !priceId.equals(price.path("id").asString(""))
                || !price.path("active").asBoolean(false) || !"brl".equals(price.path("currency").asString(""))
                || price.path("unit_amount").asInt(-1) != MONTHLY_PRICE_CENTS
                || !"month".equals(price.path("recurring").path("interval").asString(""))
                || price.path("recurring").path("interval_count").asInt(-1) != 1) {
            throw new InvalidResponse();
        }
    }

    private JsonNode post(String path, MultiValueMap<String, String> form, String key) {
        var request = http.post().uri(path).contentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (key != null) request.header("Idempotency-Key", key);
        JsonNode response = request.body(form).retrieve().body(JsonNode.class);
        if (response == null || !response.isObject()) throw new InvalidResponse();
        return response;
    }

    private static JsonNode listData(JsonNode page) {
        if (page == null || !page.path("data").isArray()) throw new InvalidResponse();
        return page.path("data");
    }

    private static String requiredId(JsonNode body, String prefix) {
        String id = body.path("id").asString("");
        if (!id.startsWith(prefix) || !id.matches("[A-Za-z0-9_]{1,150}")) throw new InvalidResponse();
        return id;
    }

    private static URI hostedUrl(JsonNode body, String host) {
        try {
            URI uri = URI.create(body.path("url").asString(""));
            if ("https".equals(uri.getScheme()) && host.equals(uri.getHost())
                    && uri.getPort() == -1 && uri.getRawUserInfo() == null) return uri;
        } catch (IllegalArgumentException ignored) {
            // The provider returned an unusable URL.
        }
        throw new InvalidResponse();
    }

    record Subscription(boolean exists, Instant accessUntil, boolean ends) {}

    static final class InvalidResponse extends RuntimeException {}

    static final class CheckoutPending extends RuntimeException {}
}
