package et.restlink.wallet;

import et.restlink.persist.LedgerEntry;
import et.restlink.persist.TxnJournal;
import et.restlink.persist.WalletAccount;
import et.restlink.spi.command.PostTransaction;
import et.restlink.spi.model.Money;
import et.restlink.spi.payment.MoneyStepResult;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Postgres double-entry wallet. Idempotency is durable: the unique index on
 * (tenant_id, idempotency_key) makes a duplicate {@code post}/{@code transfer} return the prior
 * coreTxnId instead of writing twice — even across a full cluster restart.
 */
@ApplicationScoped
public class PgWalletLedgerRepository implements WalletLedgerRepository {

    @Inject
    AgroalDataSource dataSource;

    @Override
    @Transactional
    public String openAccount(String tenantId, String customerExternalId, String product, String currency) {
        WalletAccount a = new WalletAccount();
        a.accountId = "ACC-" + UUID.randomUUID().toString().substring(0, 12);
        a.tenantId = tenantId;
        a.customerExternalId = customerExternalId;
        a.product = product == null ? "WALLET" : product;
        a.currency = currency == null ? "ETB" : currency;
        a.balance = BigDecimal.ZERO;
        a.createdMs = System.currentTimeMillis();
        a.persist();
        return a.accountId;
    }

    @Override
    @Transactional
    public String post(PostTransaction cmd) {
        Optional<String> prior = findByIdempotencyKey(cmd.tenantId(), cmd.idempotencyKey());
        if (prior.isPresent()) return prior.get();

        // Pessimistic row lock: two concurrent legs on the same account must serialize, else the
        // absolute `SET balance = ?` write loses an update and corrupts the balance (money create/destroy).
        WalletAccount acct = WalletAccount.findById(cmd.accountId(), LockModeType.PESSIMISTIC_WRITE);
        if (acct == null) throw new IllegalStateException("no wallet account " + cmd.accountId());

        Money m = cmd.amount();
        BigDecimal amount = m.amount();
        boolean credit = cmd.direction() == PostTransaction.Direction.CREDIT;
        BigDecimal next = credit ? acct.balance.add(amount) : acct.balance.subtract(amount);
        if (next.signum() < 0) throw new IllegalStateException("insufficient funds on " + acct.accountId);
        acct.balance = next;
        acct.persist();

        String coreTxnId = "TXN-" + UUID.randomUUID().toString().substring(0, 12);
        long now = System.currentTimeMillis();
        writeLeg(coreTxnId, cmd.tenantId(), acct.accountId, cmd.direction().name(), amount,
                m.currency(), cmd.narrative(), now);
        writeJournal(coreTxnId, cmd.tenantId(), cmd.requestId(), cmd.idempotencyKey(),
                acct.accountId, "COMPLETED", null, null, false, now);
        return coreTxnId;
    }

    @Override
    @Transactional
    public MoneyStepResult transfer(String tenantId, String fromAccountId, String toAccountId,
                                    Money amount, String narrative, String idempotencyKey,
                                    String paymentId, String requestId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return MoneyStepResult.businessReject("idempotencyKey required");
        }
        Optional<String> prior = findByIdempotencyKey(tenantId, idempotencyKey);
        if (prior.isPresent()) {
            return MoneyStepResult.committed(prior.get(), prior.get());
        }
        if (fromAccountId == null || toAccountId == null || fromAccountId.equals(toAccountId)) {
            return MoneyStepResult.businessReject("from/to accounts required and distinct");
        }
        if (amount == null || amount.amount().signum() <= 0) {
            return MoneyStepResult.businessReject("amount must be positive");
        }

        // Lock in sorted account-id order to avoid deadlock under concurrent opposing transfers.
        String firstId = fromAccountId.compareTo(toAccountId) <= 0 ? fromAccountId : toAccountId;
        String secondId = firstId.equals(fromAccountId) ? toAccountId : fromAccountId;
        WalletAccount first = WalletAccount.findById(firstId, LockModeType.PESSIMISTIC_WRITE);
        WalletAccount second = WalletAccount.findById(secondId, LockModeType.PESSIMISTIC_WRITE);
        if (first == null || second == null) {
            return MoneyStepResult.businessReject("account not found");
        }
        WalletAccount from = fromAccountId.equals(first.accountId) ? first : second;
        WalletAccount to = toAccountId.equals(first.accountId) ? first : second;

        if (!tenantId.equals(from.tenantId) || !tenantId.equals(to.tenantId)) {
            return MoneyStepResult.businessReject("tenant mismatch");
        }
        if (!from.currency.equals(amount.currency()) || !to.currency.equals(amount.currency())) {
            return MoneyStepResult.businessReject("currency mismatch");
        }
        BigDecimal nextFrom = from.balance.subtract(amount.amount());
        if (nextFrom.signum() < 0) {
            return MoneyStepResult.businessReject("insufficient funds on " + from.accountId);
        }
        from.balance = nextFrom;
        to.balance = to.balance.add(amount.amount());
        from.persist();
        to.persist();

