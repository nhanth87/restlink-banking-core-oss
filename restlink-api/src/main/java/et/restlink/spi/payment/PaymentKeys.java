package et.restlink.spi.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Stable money keys. Root key comes from the client {@code Idempotency-Key}; step keys are
 * derived so retries never invent a new money identity.
 */
public final class PaymentKeys {

    private PaymentKeys() {}

    public static String stepKey(String rootIdempotencyKey, PaymentStepName step) {
        Objects.requireNonNull(rootIdempotencyKey, "rootIdempotencyKey");
        Objects.requireNonNull(step, "step");
        if (rootIdempotencyKey.isBlank()) {
            throw new IllegalArgumentException("rootIdempotencyKey blank");
        }
        return rootIdempotencyKey + ":" + step.name().toLowerCase(Locale.ROOT);
    }

    /** Opaque workflow id — never embed the raw client idempotency key. */
    public static String workflowId(String tenantId, String rootIdempotencyKey) {
        return "pay/" + require(tenantId) + "/" + sha256Hex(require(rootIdempotencyKey));
    }

    public static String paymentId(String tenantId, String rootIdempotencyKey) {
        return workflowId(tenantId, rootIdempotencyKey);
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            throw new IllegalStateException("sha-256 unavailable", e);
        }
    }

    /**
     * Canonical request fingerprint for conflict detection: same key + different payload
     * ⇒ {@code IDEMPOTENCY_CONFLICT}.
     */
    public static String requestFingerprint(String operation, String tenantId, String fromAccount,
                                            String toAccount, String amount, String currency) {
        String raw = String.join("|",
                nullToEmpty(operation),
                require(tenantId),
                nullToEmpty(fromAccount),
                nullToEmpty(toAccount),
                nullToEmpty(amount),
                nullToEmpty(currency).toUpperCase(Locale.ROOT));
        return sha256Hex(raw);
    }

    private static String require(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("required");
        return v.trim();
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v.trim();
    }
}
