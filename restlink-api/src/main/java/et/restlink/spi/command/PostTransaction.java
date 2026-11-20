package et.restlink.spi.command;

import et.restlink.spi.model.Money;

/**
 * Post a financial transaction (deposit/withdrawal/transfer leg) against an account.
 * {@code idempotencyKey} must dedupe retries at the RA/engine boundary — no double debit.
 */
public record PostTransaction(
        String requestId,
        String tenantId,
        String accountId,
        Direction direction,
        Money amount,
        String narrative,
        String idempotencyKey
) implements CoreBankingCommand {
    public enum Direction { DEBIT, CREDIT }

    public PostTransaction {
        requestId = requestId == null || requestId.isBlank() ? "anon" : requestId;
    }
}
