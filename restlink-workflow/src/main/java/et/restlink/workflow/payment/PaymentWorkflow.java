package et.restlink.workflow.payment;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Durable payment saga. Exactly-once <em>business</em> effect is enforced by durable step keys,
 * not by Temporal Activity execution count (Activities are at-least-once).
 */
@WorkflowInterface
public interface PaymentWorkflow {

    @WorkflowMethod
    PaymentResult execute(PaymentRequest request);

    @QueryMethod
    PaymentResult status();

    /**
     * Provider callback / inbox delivery. {@code eventId} is deduped in Workflow state so duplicate
     * signals are no-ops.
     */
    @SignalMethod
    void providerEvent(String eventId, String stepKey, String outcomeJson);

    /** Operator: retry reconcile after MANUAL_REVIEW. Audited at the API layer. */
    @SignalMethod
    void retryReconcile();

    /** Operator: retry stuck compensation. */
    @SignalMethod
    void retryCompensation();
}
