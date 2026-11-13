package br.com.autocuidado.billing;

import br.com.autocuidado.hair.DiaryRetention;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

@Component
@ConditionalOnProperty(name = "app.billing.enabled", havingValue = "true")
class BillingDiaryMaintenance {
    private final BillingService billing;
    private final DiaryRetention retention;

    BillingDiaryMaintenance(BillingService billing, DiaryRetention retention) {
        this.billing = billing;
        this.retention = retention;
    }

    @Scheduled(cron = "0 10 * * * *", zone = "UTC")
    void confirmExpiredAccess() {
        int failures = 0;
        for (long accountId : retention.expiredAccountsAwaitingConfirmation()) {
            try {
                billing.refresh(accountId);
            } catch (RestClientException | StripeClient.InvalidResponse exception) {
                failures++;
            }
        }
        if (failures > 0) {
            LoggerFactory.getLogger(BillingDiaryMaintenance.class)
                    .warn("Não foi possível confirmar no Stripe o acesso ao diário de {} contas.", failures);
        }
    }
}
