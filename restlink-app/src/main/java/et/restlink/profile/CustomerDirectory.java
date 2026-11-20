package et.restlink.profile;

import com.microjainslee.api.DurableProfileStore;
import com.microjainslee.api.ProfileID;
import com.microjainslee.core.CmpFieldStore;

import et.restlink.cmp.CustomerSessionCmp;
import et.restlink.persist.PgDurableProfileStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.function.ToIntFunction;

/**
 * Per-customer profile + CMP. Postgres is the full set (1M+ rows). The JVM holds only a
 * <em>bounded LRU</em> of the active working set — never the whole population.
 *
 * <p>No TTL on the durable row. No boot-time rehydrate-all.
 */
public final class CustomerDirectory {

    public static final int DEFAULT_CACHE_MAX = 10_000;

    private static final Logger LOG = LoggerFactory.getLogger(CustomerDirectory.class);

    private final DurableProfileStore store;
    private final CmpFieldStore cmpStore;
    private final BoundedLruCache<CustomerProfile> hot;
    private final ToIntFunction<String> counter;

    public CustomerDirectory(DurableProfileStore store, CmpFieldStore cmpStore) {
        this(store, cmpStore, DEFAULT_CACHE_MAX);
    }

    public CustomerDirectory(DurableProfileStore store, CmpFieldStore cmpStore, int cacheMax) {
        if (store == null) throw new IllegalArgumentException("DurableProfileStore required (Postgres)");
        this.store = store;
        this.cmpStore = cmpStore;
        this.hot = new BoundedLruCache<>(cacheMax);
        this.counter = store instanceof PgDurableProfileStore pg
                ? pg::count
                : table -> store.loadTable(table).size();
    }

    public boolean durable() { return true; }

    public int cacheMax() { return hot.max(); }

    public int cacheSize() { return hot.size(); }

    public void close() { /* no facility flusher */ }

    public CustomerHandle ensure(String tenantId, String customerId, CustomerSpec spec) {
        String name = CustomerProfile.profileName(tenantId, customerId);
        CustomerProfile profile = load(name).orElseGet(() -> create(name, tenantId, customerId, spec));
        return new CustomerHandle(profile, loadOrCreateCmp(tenantId, customerId));
    }

    public Optional<CustomerHandle> get(String tenantId, String customerId) {
        String name = CustomerProfile.profileName(tenantId, customerId);
        return load(name).map(p -> new CustomerHandle(p, loadOrCreateCmp(tenantId, customerId)));
    }

    /** Durable row count (Postgres {@code COUNT(*)}), not heap cache size. */
    public int profileCount() {
        return counter.applyAsInt(CustomerProfile.TABLE_NAME);
    }

    private Optional<CustomerProfile> load(String name) {
        CustomerProfile cached = hot.get(name);
        if (cached != null) return Optional.of(cached);
        Map<String, Object> fields = store.loadFields(new ProfileID(CustomerProfile.TABLE_NAME, name));
        if (fields == null || fields.isEmpty()) return Optional.empty();
        CustomerProfile p = CustomerProfile.fromFields(fields);
        hot.put(name, p);
        return Optional.of(p);
    }

    private CustomerProfile create(String name, String tenantId, String customerId, CustomerSpec spec) {
        CustomerProfile p = new CustomerProfile();
        apply(p, tenantId, customerId, spec);
        store.storeFields(new ProfileID(CustomerProfile.TABLE_NAME, name), p.snapshot());
        hot.put(name, p);
        LOG.info("created customer profile {}", name);
        return p;
    }

    private CustomerSessionCmp loadOrCreateCmp(String tenantId, String customerId) {
        String entityId = CustomerSessionCmp.entityId(tenantId, customerId);
        CustomerSessionCmp cmp = new CustomerSessionCmp(cmpStore, entityId);
        if (!cmpStore.contains(entityId)) {
            cmp.stampIdentity(tenantId, customerId);
            LOG.info("created customer CMP {}", entityId);
        }
        return cmp;
    }

    private static void apply(CustomerProfile p, String tenantId, String customerId, CustomerSpec spec) {
        p.setTenantId(tenantId);
        p.setCustomerId(customerId);
        if (spec == null) {
            p.setStatus("ACTIVE");
            p.setKycStatus("PENDING");
            return;
        }
        p.setFirstName(spec.firstName());
        p.setLastName(spec.lastName());
        p.setMobileNo(spec.mobileNo());
        p.setKycId(spec.kycId());
        p.setProvider(spec.provider());
        p.setDefaultCurrency(spec.defaultCurrency());
        p.setStatus(spec.status() == null ? "ACTIVE" : spec.status());
        if (p.getKycStatus() == null || p.getKycStatus().isBlank()) {
            p.setKycStatus("PENDING");
        }
    }

    /**
     * KYC decision + optional document stub. Does not create a customer.
     */
    public Optional<CustomerHandle> setKyc(String tenantId, String customerId,
                                          String kycStatus, String documentType, String documentRef) {
        Optional<CustomerHandle> found = get(tenantId, customerId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        CustomerProfile p = found.get().profile();
        if (kycStatus != null && !kycStatus.isBlank()) {
            p.setKycStatus(kycStatus);
        }
        if (documentType != null && !documentType.isBlank()) {
            p.setKycDocumentType(documentType);
        }
        if (documentRef != null && !documentRef.isBlank()) {
            p.setKycDocumentRef(documentRef);
        }
        String name = CustomerProfile.profileName(tenantId, customerId);
        store.storeFields(new ProfileID(CustomerProfile.TABLE_NAME, name), p.snapshot());
        hot.put(name, p);
        return found;
    }

    public record CustomerSpec(
            String firstName,
            String lastName,
            String mobileNo,
            String kycId,
            String provider,
            String defaultCurrency,
            String status
    ) {}

    public record CustomerHandle(CustomerProfile profile, CustomerSessionCmp cmp) {
        public String key() {
            return CustomerProfile.profileName(profile.getTenantId(), profile.getCustomerId());
        }
    }
}
