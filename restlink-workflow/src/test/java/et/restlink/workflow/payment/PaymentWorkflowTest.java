package et.restlink.workflow.payment;

import et.restlink.spi.payment.MoneyStepResult;
import et.restlink.spi.payment.PaymentOperation;
import et.restlink.spi.payment.PaymentState;
import et.restlink.spi.payment.PaymentStepName;
import et.restlink.spi.payment.ProviderCapabilities;
import et.restlink.spi.payment.ProviderCapability;
import et.restlink.workflow.payment.PaymentActivities.SagaBootstrapResult;

import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowRule;

import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Deterministic Temporal workflow tests using {@link TestWorkflowRule} (JUnit4 — Temporal testing default).
 */
public class PaymentWorkflowTest {

    private final FakeActivities activities = new FakeActivities();

    @Rule
    public TestWorkflowRule testWorkflowRule =
            TestWorkflowRule.newBuilder()
                    .setWorkflowTypes(PaymentWorkflowImpl.class)
                    .setActivityImplementations(activities)
                    .build();

    private static ProviderCapabilities nativeCaps() {
        return ProviderCapabilities.of(
                ProviderCapability.NATIVE_ATOMIC_TRANSFER,
                ProviderCapability.LOOKUP_BY_IDEMPOTENCY,
                ProviderCapability.REVERSE);
    }

    private static ProviderCapabilities postLegCaps() {
        return ProviderCapabilities.of(
                ProviderCapability.POST_LEG,
                ProviderCapability.LOOKUP_BY_IDEMPOTENCY,
                ProviderCapability.REVERSE);
    }

    private static ProviderCapabilities distributedCaps() {
        return ProviderCapabilities.of(
                ProviderCapability.RESERVE,
                ProviderCapability.CAPTURE,
                ProviderCapability.RELEASE,
                ProviderCapability.REVERSE,
                ProviderCapability.LOOKUP_BY_IDEMPOTENCY);
    }

    @Test
    public void happyPathNativeTransfer() {
        activities.reset();
        activities.caps = nativeCaps();
        PaymentRequest req = request("happy-xfer", PaymentOperation.TRANSFER);
        PaymentResult result = stub(req.paymentId()).execute(req);
        assertEquals(PaymentState.COMPLETED, result.state());
        assertEquals(1, activities.atomicCalls.get());
        assertEquals(0, activities.reverseCalls.get());
    }

    @Test
    public void businessRejectNothingCommittedFailsWithoutReversal() {
        activities.reset();
        activities.caps = nativeCaps();
        activities.failAtomic = true; // returns BUSINESS_REJECT
        PaymentRequest req = request("reject-xfer", PaymentOperation.TRANSFER);
        PaymentResult result = stub(req.paymentId()).execute(req);
        // Nothing committed → terminal FAILED and NO reversal (never a phantom compensation).
        assertEquals(PaymentState.FAILED, result.state());
        assertEquals(0, activities.reverseCalls.get());
    }

    @Test
    public void thrownBusinessRejectDoesNotKillWorkflow() {
        // B1 regression: the real activity may THROW ApplicationFailure(BUSINESS_REJECT). The workflow
        // must catch the ActivityFailure, mark the saga terminal (FAILED) and never die uncaught.
        activities.reset();
        activities.caps = nativeCaps();
        activities.throwAtomicReject = true;
        PaymentRequest req = request("throw-reject", PaymentOperation.TRANSFER);
        PaymentResult result = stub(req.paymentId()).execute(req);
        assertEquals(PaymentState.FAILED, result.state());
        assertEquals(0, activities.reverseCalls.get());
    }

    @Test
    public void transientThrowExhaustsRetriesThenCompensates() {
        // A retryable ActivityFailure that never succeeds must, after retries, compensate and reach a
        // terminal state — not hang and not fail the workflow uncaught.
        activities.reset();
        activities.caps = nativeCaps();
        activities.throwTransient = true;
        PaymentRequest req = request("transient-xfer", PaymentOperation.TRANSFER);
        PaymentResult result = stub(req.paymentId()).execute(req);
        assertEquals(PaymentState.COMPENSATED, result.state());
    }

