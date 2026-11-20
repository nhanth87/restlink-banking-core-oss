package et.restlink.temporal;

import et.restlink.core.CoreBankingRouter;
import et.restlink.payment.PaymentSagaRepository;
import et.restlink.persist.PaymentStepEntity;
import et.restlink.spi.CoreBankingPort;
import et.restlink.spi.CoreProvider;
import et.restlink.spi.command.PostTransaction;
import et.restlink.spi.command.ReverseTransaction;
import et.restlink.spi.command.TransferFunds;
import et.restlink.spi.model.Money;
import et.restlink.spi.payment.MoneyOutcomeKind;
import et.restlink.spi.payment.MoneyStepResult;
import et.restlink.spi.payment.PaymentOperation;
import et.restlink.spi.payment.PaymentStepName;
import et.restlink.spi.payment.ProviderCapabilities;
import et.restlink.spi.payment.ProviderCapability;
import et.restlink.workflow.payment.PaymentActivities;
import et.restlink.workflow.payment.PaymentRequest;

import io.temporal.failure.ApplicationFailure;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * CDI-backed Temporal Activities. Money side effects go through the tenant's {@link CoreBankingPort}
 * — never raw HTTP, and never a silent fallback onto the native wallet.
 *
 * <p>Step keys are durable idempotency keys. Peer-down is {@code TRANSIENT} (retry same key).
 */
@ApplicationScoped
public class PaymentActivitiesImpl implements PaymentActivities {

    @Inject
    PaymentSagaRepository sagas;
    @Inject
    CoreBankingRouter router;

    @Override
    public SagaBootstrapResult bootstrapSaga(PaymentRequest request) {
        return sagas.bootstrap(request);
    }

    @Override
    public ProviderCapabilities loadCapabilities(String tenantId, String providerName) {
        CoreProvider provider = parseProvider(providerName);
        if (provider == null) {
            return ProviderCapabilities.of(); // unknown provider → fail closed (no caps)
        }
        return router.port(provider)
                .map(CoreBankingPort::capabilities)
                .orElseGet(ProviderCapabilities::of);
    }

    @Override
    public MoneyStepResult atomicTransfer(PaymentRequest request, String stepKey) {
        prepare(request, PaymentStepName.ATOMIC_TRANSFER, stepKey);
        CoreBankingPort port = requirePort(request);
        MoneyStepResult r = port.executeMoney(new TransferFunds(
                request.paymentId(), request.tenantId(),
                request.fromAccountId(), request.toAccountId(),
                money(request), request.narrative(), stepKey));
        return finish(request, stepKey, r);
    }

    @Override
    public MoneyStepResult postDebit(PaymentRequest request, String stepKey) {
        prepare(request, PaymentStepName.DEBIT, stepKey);
        return postLeg(request, stepKey, PostTransaction.Direction.DEBIT, request.fromAccountId());
    }

    @Override
    public MoneyStepResult postCredit(PaymentRequest request, String stepKey) {
        prepare(request, PaymentStepName.CREDIT, stepKey);
        String account = request.toAccountId() != null ? request.toAccountId() : request.fromAccountId();
        return postLeg(request, stepKey, PostTransaction.Direction.CREDIT, account);
    }

    @Override
    public MoneyStepResult reserve(PaymentRequest request, String stepKey) {
        prepare(request, PaymentStepName.RESERVE, stepKey);
        ProviderCapabilities caps = loadCapabilities(request.tenantId(), request.providerName());
        if (!caps.supports(ProviderCapability.RESERVE) && !caps.supportsNativeTransfer()) {
            // Return (don't throw) so the workflow classifies and compensates deterministically.
            return finish(request, stepKey, MoneyStepResult.businessReject("RESERVE not supported"));
        }
        return postLeg(request, stepKey, PostTransaction.Direction.DEBIT, request.fromAccountId());
    }

    @Override
    public MoneyStepResult capture(PaymentRequest request, String stepKey, String reserveTxnId) {
        prepare(request, PaymentStepName.CAPTURE, stepKey);
        ProviderCapabilities caps = loadCapabilities(request.tenantId(), request.providerName());
        if (!caps.supports(ProviderCapability.CAPTURE) && !caps.supportsNativeTransfer()) {
            return finish(request, stepKey, MoneyStepResult.businessReject("CAPTURE not supported"));
        }
        CoreBankingPort port = requirePort(request);
        Optional<String> prior = port.lookupByIdempotency(request.tenantId(), moneyAccount(request), stepKey);
        if (prior.isPresent()) {
            return finish(request, stepKey, MoneyStepResult.committed(prior.get(), prior.get()));
        }
        if (reserveTxnId == null || reserveTxnId.isBlank()) {
            return finish(request, stepKey, MoneyStepResult.businessReject("missing reserveTxnId"));
        }
        return finish(request, stepKey, MoneyStepResult.committed(reserveTxnId, reserveTxnId));
    }

    @Override
    public MoneyStepResult release(PaymentRequest request, String stepKey, String reserveTxnId) {
        prepare(request, PaymentStepName.RELEASE, stepKey);
        ProviderCapabilities caps = loadCapabilities(request.tenantId(), request.providerName());
        if (!caps.supports(ProviderCapability.RELEASE) && !caps.supportsNativeTransfer()) {
            return finish(request, stepKey, MoneyStepResult.businessReject("RELEASE not supported"));
        }
        return finish(request, stepKey, reverseOnPort(request, stepKey, reserveTxnId, "release-reserve"));
    }

