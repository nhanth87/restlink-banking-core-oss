package et.restlink.spi.command;

import et.restlink.spi.model.Money;

/**
 * First-class P2P / wallet transfer. Prefer a single engine-native atomic transfer when the
 * provider supports it; otherwise Temporal Saga orchestrates reserve → credit → capture.
 *
 * <p>{@code idempotencyKey} is the <em>root</em> money key. Step keys ({@code :reserve},
 * {@code :credit}, {@code :capture}, {@code :refund}) are derived from it — never invent a new
 * root on retry.
 */
public record TransferFunds(
        String requestId,
        String tenantId,
        String fromAccountId,
        String toAccountId,
        Money amount,
        String narrative,
        String idempotencyKey
) implements CoreBankingCommand {

    public TransferFunds {
        requestId = requestId == null || requestId.isBlank() ? "anon" : requestId;
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey required for TransferFunds");
        }
    }
}
