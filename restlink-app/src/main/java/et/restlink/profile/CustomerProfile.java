package et.restlink.profile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Detached customer identity snapshot. Fields live on this object (request-scoped / LRU entry),
 * never in a process-wide facility. Persistence is Postgres via {@link CustomerDirectory}.
 */
public final class CustomerProfile {

    public static final String TABLE_NAME = "Customer";

    private final Map<String, Object> fields = new LinkedHashMap<>();

    public static String profileName(String tenantId, String customerId) {
        return require(tenantId) + ":" + require(customerId);
    }

    public static CustomerProfile fromFields(Map<String, Object> src) {
        CustomerProfile p = new CustomerProfile();
        if (src != null) p.fields.putAll(src);
        return p;
    }

    public Map<String, Object> snapshot() {
        return new LinkedHashMap<>(fields);
    }

    public String getTenantId() { return str("tenantId"); }
    public void setTenantId(String v) { put("tenantId", v); }

    public String getCustomerId() { return str("customerId"); }
    public void setCustomerId(String v) { put("customerId", v); }

    public String getFirstName() { return str("firstName"); }
    public void setFirstName(String v) { put("firstName", v); }

    public String getLastName() { return str("lastName"); }
    public void setLastName(String v) { put("lastName", v); }

    public String getMobileNo() { return str("mobileNo"); }
    public void setMobileNo(String v) { put("mobileNo", v); }

    public String getKycId() { return str("kycId"); }
    public void setKycId(String v) { put("kycId", v); }

    public String getProvider() { return str("provider"); }
    public void setProvider(String v) { put("provider", v); }

    public String getDefaultCurrency() { return str("defaultCurrency"); }
    public void setDefaultCurrency(String v) { put("defaultCurrency", v); }

    public String getStatus() { return str("status"); }
    public void setStatus(String v) { put("status", v); }

    public String getKycStatus() { return str("kycStatus"); }
    public void setKycStatus(String v) { put("kycStatus", v); }

    public String getKycDocumentType() { return str("kycDocumentType"); }
    public void setKycDocumentType(String v) { put("kycDocumentType", v); }

    public String getKycDocumentRef() { return str("kycDocumentRef"); }
    public void setKycDocumentRef(String v) { put("kycDocumentRef", v); }

    private String str(String k) {
        Object v = fields.get(k);
        return v == null ? null : v.toString();
    }

    private void put(String k, String v) {
        if (v == null) fields.remove(k);
        else fields.put(k, v);
    }

    private static String require(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("tenantId/customerId required");
        return v.trim();
    }
}
