package et.restlink.spi.events;

import et.restlink.spi.model.Money;

/** Result of a {@code QueryBalance}. */
public record BalanceEvent(
        String requestId,
        String provider,
        String tenantId,
        String accountId,
        Money available,
        Money ledger,
        boolean ok,
        String detail
) implements CoreBankingEvent {
}
