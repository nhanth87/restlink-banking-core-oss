package et.restlink.core;

import et.restlink.cluster.ClusterMaps;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;

/**
 * Cluster-replicated request↔response correlation (DIST_SYNC). Because a core RA fires its event
 * asynchronously and it may be delivered on a different node than the caller, ANY node must be able to
 * complete a pending request by {@code requestId}.
 */
public final class PendingRequestStore {

    private final ConcurrentMap<String, PendingRequest> pending;
    private final String node;

    public PendingRequestStore(ClusterMaps maps) {
        this.pending = maps.pending();
        this.node = maps.localNode();
    }

    public void put(PendingRequest req) {
        pending.put(req.requestId(), req);
    }

    public Optional<PendingRequest> get(String requestId) {
        return Optional.ofNullable(pending.get(requestId));
    }

    /** Complete a request with its result. Returns the completed entry, or empty if unknown. */
    public Optional<PendingRequest> complete(String requestId, String resultJson) {
        PendingRequest cur = pending.get(requestId);
        if (cur == null) return Optional.empty();
        PendingRequest done = cur.completed(resultJson);
        pending.put(requestId, done);
        return Optional.of(done);
    }

    /** Pending (still SUBMITTED) requests whose owner node has departed the cluster — to be adopted. */
    public List<PendingRequest> orphansOf(List<String> departedNodes) {
        List<PendingRequest> out = new ArrayList<>();
        for (PendingRequest r : pending.values()) {
            if (r.state() == PendingRequest.State.SUBMITTED && departedNodes.contains(r.ownerNode())) {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * Take ownership of an orphaned entry so this node drives its resolution.
     * CAS via {@code replace(key, expected, next)} so two surviving nodes cannot both adopt.
     *
     * @return the adopted copy, or empty if another node already claimed it
     */
    public Optional<PendingRequest> adopt(PendingRequest orphan) {
        if (orphan == null || orphan.requestId() == null) {
            return Optional.empty();
        }
        PendingRequest mine = orphan.adoptedBy(node);
        if (pending.replace(orphan.requestId(), orphan, mine)) {
            return Optional.of(mine);
        }
        return Optional.empty();
    }

    public void mark(String requestId, PendingRequest.State state) {
        PendingRequest cur = pending.get(requestId);
        if (cur != null) pending.put(requestId, cur.withState(state));
    }

    public int size() { return pending.size(); }
}
