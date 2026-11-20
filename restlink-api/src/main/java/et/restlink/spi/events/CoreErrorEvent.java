package et.restlink.spi.events;

/** A command failed at the core engine or transport. Carries the failing command class for triage. */
public record CoreErrorEvent(
        String requestId,
        String provider,
        String tenantId,
        String commandType,
        String error
) implements CoreBankingEvent {
}
