package br.com.autocuidado.billing;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;

import br.com.autocuidado.hair.DiaryRetention;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(name = "app.billing.enabled", havingValue = "true")
public class BillingService {

    private static final StripeClient.Subscription FREE = new StripeClient.Subscription(false, null, false);
    private final BillingStore store;
    private final StripeClient stripe;
    private final Clock clock;
    private final DiaryRetention diaryRetention;
    private final SubscriptionAccess access;

    BillingService(BillingStore store, StripeClient stripe, Clock clock, DiaryRetention diaryRetention,
                   SubscriptionAccess access) {
        this.store = store;
        this.stripe = stripe;
        this.clock = clock;
        this.diaryRetention = diaryRetention;
        this.access = access;
    }

    State state(long accountId) {
        return state(store.account(accountId).map(BillingStore.Row::subscription).orElse(FREE));
    }

    public void requireActive(long accountId) {
        access.requireActive(accountId, "O diário exige uma assinatura ativa.");
    }

    @Transactional
    public State refresh(long accountId) {
        var row = store.lockAccount(accountId).orElse(null);
        return state(row == null || row.customer() == null ? FREE : synchronize(row));
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public URI checkout(long accountId) {
        store.prepare(accountId);
        store.ensureCustomer(accountId);
        var row = store.lockAccount(accountId).orElseThrow();
        if (synchronize(row).exists()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Gerencie a assinatura existente.");
        }
        return stripe.checkout(row.customer());
    }

    URI portal(long accountId) {
        String customer = store.account(accountId).map(BillingStore.Row::customer)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Conta sem assinatura no Stripe."));
        return stripe.portal(customer);
    }

    @Transactional
    public void apply(StripeWebhook.Event event) {
        var row = store.lockCustomer(event.customer()).orElse(null);
        if (row != null && store.recordEvent(event.id(), row.accountId())) {
            // Read the current provider state while holding the same lock as refresh and Checkout.
            synchronize(row);
        }
    }

    private StripeClient.Subscription synchronize(BillingStore.Row row) {
        var subscription = stripe.subscription(row.customer());
        Instant now = clock.instant();
        if (SubscriptionAccess.active(subscription.accessUntil(), now)) {
            diaryRetention.purgeBeforeRenewal(row.accountId(), now);
        }
        store.save(row.accountId(), subscription, now);
        return subscription;
    }

    private State state(StripeClient.Subscription subscription) {
        var until = subscription.accessUntil();
        boolean active = SubscriptionAccess.active(until, clock.instant());
        return new State(active, subscription.exists(), active ? until : null, active && subscription.ends(),
                StripeClient.MONTHLY_PRICE_CENTS, !stripe.liveMode());
    }

    record State(boolean active, boolean canManage, Instant accessUntil, boolean cancelAtPeriodEnd,
                 int monthlyPriceCents, boolean testMode) {}
}
