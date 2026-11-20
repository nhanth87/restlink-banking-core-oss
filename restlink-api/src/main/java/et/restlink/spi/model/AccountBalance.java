package et.restlink.spi.model;

import java.util.Objects;

/** Snapshot from a read-only {@code queryBalance}. Never a money side-effect. */
public record AccountBalance(
        String tenantId,
        String accountId,
        Money available,
        Money ledger,
        String provider
) {
    public AccountBalance {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(available, "available");
        Objects.requireNonNull(ledger, "ledger");
        provider = provider == null ? "" : provider;
    }
}
