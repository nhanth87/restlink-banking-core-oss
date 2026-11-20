package et.restlink.spi.events;

/** Periodic liveness from a core RA adaptor. {@code live} reflects a real engine probe, not RA start. */
public record CoreHeartbeatEvent(
        String requestId,
        String provider,
        boolean live,
        long epochMs
) implements CoreBankingEvent {
}
