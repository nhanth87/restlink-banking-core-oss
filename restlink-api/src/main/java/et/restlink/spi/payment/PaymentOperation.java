package et.restlink.spi.payment;

/** Top-level money operation orchestrated by Temporal. */
public enum PaymentOperation {
    CASH_IN,
    CASH_OUT,
    TRANSFER
}
