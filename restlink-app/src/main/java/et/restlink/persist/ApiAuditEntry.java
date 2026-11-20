package et.restlink.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * API-level audit row. No request body, no secrets, no PAN.
 */
@Entity
@Table(name = "api_audit")
public class ApiAuditEntry extends PanacheEntity {

    @Column(name = "occurred_ms", nullable = false)
    public long occurredMs;

    @Column(name = "tenant_id", length = 40)
    public String tenantId;

    @Column(name = "actor_id", nullable = false, length = 40)
    public String actorId;

    @Column(name = "method", nullable = false, length = 8)
    public String method;

    @Column(name = "path", nullable = false, length = 240)
    public String path;

    @Column(name = "client_ip", length = 64)
    public String clientIp;

    @Column(name = "status", nullable = false)
    public int status;

    public record New(String tenantId, String actorId, String method, String path,
                      String clientIp, int status) {}
}
