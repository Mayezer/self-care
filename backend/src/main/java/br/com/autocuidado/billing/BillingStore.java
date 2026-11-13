package br.com.autocuidado.billing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@ConditionalOnProperty(name = "app.billing.enabled", havingValue = "true")
class BillingStore {

    private final JdbcTemplate jdbc;
    private final StripeClient stripe;

    BillingStore(JdbcTemplate jdbc, StripeClient stripe) {
        this.jdbc = jdbc;
        this.stripe = stripe;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void prepare(long accountId) {
        jdbc.update("""
                INSERT INTO billing_accounts (account_id, customer_request_id) VALUES (?, ?)
                ON CONFLICT (account_id) DO NOTHING
                """, accountId, UUID.randomUUID());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureCustomer(long accountId) {
        Row row = lockAccount(accountId).orElseThrow();
        if (row.customer() == null) {
            String customer = stripe.createCustomer(row.requestId());
            jdbc.update("UPDATE billing_accounts SET stripe_customer_id = ? WHERE account_id = ?", customer, accountId);
        }
    }

    Optional<Row> account(long accountId) {
        return jdbc.query("SELECT * FROM billing_accounts WHERE account_id = ?", BillingStore::row, accountId)
                .stream().findFirst();
    }

    Optional<Row> lockAccount(long accountId) {
        return jdbc.query("SELECT * FROM billing_accounts WHERE account_id = ? FOR UPDATE", BillingStore::row, accountId)
                .stream().findFirst();
    }

    Optional<Row> lockCustomer(String customer) {
        return jdbc.query("SELECT * FROM billing_accounts WHERE stripe_customer_id = ? FOR UPDATE", BillingStore::row, customer)
                .stream().findFirst();
    }

    boolean recordEvent(String event, long accountId) {
        return jdbc.update("""
                INSERT INTO stripe_webhook_events (event_id, account_id) VALUES (?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, event, accountId) == 1;
    }

    void save(long accountId, StripeClient.Subscription subscription, Instant now) {
        OffsetDateTime until = subscription.accessUntil() == null ? null : subscription.accessUntil().atOffset(ZoneOffset.UTC);
        jdbc.update("""
                WITH snapshot AS (SELECT CAST(? AS timestamptz) AS until, CAST(? AS timestamptz) AS checked_at)
                UPDATE billing_accounts SET subscription_exists = ?, access_until = snapshot.until,
                    cancel_at_period_end = ?, access_lost_at = CASE
                        WHEN snapshot.until > snapshot.checked_at THEN NULL
                        WHEN access_until IS NULL THEN access_lost_at
                        ELSE COALESCE(access_lost_at, snapshot.checked_at)
                    END
                FROM snapshot WHERE account_id = ?
                """, until, now.atOffset(ZoneOffset.UTC), subscription.exists(), subscription.ends(), accountId);
    }

    private static Row row(ResultSet rs, int index) throws SQLException {
        var until = rs.getObject("access_until", OffsetDateTime.class);
        return new Row(rs.getLong("account_id"), rs.getObject("customer_request_id", UUID.class),
                rs.getString("stripe_customer_id"), new StripeClient.Subscription(rs.getBoolean("subscription_exists"),
                until == null ? null : until.toInstant(), rs.getBoolean("cancel_at_period_end")));
    }

    record Row(long accountId, UUID requestId, String customer, StripeClient.Subscription subscription) {}
}
