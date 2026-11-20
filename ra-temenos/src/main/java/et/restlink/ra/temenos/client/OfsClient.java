package et.restlink.ra.temenos.client;

import et.restlink.ra.temenos.TemenosRaConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Thin Temenos OFS client. All Temenos I/O lives here — never in an SBB. OFS messages are
 * {@code TRANSACTION,VERSION/FUNCTION//...} strings; this scaffold sends/receives raw OFS text.
 * Real field mapping + IRIS/TAFJ options are Phase-2 work.
 */
public final class OfsClient implements AutoCloseable {

    private final TemenosRaConfig config;
    private final HttpClient http;

    public OfsClient(TemenosRaConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.connectTimeoutMs()))
                .build();
    }

    /** Send a raw OFS request string, return the OFS response. */
    public String execute(String ofsRequest) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(config.ofsUrl()))
                .timeout(Duration.ofMillis(config.readTimeoutMs()))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(ofsRequest))
                .build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        this.lastStatus = r.statusCode();
        return r.body();
    }

    /** Engine probe for {@code coreLive} — a real OFS round-trip, not socket-open. */
    public boolean probe() {
        try {
            // ENQUIRY that returns quickly; replaced with a real health enquiry in Phase 2.
            execute("ENQUIRY.SELECT,//" + config.company() + "/" + config.user() + "/" + config.password());
            return lastStatus >= 200 && lastStatus < 500;
        } catch (Exception e) {
            return false;
        }
    }

    private volatile int lastStatus = 0;
    public int lastStatus() { return lastStatus; }

    @Override
    public void close() {
        // GC-managed HttpClient.
    }
}
