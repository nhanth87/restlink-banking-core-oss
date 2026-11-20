package et.restlink.temporal;

import et.restlink.spi.payment.PaymentOperation;
import et.restlink.workflow.payment.PaymentRequest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaymentActivitiesMoneyAccountTest {

    @Test
    void cashInReversesAgainstToAccount() {
        PaymentRequest r = new PaymentRequest(
                "pay/t1/a", "t1", PaymentOperation.CASH_IN, null, "SAV-9",
                "10", "ETB", "in", "h", "fp", "FINERACT");
        assertEquals("SAV-9", PaymentActivitiesImpl.moneyAccount(r));
    }

    @Test
    void cashOutReversesAgainstFromAccount() {
        PaymentRequest r = new PaymentRequest(
                "pay/t1/b", "t1", PaymentOperation.CASH_OUT, "SAV-9", null,
                "10", "ETB", "out", "h", "fp", "FINERACT");
        assertEquals("SAV-9", PaymentActivitiesImpl.moneyAccount(r));
    }
}
