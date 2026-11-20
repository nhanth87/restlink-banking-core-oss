package et.restlink.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Durable idempotency + correlation record — the source of truth behind the cluster's DIST_SYNC caches.
 * The unique index on (tenant_id, idempotency_key) is the last line of defence against double-posting
 * even if the in-memory guard is lost on a full cluster restart.
 */
@Entity
@Table(name = "txn_journal",
       uniqueConstraints = @UniqueConstraint(name = "uq_txn_idem", columnNames = {"tenant_id", "idempotency_key"}))
public class TxnJournal extends PanacheEntityBase {

    @Id
    @Column(name = "core_txn_id", length = 40)
    public String coreTxnId;

    @Column(name = "tenant_id", nullable = false, length = 40)
    public String tenantId;

    @Column(name = "request_id", length = 80)
    public String requestId;

    @Column(name = "idempotency_key", length = 120)
    public String idempotencyKey;

    @Column(name = "account_id", length = 40)
    public String accountId;

    @Column(name = "state", nullable = false, length = 16)
    public String state; // COMPLETED | NEEDS_REVIEW | COMPENSATED

    @Column(name = "owner_node", length = 60)
    public String ownerNode;

    @Column(name = "payment_id", length = 160)
    public String paymentId;

    @Column(name = "step_key", length = 200)
    public String stepKey;

    @Column(name = "compensation", nullable = false)
    public boolean compensation;

    @Column(name = "created_ms", nullable = false)
    public long createdMs;
}
