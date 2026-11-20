package et.restlink.spi.command;

import et.restlink.spi.model.Money;

/**
 * Compensating reverse / refund of a prior money effect. Must be idempotent on
 * {@code idempotencyKey} — safe to call when the forward step never committed (no-op).
 */
public record ReverseTransaction(
        String requestId,
        String tenantId,
        String accountId,
        String originalCoreTxnId,
        Money amount,
        String narrative,
        String idempotencyKey
) implements CoreBankingCommand {

    public ReverseTransaction {
        requestId = requestId == null || requestId.isBlank() ? "anon" : requestId;
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey required for ReverseTransaction");
        }
    }
}
