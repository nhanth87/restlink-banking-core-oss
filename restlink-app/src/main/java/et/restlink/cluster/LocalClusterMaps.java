package et.restlink.cluster;

import et.restlink.core.PendingRequest;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/** Single-node lab implementation. No replication; a lone node never has departed peers. */
public final class LocalClusterMaps implements ClusterMaps {

    private final ConcurrentMap<String, String> idem = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PendingRequest> pending = new ConcurrentHashMap<>();
    private final String node;

    public LocalClusterMaps(String node) {
        this.node = node == null || node.isBlank() ? "restlink-1" : node;
    }

    @Override public ConcurrentMap<String, String> idem() { return idem; }
    @Override public ConcurrentMap<String, PendingRequest> pending() { return pending; }
    @Override public String localNode() { return node; }
    @Override public List<String> members() { return List.of(node); }
    @Override public String mode() { return "LOCAL"; }
    @Override public void onMembersDeparted(Consumer<List<String>> listener) { /* no peers in lab */ }
}
