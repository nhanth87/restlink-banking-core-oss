package et.restlink.spi.payment;

/** Stable step names used to derive step idempotency keys from the root key. */
public enum PaymentStepName {
    VALIDATE,
    ATOMIC_TRANSFER,
    RESERVE,
    CREDIT,
    CAPTURE,
    DEBIT,
    REFUND,
    RELEASE,
    RECONCILE
}
