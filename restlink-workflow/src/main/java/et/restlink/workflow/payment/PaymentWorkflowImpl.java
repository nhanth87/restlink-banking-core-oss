package et.restlink.workflow.payment;

import et.restlink.spi.payment.MoneyOutcomeKind;
import et.restlink.spi.payment.MoneyStepResult;
import et.restlink.spi.payment.PaymentState;
import et.restlink.spi.payment.PaymentStepName;
import et.restlink.spi.payment.ProviderCapabilities;
import et.restlink.workflow.payment.PaymentActivities.SagaBootstrapResult;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * Deterministic payment saga. No CDI, network, wall clock, or random — only Temporal APIs.
 *
 * <p><b>Failure model (money-critical):</b>
 * <ul>
 *   <li>Business rejects (insufficient funds, unsupported capability) are <em>returned</em> by the
 *       Activity as a {@link MoneyStepResult}, never thrown — a thrown {@code ApplicationFailure}
 *       fails the whole workflow execution and skips compensation. See {@code PaymentActivitiesImpl.finish}.</li>
 *   <li>Transient faults are thrown as retryable {@code ApplicationFailure} so Temporal retries the
 *       Activity; once retries are exhausted the {@link ActivityFailure} is caught here and turned
 *       into a compensating {@link StepOutcome} — the workflow NEVER dies without either completing,
 *       compensating, or parking in {@code MANUAL_REVIEW}.</li>
 *   <li>Compensation is registered <em>before</em> each forward Activity; the forward's committed
 *       {@code coreTxnId} is captured into the compensation closure so a real reversal (not a no-op)
 *       runs against the exact leg that committed.</li>
 * </ul>
 *
 * <p>Only {@code IDEMPOTENCY_CONFLICT} at bootstrap intentionally throws (terminal, non-recoverable).
 */
public final class PaymentWorkflowImpl implements PaymentWorkflow {

    private static final List<String> NON_RETRYABLE = List.of(
            "BUSINESS_REJECT", "IDEMPOTENCY_CONFLICT", "UNSUPPORTED_CAPABILITY");

    private static final Duration OPERATOR_WAIT = Duration.ofDays(7);

