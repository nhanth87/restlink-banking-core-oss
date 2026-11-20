package et.restlink.spi.command;

import com.microjainslee.api.OutboundCommand;

/**
 * Sealed core-banking commands (SBB → core RA via {@code RaCommandPort.sendCommand}).
 * Both {@code ra-fineract} and {@code ra-temenos} accept the exact same set — that is what
 * makes the core engine swappable per tenant without touching SBB code.
 */
public sealed interface CoreBankingCommand extends OutboundCommand
        permits CreateCustomer, OpenAccount, PostTransaction, QueryBalance,
                TransferFunds, ReverseTransaction {

    /** Correlation id echoed back on the resulting {@code CoreBankingEvent}. */
    String requestId();

    /** Tenant whose core-banking binding must handle this command. */
    String tenantId();
}
