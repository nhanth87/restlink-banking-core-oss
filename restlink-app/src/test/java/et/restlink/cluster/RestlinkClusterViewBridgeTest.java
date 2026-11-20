package et.restlink.cluster;

import et.restlink.core.CoreBankingRouter;
import et.restlink.core.PendingRequest;
import et.restlink.core.PendingRequestStore;
import et.restlink.spi.CoreProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RestlinkClusterViewBridgeTest {

    @Test
    void moneyOrphansAreNeverAdopted() {
        FakeMaps maps = new FakeMaps("n2");
        PendingRequestStore pending = new PendingRequestStore(maps);
        pending.put(PendingRequest.submitted("PAY-1", "et", "PostTransaction",
                "idem", "WALLET_LEDGER", "n1"));
        RestlinkClusterViewBridge bridge = new RestlinkClusterViewBridge(maps, pending, router());
        bridge.start();
        maps.depart(List.of("n1"));
        assertEquals(PendingRequest.State.SUBMITTED, pending.get("PAY-1").orElseThrow().state(),
                "Temporal owns money replay — bridge must not mark NEEDS_REVIEW");
        assertEquals("n1", pending.get("PAY-1").orElseThrow().ownerNode());
    }

    @Test
    void nonMoneyOrphanGoesNeedsReviewOnce() {
        FakeMaps maps = new FakeMaps("n2");
        PendingRequestStore pending = new PendingRequestStore(maps);
        pending.put(PendingRequest.submitted("REQ-1", "et", "OpenAccount",
                null, "WALLET_LEDGER", "n1"));
        RestlinkClusterViewBridge bridge = new RestlinkClusterViewBridge(maps, pending, router());
        bridge.start();
        maps.depart(List.of("n1"));
        PendingRequest after = pending.get("REQ-1").orElseThrow();
        assertEquals(PendingRequest.State.NEEDS_REVIEW, after.state());
        assertEquals("n2", after.ownerNode());
    }

    @Test
    void concurrentAdoptDoesNotDoubleReview() {
        FakeMaps maps = new FakeMaps("n2");
        PendingRequestStore pending = new PendingRequestStore(maps);
        pending.put(PendingRequest.submitted("REQ-1", "et", "CreateCustomer",
                null, "WALLET_LEDGER", "n1"));
        RestlinkClusterViewBridge a = new RestlinkClusterViewBridge(maps, pending, router());
        RestlinkClusterViewBridge b = new RestlinkClusterViewBridge(maps, pending, router());
        a.start();
        b.start();
        a.adoptOrphans(List.of("n1"));
        b.adoptOrphans(List.of("n1"));
        assertEquals(PendingRequest.State.NEEDS_REVIEW, pending.get("REQ-1").orElseThrow().state());
        assertEquals("n2", pending.get("REQ-1").orElseThrow().ownerNode());
    }

    private static CoreBankingRouter router() {
        return new CoreBankingRouter(CoreProvider.WALLET_LEDGER, t -> CoreProvider.WALLET_LEDGER);
    }

    static final class FakeMaps implements ClusterMaps {
        private final LocalClusterMaps local;
        private final List<Consumer<List<String>>> listeners = new CopyOnWriteArrayList<>();

        FakeMaps(String node) {
            this.local = new LocalClusterMaps(node);
        }

        void depart(List<String> nodes) {
            for (Consumer<List<String>> l : listeners) {
                l.accept(nodes);
            }
        }

        @Override public java.util.concurrent.ConcurrentMap<String, String> idem() { return local.idem(); }
        @Override public java.util.concurrent.ConcurrentMap<String, PendingRequest> pending() { return local.pending(); }
        @Override public String localNode() { return local.localNode(); }
        @Override public List<String> members() { return local.members(); }
        @Override public String mode() { return "DIST_SYNC"; }
        @Override public void onMembersDeparted(Consumer<List<String>> listener) { listeners.add(listener); }
    }
}
