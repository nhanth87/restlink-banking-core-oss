package et.restlink.payment;

import et.restlink.persist.PaymentSagaEntity;
import et.restlink.persist.PaymentStepEntity;
import et.restlink.persist.ProviderEventInboxEntity;
import et.restlink.spi.payment.PaymentState;
import et.restlink.workflow.payment.PaymentActivities.SagaBootstrapResult;
import et.restlink.workflow.payment.PaymentRequest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Durable payment saga store. Unique (tenant, idempotency_key_hash) is the last line of defence
 * against double-charge even after Temporal history retention expiry.
 */
@ApplicationScoped
public class PaymentSagaRepository {

    /** Cap delivery attempts (PENDING/FAILED/stale CLAIMED) before giving up permanently. */
    public static final int INBOX_MAX_ATTEMPTS = 20;

    @Inject
    EntityManager em;

    @Transactional
    public SagaBootstrapResult bootstrap(PaymentRequest req) {
        Optional<PaymentSagaEntity> existing = findByTenantAndHash(req.tenantId(), req.idempotencyKeyHash());
        if (existing.isPresent()) {
            PaymentSagaEntity e = existing.get();
            if (!req.requestFingerprint().equals(e.requestFingerprint)) {
                return SagaBootstrapResult.ofConflict("IDEMPOTENCY_CONFLICT: same key, different payload");
            }
            return SagaBootstrapResult.ofReplay(e.state, e.coreTxnId);
        }
        long now = System.currentTimeMillis();
        PaymentSagaEntity e = new PaymentSagaEntity();
        e.paymentId = req.paymentId();
        e.tenantId = req.tenantId();
        e.idempotencyKeyHash = req.idempotencyKeyHash();
        e.requestFingerprint = req.requestFingerprint();
        e.operation = req.operation().name();
        e.fromAccountId = req.fromAccountId();
        e.toAccountId = req.toAccountId();
        e.amount = new BigDecimal(req.amount());
        e.currency = req.currency();
        e.provider = req.providerName();
        e.state = PaymentState.RECEIVED.name();
        e.createdMs = now;
        e.updatedMs = now;
        e.persist();
        return SagaBootstrapResult.ofCreated();
    }

    @Transactional
    public void markState(String paymentId, String state, String coreTxnId, String message) {
        PaymentSagaEntity e = PaymentSagaEntity.findById(paymentId);
        if (e == null) return;
        e.state = state;
        if (coreTxnId != null) e.coreTxnId = coreTxnId;
        if (message != null) e.errorMessage = truncate(message, 400);
        e.updatedMs = System.currentTimeMillis();
    }

    @Transactional
    public void setWorkflowRunId(String paymentId, String runId) {
        PaymentSagaEntity e = PaymentSagaEntity.findById(paymentId);
        if (e == null) return;
        e.workflowRunId = runId;
        e.updatedMs = System.currentTimeMillis();
    }

    public Optional<PaymentSagaEntity> find(String paymentId) {
        return Optional.ofNullable(PaymentSagaEntity.findById(paymentId));
    }

    public Optional<PaymentSagaEntity> findByTenantAndHash(String tenantId, String hash) {
        return Optional.ofNullable(PaymentSagaEntity
                .find("tenantId = ?1 and idempotencyKeyHash = ?2", tenantId, hash)
                .firstResult());
    }

    @Transactional
    public PaymentStepEntity prepareStep(String paymentId, String tenantId, String provider,
                                         String stepName, String stepKey) {
        PaymentStepEntity existing = PaymentStepEntity
                .find("tenantId = ?1 and provider = ?2 and stepKey = ?3", tenantId, provider, stepKey)
                .firstResult();
        if (existing != null) {
            existing.attempts++;
            existing.updatedMs = System.currentTimeMillis();
            return existing;
        }
        long now = System.currentTimeMillis();
        PaymentStepEntity s = new PaymentStepEntity();
        s.paymentId = paymentId;
        s.tenantId = tenantId;
        s.provider = provider;
        s.stepName = stepName;
        s.stepKey = stepKey;
        s.state = "PREPARED";
        s.attempts = 1;
        s.createdMs = now;
        s.updatedMs = now;
        s.persist();
        return s;
    }

    @Transactional
    public void completeStep(String tenantId, String provider, String stepKey,
                             String state, String coreTxnId, String providerRef, String error) {
        PaymentStepEntity s = PaymentStepEntity
                .find("tenantId = ?1 and provider = ?2 and stepKey = ?3", tenantId, provider, stepKey)
                .firstResult();
        if (s == null) return;
        s.state = state;
        s.coreTxnId = coreTxnId;
        s.providerRef = providerRef;
        if (error != null) s.errorMessage = truncate(error, 400);
        s.updatedMs = System.currentTimeMillis();
    }

    public Optional<PaymentStepEntity> findStep(String tenantId, String provider, String stepKey) {
        return Optional.ofNullable(PaymentStepEntity
                .find("tenantId = ?1 and provider = ?2 and stepKey = ?3", tenantId, provider, stepKey)
                .firstResult());
    }

