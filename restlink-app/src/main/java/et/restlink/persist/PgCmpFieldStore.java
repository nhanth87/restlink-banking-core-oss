package et.restlink.persist;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microjainslee.core.CmpFieldStore;

import et.restlink.profile.BoundedLruCache;
import et.restlink.profile.CustomerDirectory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * CMP store: Postgres is the full set. Heap holds only a bounded LRU of active sessions —
 * never {@code SELECT *} / warm-all (1M customers would not fit).
 */
public final class PgCmpFieldStore implements CmpFieldStore {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final BoundedLruCache<Map<String, Object>> hot;
    private final DataSource ds;

    public PgCmpFieldStore(DataSource ds) {
        this(ds, CustomerDirectory.DEFAULT_CACHE_MAX);
    }

    public PgCmpFieldStore(DataSource ds, int cacheMax) {
        this.ds = ds;
        this.hot = new BoundedLruCache<>(cacheMax);
    }

    @Override
    public Map<String, Object> load(String entityId) {
        Map<String, Object> cached = hot.get(entityId);
        if (cached != null) return new HashMap<>(cached);
        Map<String, Object> fromDb = loadDb(entityId);
        if (!fromDb.isEmpty()) hot.put(entityId, new HashMap<>(fromDb));
        return fromDb;
    }

    @Override
    public void store(String entityId, Map<String, Object> state) {
        Map<String, Object> snap = state == null ? Map.of() : new HashMap<>(state);
        upsertDb(entityId, snap);
        hot.put(entityId, snap);
    }

    @Override
    public void remove(String entityId) {
        hot.remove(entityId);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM customer_cmp WHERE entity_id = ?")) {
            ps.setString(1, entityId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("remove cmp " + entityId, e);
        }
    }

    @Override
    public boolean contains(String entityId) {
        if (hot.get(entityId) != null) return true;
        return existsDb(entityId);
    }

    private boolean existsDb(String entityId) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM customer_cmp WHERE entity_id = ?")) {
            ps.setString(1, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            throw new IllegalStateException("exists cmp " + entityId, e);
        }
    }

    private Map<String, Object> loadDb(String entityId) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT fields FROM customer_cmp WHERE entity_id = ?")) {
            ps.setString(1, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Collections.emptyMap();
                return parse(rs.getString(1));
            }
        } catch (Exception e) {
            throw new IllegalStateException("load cmp " + entityId, e);
        }
    }

    private void upsertDb(String entityId, Map<String, Object> state) {
        String sql = """
                INSERT INTO customer_cmp
                  (entity_id, tenant_id, customer_id, last_request_id, last_account_id,
                   last_txn_id, last_balance, fields, updated_ms)
                VALUES (?,?,?,?,?,?,?,?::jsonb,?)
                ON CONFLICT (entity_id) DO UPDATE SET
                  tenant_id = EXCLUDED.tenant_id,
                  customer_id = EXCLUDED.customer_id,
                  last_request_id = EXCLUDED.last_request_id,
                  last_account_id = EXCLUDED.last_account_id,
                  last_txn_id = EXCLUDED.last_txn_id,
                  last_balance = EXCLUDED.last_balance,
                  fields = EXCLUDED.fields,
                  updated_ms = EXCLUDED.updated_ms
                """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, entityId);
            ps.setString(2, str(state, "tenantId"));
            ps.setString(3, str(state, "customerId"));
            ps.setString(4, str(state, "lastRequestId"));
            ps.setString(5, str(state, "lastAccountId"));
            ps.setString(6, str(state, "lastTxnId"));
            ps.setString(7, str(state, "lastBalance"));
            ps.setString(8, JSON.writeValueAsString(state));
            ps.setLong(9, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("store cmp " + entityId, e);
        }
    }

    private static Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) return new HashMap<>();
        try {
            return new HashMap<>(JSON.readValue(json, MAP));
        } catch (Exception e) {
            throw new IllegalStateException("bad cmp json", e);
        }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }
}
