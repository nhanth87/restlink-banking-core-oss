package et.restlink.kyc;

import et.restlink.spi.payment.PaymentOperation;

import java.util.Locale;
import java.util.Objects;

/**
 * Minimum viable KYC gate: cash-out and transfer-out require {@code APPROVED}.
 * Cash-in (receiving) is allowed while KYC is pending.
 */
public final class KycGate {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    private KycGate() {}

    public sealed interface Decision permits Allow, Blocked {
        default boolean blocked() {
            return this instanceof Blocked;
        }
    }

    public record Allow() implements Decision {}

    public record Blocked(String customerId, String kycStatus, String message) implements Decision {
        public Blocked {
            Objects.requireNonNull(message, "message");
        }
    }

    public static boolean requiresApproved(PaymentOperation op) {
        return op == PaymentOperation.CASH_OUT || op == PaymentOperation.TRANSFER;
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return PENDING;
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean validStatus(String raw) {
        String s = normalize(raw);
        return PENDING.equals(s) || APPROVED.equals(s) || REJECTED.equals(s);
    }

    public static Decision check(PaymentOperation op, String customerId, String kycStatus) {
        if (!requiresApproved(op)) {
            return new Allow();
        }
        String status = normalize(kycStatus);
        if (APPROVED.equals(status)) {
            return new Allow();
        }
        String id = customerId == null ? "" : customerId;
        if (REJECTED.equals(status)) {
            return new Blocked(id, status, "KYC rejected");
        }
        return new Blocked(id, status, "KYC not approved");
    }
}
