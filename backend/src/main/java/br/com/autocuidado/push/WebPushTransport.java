package br.com.autocuidado.push;

import java.io.IOException;
import java.math.BigInteger;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class WebPushTransport {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final String JWT_HEADER = encode("{\"typ\":\"JWT\",\"alg\":\"ES256\"}");
    private final String publicKey;
    private final PrivateKey signingKey;
    private final HttpClient client;

    WebPushTransport(@Value("${app.agenda.web-push-enabled:false}") boolean enabled,
                     @Value("${app.agenda.reminders-enabled:false}") boolean remindersEnabled,
                     @Value("${app.agenda.web-push-public-key:}") String publicKey,
                     @Value("${app.agenda.web-push-private-key:}") String privateKey) {
        this.publicKey = publicKey;
        signingKey = enabled && remindersEnabled ? readKeys(publicKey, privateKey) : null;
        client = signingKey == null ? null : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    boolean available() {
        return signingKey != null;
    }

    String publicKey() {
        return publicKey;
    }

    Result send(String endpoint, Instant now, Instant expiresAt) {
        URI uri = WebPushSubscriptions.endpoint(endpoint);
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
                .header("Authorization", authorization(uri, now))
                .header("TTL", Long.toString(Math.max(0, Duration.between(now, expiresAt).toSeconds())))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 300) return new Result(State.ACCEPTED, status, null);
            if (status == 429) {
                return new Result(State.RETRY, status, retryAt(now, response.headers().firstValue("Retry-After").orElse("")));
            }
            if (status >= 400 && status < 500 && status != 408) return new Result(State.REJECTED, status, null);
            return new Result(State.UNCERTAIN, status, null);
        } catch (ConnectException | HttpConnectTimeoutException unavailable) {
            return new Result(State.RETRY, null, now.plusSeconds(60));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Result(State.UNCERTAIN, null, null);
        } catch (IOException unknown) {
            return new Result(State.UNCERTAIN, null, null);
        }
    }

    String authorization(URI endpoint, Instant now) {
        String audience = "https://" + endpoint.getHost().toLowerCase(Locale.ROOT);
        String claims = "{\"aud\":\"" + audience + "\",\"exp\":" + now.plusSeconds(3600).getEpochSecond() + "}";
        String unsigned = JWT_HEADER + "." + encode(claims);
        try {
            return "vapid t=" + unsigned + "." + ENCODER.encodeToString(sign(signingKey, unsigned)) + ", k=" + publicKey;
        } catch (GeneralSecurityException invalid) {
            throw new IllegalStateException("Could not sign Web Push request", invalid);
        }
    }

    private static Instant retryAt(Instant now, String header) {
        Instant earliest = now.plusSeconds(60);
        try {
            long seconds = Long.parseLong(header);
            return now.plusSeconds(Math.clamp(seconds, 60L, 86400L));
        } catch (NumberFormatException notSeconds) {
            try {
                Instant requested = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return requested.isAfter(earliest) ? requested : earliest;
            } catch (DateTimeParseException invalid) {
                return earliest;
            }
        }
    }

    private static PrivateKey readKeys(String publicKey, String privateKey) {
        try {
            byte[] publicBytes = Base64.getUrlDecoder().decode(publicKey);
            byte[] privateBytes = Base64.getUrlDecoder().decode(privateKey);
            if (publicBytes.length != 65 || publicBytes[0] != 4 || privateBytes.length != 32
                    || !ENCODER.encodeToString(publicBytes).equals(publicKey)) {
                throw new IllegalArgumentException();
            }
            var parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            var curve = parameters.getParameterSpec(ECParameterSpec.class);
            var point = new ECPoint(new BigInteger(1, Arrays.copyOfRange(publicBytes, 1, 33)),
                    new BigInteger(1, Arrays.copyOfRange(publicBytes, 33, 65)));
            var keys = KeyFactory.getInstance("EC");
            var signing = keys.generatePrivate(new ECPrivateKeySpec(new BigInteger(1, privateBytes), curve));
            var verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
            verifier.initVerify(keys.generatePublic(new ECPublicKeySpec(point, curve)));
            verifier.update((byte) 'v');
            if (!verifier.verify(sign(signing, "v"))) throw new IllegalArgumentException();
            return signing;
        } catch (GeneralSecurityException | IllegalArgumentException invalid) {
            throw new IllegalStateException("Configure a matching Web Push VAPID key pair");
        }
    }

    private static byte[] sign(PrivateKey key, String value) throws GeneralSecurityException {
        var signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initSign(key);
        signature.update(value.getBytes(StandardCharsets.US_ASCII));
        return signature.sign();
    }

    private static String encode(String value) {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.US_ASCII));
    }

    enum State { SENDING, ACCEPTED, RETRY, UNCERTAIN, REJECTED, CANCELED }

    record Result(State state, Integer providerStatus, Instant retryAt) {}
}
