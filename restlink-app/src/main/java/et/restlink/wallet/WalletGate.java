package et.restlink.wallet;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Pure freeze + limit decision. Missing snapshot = ACTIVE, unlimited.
 * Daily limit applies to spend (cash-out / transfer-out) only; freeze and per-txn apply to all money.
 */
public final class WalletGate {

    private WalletGate() {}

    public record Snapshot(boolean frozen, BigDecimal perTxnLimit, BigDecimal dailyLimit) {
        public static Snapshot open() {
            return new Snapshot(false, null, null);
        }
    }

    public sealed interface Decision permits Allow, Frozen, PerTxn, Daily {
        default boolean blocked() {
            return !(this instanceof Allow);
        }
    }

    public record Allow() implements Decision {}

    public record Frozen(String accountId) implements Decision {
        public Frozen {
            Objects.requireNonNull(accountId, "accountId");
        }
    }

    public record PerTxn(String accountId, BigDecimal limit, BigDecimal amount) implements Decision {}

    public record Daily(String accountId, BigDecimal limit, BigDecimal spent, BigDecimal amount)
            implements Decision {}

    public static Decision check(String accountId, Snapshot snap, BigDecimal amount,
                                 BigDecimal spentToday, boolean countsAsDebit) {
        if (accountId == null || accountId.isBlank()) {
            return new Allow();
        }
        Snapshot s = snap == null ? Snapshot.open() : snap;
        if (s.frozen()) {
            return new Frozen(accountId);
        }
        if (amount == null || amount.signum() <= 0) {
            return new Allow();
        }
        if (s.perTxnLimit() != null && amount.compareTo(s.perTxnLimit()) > 0) {
            return new PerTxn(accountId, s.perTxnLimit(), amount);
        }
        if (countsAsDebit && s.dailyLimit() != null) {
            BigDecimal spent = spentToday == null ? BigDecimal.ZERO : spentToday;
            if (spent.add(amount).compareTo(s.dailyLimit()) > 0) {
                return new Daily(accountId, s.dailyLimit(), spent, amount);
            }
        }
        return new Allow();
    }
}
