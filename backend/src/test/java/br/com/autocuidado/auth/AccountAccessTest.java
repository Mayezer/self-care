package br.com.autocuidado.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

import br.com.autocuidado.hair.HairPlanController;
import br.com.autocuidado.persistence.AccountRepository;
import br.com.autocuidado.persistence.AccountProfileStore;
import br.com.autocuidado.persistence.AgendaReminderService;
import br.com.autocuidado.persistence.HairPlanService;
import br.com.autocuidado.persistence.HairProfileStore;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AccountController.class, AccountSessionsController.class, AdminAccessController.class,
        HairPlanController.class, AccountProfileController.class},
        properties = {"app.auth.email.from=agenda@example.test", "app.auth.mfa-required=false",
                "app.auth.password-exposure-check=false"})
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
@Import({SecurityConfiguration.class, AccountService.class, AccountTimeZones.class, EmailRegistration.class, PasswordPolicy.class})
class AccountAccessTest {

    @MockitoBean
    private AccountProfileStore accountProfiles;

    private static final String EMAIL = "conta@example.test";
    private static final String PASSWORD = "Uma senha longa 4!";
    private static final String CODE = "abcdefghijklmnopqrstuv";
    private static final String TIME_ZONE = "America/Sao_Paulo";
    private static final Instant TEST_TIME = Instant.parse("2026-11-10T01:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoSpyBean
    private PasswordEncoder passwords;

    @Autowired
    private AdjustableClock clock;

    @MockitoBean
    private AccountRepository accounts;

    @MockitoBean
    private HairPlanService plans;

    @MockitoBean
    private HairProfileStore profiles;

    @MockitoBean
    private AgendaReminderService reminders;

    @MockitoBean
    private MailSender mail;

    @MockitoBean
    private LoginRateLimit loginRateLimit;

    @MockitoBean(name = "credentialMailTasks")
    private TaskExecutor mailTasks;

    @BeforeEach
    void allowLoginAttempts() {
        clock.now = TEST_TIME;
        when(loginRateLimit.tryAcquire(anyString())).thenReturn(true);
        when(loginRateLimit.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
    }

    @Test
    void ordinarySessionsCannotEnterTheAdminNamespaceAndOwnersCannotSkipMfa() throws Exception {
        givenConfirmedAccount(TIME_ZONE);
        mvc.perform(get("/api/admin/access")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/access").session(signedIn(EMAIL))).andExpect(status().isForbidden());

        var owner = accounts.loginByEmail(EMAIL).orElseThrow();
        when(owner.getOwner()).thenReturn(true);
        var session = new MockHttpSession();
        login(session, csrf(session), PASSWORD).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/access").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void deniesAnonymousAccessAndRequiresCsrfForRegistrationAndLogin() throws Exception {
        mvc.perform(get("/api/agenda"))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> assertNull(result.getRequest().getSession(false)));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/confirm").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login").param("username", EMAIL).param("password", PASSWORD))
                .andExpect(status().isForbidden());
        verifyNoInteractions(accounts);
    }

    @Test
    void returnsRateLimitBeforeLookingUpTheAccount() throws Exception {
        var session = new MockHttpSession();
        when(loginRateLimit.tryAcquire("login:" + EMAIL)).thenReturn(false);

        var token = csrf(session);
        mvc.perform(post("/api/auth/login").session(session).header(token.name(), token.value())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", "conta@EXAMPLE.TEST").param("password", PASSWORD))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "300"));

        verifyNoInteractions(accounts);
    }

    @Test
    void limitsLoginCredentialsToTheFormBody() throws Exception {
        var session = new MockHttpSession();
        var token = csrf(session);
        mvc.perform(post("/api/auth/login").session(session).header(token.name(), token.value())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).queryParam("username", EMAIL))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/login").session(session).header(token.name(), token.value())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(accounts);
    }

    @Test
    void sendsTheCodeOnlyByEmailAndDoesNotAuthenticateBeforeConfirmation() throws Exception {
        var session = new MockHttpSession();
        var csrf = csrf(session);
        submit("register", session, csrf, "{\"email\":\"@\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").isString());
        verifyNoInteractions(accounts, mail);

