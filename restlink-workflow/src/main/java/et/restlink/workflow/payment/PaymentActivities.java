package et.restlink.workflow.payment;

import et.restlink.spi.payment.MoneyStepResult;
import et.restlink.spi.payment.PaymentStepName;
import et.restlink.spi.payment.ProviderCapabilities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Side-effecting Activities. Implementations live in restlink-app (CDI) and call
 * {@link et.restlink.spi.CoreBankingPort} / Postgres — never called from Workflow directly for I/O.
 *
 * <p>All money Activities are <em>idempotent</em> on the step key. Temporal may run them more than
 * once; the durable unique index is the anti-double-charge fence.
 */
@ActivityInterface
public interface PaymentActivities {

    @ActivityMethod
    SagaBootstrapResult bootstrapSaga(PaymentRequest request);

    @ActivityMethod
    ProviderCapabilities loadCapabilities(String tenantId, String providerName);

    @ActivityMethod
    MoneyStepResult atomicTransfer(PaymentRequest request, String stepKey);

    @ActivityMethod
    MoneyStepResult postDebit(PaymentRequest request, String stepKey);

    @ActivityMethod
    MoneyStepResult postCredit(PaymentRequest request, String stepKey);

    @ActivityMethod
    MoneyStepResult reserve(PaymentRequest request, String stepKey);

    @ActivityMethod
    MoneyStepResult capture(PaymentRequest request, String stepKey, String reserveTxnId);

    @ActivityMethod
    MoneyStepResult release(PaymentRequest request, String stepKey, String reserveTxnId);

    @ActivityMethod
    MoneyStepResult reverse(PaymentRequest request, String stepKey, String originalTxnId);

    @ActivityMethod
    MoneyStepResult reconcile(PaymentRequest request, String stepKey, PaymentStepName step);

    @ActivityMethod
    void markSagaState(String paymentId, String state, String coreTxnId, String message);

    /** Immutable bootstrap outcome — conflict when fingerprint mismatches an existing saga. */
    record SagaBootstrapResult(
            boolean created,
            boolean conflict,
            String existingState,
            String existingCoreTxnId,
            String message
    ) implements java.io.Serializable {
        public static SagaBootstrapResult ofCreated() {
            return new SagaBootstrapResult(true, false, null, null, null);
        }

        public static SagaBootstrapResult ofReplay(String state, String coreTxnId) {
            return new SagaBootstrapResult(false, false, state, coreTxnId, "idempotent replay");
        }

        public static SagaBootstrapResult ofConflict(String message) {
            return new SagaBootstrapResult(false, true, null, null, message);
        }
    }
}
