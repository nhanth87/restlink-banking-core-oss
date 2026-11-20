package et.restlink.wallet;

import et.restlink.spi.command.PostTransaction;
import et.restlink.spi.model.BalanceQuery;
import et.restlink.spi.model.Money;
import et.restlink.spi.payment.MoneyOutcomeKind;
import et.restlink.spi.payment.MoneyStepResult;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-level atomic transfer / idempotency fence without Postgres.
 */
class AtomicTransferIdempotencyTest {

    @Test
    void sameStepKeyDoesNotDoubleMove() {
        MemLedger ledger = new MemLedger();
        ledger.open("t1", "cust", "WALLET", "ETB");
        String from = ledger.accounts.keySet().iterator().next();
        String to = ledger.open("t1", "cust2", "WALLET", "ETB");
        ledger.balances.put(from, new BigDecimal("100"));
        ledger.balances.put(to, BigDecimal.ZERO);

        Money amt = new Money(new BigDecimal("25"), "ETB");
        MoneyStepResult r1 = ledger.transfer("t1", from, to, amt, "p2p", "step-1", "pay-1", "req-1");
        MoneyStepResult r2 = ledger.transfer("t1", from, to, amt, "p2p", "step-1", "pay-1", "req-1");

        assertTrue(r1.committed());
        assertEquals(r1.coreTxnId(), r2.coreTxnId());
        assertEquals(1, ledger.transferCount.get());
        assertEquals(new BigDecimal("75"), ledger.balances.get(from));
        assertEquals(new BigDecimal("25"), ledger.balances.get(to));
    }

    @Test
    void queryBalanceFindsOpenAccountAndMissesUnknown() {
        MemLedger ledger = new MemLedger();
        String id = ledger.open("t1", "cust", "WALLET", "ETB");
        ledger.balances.put(id, new BigDecimal("12.50"));
        WalletLedgerProvider port = new WalletLedgerProvider(ledger);
        BalanceQuery found = port.queryBalance("t1", id);
        assertEquals(BalanceQuery.Kind.FOUND, found.kind());
        assertEquals(new BigDecimal("12.50"), found.snapshot().available().amount());
        BalanceQuery miss = port.queryBalance("t1", "ACC-nope");
        assertEquals(BalanceQuery.Kind.NOT_FOUND, miss.kind());
    }

    @Test
    void reverseIsNoOpWhenForwardNeverCommitted() {
        MemLedger ledger = new MemLedger();
        MoneyStepResult r = ledger.reverse("t1", "ACC-X", null,
                new Money(new BigDecimal("1"), "ETB"), "comp", "hash:refund", "pay", "req");
        assertEquals(MoneyOutcomeKind.NO_OP, r.kind());
    }

    /** Minimal in-memory ledger mirroring PgWalletLedgerRepository transfer contract. */
    static final class MemLedger implements WalletLedgerRepository {
        final Map<String, BigDecimal> balances = new ConcurrentHashMap<>();
        final Map<String, String> accounts = new ConcurrentHashMap<>();
        final Map<String, String> idem = new ConcurrentHashMap<>();
        final AtomicInteger transferCount = new AtomicInteger();

        String open(String tenant, String cust, String product, String ccy) {
            return openAccount(tenant, cust, product, ccy);
        }

        @Override
        public String openAccount(String tenantId, String customerExternalId, String product, String currency) {
            String id = "ACC-" + accounts.size();
            accounts.put(id, tenantId);
            balances.put(id, BigDecimal.ZERO);
            return id;
        }

        @Override
        public String post(PostTransaction cmd) {
            Optional<String> prior = findByIdempotencyKey(cmd.tenantId(), cmd.idempotencyKey());
            if (prior.isPresent()) return prior.get();
            BigDecimal bal = balances.get(cmd.accountId());
            BigDecimal next = cmd.direction() == PostTransaction.Direction.CREDIT
                    ? bal.add(cmd.amount().amount()) : bal.subtract(cmd.amount().amount());
            if (next.signum() < 0) throw new IllegalStateException("insufficient");
            balances.put(cmd.accountId(), next);
            String txn = "TXN-" + idem.size();
            idem.put(cmd.tenantId() + "|" + cmd.idempotencyKey(), txn);
            return txn;
        }

        @Override
        public MoneyStepResult transfer(String tenantId, String fromAccountId, String toAccountId,
                                        Money amount, String narrative, String idempotencyKey,
                                        String paymentId, String requestId) {
            Optional<String> prior = findByIdempotencyKey(tenantId, idempotencyKey);
            if (prior.isPresent()) return MoneyStepResult.committed(prior.get(), prior.get());
            BigDecimal fromBal = balances.get(fromAccountId);
            BigDecimal toBal = balances.get(toAccountId);
            BigDecimal next = fromBal.subtract(amount.amount());
            if (next.signum() < 0) return MoneyStepResult.businessReject("insufficient");
            balances.put(fromAccountId, next);
            balances.put(toAccountId, toBal.add(amount.amount()));
            String txn = "TXN-T" + transferCount.incrementAndGet();
            idem.put(tenantId + "|" + idempotencyKey, txn);
            return MoneyStepResult.committed(txn, txn);
        }

        @Override
        public MoneyStepResult reverse(String tenantId, String accountId, String originalCoreTxnId,
                                       Money amount, String narrative, String idempotencyKey,
                                       String paymentId, String requestId) {
            Optional<String> prior = findByIdempotencyKey(tenantId, idempotencyKey);
            if (prior.isPresent()) return MoneyStepResult.committed(prior.get(), prior.get());
            if (originalCoreTxnId == null || originalCoreTxnId.isBlank()) {
                return MoneyStepResult.noOp("forward never committed");
            }
            return MoneyStepResult.noOp("ok");
        }

        @Override
        public Optional<String> findByIdempotencyKey(String tenantId, String idempotencyKey) {
            return Optional.ofNullable(idem.get(tenantId + "|" + idempotencyKey));
        }

        @Override
        public Money availableBalance(String tenantId, String accountId) {
            if (!tenantId.equals(accounts.get(accountId))) {
                throw new IllegalStateException("no wallet account " + accountId + " for tenant " + tenantId);
            }
            return new Money(balances.get(accountId), "ETB");
        }

        @Override
        public boolean live() { return true; }
    }
}