        when(accounts.issueConfirmation(eq(EMAIL), eq(TIME_ZONE), anyString(), any(), any())).thenReturn(1);
        var response = submit("register", session, csrf, "{\"email\":\"conta@EXAMPLE.TEST\"}")
                .andExpect(status().isAccepted()).andReturn();
        assertEquals("", response.getResponse().getContentAsString());
        verifyNoInteractions(accounts, mail);
        var queued = ArgumentCaptor.forClass(Runnable.class);
        verify(mailTasks).execute(queued.capture());
        queued.getValue().run();
        var mailMessage = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(mailMessage.capture());
        assertEquals(EMAIL, mailMessage.getValue().getTo()[0]);
        String code = mailMessage.getValue().getText().lines()
                .filter(line -> line.matches("[A-Za-z0-9_-]{22}")).findFirst().orElseThrow();
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(code.getBytes(StandardCharsets.US_ASCII)));
        verify(accounts).issueConfirmation(eq(EMAIL), eq(TIME_ZONE), eq(digest), any(), any());
        login(session, csrf, PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void usesTheSamePasswordWorkAndFailureResponseForUnknownAndExistingAccounts() throws Exception {
        givenConfirmedAccount(TIME_ZONE);
        var session = new MockHttpSession();
        var token = csrf(session);
        String wrong = "Uma senha incorreta e longa!";
        var existing = login(session, token, wrong).andExpect(status().isUnauthorized()).andReturn().getResponse();
        var missing = mvc.perform(post("/api/auth/login").session(session).header(token.name(), token.value())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", "inexistente@example.test").param("password", wrong))
                .andExpect(status().isUnauthorized()).andReturn().getResponse();
        assertEquals(existing.getContentAsString(), missing.getContentAsString());
        assertEquals(existing.getHeader("Cache-Control"), missing.getHeader("Cache-Control"));
        var hashes = ArgumentCaptor.forClass(String.class);
        verify(passwords, times(2)).matches(eq(wrong), hashes.capture());
        assertTrue(hashes.getAllValues().stream().allMatch(hash -> hash.startsWith("{pbkdf2-sha256-600000}")));
    }

    @Test
    void registrationQueuesEveryValidAddressAndQueueSaturationDoesNotRevealAccounts() throws Exception {
        var session = new MockHttpSession();
        var token = csrf(session);
        submit("register", session, token, "{\"email\":\"known@example.test\"}").andExpect(status().isAccepted());
        submit("register", session, token, "{\"email\":\"unknown@example.test\"}").andExpect(status().isAccepted());
        verify(mailTasks, times(2)).execute(any(Runnable.class));
        verifyNoInteractions(accounts, mail);
        doThrow(new TaskRejectedException("Queue full")).when(mailTasks).execute(any(Runnable.class));
        submit("register", session, token, "{\"email\":\"known@example.test\"}").andExpect(status().isServiceUnavailable());
        submit("register", session, token, "{\"email\":\"unknown@example.test\"}").andExpect(status().isServiceUnavailable());
        verifyNoInteractions(accounts, mail);
    }

    @Test
    void confirmsWithEightCharacterCompositionAndFallsBackToTheDefaultTimeZone() throws Exception {
        String selectedPassword = "Qa7!xyzt";
        var session = new MockHttpSession();
        var csrf = csrf(session);
        when(accounts.hasValidConfirmation(eq(EMAIL), anyString(), any())).thenReturn(false, true, false);
        when(accounts.confirm(eq(EMAIL), anyString(), any(), anyString(), eq(TIME_ZONE))).thenReturn(1);
        submit("confirm", session, csrf, confirmation(selectedPassword)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.code").isString());
        submit("confirm", session, csrf, confirmation(selectedPassword)).andExpect(status().isCreated());
        var hash = ArgumentCaptor.forClass(String.class);
        verify(accounts).confirm(eq(EMAIL), anyString(), any(), hash.capture(), eq(TIME_ZONE));
        assertNotEquals(selectedPassword, hash.getValue());
        assertTrue(passwords.matches(selectedPassword, hash.getValue()));
        submit("confirm", session, csrf, confirmation(selectedPassword)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.code").isString());
    }

    @Test
    void rejectsShortOrIncompletePasswordsBeforeConsumingTheConfirmation() throws Exception {
        var session = new MockHttpSession();
        var token = csrf(session);
        for (String password : java.util.List.of("Ab1!", "abcdefgh1!", "ABCDEFGH1!", "Abcdefghi!", "Abcdefgh12")) {
            submit("confirm", session, token, confirmation(password)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.password").value(PasswordPolicy.REQUIREMENTS));
        }
        verifyNoInteractions(accounts);
    }

    @Test
    void acceptsAnExistingPasswordWithoutTheNewCompositionAtLogin() throws Exception {
        String legacyPassword = "somente letras antigas";
        givenConfirmedAccount(1L, EMAIL, TIME_ZONE, legacyPassword);
        var session = new MockHttpSession();
        login(session, csrf(session), legacyPassword).andExpect(status().isNoContent());
    }

    @Test
    void rotatesTheSessionAtLoginAndInvalidatesItAtLogout() throws Exception {
        givenConfirmedAccount(TIME_ZONE);

        var session = new MockHttpSession();
        var anonymousId = session.getId();
        var anonymousCsrf = csrf(session);
        login(session, anonymousCsrf, "outra senha longa").andExpect(status().isUnauthorized());
        login(session, anonymousCsrf, PASSWORD).andExpect(status().isNoContent());
        assertNotEquals(anonymousId, session.getId());
        mvc.perform(get("/api/agenda").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.timeZone").value(TIME_ZONE))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        mvc.perform(post("/api/auth/logout").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").session(session).header(anonymousCsrf.name(), anonymousCsrf.value()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/agenda").session(session)).andExpect(status().isOk());

        var authenticatedCsrf = csrf(session);
        mvc.perform(post("/api/auth/logout").session(session).header(authenticatedCsrf.name(), authenticatedCsrf.value()))
                .andExpect(status().isNoContent());
        assertTrue(session.isInvalid());
        mvc.perform(get("/api/agenda")).andExpect(status().isUnauthorized());
    }

    @Test
    void expiresTheSessionFromLoginEvenAfterRecentActivityAndAllowsReauthentication() throws Exception {
        givenConfirmedAccount(TIME_ZONE);
        var session = new MockHttpSession();
        var anonymousCsrf = csrf(session);
        clock.now = TEST_TIME.plus(Duration.ofMinutes(5));
        login(session, anonymousCsrf, PASSWORD).andExpect(status().isNoContent());

        var deadline = clock.instant().plus(Duration.ofHours(12));
        clock.now = deadline.minusSeconds(1);
        mvc.perform(get("/api/agenda").session(session)).andExpect(status().isOk());

        clock.now = deadline;
        mvc.perform(get("/api/agenda").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        assertTrue(session.isInvalid());

        var newSession = new MockHttpSession();
        login(newSession, csrf(newSession), PASSWORD).andExpect(status().isNoContent());
        mvc.perform(get("/api/agenda").session(newSession)).andExpect(status().isOk());
    }

    @Test
    void listsOnlyOwnedSessionsAndRequiresCsrfAndPasswordToRevokeThem() throws Exception {
        String otherEmail = "outra@example.test";
        givenConfirmedAccount(10L, EMAIL, TIME_ZONE);
        givenConfirmedAccount(20L, otherEmail, TIME_ZONE);
        var current = signedIn(EMAIL);
        var second = signedIn(EMAIL);
        var other = signedIn(otherEmail);
        var owned = mvc.perform(get("/api/auth/sessions").session(current))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.length()").value(2)).andReturn();
        var ownSessions = JsonPath.parse(owned.getResponse().getContentAsString());
        String target = ownSessions.<java.util.List<String>>read("$[?(@.current == false)].id").getFirst();
        assertNotEquals(second.getId(), target);
        var foreign = mvc.perform(get("/api/auth/sessions").session(other))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andReturn();
        String foreignId = JsonPath.parse(foreign.getResponse().getContentAsString()).read("$[0].id");

        mvc.perform(post("/api/auth/sessions/revoke").session(current).contentType(MediaType.APPLICATION_JSON)
                .content(revocation(target, PASSWORD))).andExpect(status().isForbidden());
        var token = csrf(current);
        submit("sessions/revoke", current, token, revocation(target, PASSWORD + "!"))
                .andExpect(status().isForbidden());
        submit("sessions/revoke", current, token, revocation(foreignId, PASSWORD))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/agenda").session(other)).andExpect(status().isOk());
        mvc.perform(get("/api/agenda").session(second)).andExpect(status().isOk());

        submit("sessions/revoke", current, token, revocation(target, PASSWORD)).andExpect(status().isNoContent());
        mvc.perform(get("/api/agenda").session(second)).andExpect(status().isUnauthorized());
        submit("sessions/revoke", current, token, revocation("all", PASSWORD)).andExpect(status().isNoContent());
        assertTrue(current.isInvalid());
        mvc.perform(get("/api/agenda").session(other)).andExpect(status().isOk());
    }

    @Test
    void keepsFiveSessionsAndExpiresTheLeastRecentOne() throws Exception {
        String email = "limite@example.test";
        givenConfirmedAccount(30L, email, TIME_ZONE);
        var oldest = signedIn(email);
        for (int index = 0; index < 4; index++) {
            signedIn(email);
        }
        var latest = signedIn(email);
        mvc.perform(get("/api/auth/sessions").session(latest)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5));
        mvc.perform(get("/api/agenda").session(oldest)).andExpect(status().isUnauthorized());
    }

    @Test
    void keepsTheSameSaoPauloDateBeforeAndAfterLoginEvenForAnOldAccountZone() throws Exception {
        givenConfirmedAccount("UTC");

        String draft = """
                {"startDate":"2026-11-09","weeks":1,"weekdays":["MONDAY","THURSDAY"],"timeZone":"America/Sao_Paulo"}
                """;
        mvc.perform(post("/api/hair-plans/preview").contentType(MediaType.APPLICATION_JSON).content(draft))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occurrences[0].date").value("2026-11-09"));

        var session = new MockHttpSession();
        login(session, csrf(session), PASSWORD).andExpect(status().isNoContent());
        mvc.perform(post("/api/hair-plans/preview").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(draft))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occurrences[0].date").value("2026-11-09"));
        mvc.perform(get("/api/agenda").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.timeZone").value("America/Sao_Paulo"));
        mvc.perform(post("/api/hair-plans/preview").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(draft.replace("2026-11-09", "2026-11-10")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occurrences[0].date").value("2026-11-12"));
    }

    @Test
    void keepsTheAccountProfilePrivateAndRejectsOwnerInjectionAndInvalidNames() throws Exception {
        mvc.perform(get("/api/account/profile")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/account/profile/photo")).andExpect(status().isUnauthorized());
        verifyNoInteractions(accountProfiles);
        givenConfirmedAccount(TIME_ZONE);
        var session = signedIn(EMAIL);
        mvc.perform(put("/api/account/profile").session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Teste\"}")).andExpect(status().isForbidden());
        var token = csrf(session);
        when(accountProfiles.find(1L)).thenReturn(new AccountProfileStore.Profile("Nome Teste", false, 0));
        mvc.perform(put("/api/account/profile").session(session).header(token.name(), token.value())
                .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\" Nome Teste \"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.displayName").value("Nome Teste"));
        verify(accountProfiles).rename(1L, "Nome Teste");
        for (String body : new String[]{"{\"accountId\":2,\"displayName\":\"Outra pessoa\"}",
                "{\"displayName\":42}", "{\"displayName\":\"" + "x".repeat(81) + "\"}"}) {
            mvc.perform(put("/api/account/profile").session(session).header(token.name(), token.value())
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void normalizesTheProfilePhotoAndAlwaysReadsTheAuthenticatedOwner() throws Exception {
        givenConfirmedAccount(TIME_ZONE);
        var session = signedIn(EMAIL);
        var token = csrf(session);
        var image = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(640, 320, BufferedImage.TYPE_INT_RGB), "PNG", image);
        var file = new MockMultipartFile("photo", "foto.png", "image/png", image.toByteArray());
        when(accountProfiles.find(1L)).thenReturn(new AccountProfileStore.Profile(null, true, 1));
        mvc.perform(multipart("/api/account/profile/photo").file(file).session(session)
                .header(token.name(), token.value()).with(request -> { request.setMethod("PUT"); return request; }))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasPhoto").value(true));
        var normalized = ArgumentCaptor.forClass(byte[].class);
        verify(accountProfiles).replacePhoto(eq(1L), normalized.capture());
        assertEquals(512, ImageIO.read(new ByteArrayInputStream(normalized.getValue())).getWidth());
        when(accountProfiles.photo(1L)).thenReturn(normalized.getValue());
        mvc.perform(get("/api/account/profile/photo?accountId=2").session(session))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Cross-Origin-Resource-Policy", "same-origin"))
                .andExpect(content().bytes(normalized.getValue()));
        verify(accountProfiles).photo(1L);
        var invalid = new MockMultipartFile("photo", "foto.jpg", "image/jpeg", "<svg></svg>".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/account/profile/photo").file(invalid).session(session)
                .header(token.name(), token.value()).with(request -> { request.setMethod("PUT"); return request; }))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.photo").isString());
    }

    private void givenConfirmedAccount(String timeZone) {
        givenConfirmedAccount(1L, EMAIL, timeZone);
    }

    private void givenConfirmedAccount(long id, String email, String timeZone) {
        givenConfirmedAccount(id, email, timeZone, PASSWORD);
    }

    private void givenConfirmedAccount(long id, String email, String timeZone, String password) {
        var account = mock(AccountRepository.LoginCredential.class);
        String hash = passwords.encode(password);
        when(account.getId()).thenReturn(id);
        when(account.getEmail()).thenReturn(email);
        when(account.getPasswordHash()).thenReturn(hash);
        when(accounts.findPasswordHash(id)).thenReturn(hash);
        when(accounts.findCredentialVersion(id)).thenReturn(0L);
        when(accounts.findTimeZone(id)).thenReturn(timeZone);
        when(accounts.loginByEmail(email)).thenReturn(Optional.of(account));
    }

    private MockHttpSession signedIn(String email) throws Exception {
        var session = new MockHttpSession();
        var token = csrf(session);
        mvc.perform(post("/api/auth/login").session(session).header(token.name(), token.value())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", email).param("password", PASSWORD)).andExpect(status().isNoContent());
        return session;
    }

    private static String revocation(String session, String password) {
        return """
                {"session":"%s","password":"%s"}
                """.formatted(session, password);
    }

    private ResultActions submit(String action, MockHttpSession session, CsrfHeader csrf, String body) throws Exception {
        return mvc.perform(post("/api/auth/" + action).session(session).header(csrf.name(), csrf.value())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions login(MockHttpSession session, CsrfHeader csrf, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").session(session).header(csrf.name(), csrf.value())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .with(request -> {
                    request.setRequestedSessionId(session.getId());
                    return request;
                })
                .param("username", "conta@EXAMPLE.TEST").param("password", password));
    }

    private CsrfHeader csrf(MockHttpSession session) throws Exception {
        var result = mvc.perform(get("/api/auth/csrf").session(session)).andExpect(status().isOk()).andReturn();
        var json = JsonPath.parse(result.getResponse().getContentAsString());
        return new CsrfHeader(json.read("$.headerName"), json.read("$.token"));
    }

    private static String confirmation(String password) {
        return """
                {"email":"%s","code":"%s","password":"%s","timeZone":"+03:00"}
                """.formatted(EMAIL, CODE, password);
    }

    private record CsrfHeader(String name, String value) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfiguration {
        @Bean
        @Primary
        AdjustableClock testClock() {
            return new AdjustableClock();
        }
    }

    static final class AdjustableClock extends Clock {
        private Instant now = TEST_TIME;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
