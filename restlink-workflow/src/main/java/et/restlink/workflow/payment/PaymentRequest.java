package et.restlink.workflow.payment;

import et.restlink.spi.payment.PaymentOperation;

import java.io.Serializable;
import java.util.Objects;

/**
 * Opaque payment input for Temporal. Contains hashed keys / account ids — never PAN or raw PII.
 * The raw client {@code Idempotency-Key} is hashed before storage in Temporal history.
 */
public record PaymentRequest(
        String paymentId,
        String tenantId,
        PaymentOperation operation,
        String fromAccountId,
        String toAccountId,
        String amount,
        String currency,
        String narrative,
        /** SHA-256 of the client Idempotency-Key (opaque). */
        String idempotencyKeyHash,
        /** Canonical request fingerprint for conflict detection. */
        String requestFingerprint,
        String providerName
) implements Serializable {

    public PaymentRequest {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(idempotencyKeyHash, "idempotencyKeyHash");
        Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        Objects.requireNonNull(providerName, "providerName");
    }
}
