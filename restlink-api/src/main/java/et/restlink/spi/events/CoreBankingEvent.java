package et.restlink.spi.events;

import com.microjainslee.api.SleeEvent;

/**
 * Sealed inbound events fired by a core RA adaptor into the SLEE (RA → SBB).
 * Same set for Fineract and Temenos.
 */
public sealed interface CoreBankingEvent extends SleeEvent
        permits CustomerCreatedEvent, AccountOpenedEvent, TransactionPostedEvent,
                BalanceEvent, CoreErrorEvent, CoreHeartbeatEvent {

    /** Correlation id of the originating command (or "-" for unsolicited events like heartbeat). */
    String requestId();

    /** Which engine produced this. */
    String provider();
}
