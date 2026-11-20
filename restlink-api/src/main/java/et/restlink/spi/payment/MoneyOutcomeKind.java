package et.restlink.spi.payment;

/**
 * Result classification for money Activities. Business rejects are non-retryable;
 * transport failures retry; ambiguous commits go through reconcile before any re-post.
 */
public enum MoneyOutcomeKind {
    COMMITTED,
    BUSINESS_REJECT,
    TRANSIENT,
    AMBIGUOUS,
    NOT_FOUND,
    NO_OP
}
