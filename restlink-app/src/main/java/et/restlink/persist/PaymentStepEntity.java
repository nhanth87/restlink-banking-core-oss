package et.restlink.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "payment_step",
       uniqueConstraints = @UniqueConstraint(name = "uq_payment_step_key",
               columnNames = {"tenant_id", "provider", "step_key"}))
public class PaymentStepEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "payment_id", nullable = false, length = 160)
    public String paymentId;

    @Column(name = "tenant_id", nullable = false, length = 40)
    public String tenantId;

    @Column(name = "provider", nullable = false, length = 24)
    public String provider;

    @Column(name = "step_name", nullable = false, length = 24)
    public String stepName;

    @Column(name = "step_key", nullable = false, length = 200)
    public String stepKey;

    @Column(name = "state", nullable = false, length = 24)
    public String state;

    @Column(name = "core_txn_id", length = 40)
    public String coreTxnId;

    @Column(name = "provider_ref", length = 80)
    public String providerRef;

    @Column(name = "attempts", nullable = false)
    public int attempts;

    @Column(name = "compensation_state", length = 24)
    public String compensationState;

    @Column(name = "error_message", length = 400)
    public String errorMessage;

    @Column(name = "created_ms", nullable = false)
    public long createdMs;

    @Column(name = "updated_ms", nullable = false)
    public long updatedMs;
}
