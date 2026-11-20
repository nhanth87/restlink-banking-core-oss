package et.restlink.spi.events;

/** Result of a {@code PostTransaction}. {@code coreTxnId} is the engine's ledger id. */
public record TransactionPostedEvent(
        String requestId,
        String provider,
        String tenantId,
        String accountId,
        String coreTxnId,
        boolean ok,
        String detail
) implements CoreBankingEvent {
}
