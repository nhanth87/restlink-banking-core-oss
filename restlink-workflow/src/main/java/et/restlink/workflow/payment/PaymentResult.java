package et.restlink.workflow.payment;

import et.restlink.spi.payment.PaymentState;

import java.io.Serializable;
import java.util.Objects;

/** Terminal / queryable payment result returned by the Workflow. */
public record PaymentResult(
        String paymentId,
        PaymentState state,
        String coreTxnId,
        String message
) implements Serializable {

    public PaymentResult {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(state, "state");
    }

    public static PaymentResult of(String paymentId, PaymentState state, String coreTxnId, String message) {
        return new PaymentResult(paymentId, state, coreTxnId, message);
    }
}
