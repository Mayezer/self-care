package br.com.autocuidado.auth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class AuthSecrets {
    private static final int NONCE_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final SecretKeySpec key;

    AuthSecrets(@Value("${app.auth.mfa-key:}") String value) {
        if (!value.isEmpty() && !value.matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("app.auth.mfa-key must contain 32 bytes encoded as hexadecimal");
        }
        key = value.isEmpty() ? null : new SecretKeySpec(HexFormat.of().parseHex(value), "AES");
    }

    void requireKey() {
        if (key == null) {
            throw new IllegalStateException("Configure app.auth.mfa-key for MFA");
        }
    }

    static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    static String token() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
    }

    static boolean validToken(String token) {
        return token != null && token.matches("[A-Za-z0-9_-]{43}");
    }

    static String digest(String token) {
        return HexFormat.of().formatHex(hash("SHA-256", token));
    }

    static byte[] hash(String algorithm, String value) {
        try {
            return MessageDigest.getInstance(algorithm).digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Digest unavailable", exception);
        }
    }

    static boolean matchesDigest(String token, String storedDigest) {
        return validToken(token) && storedDigest != null && equal(digest(token), storedDigest);
    }

    static boolean equal(String first, String second) {
        return MessageDigest.isEqual(first.getBytes(StandardCharsets.US_ASCII), second.getBytes(StandardCharsets.US_ASCII));
    }

    byte[] encrypt(long accountId, byte[] seed) {
        byte[] nonce = randomBytes(NONCE_BYTES);
        byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, accountId, nonce, seed);
        return ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array();
    }

    byte[] decrypt(long accountId, byte[] value) {
        var buffer = ByteBuffer.wrap(value);
        byte[] nonce = new byte[NONCE_BYTES];
        buffer.get(nonce);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);
        return crypt(Cipher.DECRYPT_MODE, accountId, nonce, ciphertext);
    }

    private byte[] crypt(int mode, long accountId, byte[] nonce, byte[] value) {
        requireKey();
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(ByteBuffer.allocate(Long.BYTES).putLong(accountId).array());
            return cipher.doFinal(value);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Authenticator seed cannot be processed", exception);
        }
    }

    static long step(Instant now) {
        return Math.floorDiv(now.getEpochSecond(), 30);
    }

    static boolean matchesTotp(byte[] seed, long step, String code) {
        return code != null && code.matches("[0-9]{6}") && equal(totp(seed, step), code);
    }

    static String totp(byte[] seed, long step) {
        try {
            var mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(seed, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int truncated = ByteBuffer.wrap(hash, offset, Integer.BYTES).getInt() & Integer.MAX_VALUE;
            return String.format(Locale.ROOT, "%06d", truncated % 1_000_000);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("TOTP algorithm unavailable", exception);
        }
    }

    static String base32(byte[] seed) {
        var result = new StringBuilder();
        int bits = 0;
        int buffer = 0;
        for (byte value : seed) {
            buffer = (buffer << 8) | Byte.toUnsignedInt(value);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                result.append(BASE32.charAt((buffer >>> bits) & 31));
            }
        }
        if (bits > 0) {
            result.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return result.toString();
    }
}
