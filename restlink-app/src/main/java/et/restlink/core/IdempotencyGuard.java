package et.restlink.core;

import et.restlink.cluster.ClusterMaps;

import java.util.Optional;
import java.util.concurrent.ConcurrentMap;

/**
 * Cluster-wide exactly-once guard for money effects. Backed by a DIST_SYNC map so a claim made on any
 * node is visible to all — a fail-closed atomic CAS on duplicate, so a transaction is never posted twice.
 *
 * <p>Contract: call {@link #claim(String)} BEFORE touching the core engine. If it returns a present
 * value, the effect already happened (or is in flight) — return that result, do not re-post.
 */
public final class IdempotencyGuard {

    private static final String CLAIMED = "CLAIMED";

    private final ConcurrentMap<String, String> idem;
    private final String node;

    public IdempotencyGuard(ClusterMaps maps) {
        this.idem = maps.idem();
        this.node = maps.localNode();
    }

    /**
     * Atomically claim {@code key}. Returns empty if THIS call won the claim (caller must proceed to the
     * engine). Returns the prior marker/result if the key was already claimed (caller must NOT re-post).
     */
    public Optional<String> claim(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey required for a money command");
        }
        String prior = idem.putIfAbsent(key, CLAIMED + ":" + node);
        return Optional.ofNullable(prior);
    }

    /** Store the final result JSON for a claimed key (visible cluster-wide for idempotent replay). */
    public void store(String key, String resultJson) {
        idem.put(key, resultJson);
    }

    /** Result if the key resolved to a final (non-CLAIMED) result. */
    public Optional<String> result(String key) {
        String v = idem.get(key);
        if (v == null || v.startsWith(CLAIMED)) return Optional.empty();
        return Optional.of(v);
    }

    /** Release a claim (only when the engine call failed cleanly and a retry is safe). */
    public void release(String key) {
        idem.remove(key, CLAIMED + ":" + node);
    }

    public int claimCount() { return idem.size(); }
}
