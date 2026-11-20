package et.restlink.spi.command;

/** Open a deposit/savings/current account for an existing customer. */
public record OpenAccount(
        String requestId,
        String tenantId,
        String customerExternalId,
        String product,
        String currency
) implements CoreBankingCommand {
    public OpenAccount {
        requestId = requestId == null || requestId.isBlank() ? "anon" : requestId;
    }
}
