package et.restlink.core;

import et.restlink.cluster.ClusterMaps;
import et.restlink.cluster.LocalClusterMaps;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingRequestStoreAdoptTest {

    @Test
    void putIfAbsentRaceOnlyOneNodeAdopts() {
        LocalClusterMaps heap = new LocalClusterMaps("heap");
        PendingRequestStore storeA = new PendingRequestStore(view(heap, "node-a"));
        PendingRequestStore storeB = new PendingRequestStore(view(heap, "node-b"));

        PendingRequest orphan = PendingRequest.submitted("REQ-1", "et", "OpenAccount",
                null, "WALLET_LEDGER", "dead-node");
        storeA.put(orphan);

        Optional<PendingRequest> a = storeA.adopt(orphan);
        Optional<PendingRequest> b = storeB.adopt(orphan);

        assertTrue(a.isPresent(), "first CAS wins");
        assertTrue(b.isEmpty(), "second CAS must not steal");
        assertEquals("node-a", storeA.get("REQ-1").orElseThrow().ownerNode());
        assertEquals(List.of(), storeA.orphansOf(List.of("dead-node")));
    }

    @Test
    void adoptNullIsEmpty() {
        PendingRequestStore store = new PendingRequestStore(new LocalClusterMaps("n1"));
        assertTrue(store.adopt(null).isEmpty());
    }

    private static ClusterMaps view(LocalClusterMaps heap, String node) {
        return new ClusterMaps() {
            @Override public ConcurrentMap<String, String> idem() { return heap.idem(); }
            @Override public ConcurrentMap<String, PendingRequest> pending() { return heap.pending(); }
            @Override public String localNode() { return node; }
            @Override public List<String> members() { return List.of(node); }
            @Override public String mode() { return "DIST_SYNC"; }
            @Override public void onMembersDeparted(Consumer<List<String>> listener) { }
        };
    }
}
