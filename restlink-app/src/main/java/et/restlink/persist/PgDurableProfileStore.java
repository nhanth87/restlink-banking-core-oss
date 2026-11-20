package et.restlink.persist;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microjainslee.api.DurableProfileStore;
import com.microjainslee.api.ProfileID;
import com.microjainslee.api.ProfileMutation;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL is the full customer set (1M+). {@link #storeFields} is synchronous so onboard
 * survives an immediate crash. {@link #count} is {@code COUNT(*)} — never {@link #loadTable}
 * for population size (that would pull every row onto the heap).
 *
 * <p>No TTL — a profile lives until {@link #remove}. Heap holds only a bounded LRU of active users.
 */
public final class PgDurableProfileStore implements DurableProfileStore {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final DataSource ds;

    public PgDurableProfileStore(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public Map<String, Object> loadFields(ProfileID id) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT fields FROM customer_profile WHERE profile_name = ? AND table_name = ?")) {
            ps.setString(1, id.getProfileName());
            ps.setString(2, id.getProfileTableName());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return parse(rs.getString(1));
            }
        } catch (Exception e) {
            throw new IllegalStateException("loadFields " + id, e);
        }
    }

    @Override
    public void storeFields(ProfileID id, Map<String, Object> fields) {
        Map<String, Object> snap = fields == null ? Map.of() : fields;
        String tenant = str(snap, "tenantId");
        String customer = str(snap, "customerId");
        if (tenant == null || customer == null) {
            String[] parts = id.getProfileName().split(":", 2);
            if (tenant == null && parts.length > 0) tenant = parts[0];
            if (customer == null && parts.length > 1) customer = parts[1];
        }
        long now = System.currentTimeMillis();
        String sql = """
                INSERT INTO customer_profile
                  (profile_name, table_name, tenant_id, customer_id, first_name, last_name,
                   mobile_no, kyc_id, provider, default_currency, status,
                   kyc_status, kyc_document_type, kyc_document_ref,
                   fields, created_ms, updated_ms)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?)
                ON CONFLICT (profile_name) DO UPDATE SET
                  tenant_id = EXCLUDED.tenant_id,
                  customer_id = EXCLUDED.customer_id,
                  first_name = EXCLUDED.first_name,
                  last_name = EXCLUDED.last_name,
                  mobile_no = EXCLUDED.mobile_no,
                  kyc_id = EXCLUDED.kyc_id,
                  provider = EXCLUDED.provider,
                  default_currency = EXCLUDED.default_currency,
                  status = EXCLUDED.status,
                  kyc_status = EXCLUDED.kyc_status,
                  kyc_document_type = EXCLUDED.kyc_document_type,
                  kyc_document_ref = EXCLUDED.kyc_document_ref,
                  fields = EXCLUDED.fields,
                  updated_ms = EXCLUDED.updated_ms
                """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, id.getProfileName());
            ps.setString(i++, id.getProfileTableName());
            ps.setString(i++, tenant);
            ps.setString(i++, customer);
            ps.setString(i++, str(snap, "firstName"));
            ps.setString(i++, str(snap, "lastName"));
            ps.setString(i++, str(snap, "mobileNo"));
            ps.setString(i++, str(snap, "kycId"));
            ps.setString(i++, str(snap, "provider"));
            ps.setString(i++, str(snap, "defaultCurrency"));
            ps.setString(i++, str(snap, "status") == null ? "ACTIVE" : str(snap, "status"));
            ps.setString(i++, str(snap, "kycStatus") == null ? "PENDING" : str(snap, "kycStatus"));
            ps.setString(i++, str(snap, "kycDocumentType"));
            ps.setString(i++, str(snap, "kycDocumentRef"));
            ps.setString(i++, JSON.writeValueAsString(snap));
            ps.setLong(i++, now);
            ps.setLong(i, now);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("storeFields " + id, e);
        }
    }

    @Override
    public void remove(ProfileID id) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM customer_profile WHERE profile_name = ? AND table_name = ?")) {
            ps.setString(1, id.getProfileName());
            ps.setString(2, id.getProfileTableName());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("remove " + id, e);
        }
    }

    @Override
    public void storeBatch(List<ProfileMutation> mutations) {
        if (mutations == null || mutations.isEmpty()) return;
        for (ProfileMutation m : mutations) {
            if (m.getType() == ProfileMutation.Type.DELETE) remove(m.getId());
            else storeFields(m.getId(), m.getFields());
        }
    }

    /** {@code COUNT(*)} — never {@code loadTable().size()} (that would pull 1M rows). */
    public int count(String tableName) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM customer_profile WHERE table_name = ?")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            throw new IllegalStateException("count " + tableName, e);
        }
    }

    /**
     * Full-table scan — do <em>not</em> call at boot or for {@code profileCount}.
     * Use {@link #count} / {@link #loadFields} instead.
     */
    @Override
    public Map<String, Map<String, Object>> loadTable(String tableName) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT profile_name, fields FROM customer_profile WHERE table_name = ?")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), parse(rs.getString(2)));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("loadTable " + tableName, e);
        }
        return out;
    }

    private static Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return JSON.readValue(json, MAP);
        } catch (Exception e) {
            throw new IllegalStateException("bad profile json", e);
        }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }
}