    private final PaymentActivities acts = Workflow.newActivityStub(
            PaymentActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setScheduleToCloseTimeout(Duration.ofMinutes(5))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setBackoffCoefficient(2.0)
                            .setMaximumInterval(Duration.ofSeconds(30))
                            .setMaximumAttempts(8)
                            .setDoNotRetry(NON_RETRYABLE.toArray(String[]::new))
                            .build())
                    .build());

    private PaymentRequest requestRef;
    private PaymentResult current = PaymentResult.of("unknown", PaymentState.RECEIVED, null, null);
    private int compensationFailures;
    private boolean retryReconcileRequested;
    private boolean retryCompensationRequested;

    @Override
    public PaymentResult execute(PaymentRequest request) {
        this.requestRef = request;
        current = PaymentResult.of(request.paymentId(), PaymentState.RECEIVED, null, null);

        SagaBootstrapResult boot = acts.bootstrapSaga(request);
        if (boot.conflict()) {
            current = PaymentResult.of(request.paymentId(), PaymentState.FAILED, null, boot.message());
            throw ApplicationFailure.newNonRetryableFailure(
                    boot.message() == null ? "IDEMPOTENCY_CONFLICT" : boot.message(),
                    "IDEMPOTENCY_CONFLICT");
        }
        if (!boot.created() && boot.existingState() != null) {
            PaymentState st = parseState(boot.existingState());
            current = PaymentResult.of(request.paymentId(), st, boot.existingCoreTxnId(), boot.message());
            if (st != null && (st == PaymentState.COMPLETED || st == PaymentState.COMPENSATED
                    || st == PaymentState.FAILED)) {
                return current;
            }
        }

        // Layout marker after money-safety audit (B1–B8). Bump maxSupported and branch when changing
        // control flow / compensation / mark interleaving — never edit unmarked history-sensitive code.
        Workflow.getVersion("PaymentWorkflow.money-safety-v1", Workflow.DEFAULT_VERSION, 1);

        mark(PaymentState.VALIDATED, null, null);
        ProviderCapabilities caps = acts.loadCapabilities(request.tenantId(), request.providerName());

        Saga saga = new Saga(new Saga.Options.Builder().setParallelCompensation(false).build());
        mark(PaymentState.EXECUTING, null, null);

        StepOutcome outcome = switch (request.operation()) {
            case TRANSFER -> executeTransfer(request, caps, saga);
            case CASH_IN -> executeSingleLeg(request, caps, saga, PaymentStepName.CREDIT, true);
            case CASH_OUT -> executeSingleLeg(request, caps, saga, PaymentStepName.DEBIT, false);
        };

        if (outcome.ok()) {
            mark(PaymentState.COMPLETED, outcome.coreTxnId(), "ok");
            return current;
        }
        if ("MANUAL_REVIEW".equals(outcome.type())) {
            mark(PaymentState.MANUAL_REVIEW, null, outcome.message());
            return current;
        }
        if ("BUSINESS_REJECT".equals(outcome.type()) || "UNSUPPORTED_CAPABILITY".equals(outcome.type())) {
            compensate(saga);
            finalizeAfterCompensation(PaymentState.FAILED, outcome.message());
            return current;
        }
        // Transient / infrastructure fault after retries exhausted → compensate, then surface.
        compensate(saga);
        finalizeAfterCompensation(PaymentState.COMPENSATED, outcome.message());
        return current;
    }

    private StepOutcome executeTransfer(PaymentRequest request, ProviderCapabilities caps, Saga saga) {
        if (caps.supportsNativeTransfer()) {
            String stepKey = stepKey(request, PaymentStepName.ATOMIC_TRANSFER);
            String[] fwdTxn = new String[1];
            saga.addCompensation(() ->
                    reverseQuiet(request, stepKey(request, PaymentStepName.REFUND), fwdTxn[0]));
            StepOutcome r = forward(PaymentStepName.ATOMIC_TRANSFER,
                    () -> acts.atomicTransfer(request, stepKey));
            if (r.ok()) {
                fwdTxn[0] = r.coreTxnId();
            }
            return r;
        }
        if (!caps.supportsDistributedSaga()) {
            return StepOutcome.fail(
                    "UNSUPPORTED_CAPABILITY",
                    "provider lacks NATIVE_ATOMIC_TRANSFER or full distributed saga caps");
        }
        return executeDistributedTransfer(request, saga);
    }

    private StepOutcome executeDistributedTransfer(PaymentRequest request, Saga saga) {
        String reserveKey = stepKey(request, PaymentStepName.RESERVE);
        String[] reserveTxn = new String[1];
        saga.addCompensation(() ->
                releaseQuiet(request, stepKey(request, PaymentStepName.RELEASE), reserveTxn[0]));
        StepOutcome reserved = forward(PaymentStepName.RESERVE, () -> acts.reserve(request, reserveKey));
        if (!reserved.ok()) {
            return reserved;
        }
        reserveTxn[0] = reserved.coreTxnId();
        mark(PaymentState.DEBIT_RESERVED, reserved.coreTxnId(), null);

        String creditKey = stepKey(request, PaymentStepName.CREDIT);
        String[] creditTxn = new String[1];
        saga.addCompensation(() ->
                reverseQuiet(request, stepKey(request, PaymentStepName.REFUND), creditTxn[0]));
        StepOutcome credited = forward(PaymentStepName.CREDIT, () -> acts.postCredit(request, creditKey));
        if (!credited.ok()) {
            return credited;
        }
        creditTxn[0] = credited.coreTxnId();
        mark(PaymentState.CREDIT_CONFIRMED, credited.coreTxnId(), null);

        String captureKey = stepKey(request, PaymentStepName.CAPTURE);
        StepOutcome captured = forward(PaymentStepName.CAPTURE,
                () -> acts.capture(request, captureKey, reserved.coreTxnId()));
        if (!captured.ok()) {
            return captured;
        }
        mark(PaymentState.DEBIT_CAPTURED, captured.coreTxnId(), null);
        String txn = captured.coreTxnId() != null ? captured.coreTxnId() : credited.coreTxnId();
        return StepOutcome.ok(txn);
    }

    private StepOutcome executeSingleLeg(
            PaymentRequest request, ProviderCapabilities caps, Saga saga,
            PaymentStepName step, boolean credit) {
        // v1: POST_LEG+LOOKUP (Fineract cash-in/out) is enough; transfer still needs native/distributed.
        // DEFAULT_VERSION: in-flight histories that only knew NATIVE_ATOMIC_TRANSFER.
        int v = Workflow.getVersion("PaymentWorkflow.single-leg-post-v1", Workflow.DEFAULT_VERSION, 1);
        boolean allowed = v == Workflow.DEFAULT_VERSION
                ? caps.supportsNativeTransfer()
                : caps.supportsSingleLeg();
        if (!allowed) {
            return StepOutcome.fail("UNSUPPORTED_CAPABILITY",
                    "provider cannot post a single money leg (needs POST_LEG+LOOKUP or NATIVE_ATOMIC_TRANSFER)");
        }
        String key = stepKey(request, step);
        String[] fwdTxn = new String[1];
        saga.addCompensation(() ->
                reverseQuiet(request, stepKey(request, PaymentStepName.REFUND), fwdTxn[0]));
        StepOutcome r = forward(step,
                () -> credit ? acts.postCredit(request, key) : acts.postDebit(request, key));
        if (r.ok()) {
            fwdTxn[0] = r.coreTxnId();
        }
        return r;
    }

    /** Runs a forward money Activity, mapping any exhausted-retry {@link ActivityFailure} to a
     *  compensating {@link StepOutcome} so the workflow can never die without cleanup. */
    private StepOutcome forward(PaymentStepName step, Supplier<MoneyStepResult> call) {
        MoneyStepResult r;
        try {
            r = call.get();
        } catch (ActivityFailure af) {
            return StepOutcome.fail(failureType(af), messageOf(af));
        }
        return requireOk(r, step);
    }

    private void compensate(Saga saga) {
        mark(PaymentState.COMPENSATING, null, null);
        try {
            saga.compensate();
        } catch (RuntimeException e) {
            // Individual reversals swallow their own errors (see reverseQuiet); reaching here is rare.
            compensationFailures++;
        }
    }

    /** After compensation, park in MANUAL_REVIEW if any reversal failed; else the requested terminal state. */
    private void finalizeAfterCompensation(PaymentState terminalOnClean, String message) {
        if (compensationFailures > 0) {
            mark(PaymentState.MANUAL_REVIEW, null,
                    "compensation incomplete (" + compensationFailures + " failed): " + message);
            // Wait (bounded) for an operator to resolve the ledger out-of-band, then signal.
            boolean signaled = Workflow.await(OPERATOR_WAIT, () -> retryCompensationRequested);
            if (signaled) {
                retryCompensationRequested = false;
                mark(terminalOnClean, null, "resolved after manual review");
            } else {
                mark(PaymentState.MANUAL_REVIEW, null, "manual review unresolved (timed out)");
            }
            return;
        }
        mark(terminalOnClean, null, message);
    }

    /** Compensation for a committed leg. Best-effort but records failure so MANUAL_REVIEW is reachable. */
    private void reverseQuiet(PaymentRequest request, String stepKey, String forwardCoreTxnId) {
        if (forwardCoreTxnId == null || forwardCoreTxnId.isBlank()) {
            return; // forward never committed → nothing to reverse
        }
        try {
            MoneyStepResult r = acts.reverse(request, stepKey, forwardCoreTxnId);
            if (r == null || (r.kind() != MoneyOutcomeKind.COMMITTED && r.kind() != MoneyOutcomeKind.NO_OP)) {
                compensationFailures++;
            }
        } catch (RuntimeException e) {
            compensationFailures++;
        }
    }

    private void releaseQuiet(PaymentRequest request, String stepKey, String reserveTxnId) {
        if (reserveTxnId == null || reserveTxnId.isBlank()) {
            return;
        }
        try {
            MoneyStepResult r = acts.release(request, stepKey, reserveTxnId);
            if (r == null || (r.kind() != MoneyOutcomeKind.COMMITTED && r.kind() != MoneyOutcomeKind.NO_OP)) {
                compensationFailures++;
            }
        } catch (RuntimeException e) {
            compensationFailures++;
        }
    }

    private StepOutcome requireOk(MoneyStepResult r, PaymentStepName step) {
        if (r == null) {
            return StepOutcome.fail("TRANSIENT", "null result for " + step);
        }
        return switch (r.kind()) {
            case COMMITTED -> StepOutcome.ok(r.coreTxnId());
            // A NO_OP on a FORWARD step means the effect wasn't applied — never treat as success.
            case NO_OP -> StepOutcome.fail("TRANSIENT", "forward step " + step + " returned no-op");
            case BUSINESS_REJECT -> StepOutcome.fail(
                    "BUSINESS_REJECT", r.message() == null ? "rejected" : r.message());
            case AMBIGUOUS -> resolveAmbiguous(r, step);
            case TRANSIENT -> StepOutcome.fail(
                    "TRANSIENT", r.message() == null ? "transient" : r.message());
            case NOT_FOUND -> StepOutcome.fail("TRANSIENT", "not found");
        };
    }

    private StepOutcome resolveAmbiguous(MoneyStepResult r, PaymentStepName step) {
        mark(PaymentState.RECONCILING, null, r.message());
        MoneyStepResult recon = acts.reconcile(requestRef, stepKey(requestRef, step), step);
        if (recon.committed()) {
            return StepOutcome.ok(recon.coreTxnId());
        }
        if (recon.kind() == MoneyOutcomeKind.NOT_FOUND || recon.kind() == MoneyOutcomeKind.NO_OP) {
            return StepOutcome.fail("TRANSIENT", "ambiguous then absent — retry forward");
        }
        mark(PaymentState.MANUAL_REVIEW, null, "ambiguous commit unresolved");
        Workflow.await(OPERATOR_WAIT, () -> retryReconcileRequested);
        retryReconcileRequested = false;
        MoneyStepResult again = acts.reconcile(requestRef, stepKey(requestRef, step), step);
        if (again.committed()) {
            return StepOutcome.ok(again.coreTxnId());
        }
        return StepOutcome.fail("MANUAL_REVIEW", "unresolved ambiguous commit");
    }

    /**
     * Persist durable saga state via an Activity. Each state uses its own {@code getVersion}
     * changeId so a future edit can add/remove/reorder a single mark without breaking replay of
     * in-flight histories (bump that changeId's maxSupported and branch).
     */
    private void mark(PaymentState state, String coreTxnId, String message) {
        current = PaymentResult.of(current.paymentId(), state, coreTxnId, message);
        int v = Workflow.getVersion(
                "PaymentWorkflow.mark-" + state.name(), Workflow.DEFAULT_VERSION, 1);
        // v1 (and any pre-marker DEFAULT_VERSION history): schedule the activity.
        // To drop this mark later: raise maxSupported to 2 and only call when v < 2.
        if (v == Workflow.DEFAULT_VERSION || v >= 1) {
            acts.markSagaState(current.paymentId(), state.name(), coreTxnId, message);
        }
    }

    private static String failureType(ActivityFailure af) {
        Throwable c = af.getCause();
        if (c instanceof ApplicationFailure appFail && appFail.getType() != null) {
            return appFail.getType();
        }
        return "TRANSIENT";
    }

    private static String messageOf(ActivityFailure af) {
        Throwable c = af.getCause();
        if (c instanceof ApplicationFailure appFail && appFail.getOriginalMessage() != null) {
            return appFail.getOriginalMessage();
        }
        return af.getMessage();
    }

    private static PaymentState parseState(String raw) {
        try {
            return PaymentState.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null; // unknown/legacy state → treat as non-terminal, re-drive forward
        }
    }

    private static String stepKey(PaymentRequest request, PaymentStepName name) {
        return request.idempotencyKeyHash() + ":" + name.name().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public PaymentResult status() {
        return current;
    }

    @Override
    public void providerEvent(String eventId, String stepKey, String outcomeJson) {
        // Reserved for provider callback delivery; deduping is enforced durably by the inbox
        // (provider_event_inbox.event_id PK), so no unbounded in-workflow Set is kept here.
    }

    @Override
    public void retryReconcile() {
        retryReconcileRequested = true;
    }

    @Override
    public void retryCompensation() {
        retryCompensationRequested = true;
    }

    private record StepOutcome(boolean ok, String type, String coreTxnId, String message) {
        static StepOutcome ok(String coreTxnId) {
            return new StepOutcome(true, null, coreTxnId, null);
        }

        static StepOutcome fail(String type, String message) {
            return new StepOutcome(false, type, null, message);
        }
    }
}
