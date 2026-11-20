package et.restlink.spi.payment;

import java.util.Objects;

/**
 * Immutable Activity / engine money result. Never invent a new coreTxnId on retry —
 * return the prior one when the step key already exists.
 */
public record MoneyStepResult(
        MoneyOutcomeKind kind,
        String coreTxnId,
        String providerRef,
        String message
) {
    public MoneyStepResult {
        Objects.requireNonNull(kind, "kind");
    }

    public static MoneyStepResult committed(String coreTxnId, String providerRef) {
        return new MoneyStepResult(MoneyOutcomeKind.COMMITTED, coreTxnId, providerRef, null);
    }

    public static MoneyStepResult businessReject(String message) {
        return new MoneyStepResult(MoneyOutcomeKind.BUSINESS_REJECT, null, null, message);
    }

    public static MoneyStepResult transientFailure(String message) {
        return new MoneyStepResult(MoneyOutcomeKind.TRANSIENT, null, null, message);
    }

    public static MoneyStepResult ambiguous(String message) {
        return new MoneyStepResult(MoneyOutcomeKind.AMBIGUOUS, null, null, message);
    }

    public static MoneyStepResult notFound() {
        return new MoneyStepResult(MoneyOutcomeKind.NOT_FOUND, null, null, "not found");
    }

    public static MoneyStepResult noOp(String message) {
        return new MoneyStepResult(MoneyOutcomeKind.NO_OP, null, null, message);
    }

    public boolean committed() {
        return kind == MoneyOutcomeKind.COMMITTED;
    }
}
