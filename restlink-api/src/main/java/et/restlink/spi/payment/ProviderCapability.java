package et.restlink.spi.payment;

/**
 * Money capabilities a {@link et.restlink.spi.CoreBankingPort} must advertise.
 * Cross-provider Saga is fail-closed unless reserve/reverse/reconcile are present.
 */
public enum ProviderCapability {
    /** Single-engine debit+credit in one local transaction. */
    NATIVE_ATOMIC_TRANSFER,
    /**
     * Idempotent single debit or credit (cash-in / cash-out). Not a two-sided transfer.
     * Must be paired with {@link #LOOKUP_BY_IDEMPOTENCY} or the saga will fail closed.
     */
    POST_LEG,
    /** Hold / authorize funds before capture. */
    RESERVE,
    /** Finalize a prior reserve. */
    CAPTURE,
    /** Release an unused reserve. */
    RELEASE,
    /** Reverse / refund a committed money effect. */
    REVERSE,
    /** Lookup prior effect by step idempotency key (required for ambiguous-commit reconcile). */
    LOOKUP_BY_IDEMPOTENCY
}