    /**
     * Persist-before-signal. Returns false if the event_id was already stored (duplicate).
     */
    @Transactional
    public boolean ingestEvent(String eventId, String paymentId, String requestId, String stepKey,
                               String provider, String payloadJson) {
        ProviderEventInboxEntity existing = ProviderEventInboxEntity.findById(eventId);
        if (existing != null) return false;
        ProviderEventInboxEntity e = new ProviderEventInboxEntity();
        e.eventId = eventId;
        e.paymentId = paymentId;
        e.requestId = requestId;
        e.stepKey = stepKey;
        e.provider = provider;
        e.payloadJson = payloadJson;
        e.deliveryState = "PENDING";
        e.attempts = 0;
        e.createdMs = System.currentTimeMillis();
        e.persist();
        return true;
    }

    @Transactional
    public void markDelivered(String eventId) {
        ProviderEventInboxEntity e = ProviderEventInboxEntity.findById(eventId);
        if (e == null) return;
        e.deliveryState = "DELIVERED";
        e.deliveredMs = System.currentTimeMillis();
    }

    @Transactional
    public void markDeliveryFailed(String eventId) {
        ProviderEventInboxEntity e = ProviderEventInboxEntity.findById(eventId);
        if (e == null) return;
        e.deliveryState = "FAILED";
        e.claimedMs = null;
    }

    /**
     * Atomically claim up to {@code limit} inbox rows for this node using
     * {@code FOR UPDATE SKIP LOCKED} so N–N pollers never double-signal the same event.
     * Reclaims stale {@code CLAIMED} leases and retries {@code FAILED} until max attempts.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public List<ProviderEventInboxEntity> claimInboxEvents(int limit, long claimLeaseMs) {
        int lim = Math.max(1, Math.min(limit, 200));
        long now = System.currentTimeMillis();
        long staleBefore = now - Math.max(1_000L, claimLeaseMs);

        List<String> ids = em.createNativeQuery("""
                SELECT event_id FROM provider_event_inbox
                WHERE attempts < :maxAttempts
                  AND (
                    delivery_state IN ('PENDING', 'FAILED')
                    OR (delivery_state = 'CLAIMED' AND claimed_ms IS NOT NULL AND claimed_ms < :staleBefore)
                  )
                ORDER BY created_ms
                FOR UPDATE SKIP LOCKED
                LIMIT :lim
                """)
                .setParameter("maxAttempts", INBOX_MAX_ATTEMPTS)
                .setParameter("staleBefore", staleBefore)
                .setParameter("lim", lim)
                .getResultList();

        if (ids.isEmpty()) return List.of();

        List<ProviderEventInboxEntity> claimed = new ArrayList<>(ids.size());
        for (String id : ids) {
            ProviderEventInboxEntity e = ProviderEventInboxEntity.findById(id);
            if (e == null) continue;
            e.deliveryState = "CLAIMED";
            e.claimedMs = now;
            e.attempts++;
            claimed.add(e);
        }
        return claimed;
    }

    /** Peek only — prefer {@link #claimInboxEvents(int, long)} under N–N. */
    public List<ProviderEventInboxEntity> pendingEvents(int limit) {
        return ProviderEventInboxEntity
                .find("deliveryState in ?1 order by createdMs", List.of("PENDING", "FAILED"))
                .page(0, Math.max(1, limit))
                .list();
    }

    public long countByState(String state) {
        return PaymentSagaEntity.count("state", state);
    }

    /**
     * Newest-first money history for an account (cash-in to, cash-out/transfer from).
     * {@code range} is inclusive on both ends.
     */
    public List<PaymentSagaEntity> listForAccount(String tenantId, String accountId, int offset, int limit) {
        int off = Math.max(0, offset);
        int lim = Math.max(1, Math.min(limit, 200));
        return PaymentSagaEntity.find(
                        "tenantId = ?1 and (fromAccountId = ?2 or toAccountId = ?2) order by createdMs desc",
                        tenantId, accountId)
                .range(off, off + lim - 1)
                .list();
    }

    public long countForAccount(String tenantId, String accountId) {
        return PaymentSagaEntity.count(
                "tenantId = ?1 and (fromAccountId = ?2 or toAccountId = ?2)",
                tenantId, accountId);
    }

    /** COMPLETED cash-out + transfer-out since {@code sinceMs} — the daily spend fence. */
    public BigDecimal sumCompletedDebitsSince(String tenantId, String accountId, long sinceMs) {
        if (tenantId == null || accountId == null) {
            return BigDecimal.ZERO;
        }
        Object v = em.createQuery("""
                        select coalesce(sum(e.amount), 0) from PaymentSagaEntity e
                        where e.tenantId = :t and e.fromAccountId = :a
                          and e.state = :st
                          and e.operation in ('CASH_OUT', 'TRANSFER')
                          and e.createdMs >= :since
                        """)
                .setParameter("t", tenantId)
                .setParameter("a", accountId)
                .setParameter("st", PaymentState.COMPLETED.name())
                .setParameter("since", sinceMs)
                .getSingleResult();
        return v instanceof BigDecimal b ? b : BigDecimal.ZERO;
    }

    public long inboxPendingCount() {
        return ProviderEventInboxEntity.count(
                "deliveryState in ?1", List.of("PENDING", "FAILED", "CLAIMED"));
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
