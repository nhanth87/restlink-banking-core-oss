package et.restlink.cmp;

import com.microjainslee.core.CmpFieldStore;

import java.util.HashMap;
import java.util.Map;

/**
 * One CMP entity per customer. Entity id is {@code tenantId:customerId} — the same key as the
 * customer profile, but a separate store: profile is provisioned identity; CMP is in-flight session
 * (last request, last account, last txn, cached balance). Two customers never share a CMP row.
 */
public final class CustomerSessionCmp {

    public static final String FIELD_TENANT = "tenantId";
    public static final String FIELD_CUSTOMER = "customerId";
    public static final String FIELD_LAST_REQUEST = "lastRequestId";
    public static final String FIELD_LAST_ACCOUNT = "lastAccountId";
    public static final String FIELD_LAST_TXN = "lastTxnId";
    public static final String FIELD_LAST_BALANCE = "lastBalance";

    private final CmpFieldStore store;
    private final String entityId;

    public CustomerSessionCmp(CmpFieldStore store, String entityId) {
        if (store == null) throw new IllegalArgumentException("CmpFieldStore required");
        if (entityId == null || entityId.isBlank()) throw new IllegalArgumentException("entityId required");
        this.store = store;
        this.entityId = entityId;
    }

    public static String entityId(String tenantId, String customerId) {
        return require(tenantId) + ":" + require(customerId);
    }

    public String entityId() { return entityId; }

    public String tenantId() { return str(FIELD_TENANT); }
    public String customerId() { return str(FIELD_CUSTOMER); }
    public String lastRequestId() { return str(FIELD_LAST_REQUEST); }
    public String lastAccountId() { return str(FIELD_LAST_ACCOUNT); }
    public String lastTxnId() { return str(FIELD_LAST_TXN); }
    public String lastBalance() { return str(FIELD_LAST_BALANCE); }

    public void stampIdentity(String tenantId, String customerId) {
        put(FIELD_TENANT, tenantId);
        put(FIELD_CUSTOMER, customerId);
    }

    public void recordRequest(String requestId) { put(FIELD_LAST_REQUEST, requestId); }
    public void recordAccount(String accountId) { put(FIELD_LAST_ACCOUNT, accountId); }
    public void recordTxn(String txnId) { put(FIELD_LAST_TXN, txnId); }
    public void recordBalance(String balance) { put(FIELD_LAST_BALANCE, balance); }

    public Map<String, Object> snapshot() {
        return store.load(entityId);
    }

    private String str(String field) {
        Object v = store.load(entityId).get(field);
        return v == null ? null : v.toString();
    }

    private void put(String field, String value) {
        Map<String, Object> cur = new HashMap<>(store.load(entityId));
        if (value == null) cur.remove(field);
        else cur.put(field, value);
        store.store(entityId, cur);
    }

    private static String require(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("tenantId/customerId required");
        return v.trim();
    }
}
