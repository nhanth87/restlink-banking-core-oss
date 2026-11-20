package et.restlink.cluster;

import et.restlink.core.PendingRequest;

import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * Cluster state seam for N–N banking HA. Two implementations:
 * <ul>
 *   <li>{@link LocalClusterMaps} — single-node lab (ConcurrentHashMap).</li>
 *   <li>{@link RestlinkInfinispanManager} — multi-node DIST_SYNC (one JGroups view).</li>
 * </ul>
 * The money-correctness logic (idempotency, correlation, adoption) depends only on this interface,
 * so it is unit-testable without a live cluster.
 */
public interface ClusterMaps {

    /** idempotencyKey → result JSON (or a CLAIMED marker). Replicated, atomic putIfAbsent. */
    ConcurrentMap<String, String> idem();

    /** requestId → PendingRequest. Replicated so any node can complete a pending request. */
    ConcurrentMap<String, PendingRequest> pending();

    /** This node's stable id (owner stamp on pending entries). */
    String localNode();

    /** Current cluster members (node ids). */
    List<String> members();

    /** "DIST_SYNC" (N–N) or "LOCAL" (single-node lab). */
    String mode();

    /**
     * Register a listener for membership changes; receives the set of members that just left.
     * Used by {@link RestlinkClusterViewBridge} to adopt orphaned pending requests.
     */
    void onMembersDeparted(Consumer<List<String>> listener);
}