    @Test
    public void distributedCompensationReversesCommittedLegs() {
        // reserve + credit commit, capture rejects → both committed legs must be reversed/released.
        activities.reset();
        activities.caps = distributedCaps();
        activities.failCapture = true;
        PaymentRequest req = request("dist-comp", PaymentOperation.TRANSFER);
        PaymentResult result = stub(req.paymentId()).execute(req);
        assertEquals(PaymentState.FAILED, result.state());
        assertTrue("expected reserve+credit to be reversed", activities.reverseCalls.get() >= 2);
    }

    @Test
    public void idempotencyConflictIsNonRetryable() {
        activities.reset();
        activities.conflict = true;
        PaymentRequest req = request("conflict-in", PaymentOperation.CASH_IN);
        try {
            stub(req.paymentId()).execute(req);
            fail("expected WorkflowFailedException");
        } catch (WorkflowFailedException e) {
            Throwable c = e.getCause();
            while (c != null && !(c instanceof ApplicationFailure)) {
                c = c.getCause();
            }
            assertTrue(c instanceof ApplicationFailure);
            assertEquals("IDEMPOTENCY_CONFLICT", ((ApplicationFailure) c).getType());
        }
    }

    @Test
    public void unsupportedCapabilityFailsClosed() {
        activities.reset();
        activities.caps = ProviderCapabilities.of();
        PaymentRequest req = request("nocaps-xfer", PaymentOperation.TRANSFER);
        PaymentResult result = stub(req.paymentId()).execute(req);
        assertEquals(PaymentState.FAILED, result.state());
    }

    @Test
    public void cashInFailsClosedWithoutNativeCaps() {
        activities.reset();
        activities.caps = ProviderCapabilities.of(); // no local money engine
        PaymentRequest req = request("cashin-nocaps", PaymentOperation.CASH_IN);
        PaymentResult result = stub(req.paymentId()).execute(req);
        assertEquals(PaymentState.FAILED, result.state());
        assertEquals(0, activities.creditCalls.get());
    }

    @Test
    public void cashInHappyPathPostLeg() {
        activities.reset();
        activities.caps = postLegCaps();
        PaymentRequest req = request("cashin-postleg", PaymentOperation.CASH_IN);
        req = withProvider(req, "FINERACT");
        PaymentResult result = stub(req.paymentId()).execute(req);
        assertEquals(PaymentState.COMPLETED, result.state());
        assertEquals(1, activities.creditCalls.get());
        assertEquals(0, activities.atomicCalls.get());
    }

    @Test
    public void transferFailsClosedWithPostLegOnly() {
        activities.reset();
        activities.caps = postLegCaps();
        PaymentRequest req = withProvider(request("xfer-postleg", PaymentOperation.TRANSFER), "FINERACT");
        PaymentResult result = stub(req.paymentId()).execute(req);
        assertEquals(PaymentState.FAILED, result.state());
        assertEquals(0, activities.atomicCalls.get());
        assertEquals(0, activities.creditCalls.get());
        assertEquals(0, activities.reverseCalls.get());
    }

    @Test
    public void cashInHappyPath() {
        activities.reset();
        activities.caps = nativeCaps();
        PaymentRequest req = request("cashin-ok", PaymentOperation.CASH_IN);
        PaymentResult result = stub(req.paymentId()).execute(req);
        assertEquals(PaymentState.COMPLETED, result.state());
        assertEquals(1, activities.creditCalls.get());
    }

    private PaymentWorkflow stub(String workflowId) {
        return testWorkflowRule.getWorkflowClient().newWorkflowStub(
                PaymentWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(testWorkflowRule.getTaskQueue())
                        .setWorkflowId(workflowId)
                        // Generous cap so transient-retry backoff (virtual time) completes; prod sets none.
                        .setWorkflowExecutionTimeout(java.time.Duration.ofMinutes(10))
                        .build());
    }

