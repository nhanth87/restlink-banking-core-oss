package et.restlink.spi.payment;

/**
 * Durable payment saga states. Terminal: {@link #COMPLETED}, {@link #COMPENSATED},
 * {@link #FAILED}, {@link #MANUAL_REVIEW} (operator must resolve — never silent re-post).
 */
public enum PaymentState {
    RECEIVED,
    VALIDATED,
    EXECUTING,
    DEBIT_RESERVED,
    CREDIT_CONFIRMED,
    DEBIT_CAPTURED,
    COMPLETED,
    COMPENSATING,
    COMPENSATED,
    RECONCILING,
    MANUAL_REVIEW,
    FAILED
}
