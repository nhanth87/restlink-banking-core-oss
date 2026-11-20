package et.restlink.ra.fineract.client;

import et.restlink.ra.fineract.FineractRaConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/**
 * Thin Fineract REST client (java.net.http). All Fineract HTTP lives here — never in an SBB.
 *
 * <p>Applies Fineract community best practices:
 * <ul>
 *   <li>{@code Fineract-Platform-TenantId} header + Basic auth;</li>
 *   <li>{@code Idempotency-Key} header on POST/PUT/PATCH only (never GET/DELETE) — same key on retry
 *       ⇒ Fineract returns the cached response, no double transaction;</li>
 *   <li>keys derived from a stable seed and normalised to UUID layout (Fineract validates the format);</li>
 *   <li>business (4xx) vs transport (I/O, timeout, 5xx) errors are separated so link-truth only flips
 *       on real peer failure — a validation 400 does not mark Fineract "down".</li>
 * </ul>
 */
public final class FineractRestClient implements AutoCloseable {

    /** Outcome of an HTTP call: status + body, with helpers to classify per link-truth. */
    public record HttpResult(int status, String body, boolean transportError, String transportDetail) {
        public boolean is2xx() { return status >= 200 && status < 300; }
        public boolean isBusinessError() { return status >= 400 && status < 500; }
        /** Peer/transport trouble: connection/timeout or 5xx — the only thing that flips coreLive. */
        public boolean isPeerDown() { return transportError || status >= 500; }
    }

    private final FineractRaConfig config;
    private final HttpClient http;

    public FineractRestClient(FineractRaConfig config) {
        this.config = config;
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.connectTimeoutMs()));
        if (config.insecureTls()) {
            b.sslContext(FineractTls.requireLabTrustAll(config.allowInsecureTlsLab()));
        }
        this.http = b.build();
    }

    private String authHeader() {
        String raw = config.user() + ":" + config.password();
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private HttpRequest.Builder base(String path) {
        return HttpRequest.newBuilder(URI.create(config.baseUrl() + path))
                .timeout(Duration.ofMillis(config.readTimeoutMs()))
                .header("Authorization", authHeader())
                .header("Fineract-Platform-TenantId", config.tenant())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
    }

    /** GET — never carries an idempotency key (natively idempotent). */
    public HttpResult get(String path) {
        return send(base(path).GET().build());
    }

    /** POST JSON without idempotency (non-money commands, e.g. approve/activate lifecycle). */
    public HttpResult post(String path, String json) {
        return send(base(path).POST(HttpRequest.BodyPublishers.ofString(json)).build());
    }

    /**
     * POST JSON with an {@code Idempotency-Key}. {@code seed} is a stable business key (e.g. the
     * caller's idempotencyKey); the same seed always yields the same header value so a retry is
     * de-duplicated by Fineract.
     */
    public HttpResult postIdempotent(String path, String json, String seed) {
        HttpRequest req = base(path)
                .header(config.idempotencyHeader(), idempotencyKey(seed))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return send(req);
    }

    private HttpResult send(HttpRequest req) {
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            this.lastStatus = r.statusCode();
            return new HttpResult(r.statusCode(), r.body(), false, null);
        } catch (Exception e) {
            this.lastStatus = 0;
            return new HttpResult(0, null, true, e.toString());
        }
    }

    /** Real engine probe for {@code coreLive} — never trust RA start alone (house link-truth rule). */
    public boolean probe() {
        HttpResult r = get(config.healthPath());
        // Health reachable and not a server error ⇒ live. 4xx (auth on actuator) still means reachable.
        return !r.isPeerDown();
    }

    /** Fineract validates idempotency keys against a UUID layout; derive a stable one from the seed. */
    public static String idempotencyKey(String seed) {
        if (seed == null || seed.isBlank()) return UUID.randomUUID().toString();
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private volatile int lastStatus = 0;
    public int lastStatus() { return lastStatus; }

    @Override
    public void close() {
        // java.net.HttpClient is GC-managed; nothing to release here.
    }
}
