package et.restlink.temporal;

import et.restlink.payment.PaymentSagaRepository;
import et.restlink.persist.ProviderEventInboxEntity;
import et.restlink.workflow.payment.PaymentWorkflow;

import io.quarkus.scheduler.Scheduled;
import io.temporal.client.WorkflowClient;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Persist-before-signal dispatcher. Callbacks land in {@code provider_event_inbox} first; this
 * poller claims rows with {@code FOR UPDATE SKIP LOCKED} (N–N safe) then signals Temporal.
 */
@ApplicationScoped
public class ProviderEventDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(ProviderEventDispatcher.class);

    /** Lease for CLAIMED rows — crashed nodes release work after this. */
    private static final long CLAIM_LEASE_MS = 30_000L;

    @Inject
    PaymentSagaRepository sagas;
    @Inject
    TemporalRuntime temporal;

    @Scheduled(every = "5s")
    void drain() {
        if (!temporal.moneyReady()) return;
        WorkflowClient client = temporal.client().orElse(null);
        if (client == null) return;
        List<ProviderEventInboxEntity> claimed = sagas.claimInboxEvents(50, CLAIM_LEASE_MS);
        for (ProviderEventInboxEntity e : claimed) {
            try {
                if (e.paymentId == null || e.paymentId.isBlank()) {
                    // No workflow to signal — durable ingest is enough; mark done.
                    sagas.markDelivered(e.eventId);
                    continue;
                }
                PaymentWorkflow stub = client.newWorkflowStub(PaymentWorkflow.class, e.paymentId);
                stub.providerEvent(e.eventId, e.stepKey, e.payloadJson);
                sagas.markDelivered(e.eventId);
            } catch (Exception ex) {
                sagas.markDeliveryFailed(e.eventId);
                LOG.debug("inbox deliver failed event={}: {}", e.eventId, ex.toString());
            }
        }
    }
}
