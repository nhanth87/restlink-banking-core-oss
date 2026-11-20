package et.restlink.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/** Restlink freeze + spend limits for any bound account id (wallet or Fineract savings). */
@Entity
@Table(name = "wallet_control")
@IdClass(WalletControl.Key.class)
public class WalletControl extends PanacheEntityBase {

    @Id
    @Column(name = "tenant_id", length = 40)
    public String tenantId;

    @Id
    @Column(name = "account_id", length = 40)
    public String accountId;

    @Column(name = "frozen", nullable = false)
    public boolean frozen;

    @Column(name = "per_txn_limit", precision = 23, scale = 4)
    public BigDecimal perTxnLimit;

    @Column(name = "daily_limit", precision = 23, scale = 4)
    public BigDecimal dailyLimit;

    @Column(name = "updated_ms", nullable = false)
    public long updatedMs;

    public static Optional<WalletControl> find(String tenantId, String accountId) {
        return Optional.ofNullable(findById(new Key(tenantId, accountId)));
    }

    public static final class Key implements Serializable {
        public String tenantId;
        public String accountId;

        public Key() {}

        public Key(String tenantId, String accountId) {
            this.tenantId = tenantId;
            this.accountId = accountId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return Objects.equals(tenantId, k.tenantId) && Objects.equals(accountId, k.accountId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tenantId, accountId);
        }
    }
}
