package et.restlink.spi.command;

/** Read the current/available balance of an account. Result arrives as {@code BalanceEvent}. */
public record QueryBalance(
        String requestId,
        String tenantId,
        String accountId
) implements CoreBankingCommand {
    public QueryBalance {
        requestId = requestId == null || requestId.isBlank() ? "anon" : requestId;
    }
}
