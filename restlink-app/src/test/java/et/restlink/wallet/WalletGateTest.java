package et.restlink.wallet;

import et.restlink.wallet.WalletGate.Snapshot;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalletGateTest {

    private static final BigDecimal TEN = new BigDecimal("10.00");
    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    @Test
    void missingSnapshotAllows() {
        WalletGate.Decision d = WalletGate.check("ACC-1", null, TEN, BigDecimal.ZERO, true);
        assertInstanceOf(WalletGate.Allow.class, d);
        assertFalse(d.blocked());
    }

    @Test
    void freezeBlocksCashInToo() {
        Snapshot frozen = new Snapshot(true, null, null);
        WalletGate.Decision d = WalletGate.check("ACC-1", frozen, TEN, BigDecimal.ZERO, false);
        assertInstanceOf(WalletGate.Frozen.class, d);
        assertEquals("ACC-1", ((WalletGate.Frozen) d).accountId());
        assertTrue(d.blocked());
    }

    @Test
    void perTxnBlocksOverLimit() {
        Snapshot snap = new Snapshot(false, HUNDRED, null);
        WalletGate.Decision over = WalletGate.check("ACC-1", snap, new BigDecimal("100.01"), BigDecimal.ZERO, false);
        assertInstanceOf(WalletGate.PerTxn.class, over);
        WalletGate.Decision ok = WalletGate.check("ACC-1", snap, HUNDRED, BigDecimal.ZERO, false);
        assertInstanceOf(WalletGate.Allow.class, ok);
    }

    @Test
    void dailyAppliesOnlyToDebit() {
        Snapshot snap = new Snapshot(false, null, HUNDRED);
        WalletGate.Decision cashIn = WalletGate.check("ACC-1", snap, HUNDRED, HUNDRED, false);
        assertInstanceOf(WalletGate.Allow.class, cashIn);
        WalletGate.Decision cashOut = WalletGate.check("ACC-1", snap, TEN, HUNDRED, true);
        assertInstanceOf(WalletGate.Daily.class, cashOut);
        WalletGate.Decision under = WalletGate.check("ACC-1", snap, TEN, new BigDecimal("90.00"), true);
        assertInstanceOf(WalletGate.Allow.class, under);
    }

    @Test
    void blankAccountIsAllow() {
        Snapshot frozen = new Snapshot(true, TEN, TEN);
        assertInstanceOf(WalletGate.Allow.class, WalletGate.check("", frozen, TEN, BigDecimal.ZERO, true));
        assertInstanceOf(WalletGate.Allow.class, WalletGate.check(null, frozen, TEN, BigDecimal.ZERO, true));
    }
}
