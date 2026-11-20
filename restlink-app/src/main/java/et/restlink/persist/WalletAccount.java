package et.restlink.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** A native wallet account (Phase 1 WALLET_LEDGER). One row per account; balance is derived truth. */
@Entity
@Table(name = "wallet_account")
public class WalletAccount extends PanacheEntityBase {

    @Id
    @Column(name = "account_id", length = 40)
    public String accountId;

    @Column(name = "tenant_id", nullable = false, length = 40)
    public String tenantId;

    @Column(name = "customer_external_id", length = 80)
    public String customerExternalId;

    @Column(name = "product", length = 40)
    public String product;

    @Column(name = "currency", nullable = false, length = 3)
    public String currency;

    @Column(name = "balance", nullable = false, precision = 23, scale = 4)
    public BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "created_ms", nullable = false)
    public long createdMs;
}
