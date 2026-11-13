package br.com.autocuidado.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

final class PlanCreation {

    private PlanCreation() {}

    static RecurringPlan findRetry(EntityManager entityManager, long accountId, UUID requestId, String requestHash) {
        // Serialize creations for this account so a concurrent retry can read the committed result.
        entityManager.find(Account.class, accountId, LockModeType.PESSIMISTIC_WRITE);
        var previous = entityManager.createQuery("""
                FROM RecurringPlan WHERE accountId = :accountId AND creationRequestId = :requestId
                """, RecurringPlan.class)
                .setParameter("accountId", accountId)
                .setParameter("requestId", requestId)
                .getSingleResultOrNull();
        if (previous != null && !requestHash.equals(previous.creationRequestHash())) {
            throw new PlanCreationConflictException();
        }
        return previous;
    }

    static String hash(String canonicalRequest) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
