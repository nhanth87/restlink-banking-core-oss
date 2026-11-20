package et.restlink.kyc;

import et.restlink.spi.payment.PaymentOperation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KycGateTest {

    @Test
    void cashInAllowedWhilePending() {
        KycGate.Decision d = KycGate.check(PaymentOperation.CASH_IN, "cust-1", KycGate.PENDING);
        assertInstanceOf(KycGate.Allow.class, d);
        assertFalse(d.blocked());
    }

    @Test
    void cashOutRequiresApproved() {
        KycGate.Decision pending = KycGate.check(PaymentOperation.CASH_OUT, "cust-1", null);
        assertInstanceOf(KycGate.Blocked.class, pending);
        assertEquals("KYC not approved", ((KycGate.Blocked) pending).message());

        KycGate.Decision rejected = KycGate.check(PaymentOperation.CASH_OUT, "cust-1", "rejected");
        assertInstanceOf(KycGate.Blocked.class, rejected);
        assertEquals("KYC rejected", ((KycGate.Blocked) rejected).message());

        KycGate.Decision ok = KycGate.check(PaymentOperation.CASH_OUT, "cust-1", "APPROVED");
        assertInstanceOf(KycGate.Allow.class, ok);
    }

    @Test
    void transferRequiresApproved() {
        assertTrue(KycGate.check(PaymentOperation.TRANSFER, "cust-1", "PENDING").blocked());
        assertFalse(KycGate.check(PaymentOperation.TRANSFER, "cust-1", "APPROVED").blocked());
    }

    @Test
    void validStatus() {
        assertTrue(KycGate.validStatus("approved"));
        assertTrue(KycGate.validStatus("PENDING"));
        assertFalse(KycGate.validStatus("maybe"));
        assertEquals(KycGate.APPROVED, KycGate.normalize(" approved "));
    }
}
