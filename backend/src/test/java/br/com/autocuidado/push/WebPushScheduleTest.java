package br.com.autocuidado.push;

import java.net.URI;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebPushScheduleTest {
    @Test
    void resolvesGapToItsFirstValidTimeAndOverlapToItsFirstOccurrence() {
        var zone = ZoneId.of("America/New_York");
        assertEquals(Instant.parse("2026-03-08T07:00:00Z"),
                WebPushSchedule.resolve(LocalDate.parse("2026-03-08"), LocalTime.of(2, 45), zone).orElseThrow());
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"),
                WebPushSchedule.resolve(LocalDate.parse("2026-11-01"), LocalTime.of(1, 30), zone).orElseThrow());
        assertTrue(WebPushSchedule.resolve(LocalDate.parse("2011-12-30"), LocalTime.NOON, ZoneId.of("Pacific/Apia")).isEmpty());
        assertEquals(Instant.parse("2026-11-11T02:50:00Z"), WebPushSchedule.resolve(
                LocalDate.parse("2026-11-10"), LocalTime.of(23, 50), ZoneId.of("America/Sao_Paulo")).orElseThrow());
    }

    @Test
    void acceptsOnlyKnownHttpsPushEndpointsAndSignsForTheProviderOrigin() throws Exception {
        var endpoint = WebPushSubscriptions.endpoint("https://fcm.googleapis.com/fcm/send/fictional");
        String chromiumStaging = "https://jmt17.google.com/fcm/send/fictional:token";
        assertEquals(URI.create(chromiumStaging), WebPushSubscriptions.endpoint(chromiumStaging));
        for (String invalid : new String[] {"http://fcm.googleapis.com/fcm/send/x", "https://127.0.0.1/x",
                "https://fcm.googleapis.com@localhost/x", "https://fcm.googleapis.com:444/x",
                "https://fcm.googleapis.com.evil.test/x", "https://updates.push.services.mozilla.com/x#fragment",
                "https://jmt17.google.com/log", "https://jmt17.google.com/fcm/send/../x", chromiumStaging + "?x=1"}) {
            assertThrows(IllegalArgumentException.class, () -> WebPushSubscriptions.endpoint(invalid));
        }
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var keys = generator.generateKeyPair();
        var point = ((ECPublicKey) keys.getPublic()).getW();
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String publicKey = encoder.encodeToString(HexFormat.of().parseHex("04%064x%064x".formatted(
                point.getAffineX(), point.getAffineY())));
        String privateKey = encoder.encodeToString(HexFormat.of().parseHex("%064x".formatted(
                ((ECPrivateKey) keys.getPrivate()).getS())));
        var transport = new WebPushTransport(true, true, publicKey, privateKey);
        Instant now = Instant.parse("2026-11-10T10:00:00Z");
        String authorization = transport.authorization(endpoint, now);
        assertTrue(authorization.endsWith(", k=" + publicKey));
        String[] parts = authorization.substring("vapid t=".length(), authorization.indexOf(',')).split("\\.");
        var decoder = Base64.getUrlDecoder();
        assertEquals("{\"aud\":\"https://fcm.googleapis.com\",\"exp\":" + now.plusSeconds(3600).getEpochSecond() + "}",
                new String(decoder.decode(parts[1]), US_ASCII));
        var signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initVerify(keys.getPublic());
        signature.update((parts[0] + "." + parts[1]).getBytes(US_ASCII));
        assertTrue(signature.verify(decoder.decode(parts[2])));
        assertThrows(IllegalStateException.class, () -> new WebPushTransport(true, true, publicKey, ""));
        assertThrows(IllegalArgumentException.class, () -> WebPushSubscriptions.endpoint(URI.create("https://[::1]/x").toString()));
    }
}
