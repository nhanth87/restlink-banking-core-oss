package et.restlink.spi.events;

/** An account was opened/activated in the core engine. */
public record AccountOpenedEvent(
        String requestId,
        String provider,
        String tenantId,
        String accountId,
        String product,
        String currency,
        boolean ok,
        String detail
) implements CoreBankingEvent {
}
