package et.restlink.spi.payment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PaymentKeysTest {

    @Test
    void workflowIdIsOpaqueHash() {
        String id = PaymentKeys.workflowId("t1", "secret-key");
        assertEquals(true, id.startsWith("pay/t1/"));
        assertEquals(false, id.contains("secret-key"));
        assertEquals(id, PaymentKeys.paymentId("t1", "secret-key"));
    }

    @Test
    void fingerprintDetectsPayloadChange() {
        String a = PaymentKeys.requestFingerprint("TRANSFER", "t1", "A", "B", "10", "ETB");
        String b = PaymentKeys.requestFingerprint("TRANSFER", "t1", "A", "B", "11", "ETB");
        assertNotEquals(a, b);
        assertEquals(a, PaymentKeys.requestFingerprint("TRANSFER", "t1", "A", "B", "10", "ETB"));
    }

    @Test
    void stepKeyDerivedFromRoot() {
        assertEquals("root:reserve", PaymentKeys.stepKey("root", PaymentStepName.RESERVE));
    }
}
