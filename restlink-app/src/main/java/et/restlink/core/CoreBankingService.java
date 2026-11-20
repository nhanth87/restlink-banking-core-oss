package et.restlink.core;

import et.restlink.cluster.ClusterMaps;
import et.restlink.payment.PaymentSagaRepository;
import et.restlink.spi.CoreBankingPort;
import et.restlink.spi.CoreProvider;
import et.restlink.spi.command.CoreBankingCommand;
import et.restlink.spi.command.PostTransaction;
import et.restlink.spi.command.ReverseTransaction;
import et.restlink.spi.command.TransferFunds;
import et.restlink.spi.events.CoreBankingEvent;
import et.restlink.spi.events.CoreErrorEvent;
import et.restlink.spi.events.TransactionPostedEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Non-money command façade + event correlation. Money effects (PostTransaction / TransferFunds /
 * ReverseTransaction) must go through Temporal {@code PaymentService} — this class rejects direct
 * money submits to prevent bypassing the saga.
 *
 * <p>Provider events are persisted to {@code provider_event_inbox} before Temporal signal.
 */
public final class CoreBankingService {

    private static final Logger LOG = LoggerFactory.getLogger(CoreBankingService.class);

    private final ClusterMaps cluster;
    private final IdempotencyGuard idem;
    private final PendingRequestStore pending;
    private final CoreBankingRouter router;
    private volatile PaymentSagaRepository eventInbox;

    private java.util.function.Consumer<PendingRequest> journalSink = pr -> {};

    public CoreBankingService(ClusterMaps cluster, CoreBankingRouter router) {
        this.cluster = cluster;
        this.router = router;
        this.idem = new IdempotencyGuard(cluster);
        this.pending = new PendingRequestStore(cluster);
    }

    public void setJournalSink(java.util.function.Consumer<PendingRequest> sink) {
        if (sink != null) this.journalSink = sink;
    }

    public void setEventInbox(PaymentSagaRepository inbox) {
        this.eventInbox = inbox;
    }

    public Submit submit(CoreBankingCommand cmd) {
        if (isMoneyCommand(cmd)) {
            throw new IllegalStateException(
                    "money commands must go through PaymentService/Temporal — refused: "
                            + cmd.getClass().getSimpleName());
        }
        String requestId = cmd.requestId();
        String tenant = cmd.tenantId();
        CoreProvider provider = router.providerFor(tenant);

        PendingRequest pr = PendingRequest.submitted(requestId, tenant,
                cmd.getClass().getSimpleName(), null, provider.name(), cluster.localNode());
        pending.put(pr);

        try {
            CoreBankingPort port = router.portFor(tenant);
            port.submit(cmd);
        } catch (RuntimeException e) {
            pending.mark(requestId, PendingRequest.State.NEEDS_REVIEW);
            LOG.error("core submit failed tenant={} req={} provider={}: {}",
                    tenant, requestId, provider, e.toString());
            throw e;
        }
        return new Submit(SubmitResult.ACCEPTED, requestId);
    }

    public void onEvent(CoreBankingEvent event) {
        String requestId = event.requestId();
        String resultJson = renderResult(event);

        // Persist-before-signal so callbacks survive Temporal downtime.
        PaymentSagaRepository inbox = eventInbox;
        if (inbox != null && requestId != null && !"-".equals(requestId)) {
            String eventId = event.provider() + ":" + event.getClass().getSimpleName() + ":" + requestId;
            inbox.ingestEvent(eventId, null, requestId, null, event.provider(), resultJson);
        }

        Optional<PendingRequest> done = pending.complete(requestId, resultJson);
        if (done.isEmpty()) {
            LOG.debug("event with no pending req={} provider={} type={}",
                    requestId, event.provider(), event.getClass().getSimpleName());
            return;
        }
        PendingRequest pr = done.get();
        if (pr.idempotencyKey() != null) idem.store(pr.idempotencyKey(), resultJson);
        journalSink.accept(pr);
        LOG.info("completed req={} tenant={} provider={} node={}",
                requestId, pr.tenantId(), pr.provider(), cluster.localNode());
    }

    public Optional<PendingRequest> lookup(String requestId) {
        return pending.get(requestId);
    }

    public PendingRequestStore pending() { return pending; }
    public IdempotencyGuard idempotency() { return idem; }

    private static boolean isMoneyCommand(CoreBankingCommand cmd) {
        return cmd instanceof PostTransaction
                || cmd instanceof TransferFunds
                || cmd instanceof ReverseTransaction;
    }

    private static String renderResult(CoreBankingEvent event) {
        return switch (event) {
            case TransactionPostedEvent t -> "{\"type\":\"txn\",\"req\":\"" + t.requestId() + "\"}";
            case CoreErrorEvent e -> "{\"type\":\"error\",\"req\":\"" + e.requestId() + "\"}";
            default -> "{\"type\":\"" + event.getClass().getSimpleName() + "\",\"req\":\""
                    + event.requestId() + "\"}";
        };
    }

    public enum SubmitResult { ACCEPTED, REPLAY }

    public record Submit(SubmitResult result, String payload) {}
}
