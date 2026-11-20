package et.restlink.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

@Entity
@Table(name = "payment_saga",
       uniqueConstraints = @UniqueConstraint(name = "uq_payment_saga_idem",
               columnNames = {"tenant_id", "idempotency_key_hash"}))
public class PaymentSagaEntity extends PanacheEntityBase {

    @Id
    @Column(name = "payment_id", length = 160)
    public String paymentId;

    @Column(name = "tenant_id", nullable = false, length = 40)
    public String tenantId;

    @Column(name = "idempotency_key_hash", nullable = false, length = 64)
    public String idempotencyKeyHash;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    public String requestFingerprint;

    @Column(name = "operation", nullable = false, length = 16)
    public String operation;

    @Column(name = "from_account_id", length = 40)
    public String fromAccountId;

    @Column(name = "to_account_id", length = 40)
    public String toAccountId;

    @Column(name = "amount", nullable = false, precision = 23, scale = 4)
    public java.math.BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    public String currency;

    @Column(name = "provider", nullable = false, length = 24)
    public String provider;

    @Column(name = "state", nullable = false, length = 24)
    public String state;

    @Column(name = "core_txn_id", length = 40)
    public String coreTxnId;

    @Column(name = "workflow_run_id", length = 80)
    public String workflowRunId;

    @Column(name = "error_message", length = 400)
    public String errorMessage;

    @Version
    @Column(name = "version", nullable = false)
    public long version;

    @Column(name = "created_ms", nullable = false)
    public long createdMs;

    @Column(name = "updated_ms", nullable = false)
    public long updatedMs;
}
