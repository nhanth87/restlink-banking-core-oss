package et.restlink.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "provider_event_inbox")
public class ProviderEventInboxEntity extends PanacheEntityBase {

    @Id
    @Column(name = "event_id", length = 120)
    public String eventId;

    @Column(name = "payment_id", length = 160)
    public String paymentId;

    @Column(name = "request_id", length = 160)
    public String requestId;

    @Column(name = "step_key", length = 200)
    public String stepKey;

    @Column(name = "provider", nullable = false, length = 24)
    public String provider;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    public String payloadJson;

    /** PENDING | CLAIMED | DELIVERED | FAILED */
    @Column(name = "delivery_state", nullable = false, length = 16)
    public String deliveryState;

    @Column(name = "attempts", nullable = false)
    public int attempts;

    @Column(name = "created_ms", nullable = false)
    public long createdMs;

    @Column(name = "claimed_ms")
    public Long claimedMs;

    @Column(name = "delivered_ms")
    public Long deliveredMs;
}
