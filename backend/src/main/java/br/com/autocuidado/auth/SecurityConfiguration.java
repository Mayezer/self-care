package br.com.autocuidado.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import br.com.autocuidado.persistence.AccountRepository;
import br.com.autocuidado.push.WebPushSubscriptions;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.session.HttpSessionEventPublisher;

import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.HEAD;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.security.crypto.password.Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256;
import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    private static final String PREVIEW = "/api/hair-plans/preview";
    private static final String STRIPE_WEBHOOK = "/api/billing/stripe/webhook";
    private static final String[] PUBLIC_FORUM_READS = {"/api/forum/public/topics",
            "/api/forum/public/topics/{topicId}", "/api/forum/public/topics/{topicId}/images/{imageId}",
            "/api/forum/participation"};

    @Bean
    static PasswordEncoder passwordEncoder() {
        String id = "pbkdf2-sha256-600000";
        var encoder = new Pbkdf2PasswordEncoder("", 16, 600_000, PBKDF2WithHmacSHA256);
        return new DelegatingPasswordEncoder(id, Map.of(id, encoder));
    }

    @Bean
    AccountSessions accountSessions() {
        return new AccountSessions();
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    LoginSessions loginSessions(AccountSessions sessions, ObjectProvider<WebPushSubscriptions> pushSubscriptions, Clock clock,
            @Value("${app.auth.session-max-age}") Duration sessionMaxAge) {
        return new LoginSessions(sessions, pushSubscriptions, clock, sessionMaxAge);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    HttpSessionEventPublisher sessionEvents() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    ThreadPoolTaskExecutor credentialMailTasks() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("credential-mail-");
        return executor;
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectProvider<WebPushSubscriptions> pushSubscriptions,
            LoginRateLimit loginRateLimit, Clock clock, AccountSessions sessions, LoginSessions loginSessions,
            ObjectProvider<MfaLoginController> mfaLogin, ObjectProvider<AuthSecrets> secrets,
            ObjectProvider<AccountRepository> accounts,
            @Value("${app.auth.registration-enabled:true}") boolean registrationEnabled,
            @Value("${app.security.proxy-host:}") String trustedProxyHost,
            @Value("${app.auth.mfa-required:true}") boolean mfaRequired) throws Exception {
        if (mfaRequired) {
            secrets.getObject().requireKey();
        }
        var sessionLifetime = new SessionLifetimeFilter(clock, accounts, sessions, pushSubscriptions);
        var forumApi = pathPattern("/api/forum/**");
        var accessDenied = new AccessDeniedHandlerImpl();
        return http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(GET, "/api/health", "/api/ready", "/api/auth/csrf", "/api/agenda/web-push/delivery",
                                "/api/help/features").permitAll()
                        .requestMatchers(GET, PUBLIC_FORUM_READS).permitAll()
                        .requestMatchers(HEAD, PUBLIC_FORUM_READS).permitAll()
                        .requestMatchers(POST, PREVIEW, STRIPE_WEBHOOK, "/api/auth/register", "/api/auth/confirm", "/api/auth/login",
                                "/api/auth/mfa/**", "/api/auth/password/reset/**")
                        .permitAll()
                        .requestMatchers("/api/forum/moderation/**").hasAuthority(AccountPrincipal.FORUM_MODERATOR)
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .securityContext(context -> context.securityContextRepository(loginSessions.contexts))
                .csrf(csrf -> csrf.csrfTokenRepository(loginSessions.csrf)
                        .ignoringRequestMatchers(pathPattern(POST, PREVIEW), pathPattern(POST, STRIPE_WEBHOOK)))
                .addFilterBefore(sessionLifetime, CsrfFilter.class)
                .addFilterBefore(new SecurityEventsFilter(), SessionLifetimeFilter.class)
                .addFilterAfter(new RequestRateLimitFilter(loginRateLimit, trustedProxyHost), SessionLifetimeFilter.class)
                .addFilterBefore(new LoginRateLimitFilter(loginRateLimit), UsernamePasswordAuthenticationFilter.class)
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors
                        .accessDeniedHandler((request, response, exception) -> {
                            var auth = SecurityContextHolder.getContext().getAuthentication();
                            if (forumApi.matches(request) && (auth == null || !(auth.getPrincipal() instanceof AccountPrincipal))) {
                                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            } else {
                                accessDenied.handle(request, response, exception);
                            }
                        })
                        .authenticationEntryPoint((request, response, exception) -> {
                            if (pathPattern(GET, "/api/agenda").matches(request)) {
                                response.setHeader("X-Registration-Enabled", Boolean.toString(registrationEnabled));
                            }
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        }))
                .formLogin(login -> login
                        .loginPage("/agenda")
                        .loginProcessingUrl("/api/auth/login")
                        .securityContextRepository(new NullSecurityContextRepository())
                        .successHandler((request, response, authentication) -> {
                            var account = (AccountPrincipal) authentication.getPrincipal();
                            if (account.owner() && !mfaRequired) {
                                // Never establish an owner session in a profile with MFA disabled.
                                loginSessions.prepare(request, response);
                                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            } else if (mfaRequired) {
                                mfaLogin.getObject().begin(account, request, response);
                            } else {
                                loginSessions.finish(account, request, response, false);
                                response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                            }
                        })
                        .failureHandler((request, response, exception) ->
                                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED)))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .addLogoutHandler((request, response, authentication) -> {
                            var session = request.getSession(false);
                            if (session != null && authentication != null
                                    && authentication.getPrincipal() instanceof AccountPrincipal account) {
                                sessions.removeSessionInformation(session.getId());
                                pushSubscriptions.ifAvailable(subscriptions ->
                                        subscriptions.endSession(account.accountId(), session.getId()));
                            }
                        })
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpServletResponse.SC_NO_CONTENT)))
                .build();
    }
}
