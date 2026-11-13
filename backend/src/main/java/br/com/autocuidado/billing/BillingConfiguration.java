package br.com.autocuidado.billing;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.billing.enabled", havingValue = "true")
class BillingConfiguration {

    @Bean
    StripeClient stripeClient(@Value("${app.billing.stripe.secret-key}") String key,
                              @Value("${app.billing.stripe.price-id}") String price,
                              @Value("${app.billing.return-url}") String returnUrl) {
        if (!key.matches("sk_(test|live)_[A-Za-z0-9_]+") || !price.matches("price_[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Configure a chave secreta e o preço recorrente do Stripe.");
        }
        URI target = URI.create(returnUrl);
        boolean loopbackTest = key.startsWith("sk_test_") && "http".equals(target.getScheme())
                && ("127.0.0.1".equals(target.getHost()) || "localhost".equals(target.getHost()));
        if ((!"https".equals(target.getScheme()) && !loopbackTest) || target.getHost() == null
                || target.getRawUserInfo() != null || target.getRawQuery() != null || target.getRawFragment() != null
                || !"/configuracoes/assinatura".equals(target.getPath())) {
            throw new IllegalArgumentException("Configure a URL de retorno da página de assinatura.");
        }
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        var client = RestClient.builder().baseUrl("https://api.stripe.com/v1")
                .requestFactory(factory)
                .defaultHeaders(headers -> headers.setBearerAuth(key))
                .defaultHeader("Stripe-Version", "2026-10-28.endive")
                .build();
        return new StripeClient(client, price, returnUrl, key.startsWith("sk_live_"));
    }
}
