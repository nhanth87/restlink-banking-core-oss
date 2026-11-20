package et.restlink.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** One leg of a double-entry posting. Immutable once written (audit truth). */
@Entity
@Table(name = "ledger_entry")
public class LedgerEntry extends PanacheEntity {

    @Column(name = "core_txn_id", nullable = false, length = 40)
    public String coreTxnId;

    @Column(name = "tenant_id", nullable = false, length = 40)
    public String tenantId;

    @Column(name = "account_id", nullable = false, length = 40)
    public String accountId;

    @Column(name = "direction", nullable = false, length = 6)
    public String direction; // DEBIT | CREDIT

    @Column(name = "amount", nullable = false, precision = 23, scale = 4)
    public BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    public String currency;

    @Column(name = "narrative", length = 140)
    public String narrative;

    @Column(name = "created_ms", nullable = false)
    public long createdMs;
}
