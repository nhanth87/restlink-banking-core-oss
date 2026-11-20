package et.restlink.ra.fineract;

/**
 * Connection + request config for Apache Fineract REST. Fineract is an external engine on its own JDK
 * (D4 escape hatch) — this RA only holds network coordinates, credentials, and the request conventions
 * Fineract mandates.
 *
 * <p>Defaults follow Fineract community best practice
 * (<a href="https://cwiki.apache.org/confluence/display/FINERACT/Idempotency+-+API+Retry+Fault+Tolerance">
 * Idempotency - API Retry Fault Tolerance</a> and the platform docs):
 * <ul>
 *   <li>{@code Idempotency-Key} header on every POST/PUT/PATCH money command (configurable name);</li>
 *   <li>{@code dateFormat} + {@code locale} in every write body (else 400);</li>
 *   <li>{@code Fineract-Platform-TenantId} header for multi-tenancy.</li>
 * </ul>
 */
public final class FineractRaConfig {

    private String baseUrl = "https://127.0.0.1:8443/fineract-provider/api/v1";
    private String tenant = "default";
    private String user = "mifos";
    private String password = "password";
    private int connectTimeoutMs = 3_000;
    private int readTimeoutMs = 10_000;
    private long heartbeatMs = 20_000;

    // --- Fineract request conventions (best practice) ---
    private String idempotencyHeader = "Idempotency-Key";
    private String locale = "en";
    private String dateFormat = "yyyy-MM-dd";
    private String healthPath = "/actuator/health";

    // --- Product/org defaults for onboarding + savings (per-tenant in Phase 2 profiles) ---
    private long defaultOfficeId = 1;
    private long savingsProductId = 1;

    // --- TLS: Fineract dev images use self-signed certs. Both flags default false; prod never trust-all. ---
    private boolean insecureTls = false;
    private boolean allowInsecureTlsLab = false;

    public String baseUrl() { return baseUrl; }
    public String tenant() { return tenant; }
    public String user() { return user; }
    public String password() { return password; }
    public int connectTimeoutMs() { return connectTimeoutMs; }
    public int readTimeoutMs() { return readTimeoutMs; }
    public long heartbeatMs() { return heartbeatMs; }
    public String idempotencyHeader() { return idempotencyHeader; }
    public String locale() { return locale; }
    public String dateFormat() { return dateFormat; }
    public String healthPath() { return healthPath; }
    public long defaultOfficeId() { return defaultOfficeId; }
    public long savingsProductId() { return savingsProductId; }
    public boolean insecureTls() { return insecureTls; }
    public boolean allowInsecureTlsLab() { return allowInsecureTlsLab; }

    public FineractRaConfig baseUrl(String v) { this.baseUrl = v; return this; }
    public FineractRaConfig tenant(String v) { this.tenant = v; return this; }
    public FineractRaConfig user(String v) { this.user = v; return this; }
    public FineractRaConfig password(String v) { this.password = v; return this; }
    public FineractRaConfig connectTimeoutMs(int v) { this.connectTimeoutMs = v; return this; }
    public FineractRaConfig readTimeoutMs(int v) { this.readTimeoutMs = v; return this; }
    public FineractRaConfig heartbeatMs(long v) { this.heartbeatMs = v; return this; }
    public FineractRaConfig idempotencyHeader(String v) { this.idempotencyHeader = v; return this; }
    public FineractRaConfig locale(String v) { this.locale = v; return this; }
    public FineractRaConfig dateFormat(String v) { this.dateFormat = v; return this; }
    public FineractRaConfig healthPath(String v) { this.healthPath = v; return this; }
    public FineractRaConfig defaultOfficeId(long v) { this.defaultOfficeId = v; return this; }
    public FineractRaConfig savingsProductId(long v) { this.savingsProductId = v; return this; }
    public FineractRaConfig insecureTls(boolean v) { this.insecureTls = v; return this; }
    public FineractRaConfig allowInsecureTlsLab(boolean v) { this.allowInsecureTlsLab = v; return this; }
}
