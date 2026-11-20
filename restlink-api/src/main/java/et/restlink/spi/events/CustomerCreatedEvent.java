package et.restlink.spi.events;

/** A customer (CIF) was created in the core engine. */
public record CustomerCreatedEvent(
        String requestId,
        String provider,
        String tenantId,
        String customerExternalId,
        boolean ok,
        String detail
) implements CoreBankingEvent {
}