        String coreTxnId = "TXN-" + UUID.randomUUID().toString().substring(0, 12);
        long now = System.currentTimeMillis();
        writeLeg(coreTxnId, tenantId, from.accountId, "DEBIT", amount.amount(), amount.currency(),
                narrative == null ? "transfer-out" : narrative, now);
        writeLeg(coreTxnId, tenantId, to.accountId, "CREDIT", amount.amount(), amount.currency(),
                narrative == null ? "transfer-in" : narrative, now);
        writeJournal(coreTxnId, tenantId, requestId, idempotencyKey, from.accountId,
                "COMPLETED", paymentId, idempotencyKey, false, now);
        return MoneyStepResult.committed(coreTxnId, coreTxnId);
    }

    /**
     * Reverses a committed forward leg (or full 2-leg transfer) identified by {@code originalCoreTxnId}.
     * Each original ledger leg is undone with its OPPOSITE direction against its OWN account — never a
     * blanket credit to {@code fromAccountId} (which would create money when reversing a credit leg).
     * Idempotent on {@code idempotencyKey}; compensation is authoritative so balances may go negative.
     */
    @Override
    @Transactional
    public MoneyStepResult reverse(String tenantId, String accountId, String originalCoreTxnId,
                                   Money amount, String narrative, String idempotencyKey,
                                   String paymentId, String requestId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return MoneyStepResult.businessReject("idempotencyKey required");
        }
        Optional<String> prior = findByIdempotencyKey(tenantId, idempotencyKey);
        if (prior.isPresent()) {
            return MoneyStepResult.committed(prior.get(), prior.get()); // idempotent replay
        }
        if (originalCoreTxnId == null || originalCoreTxnId.isBlank()) {
            return MoneyStepResult.noOp("forward never committed");
        }

        @SuppressWarnings("unchecked")
        java.util.List<LedgerEntry> legs = (java.util.List<LedgerEntry>) (java.util.List<?>)
                LedgerEntry.list("coreTxnId = ?1 and tenantId = ?2", originalCoreTxnId, tenantId);
        if (legs.isEmpty()) {
            return MoneyStepResult.noOp("nothing to reverse");
        }

        // Lock every affected account in sorted id order (deadlock-free), then apply opposite legs.
        java.util.List<String> ids = legs.stream().map(l -> l.accountId).distinct().sorted().toList();
        java.util.Map<String, WalletAccount> locked = new java.util.HashMap<>();
        for (String id : ids) {
            WalletAccount a = WalletAccount.findById(id, LockModeType.PESSIMISTIC_WRITE);
            if (a == null || !tenantId.equals(a.tenantId)) {
                return MoneyStepResult.businessReject("reverse: account not found " + id);
            }
            locked.put(id, a);
        }

        String reversalTxn = "TXN-" + UUID.randomUUID().toString().substring(0, 12);
        long now = System.currentTimeMillis();
        for (LedgerEntry leg : legs) {
            WalletAccount a = locked.get(leg.accountId);
            boolean origCredit = "CREDIT".equals(leg.direction);
            // opposite direction: undo a credit by debiting, undo a debit by crediting
            a.balance = origCredit ? a.balance.subtract(leg.amount) : a.balance.add(leg.amount);
            a.persist();
            writeLeg(reversalTxn, tenantId, a.accountId, origCredit ? "DEBIT" : "CREDIT",
                    leg.amount, leg.currency, narrative == null ? "reverse" : narrative, now);
        }
        String journalAcct = legs.get(0).accountId;
        writeJournal(reversalTxn, tenantId, requestId, idempotencyKey, journalAcct,
                "COMPLETED", paymentId, idempotencyKey, true, now);
        return MoneyStepResult.committed(reversalTxn, reversalTxn);
    }

    @Override
    public Optional<String> findByIdempotencyKey(String tenantId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return Optional.empty();
        TxnJournal j = TxnJournal.find("tenantId = ?1 and idempotencyKey = ?2", tenantId, idempotencyKey)
                .firstResult();
        return Optional.ofNullable(j).map(x -> x.coreTxnId);
    }

    @Override
    public Money availableBalance(String tenantId, String accountId) {
        WalletAccount acct = WalletAccount.findById(accountId);
        if (acct == null || !tenantId.equals(acct.tenantId)) {
            throw new IllegalStateException("no wallet account " + accountId + " for tenant " + tenantId);
        }
        return new Money(acct.balance, acct.currency);
    }

    @Override
    public boolean live() {
        try (var c = dataSource.getConnection()) {
            return c.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }

    private static void writeLeg(String coreTxnId, String tenantId, String accountId, String direction,
                                 BigDecimal amount, String currency, String narrative, long now) {
        LedgerEntry leg = new LedgerEntry();
        leg.coreTxnId = coreTxnId;
        leg.tenantId = tenantId;
        leg.accountId = accountId;
        leg.direction = direction;
        leg.amount = amount;
        leg.currency = currency;
        leg.narrative = narrative;
        leg.createdMs = now;
        leg.persist();
    }

    private static void writeJournal(String coreTxnId, String tenantId, String requestId, String idemKey,
                                     String accountId, String state, String paymentId, String stepKey,
                                     boolean compensation, long now) {
        TxnJournal j = new TxnJournal();
        j.coreTxnId = coreTxnId;
        j.tenantId = tenantId;
        j.requestId = requestId;
        j.idempotencyKey = idemKey;
        j.accountId = accountId;
        j.state = state;
        j.paymentId = paymentId;
        j.stepKey = stepKey;
        j.compensation = compensation;
        j.createdMs = now;
        j.persist();
    }
}