    @Override
    public MoneyStepResult reverse(PaymentRequest request, String stepKey, String originalTxnId) {
        prepare(request, PaymentStepName.REFUND, stepKey);
        return finish(request, stepKey, reverseOnPort(request, stepKey, originalTxnId, "compensation"));
    }

    @Override
    public MoneyStepResult reconcile(PaymentRequest request, String stepKey, PaymentStepName step) {
        CoreProvider bound = parseProvider(request.providerName());
        if (bound == null) {
            return MoneyStepResult.notFound();
        }
        Optional<CoreBankingPort> port = router.port(bound);
        if (port.isEmpty()) {
            return MoneyStepResult.notFound();
        }
        Optional<String> found = port.get().lookupByIdempotency(
                request.tenantId(), moneyAccount(request), stepKey);
        if (found.isPresent()) {
            return MoneyStepResult.committed(found.get(), found.get());
        }
        Optional<PaymentStepEntity> row = sagas.findStep(request.tenantId(), request.providerName(), stepKey);
        if (row.isPresent() && row.get().coreTxnId != null) {
            return MoneyStepResult.committed(row.get().coreTxnId, row.get().providerRef);
        }
        return MoneyStepResult.notFound();
    }

    @Override
    public void markSagaState(String paymentId, String state, String coreTxnId, String message) {
        sagas.markState(paymentId, state, coreTxnId, message);
    }

    private MoneyStepResult postLeg(PaymentRequest request, String stepKey,
                                    PostTransaction.Direction direction, String accountId) {
        CoreBankingPort port = requirePort(request);
        Optional<String> prior = port.lookupByIdempotency(request.tenantId(), accountId, stepKey);
        if (prior.isPresent()) {
            return finish(request, stepKey, MoneyStepResult.committed(prior.get(), prior.get()));
        }
        MoneyStepResult r = port.executeMoney(new PostTransaction(
                request.paymentId(), request.tenantId(), accountId, direction,
                money(request), request.narrative(), stepKey));
        return finish(request, stepKey, r);
    }

    private MoneyStepResult reverseOnPort(PaymentRequest request, String stepKey,
                                          String originalTxnId, String narrative) {
        CoreBankingPort port = requirePort(request);
        return port.executeMoney(new ReverseTransaction(
                request.paymentId(), request.tenantId(), moneyAccount(request),
                originalTxnId, money(request), narrative, stepKey));
    }

    private void prepare(PaymentRequest request, PaymentStepName name, String stepKey) {
        // Fail closed: the stamped provider must have a registered port. Never phantom-post to wallet.
        requirePort(request);
        sagas.prepareStep(request.paymentId(), request.tenantId(), request.providerName(),
                name.name(), stepKey);
    }

    private CoreBankingPort requirePort(PaymentRequest request) {
        CoreProvider bound = parseProvider(request.providerName());
        if (bound == null) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "unknown provider " + request.providerName(), "UNSUPPORTED_CAPABILITY");
        }
        return router.port(bound).orElseThrow(() -> ApplicationFailure.newNonRetryableFailure(
                "no CoreBankingPort registered for " + bound + " — fail-closed, no wallet fallback",
                "UNSUPPORTED_CAPABILITY"));
    }

    /** Account that actually moved for cash-in (to) vs cash-out/transfer (from). */
    static String moneyAccount(PaymentRequest request) {
        if (request.operation() == PaymentOperation.CASH_IN) {
            return request.toAccountId();
        }
        if (request.toAccountId() != null && !request.toAccountId().isBlank()
                && (request.fromAccountId() == null || request.fromAccountId().isBlank())) {
            return request.toAccountId();
        }
        return request.fromAccountId();
    }

    private static CoreProvider parseProvider(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return CoreProvider.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private MoneyStepResult finish(PaymentRequest request, String stepKey, MoneyStepResult r) {
        if (r == null) {
            r = MoneyStepResult.transientFailure("null money result");
        }
        String state = switch (r.kind()) {
            case COMMITTED, NO_OP -> "COMPLETED";
            case BUSINESS_REJECT -> "REJECTED";
            case AMBIGUOUS -> "AMBIGUOUS";
            case TRANSIENT, NOT_FOUND -> "FAILED";
        };
        sagas.completeStep(request.tenantId(), request.providerName(), stepKey,
                state, r.coreTxnId(), r.providerRef(), r.message());
        // Business rejects are a NORMAL outcome (e.g. insufficient funds): RETURN them so the workflow
        // compensates + marks the saga terminal. Throwing here would fail the whole workflow execution
        // and skip compensation (money-loss). Only TRANSIENT faults throw — retryable so Temporal
        // retries the Activity; once retries are exhausted the workflow catches the ActivityFailure.
        if (r.kind() == MoneyOutcomeKind.TRANSIENT) {
            throw ApplicationFailure.newFailure(
                    r.message() == null ? "transient" : r.message(), "TRANSIENT");
        }
        return r;
    }

    private static Money money(PaymentRequest request) {
        return new Money(new BigDecimal(request.amount()), request.currency());
    }
}
