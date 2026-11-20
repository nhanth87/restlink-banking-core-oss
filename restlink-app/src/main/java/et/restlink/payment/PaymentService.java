package et.restlink.payment;

import et.restlink.core.CoreBankingRouter;
import et.restlink.persist.PaymentSagaEntity;
import et.restlink.spi.CoreProvider;
import et.restlink.spi.payment.PaymentKeys;
import et.restlink.spi.payment.PaymentOperation;
import et.restlink.spi.payment.PaymentState;
import et.restlink.kyc.KycGate;
import et.restlink.kyc.KycService;
import et.restlink.temporal.TemporalRuntime;
import et.restlink.wallet.WalletControlService;
import et.restlink.wallet.WalletGate;
import et.restlink.workflow.RestlinkTaskQueues;
import et.restlink.workflow.payment.PaymentRequest;
import et.restlink.workflow.payment.PaymentResult;
import et.restlink.workflow.payment.PaymentWorkflow;

import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Starts / attaches Temporal payment workflows. When Temporal is down → fail closed (no direct post).
 */
@ApplicationScoped
public class PaymentService {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentService.class);

    @Inject
    TemporalRuntime temporal;
    @Inject
    PaymentSagaRepository sagas;
    @Inject
    CoreBankingRouter router;
    @Inject
    WalletControlService controls;
    @Inject
    KycService kyc;

    public sealed interface Start
            permits Start.Accepted, Start.Replay, Start.Conflict, Start.Unavailable,
                    Start.Invalid, Start.Frozen, Start.LimitExceeded, Start.KycBlocked {
        record Accepted(String paymentId) implements Start {}
        record Replay(String paymentId, String state, String coreTxnId) implements Start {}
        record Conflict(String message) implements Start {}
        record Unavailable(String message) implements Start {}
        record Invalid(String message) implements Start {}
        record Frozen(String message) implements Start {}
        record LimitExceeded(String message) implements Start {}
        record KycBlocked(String message) implements Start {}
    }

    public Start cashIn(String tenantId, String accountId, String amount, String currency,
                        String idempotencyKey) {
        return start(PaymentOperation.CASH_IN, tenantId, null, accountId, amount, currency,
                "cash-in", idempotencyKey);
    }

    public Start cashOut(String tenantId, String accountId, String amount, String currency,
                         String idempotencyKey) {
        return start(PaymentOperation.CASH_OUT, tenantId, accountId, null, amount, currency,
                "cash-out", idempotencyKey);
    }

    public Start transfer(String tenantId, String fromAccount, String toAccount,
                          String amount, String currency, String idempotencyKey) {
        return start(PaymentOperation.TRANSFER, tenantId, fromAccount, toAccount, amount, currency,
                "transfer", idempotencyKey);
    }

    private Start start(PaymentOperation op, String tenantId, String from, String to,
                        String amount, String currency, String narrative, String rawKey) {
        // Validate at the edge → HTTP 400, never let a malformed request become 8 activity retries
        // and a permanently stuck payment.
        String invalid = validate(op, tenantId, from, to, amount, currency, rawKey);
        if (invalid != null) {
            return new Start.Invalid(invalid);
        }
        Start blocked = gate(op, tenantId, from, to, amount);
        if (blocked != null) {
            return blocked;
        }
        Start kycBlocked = kycGate(op, tenantId, from);
        if (kycBlocked != null) {
            return kycBlocked;
        }
        if (!temporal.moneyReady()) {
            return new Start.Unavailable("Temporal not ready — money plane fail-closed");
        }
        String hash = PaymentKeys.sha256Hex(rawKey.trim());
        String fingerprint = PaymentKeys.requestFingerprint(op.name(), tenantId, from, to, amount, currency);
        String paymentId = PaymentKeys.paymentId(tenantId, rawKey.trim());

        Optional<PaymentSagaEntity> existing = sagas.findByTenantAndHash(tenantId, hash);
        if (existing.isPresent()) {
            PaymentSagaEntity e = existing.get();
            if (!fingerprint.equals(e.requestFingerprint)) {
                return new Start.Conflict("IDEMPOTENCY_CONFLICT: same key, different payload");
            }
            if (isTerminal(e.state)) {
                return new Start.Replay(e.paymentId, e.state, e.coreTxnId);
            }
            return attachOrStart(paymentId, buildRequest(op, paymentId, tenantId, from, to,
                    amount, currency, narrative, hash, fingerprint));
        }
        return attachOrStart(paymentId, buildRequest(op, paymentId, tenantId, from, to,
                amount, currency, narrative, hash, fingerprint));
    }

    private Start attachOrStart(String paymentId, PaymentRequest request) {
        WorkflowClient client = temporal.client().orElse(null);
        if (client == null) {
            return new Start.Unavailable("Temporal client not started");
        }
        try {
            PaymentWorkflow stub = client.newWorkflowStub(PaymentWorkflow.class, WorkflowOptions.newBuilder()
                    .setWorkflowId(paymentId)
                    .setTaskQueue(RestlinkTaskQueues.PAYMENT_WORKFLOW)
                    // ALLOW_DUPLICATE_FAILED_ONLY: a succeeded/compensated payment can never be re-run
                    // under the same key (exactly-once), but a FAILED one can be re-driven for recovery.
                    .setWorkflowIdReusePolicy(
                            WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                    // No execution timeout: a saga may legitimately park in MANUAL_REVIEW for days
                    // awaiting an operator; a 30-min cap would kill it before anyone can act.
                    .build());
            WorkflowClient.start(stub::execute, request);
            LOG.info("payment workflow started id={} op={}", paymentId, request.operation());
            return new Start.Accepted(paymentId);
        } catch (WorkflowExecutionAlreadyStarted dup) {
            Optional<PaymentSagaEntity> e = sagas.find(paymentId);
            if (e.isPresent() && !request.requestFingerprint().equals(e.get().requestFingerprint)) {
                return new Start.Conflict("IDEMPOTENCY_CONFLICT: same key, different payload");
            }
            return new Start.Accepted(paymentId);
        } catch (RuntimeException e) {
            LOG.error("start payment failed id={}: {}", paymentId, e.toString());
            return new Start.Unavailable(e.getMessage());
        }
    }

    public Optional<Map<String, Object>> status(String paymentId) {
        Optional<PaymentSagaEntity> row = sagas.find(paymentId);
        if (row.isPresent()) {
            PaymentSagaEntity e = row.get();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("paymentId", e.paymentId);
            out.put("state", e.state);
            out.put("coreTxnId", e.coreTxnId == null ? "" : e.coreTxnId);
            out.put("operation", e.operation);
            out.put("provider", e.provider);
            out.put("error", e.errorMessage == null ? "" : e.errorMessage);
            out.put("source", "postgres");
            return Optional.of(out);
        }
        return temporal.client().flatMap(c -> {
            try {
                PaymentWorkflow stub = c.newWorkflowStub(PaymentWorkflow.class, paymentId);
                PaymentResult r = stub.status();
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("paymentId", r.paymentId());
                out.put("state", r.state().name());
                out.put("coreTxnId", r.coreTxnId() == null ? "" : r.coreTxnId());
                out.put("error", r.message() == null ? "" : r.message());
                out.put("source", "temporal");
                return Optional.of(out);
            } catch (Exception e) {
                return Optional.empty();
            }
        });
    }

    public boolean signalRetryReconcile(String paymentId) {
        return signal(paymentId, "retryReconcile");
    }

    public boolean signalRetryCompensation(String paymentId) {
        return signal(paymentId, "retryCompensation");
    }

    private boolean signal(String paymentId, String method) {
        WorkflowClient client = temporal.client().orElse(null);
        if (client == null) return false;
        try {
            WorkflowStub stub = client.newUntypedWorkflowStub(paymentId);
            stub.signal(method);
            LOG.info("operator signal {} -> {}", method, paymentId);
            return true;
        } catch (Exception e) {
            LOG.warn("signal {} failed for {}: {}", method, paymentId, e.toString());
            return false;
        }
    }

    private Start gate(PaymentOperation op, String tenantId, String from, String to, String amount) {
        WalletGate.Decision d = controls.decide(op, tenantId, from, to, new java.math.BigDecimal(amount));
        return switch (d) {
            case WalletGate.Allow _ -> null;
            case WalletGate.Frozen f -> new Start.Frozen("account frozen: " + f.accountId());
            case WalletGate.PerTxn p -> new Start.LimitExceeded(
                    "per-txn limit " + p.limit().toPlainString() + " exceeded by " + p.amount().toPlainString());
            case WalletGate.Daily x -> new Start.LimitExceeded(
                    "daily limit " + x.limit().toPlainString() + " exceeded (spent "
                            + x.spent().toPlainString() + ")");
        };
    }

    private Start kycGate(PaymentOperation op, String tenantId, String fromAccount) {
        KycGate.Decision d = kyc.decide(op, tenantId, fromAccount);
        return switch (d) {
            case KycGate.Allow _ -> null;
            case KycGate.Blocked b -> new Start.KycBlocked(b.message());
        };
    }

    private static String validate(PaymentOperation op, String tenantId, String from, String to,
                                   String amount, String currency, String rawKey) {
        if (rawKey == null || rawKey.isBlank()) return "Idempotency-Key required";
        if (tenantId == null || tenantId.isBlank()) return "tenantId required";
        if (currency == null || currency.length() != 3) return "currency must be a 3-letter code";
        if (amount == null || amount.isBlank()) return "amount required";
        try {
            if (new java.math.BigDecimal(amount).signum() <= 0) return "amount must be positive";
        } catch (NumberFormatException e) {
            return "amount is not a valid decimal";
        }
        switch (op) {
            case TRANSFER -> {
                if (from == null || from.isBlank() || to == null || to.isBlank()) {
                    return "from/to accounts required";
                }
                if (from.equals(to)) return "from/to accounts must differ";
            }
            case CASH_IN -> { if (to == null || to.isBlank()) return "accountId required"; }
            case CASH_OUT -> { if (from == null || from.isBlank()) return "accountId required"; }
        }
        return null;
    }

    private PaymentRequest buildRequest(PaymentOperation op, String paymentId, String tenantId,
                                        String from, String to, String amount, String currency,
                                        String narrative, String hash, String fingerprint) {
        CoreProvider provider = router.providerFor(tenantId);
        return new PaymentRequest(paymentId, tenantId, op, from, to, amount, currency,
                narrative, hash, fingerprint, provider.name());
    }

    private static boolean isTerminal(String state) {
        if (state == null) return false;
        return state.equals(PaymentState.COMPLETED.name())
                || state.equals(PaymentState.COMPENSATED.name())
                || state.equals(PaymentState.FAILED.name());
    }
}