    private static PaymentRequest request(String id, PaymentOperation op) {
        return new PaymentRequest(
                "pay/t1/" + id, "t1", op, "ACC-A", "ACC-B", "10.00", "ETB",
                "test", "hash-" + id, "fp-" + id, "WALLET_LEDGER");
    }

    private static PaymentRequest withProvider(PaymentRequest r, String provider) {
        return new PaymentRequest(r.paymentId(), r.tenantId(), r.operation(), r.fromAccountId(),
                r.toAccountId(), r.amount(), r.currency(), r.narrative(), r.idempotencyKeyHash(),
                r.requestFingerprint(), provider);
    }

    public static final class FakeActivities implements PaymentActivities {
        ProviderCapabilities caps = ProviderCapabilities.of();
        boolean failAtomic;
        boolean throwAtomicReject;
        boolean throwTransient;
        boolean failCapture;
        boolean conflict;
        final AtomicInteger atomicCalls = new AtomicInteger();
        final AtomicInteger reverseCalls = new AtomicInteger();
        final AtomicInteger creditCalls = new AtomicInteger();
        final List<String> states = new ArrayList<>();

        void reset() {
            failAtomic = false;
            throwAtomicReject = false;
            throwTransient = false;
            failCapture = false;
            conflict = false;
            atomicCalls.set(0);
            reverseCalls.set(0);
            creditCalls.set(0);
            states.clear();
            caps = ProviderCapabilities.of();
        }

        @Override
        public SagaBootstrapResult bootstrapSaga(PaymentRequest request) {
            if (conflict) return SagaBootstrapResult.ofConflict("IDEMPOTENCY_CONFLICT");
            return SagaBootstrapResult.ofCreated();
        }

        @Override
        public ProviderCapabilities loadCapabilities(String tenantId, String providerName) {
            return caps;
        }

        @Override
        public MoneyStepResult atomicTransfer(PaymentRequest request, String stepKey) {
            atomicCalls.incrementAndGet();
            if (throwAtomicReject) {
                throw ApplicationFailure.newNonRetryableFailure("insufficient funds", "BUSINESS_REJECT");
            }
            if (throwTransient) {
                throw ApplicationFailure.newFailure("db down", "TRANSIENT");
            }
            if (failAtomic) return MoneyStepResult.businessReject("insufficient funds");
            return MoneyStepResult.committed("TXN-1", "TXN-1");
        }

        @Override
        public MoneyStepResult postDebit(PaymentRequest request, String stepKey) {
            return MoneyStepResult.committed("TXN-D", "TXN-D");
        }

        @Override
        public MoneyStepResult postCredit(PaymentRequest request, String stepKey) {
            creditCalls.incrementAndGet();
            return MoneyStepResult.committed("TXN-C", "TXN-C");
        }

        @Override
        public MoneyStepResult reserve(PaymentRequest request, String stepKey) {
            return MoneyStepResult.committed("TXN-R", "TXN-R");
        }

        @Override
        public MoneyStepResult capture(PaymentRequest request, String stepKey, String reserveTxnId) {
            if (failCapture) return MoneyStepResult.businessReject("capture failed");
            return MoneyStepResult.committed(reserveTxnId, reserveTxnId);
        }

        @Override
        public MoneyStepResult release(PaymentRequest request, String stepKey, String reserveTxnId) {
            reverseCalls.incrementAndGet();
            return MoneyStepResult.noOp("released");
        }

        @Override
        public MoneyStepResult reverse(PaymentRequest request, String stepKey, String originalTxnId) {
            reverseCalls.incrementAndGet();
            return MoneyStepResult.committed("TXN-REV", "TXN-REV");
        }

        @Override
        public MoneyStepResult reconcile(PaymentRequest request, String stepKey, PaymentStepName step) {
            return MoneyStepResult.notFound();
        }

        @Override
        public void markSagaState(String paymentId, String state, String coreTxnId, String message) {
            states.add(state);
        }
    }
}
