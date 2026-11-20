package et.restlink.ra.temenos;

/**
 * Connection config for Temenos T24/Transact via OFS (OpenFinancialService). Temenos runs on its own
 * licensed runtime (D4 escape hatch); this RA only holds the OFS endpoint + credentials.
 */
public final class TemenosRaConfig {

    private String ofsUrl = "http://127.0.0.1:9089/axis2/services/OfsExecute";
    private String user = "INPUTT";
    private String password = "123456";
    private String company = "GB0010001";
    private int connectTimeoutMs = 3_000;
    private int readTimeoutMs = 15_000;
    private long heartbeatMs = 20_000;

    public String ofsUrl() { return ofsUrl; }
    public String user() { return user; }
    public String password() { return password; }
    public String company() { return company; }
    public int connectTimeoutMs() { return connectTimeoutMs; }
    public int readTimeoutMs() { return readTimeoutMs; }
    public long heartbeatMs() { return heartbeatMs; }

    public TemenosRaConfig ofsUrl(String v) { this.ofsUrl = v; return this; }
    public TemenosRaConfig user(String v) { this.user = v; return this; }
    public TemenosRaConfig password(String v) { this.password = v; return this; }
    public TemenosRaConfig company(String v) { this.company = v; return this; }
    public TemenosRaConfig connectTimeoutMs(int v) { this.connectTimeoutMs = v; return this; }
    public TemenosRaConfig readTimeoutMs(int v) { this.readTimeoutMs = v; return this; }
    public TemenosRaConfig heartbeatMs(long v) { this.heartbeatMs = v; return this; }
}
