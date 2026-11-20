package et.restlink.wallet;

import et.restlink.spi.command.PostTransaction;
import et.restlink.spi.model.Money;
import et.restlink.spi.payment.MoneyStepResult;

import java.util.Optional;

/**
 * Persistence seam for the native Phase-1 wallet. Double-entry, idempotent posting backed by Postgres.
 * Separated from {@link WalletLedgerProvider} so the ledger rules are unit-testable with an in-memory fake.
 */
public interface WalletLedgerRepository {

    String openAccount(String tenantId, String customerExternalId, String product, String currency);

    /**
     * Post a transaction leg idempotently. If {@code idempotencyKey} was already applied, return the
     * existing coreTxnId (no second write). Returns the ledger txn id.
     */
    String post(PostTransaction cmd);

    /**
     * Atomic debit+credit in one JTA transaction. Unique on {@code idempotencyKey} — retries return
     * the same coreTxnId. Locks accounts in account-id order to avoid deadlock.
     */
    MoneyStepResult transfer(String tenantId, String fromAccountId, String toAccountId,
                             Money amount, String narrative, String idempotencyKey,
                             String paymentId, String requestId);

    /** Compensating reverse of a prior effect (or no-op if never committed). */
    MoneyStepResult reverse(String tenantId, String accountId, String originalCoreTxnId,
                            Money amount, String narrative, String idempotencyKey,
                            String paymentId, String requestId);

    /** Look up a previously-applied idempotencyKey → coreTxnId (for reconcile). */
    Optional<String> findByIdempotencyKey(String tenantId, String idempotencyKey);

    Money availableBalance(String tenantId, String accountId);

    boolean live();
}
